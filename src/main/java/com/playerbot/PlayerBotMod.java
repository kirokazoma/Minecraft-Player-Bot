package com.playerbot;

import com.mojang.logging.LogUtils;
import com.playerbot.bot.BotManager;
import com.playerbot.bot.FakeServerPlayer;
import com.playerbot.command.BotCommand;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

@Mod(PlayerBotMod.MODID)
public class PlayerBotMod {
    public static final String MODID = "playerbot";
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Track pending respawns: botName -> RespawnData
    private static final Map<String, RespawnData> pendingRespawns = new HashMap<>();
    
    // Track pending wander mode activations: botName -> activationTick
    private static final Map<String, Integer> pendingWanderActivations = new HashMap<>();
    
    private static class RespawnData {
        final ServerLevel level;
        final ServerPlayer followTarget;
        final boolean wasWandering;
        final int respawnTick;
        
        RespawnData(ServerLevel level, ServerPlayer followTarget, boolean wasWandering, int respawnTick) {
            this.level = level;
            this.followTarget = followTarget;
            this.wasWandering = wasWandering;
            this.respawnTick = respawnTick;
        }
    }

    public PlayerBotMod() {
        LOGGER.info("PlayerBot mod constructor called - registering to event bus");
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("PlayerBot mod initialized and registered to Forge event bus");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LOGGER.info("RegisterCommandsEvent fired - registering bot commands");
        BotCommand.register(event.getDispatcher());
        LOGGER.info("Bot commands registered successfully");
    }
    
    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof FakeServerPlayer bot) {
            String botName = bot.getName().getString();
            ServerPlayer followTarget = bot.getFollowTarget();
            boolean wasWandering = bot.isWandering();
            ServerLevel level = (ServerLevel) bot.level();
            var server = bot.getServer();
            
            int currentTick = server.getTickCount();
            int respawnTick = currentTick + 60; // 60 ticks = 3 seconds
            
            LOGGER.info("Bot {} died at tick {}, will respawn at tick {}", botName, currentTick, respawnTick);
            
            // Check keepInventory gamerule
            boolean keepInventory = level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY);
            
            if (!keepInventory) {
                // Drop all items
                bot.getInventory().dropAll();
                
                // Drop XP
                int xpToDrop = bot.totalExperience;
                if (xpToDrop > 0) {
                    net.minecraft.world.entity.ExperienceOrb.award(level, bot.position(), xpToDrop);
                }
                
                // Clear bot's XP
                bot.totalExperience = 0;
                bot.experienceLevel = 0;
                bot.experienceProgress = 0.0F;
            }
            
            // Stop following and wandering immediately
            bot.stopFollowing();
            bot.setWanderMode(false);
            
            // Schedule respawn
            pendingRespawns.put(botName, new RespawnData(level, followTarget, wasWandering, respawnTick));
        }
    }
    
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        // Only process at end of tick
        if (event.phase != TickEvent.Phase.END) return;
        
        MinecraftServer server = event.getServer();
        int currentTick = server.getTickCount();
        
        // Check all pending respawns
        if (!pendingRespawns.isEmpty()) {
            pendingRespawns.entrySet().removeIf(entry -> {
                String botName = entry.getKey();
                RespawnData data = entry.getValue();
                
                if (currentTick >= data.respawnTick) {
                    // Time to respawn
                    try {
                        LOGGER.info("Respawning bot {} at tick {}", botName, currentTick);
                        
                        // Get the existing bot and revive it
                        FakeServerPlayer bot = BotManager.getBot(botName);
                        if (bot != null) {
                            // Teleport to spawn
                            double spawnX = data.level.getSharedSpawnPos().getX() + 0.5;
                            double spawnY = data.level.getSharedSpawnPos().getY() + 1;
                            double spawnZ = data.level.getSharedSpawnPos().getZ() + 0.5;
                            bot.teleportTo(spawnX, spawnY, spawnZ);
                            
                            // Revive the bot (clears death state and syncs to clients)
                            bot.revive();
                            
                            // Restore follow target
                            if (data.followTarget != null && !data.followTarget.isRemoved()) {
                                bot.setFollowTarget(data.followTarget);
                                LOGGER.info("Bot {} respawned and resumed following", botName);
                            } else if (data.wasWandering) {
                                // Schedule wander mode activation for 6 seconds after death (3 seconds after respawn)
                                int wanderActivationTick = currentTick + 60; // 60 more ticks = 3 seconds from now
                                pendingWanderActivations.put(botName, wanderActivationTick);
                                LOGGER.info("Bot {} respawned, will resume wandering at tick {}", botName, wanderActivationTick);
                            } else {
                                LOGGER.info("Bot {} respawned successfully", botName);
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.error("Failed to respawn bot {}: {}", botName, e.getMessage());
                        e.printStackTrace();
                    }
                    
                    // Remove from pending list
                    return true;
                }
                
                // Keep in list
                return false;
            });
        }
        
        // Check all pending wander mode activations
        if (!pendingWanderActivations.isEmpty()) {
            pendingWanderActivations.entrySet().removeIf(entry -> {
                String botName = entry.getKey();
                int activationTick = entry.getValue();
                
                if (currentTick >= activationTick) {
                    // Time to activate wander mode
                    try {
                        FakeServerPlayer bot = BotManager.getBot(botName);
                        if (bot != null && !bot.isDeadOrDying()) {
                            bot.setWanderMode(true);
                            LOGGER.info("Bot {} resumed wandering at tick {}", botName, currentTick);
                        }
                    } catch (Exception e) {
                        LOGGER.error("Failed to activate wander mode for bot {}: {}", botName, e.getMessage());
                        e.printStackTrace();
                    }
                    
                    // Remove from pending list
                    return true;
                }
                
                // Keep in list
                return false;
            });
        }
    }
    
    @SubscribeEvent
    public void onItemPickup(EntityItemPickupEvent event) {
        // Allow bots to pick up items
        if (event.getEntity() instanceof FakeServerPlayer) {
            event.setResult(Event.Result.ALLOW);
        }
    }
    
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("Server stopping - saving all bot positions");
        BotManager.saveAllBotPositions();
    }
}
