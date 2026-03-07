package com.playerbot.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.playerbot.bot.BotManager;
import com.playerbot.bot.FakeServerPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

/**
 * Command handler for bot operations
 * Usage:
 *   /bot spawn <name> - Creates a new bot
 *   /bot remove <name> - Removes a bot
 *   /bot list - Lists all active bots
 *   /bot removeall - Removes all bots
 *   /bot follow_player <bot_name> <player_name> - Makes a bot follow a specific player
 *   /bot stop <name> - Makes a bot stop following
 *   /bot wander <name> - Makes a bot wander around randomly (normal mode)
 *   /bot wander_survival <name> - Makes a bot wander and gather resources (survival mode)
 *   /bot breaking_enable <name> - Enables block breaking for the bot
 *   /bot breaking_disable <name> - Disables block breaking for the bot
 */
public class BotCommand {
    
    // Custom suggestion provider for bot names
    private static final SuggestionProvider<CommandSourceStack> BOT_NAME_SUGGESTIONS = (context, builder) -> {
        // Get all bot names and add them as suggestions
        for (String botName : BotManager.getAllBotNames()) {
            builder.suggest(botName);
        }
        return builder.buildFuture();
    };
    
    // Custom suggestion provider for online player names (excluding bots)
    private static final SuggestionProvider<CommandSourceStack> PLAYER_NAME_SUGGESTIONS = (context, builder) -> {
        // Get all online players and suggest their names (excluding bots)
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            // Only suggest real players, not bots
            if (!(player instanceof FakeServerPlayer)) {
                builder.suggest(player.getName().getString());
            }
        }
        return builder.buildFuture();
    };
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        System.out.println("[PlayerBot] Registering /bot command...");
        dispatcher.register(
            Commands.literal("bot")
                .requires(source -> source.hasPermission(2)) // Requires OP level 2
                .then(Commands.literal("spawn")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(BotCommand::spawnBot)))
                .then(Commands.literal("remove")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BOT_NAME_SUGGESTIONS)
                        .executes(BotCommand::removeBot)))
                .then(Commands.literal("list")
                    .executes(BotCommand::listBots))
                .then(Commands.literal("removeall")
                    .executes(BotCommand::removeAllBots))
                .then(Commands.literal("follow_player")
                    .then(Commands.argument("bot_name", StringArgumentType.word())
                        .suggests(BOT_NAME_SUGGESTIONS)
                        .then(Commands.argument("player_name", StringArgumentType.word())
                            .suggests(PLAYER_NAME_SUGGESTIONS)
                            .executes(BotCommand::followSpecificPlayer))))
                .then(Commands.literal("stop")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BOT_NAME_SUGGESTIONS)
                        .executes(BotCommand::stopFollowing)))
                .then(Commands.literal("wander")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BOT_NAME_SUGGESTIONS)
                        .executes(BotCommand::wanderMode)))
                .then(Commands.literal("wander_survival")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BOT_NAME_SUGGESTIONS)
                        .executes(BotCommand::wanderSurvivalMode)))
                .then(Commands.literal("debug_path")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BOT_NAME_SUGGESTIONS)
                        .executes(BotCommand::debugPath)))
                .then(Commands.literal("breaking_enable")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BOT_NAME_SUGGESTIONS)
                        .executes(BotCommand::enableBreaking)))
                .then(Commands.literal("breaking_disable")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(BOT_NAME_SUGGESTIONS)
                        .executes(BotCommand::disableBreaking)))
        );
        System.out.println("[PlayerBot] /bot command registered successfully");
    }

    private static int spawnBot(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String botName = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        
        // Check if bot already exists
        if (BotManager.getBot(botName) != null) {
            source.sendFailure(Component.literal("Bot '" + botName + "' already exists!"));
            return 0;
        }

        // Create the bot
        try {
            FakeServerPlayer bot = BotManager.createBot(source.getServer(), level, botName);
            source.sendSuccess(() -> Component.literal("Bot '" + botName + "' spawned successfully at " +
                String.format("%.1f, %.1f, %.1f", bot.getX(), bot.getY(), bot.getZ())), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to spawn bot: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    private static int removeBot(CommandContext<CommandSourceStack> context) {
        String botName = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        boolean removed = BotManager.removeBot(source.getServer(), botName);
        
        if (removed) {
            source.sendSuccess(() -> Component.literal("Bot '" + botName + "' removed successfully"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Bot '" + botName + "' not found"));
            return 0;
        }
    }

    private static int listBots(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        var botNames = BotManager.getAllBotNames();
        
        if (botNames.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No active bots"), false);
        } else {
            source.sendSuccess(() -> Component.literal("Active bots (" + botNames.size() + "): " + 
                String.join(", ", botNames)), false);
        }
        return botNames.size();
    }

    private static int removeAllBots(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int count = BotManager.getAllBotNames().size();
        
        BotManager.removeAllBots(source.getServer());
        
        source.sendSuccess(() -> Component.literal("Removed " + count + " bot(s)"), true);
        return count;
    }
    
    
    private static int followSpecificPlayer(CommandContext<CommandSourceStack> context) {
        String botName = StringArgumentType.getString(context, "bot_name");
        String playerName = StringArgumentType.getString(context, "player_name");
        CommandSourceStack source = context.getSource();
        
        FakeServerPlayer bot = BotManager.getBot(botName);
        if (bot == null) {
            source.sendFailure(Component.literal("Bot '" + botName + "' not found"));
            return 0;
        }
        
        // Find the target player by name
        ServerPlayer targetPlayer = source.getServer().getPlayerList().getPlayerByName(playerName);
        if (targetPlayer == null) {
            source.sendFailure(Component.literal("Player '" + playerName + "' not found"));
            return 0;
        }
        
        // Make bot follow the target player
        bot.setFollowTarget(targetPlayer);
        source.sendSuccess(() -> Component.literal("Bot '" + botName + "' is now following " + playerName), true);
        return 1;
    }
    
    private static int stopFollowing(CommandContext<CommandSourceStack> context) {
        String botName = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        FakeServerPlayer bot = BotManager.getBot(botName);
        if (bot == null) {
            source.sendFailure(Component.literal("Bot '" + botName + "' not found"));
            return 0;
        }
        
        bot.stopFollowing();
        bot.setWanderMode(false); // Also disable wander mode
        source.sendSuccess(() -> Component.literal("Bot '" + botName + "' stopped following"), true);
        return 1;
    }
    
    private static int wanderMode(CommandContext<CommandSourceStack> context) {
        String botName = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        FakeServerPlayer bot = BotManager.getBot(botName);
        if (bot == null) {
            source.sendFailure(Component.literal("Bot '" + botName + "' not found"));
            return 0;
        }
        
        // Stop following first
        bot.stopFollowing();
        
        // Toggle wander mode (normal mode - no survival)
        boolean newState = !bot.isWandering();
        bot.setWanderMode(newState);
        bot.setSurvivalMode(false); // Disable survival mode
        
        if (newState) {
            source.sendSuccess(() -> Component.literal("Bot '" + botName + "' is now wandering around (normal mode)"), true);
        } else {
            source.sendSuccess(() -> Component.literal("Bot '" + botName + "' stopped wandering"), true);
        }
        return 1;
    }
    
    private static int wanderSurvivalMode(CommandContext<CommandSourceStack> context) {
        String botName = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        FakeServerPlayer bot = BotManager.getBot(botName);
        if (bot == null) {
            source.sendFailure(Component.literal("Bot '" + botName + "' not found"));
            return 0;
        }
        
        // Stop following first
        bot.stopFollowing();
        
        // Toggle survival mode
        boolean newState = !bot.isSurvivalMode();
        bot.setSurvivalMode(newState);
        
        if (newState) {
            source.sendSuccess(() -> Component.literal("Bot '" + botName + "' is now in survival mode (will gather resources and craft weapons)"), true);
        } else {
            source.sendSuccess(() -> Component.literal("Bot '" + botName + "' exited survival mode"), true);
        }
        return 1;
    }
    
    private static int debugPath(CommandContext<CommandSourceStack> context) {
        String botName = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        FakeServerPlayer bot = BotManager.getBot(botName);
        if (bot == null) {
            source.sendFailure(Component.literal("Bot '" + botName + "' not found"));
            return 0;
        }
        
        // Toggle debug path
        boolean newState = !bot.isDebugPath();
        bot.setDebugPath(newState);
        
        if (newState) {
            source.sendSuccess(() -> Component.literal("Bot '" + botName + "' path visualization enabled"), true);
        } else {
            source.sendSuccess(() -> Component.literal("Bot '" + botName + "' path visualization disabled"), true);
        }
        return 1;
    }
    
    private static int enableBreaking(CommandContext<CommandSourceStack> context) {
        String botName = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        FakeServerPlayer bot = BotManager.getBot(botName);
        if (bot == null) {
            source.sendFailure(Component.literal("Bot '" + botName + "' not found"));
            return 0;
        }
        
        bot.setBreakingEnabled(true);
        source.sendSuccess(() -> Component.literal("Bot '" + botName + "' block breaking enabled"), true);
        return 1;
    }
    
    private static int disableBreaking(CommandContext<CommandSourceStack> context) {
        String botName = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        FakeServerPlayer bot = BotManager.getBot(botName);
        if (bot == null) {
            source.sendFailure(Component.literal("Bot '" + botName + "' not found"));
            return 0;
        }
        
        bot.setBreakingEnabled(false);
        source.sendSuccess(() -> Component.literal("Bot '" + botName + "' block breaking disabled"), true);
        return 1;
    }
}
