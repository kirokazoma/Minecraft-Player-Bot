package com.playerbot.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Manages all player bots on the server
 */
public class BotManager {
    private static final Map<UUID, FakeServerPlayer> activeBots = new HashMap<>();
    private static final Map<String, UUID> botNameToUUID = new HashMap<>();
    private static final String BOT_DATA_FILE = "bot_positions.dat";
    
    // Stores bot positions: botName -> [x, y, z, dimensionKey]
    private static final Map<String, BotPosition> savedPositions = new HashMap<>();
    
    static {
        loadBotPositions();
    }
    
    private static class BotPosition {
        double x, y, z;
        String dimension;
        
        BotPosition(double x, double y, double z, String dimension) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
        }
    }

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

        // Check if we have a saved position for this bot
        BotPosition savedPos = savedPositions.get(botName);
        ServerLevel targetLevel = level;
        
        if (savedPos != null) {
            // Try to get the saved dimension
            for (ServerLevel serverLevel : server.getAllLevels()) {
                if (serverLevel.dimension().location().toString().equals(savedPos.dimension)) {
                    targetLevel = serverLevel;
                    break;
                }
            }
        }

        // Create the fake player
        FakeServerPlayer bot = new FakeServerPlayer(server, targetLevel, profile);
        
        // Set game mode to survival
        bot.setGameMode(GameType.SURVIVAL);

        // Add to player list and world
        server.getPlayerList().placeNewPlayer(bot.connection.connection, bot);
        
        // Set spawn position - use saved position if available, otherwise world spawn
        double spawnX, spawnY, spawnZ;
        if (savedPos != null) {
            spawnX = savedPos.x;
            spawnY = savedPos.y;
            spawnZ = savedPos.z;
        } else {
            spawnX = targetLevel.getSharedSpawnPos().getX() + 0.5;
            spawnY = targetLevel.getSharedSpawnPos().getY() + 1;
            spawnZ = targetLevel.getSharedSpawnPos().getZ() + 0.5;
        }
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
            // Save bot position before removing
            saveBotPosition(botName, bot);
            
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
    
    /**
     * Save bot position to file
     */
    private static void saveBotPosition(String botName, FakeServerPlayer bot) {
        if (bot == null || bot.isDeadOrDying()) return;
        
        String dimension = bot.level().dimension().location().toString();
        savedPositions.put(botName, new BotPosition(bot.getX(), bot.getY(), bot.getZ(), dimension));
        saveBotPositions();
    }
    
    /**
     * Save all bot positions to disk
     */
    private static void saveBotPositions() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(BOT_DATA_FILE))) {
            Map<String, double[]> data = new HashMap<>();
            for (Map.Entry<String, BotPosition> entry : savedPositions.entrySet()) {
                BotPosition pos = entry.getValue();
                data.put(entry.getKey(), new double[]{pos.x, pos.y, pos.z});
                data.put(entry.getKey() + "_dim", new double[]{pos.dimension.hashCode()}); // Store dimension as hash
            }
            
            // Store dimension strings separately
            Map<String, String> dimensions = new HashMap<>();
            for (Map.Entry<String, BotPosition> entry : savedPositions.entrySet()) {
                dimensions.put(entry.getKey(), entry.getValue().dimension);
            }
            
            oos.writeObject(data);
            oos.writeObject(dimensions);
        } catch (IOException e) {
            System.err.println("[PlayerBot] Failed to save bot positions: " + e.getMessage());
        }
    }
    
    /**
     * Load bot positions from disk
     */
    @SuppressWarnings("unchecked")
    private static void loadBotPositions() {
        File file = new File(BOT_DATA_FILE);
        if (!file.exists()) return;
        
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            Map<String, double[]> data = (Map<String, double[]>) ois.readObject();
            Map<String, String> dimensions = (Map<String, String>) ois.readObject();
            
            for (Map.Entry<String, double[]> entry : data.entrySet()) {
                String botName = entry.getKey();
                if (botName.endsWith("_dim")) continue; // Skip dimension entries
                
                double[] pos = entry.getValue();
                String dimension = dimensions.getOrDefault(botName, "minecraft:overworld");
                savedPositions.put(botName, new BotPosition(pos[0], pos[1], pos[2], dimension));
            }
            
            System.out.println("[PlayerBot] Loaded " + savedPositions.size() + " bot positions");
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[PlayerBot] Failed to load bot positions: " + e.getMessage());
        }
    }
    
    /**
     * Save all active bot positions (called on server shutdown)
     */
    public static void saveAllBotPositions() {
        for (Map.Entry<String, UUID> entry : botNameToUUID.entrySet()) {
            FakeServerPlayer bot = activeBots.get(entry.getValue());
            if (bot != null) {
                saveBotPosition(entry.getKey(), bot);
            }
        }
    }
}
