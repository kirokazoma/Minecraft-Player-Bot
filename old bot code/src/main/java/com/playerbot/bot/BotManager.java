package com.playerbot.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

import java.util.*;

/**
 * Manages all player bots on the server
 */
public class BotManager {
    private static final Map<UUID, FakeServerPlayer> activeBots = new HashMap<>();
    private static final Map<String, UUID> botNameToUUID = new HashMap<>();

    /**
     * Creates and spawns a new bot player
     */
    public static FakeServerPlayer createBot(MinecraftServer server, ServerLevel level, String botName) {
        // Check if bot already exists
        UUID botUUID = botNameToUUID.get(botName);
        if (botUUID != null) {
            FakeServerPlayer existingBot = activeBots.get(botUUID);
            if (existingBot != null) {
                // Always remove old bot completely (dead or alive)
                server.getPlayerList().remove(existingBot);
                activeBots.remove(botUUID);
            }
        }

        // Create game profile for offline mode
        botUUID = UUID.nameUUIDFromBytes(("OfflinePlayer:" + botName).getBytes());
        GameProfile profile = new GameProfile(botUUID, botName);

        // Create the fake player
        FakeServerPlayer bot = new FakeServerPlayer(server, level, profile);
        
        // Set game mode to survival
        bot.setGameMode(GameType.SURVIVAL);

        // Add to player list and world
        server.getPlayerList().placeNewPlayer(bot.connection.connection, bot);
        
        // Set spawn position at world spawn
        double spawnX = level.getSharedSpawnPos().getX() + 0.5;
        double spawnY = level.getSharedSpawnPos().getY() + 1;
        double spawnZ = level.getSharedSpawnPos().getZ() + 0.5;
        bot.teleportTo(spawnX, spawnY, spawnZ);
        
        // Reset death state
        bot.setHealth(bot.getMaxHealth());
        bot.deathTime = 0;
        
        // Store bot reference
        activeBots.put(botUUID, bot);
        botNameToUUID.put(botName, botUUID);

        return bot;
    }

    /**
     * Removes a bot from the server
     */
    public static boolean removeBot(MinecraftServer server, String botName) {
        UUID botUUID = botNameToUUID.get(botName);
        if (botUUID == null) {
            return false;
        }

        FakeServerPlayer bot = activeBots.get(botUUID);
        if (bot != null) {
            // Don't remove from maps if bot is dead (for respawn)
            if (!bot.isDeadOrDying()) {
                // Disconnect the bot properly
                bot.connection.onDisconnect(net.minecraft.network.chat.Component.literal("Bot removed"));
                server.getPlayerList().remove(bot);
                
                activeBots.remove(botUUID);
                botNameToUUID.remove(botName);
            }
            return true;
        }
        return false;
    }

    /**
     * Gets a bot by name
     */
    public static FakeServerPlayer getBot(String botName) {
        UUID botUUID = botNameToUUID.get(botName);
        return botUUID != null ? activeBots.get(botUUID) : null;
    }

    /**
     * Gets all active bots
     */
    public static Collection<FakeServerPlayer> getAllBots() {
        return activeBots.values();
    }

    /**
     * Gets all bot names
     */
    public static Set<String> getAllBotNames() {
        return botNameToUUID.keySet();
    }

    /**
     * Checks if a player is a bot
     */
    public static boolean isBot(Player player) {
        return player instanceof FakeServerPlayer;
    }

    /**
     * Removes all bots from the server
     */
    public static void removeAllBots(MinecraftServer server) {
        List<String> botNames = new ArrayList<>(botNameToUUID.keySet());
        for (String botName : botNames) {
            removeBot(server, botName);
        }
    }
}
