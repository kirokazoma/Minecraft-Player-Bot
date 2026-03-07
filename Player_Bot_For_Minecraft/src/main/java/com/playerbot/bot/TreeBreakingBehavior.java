package com.playerbot.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles tree breaking with shaft mining to handle floating logs
 */
public class TreeBreakingBehavior {
    private final FakeServerPlayer bot;
    
    private BlockPos currentTargetLog = null;
    private int treeBreakingTicks = 0;
    private static final int TREE_BREAK_TIME = 60;
    private BlockPos lastBrokenLogPos = null;
    private int logPickupTicks = 0;
    private static final int LOG_PICKUP_WAIT = 40;
    
    public TreeBreakingBehavior(FakeServerPlayer bot) {
        this.bot = bot;
    }
    
    public void tick(boolean hasWeapon, int totalLogs) {
        if (!hasWeapon && totalLogs < 3) {
            // Look for logs if we don't have a target
            if (currentTargetLog == null) {
                currentTargetLog = findNearbyLog();
            }
            
            // Validate current target still exists
            if (currentTargetLog != null) {
                BlockState state = bot.level().getBlockState(currentTargetLog);
                if (!(state.getBlock() instanceof net.minecraft.world.level.block.RotatedPillarBlock)) {
                    currentTargetLog = null;
                    treeBreakingTicks = 0;
                }
            }
            
            // Break log if in range
            if (currentTargetLog != null) {
                double distance = Math.sqrt(bot.distanceToSqr(Vec3.atCenterOf(currentTargetLog)));
                if (distance <= 4.5) {
                    breakLog();
                } else {
                    // Reset breaking if out of range
                    if (treeBreakingTicks > 0) {
                        clearBreakingAnimation();
                        treeBreakingTicks = 0;
                    }
                }
            }
        } else {
            // Has weapon or enough logs - reset
            currentTargetLog = null;
            treeBreakingTicks = 0;
            lastBrokenLogPos = null;
            logPickupTicks = 0;
        }
    }
    
    private void breakLog() {
        treeBreakingTicks++;
        
        int breakStage = (int) ((treeBreakingTicks / (float) TREE_BREAK_TIME) * 10);
        if (breakStage > 9) breakStage = 9;
        
        ServerLevel serverLevel = (ServerLevel) bot.level();
        for (ServerPlayer player : serverLevel.players()) {
            if (player.distanceTo(bot) < 64) {
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                    bot.getId(), currentTargetLog, breakStage
                ));
            }
        }
        
        if (treeBreakingTicks >= TREE_BREAK_TIME) {
            serverLevel.destroyBlock(currentTargetLog, true);
            
            // SHAFT MINING: Check for log directly above
            BlockPos checkPos = currentTargetLog.above();
            boolean foundLogAbove = false;
            
            for (int i = 0; i < 10; i++) {
                BlockState aboveState = bot.level().getBlockState(checkPos);
                if (isLog(aboveState)) {
                    // Found log above - target it immediately
                    currentTargetLog = checkPos;
                    foundLogAbove = true;
                    break;
                }
                checkPos = checkPos.above();
            }
            
            if (!foundLogAbove) {
                lastBrokenLogPos = currentTargetLog;
                currentTargetLog = null;
                logPickupTicks = LOG_PICKUP_WAIT;
            }
            
            treeBreakingTicks = 0;
        }
    }
    
    private void clearBreakingAnimation() {
        ServerLevel serverLevel = (ServerLevel) bot.level();
        for (ServerPlayer player : serverLevel.players()) {
            if (player.distanceTo(bot) < 64) {
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                    bot.getId(), currentTargetLog, -1
                ));
            }
        }
    }
    
    private BlockPos findNearbyLog() {
        BlockPos botPos = bot.blockPosition();
        int range = 16;
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;
        
        // BARITONE-STYLE SHAFT MINING: Only look for logs in same X/Z column ABOVE the bot
        // This matches Baritone's filter: pos.getY() >= ctx.playerFeet().getY()
        for (int y = 0; y <= range; y++) {  // Changed from -range to 0 (only above)
            BlockPos checkPos = botPos.offset(0, y, 0);
            BlockState state = bot.level().getBlockState(checkPos);
            if (isLog(state)) {
                double dist = Math.abs(y);
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = checkPos;
                }
            }
        }
        
        if (closest != null) return closest;
        
        // No logs in shaft - find closest log anywhere
        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos checkPos = botPos.offset(x, y, z);
                    BlockState state = bot.level().getBlockState(checkPos);
                    if (isLog(state)) {
                        double dist = Math.sqrt(bot.distanceToSqr(Vec3.atCenterOf(checkPos)));
                        if (dist < closestDist) {
                            closestDist = dist;
                            closest = checkPos;
                        }
                    }
                }
            }
        }
        
        return closest;
    }
    
    private boolean isLog(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.RotatedPillarBlock &&
               (state.is(net.minecraft.world.level.block.Blocks.OAK_LOG) ||
                state.is(net.minecraft.world.level.block.Blocks.SPRUCE_LOG) ||
                state.is(net.minecraft.world.level.block.Blocks.BIRCH_LOG) ||
                state.is(net.minecraft.world.level.block.Blocks.JUNGLE_LOG) ||
                state.is(net.minecraft.world.level.block.Blocks.ACACIA_LOG) ||
                state.is(net.minecraft.world.level.block.Blocks.DARK_OAK_LOG));
    }
    
    public BlockPos getCurrentTargetLog() {
        return currentTargetLog;
    }
    
    public BlockPos getLastBrokenLogPos() {
        return lastBrokenLogPos;
    }
    
    public int getLogPickupTicks() {
        return logPickupTicks;
    }
    
    public void decrementLogPickupTicks() {
        if (logPickupTicks > 0) logPickupTicks--;
    }
}
