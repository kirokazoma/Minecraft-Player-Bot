package com.playerbot.bot;

import com.mojang.authlib.GameProfile;
import com.playerbot.pathfinding.PathFinder;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Fake server player that behaves like a real player
 * The server treats this as a legitimate player entity
 */
public class FakeServerPlayer extends ServerPlayer {
    
    private ServerPlayer followTarget = null;
    private static final double FOLLOW_DISTANCE = 3.0;
    private static final double FOLLOW_SPEED = 0.3;
    private static final double ATTACK_RANGE = 3.3;
    private static final int ATTACK_COOLDOWN = 10; // ticks between attacks
    private int attackCooldownTicks = 0;
    private int syncTicks = 0; // Counter for periodic sync
    private int healTicks = 0; // Counter for health regeneration
    
    // Pathfinding fields
    private PathFinder pathFinder;
    private List<BlockPos> currentPath = null;
    private int pathIndex = 0;
    private int pathRecalculateTicks = 0;
    private static final int PATH_RECALCULATE_INTERVAL = 40; // Recalculate every 2 seconds
    private int ticksAwayFromPath = 0; // Track how long bot has been off path
    private static final int MAX_TICKS_AWAY_FROM_PATH = 100; // 5 seconds = 100 ticks
    private static final double MAX_DIST_FROM_PATH = 5.0; // 5 blocks away = off path
    
    // Wander mode fields
    private boolean wanderMode = false;
    private boolean survivalMode = false; // If true, bot breaks trees and crafts weapons
    private boolean debugPath = false; // If true, show path with particles
    private BlockPos wanderTarget = null;
    private int wanderCooldown = 0;
    private int justCreatedPathTicks = 0; // Prevent immediate re-picking of target after path creation
    private static final int WANDER_COOLDOWN_TICKS = 100; // Wait 5 seconds between wander targets
    private static final int WANDER_RANGE = 20; // Wander within 20 blocks
    
    // Crafting fields
    private BlockPos craftingTableTarget = null;
    private int craftingCheckCooldown = 0;
    private static final int CRAFTING_CHECK_INTERVAL = 100; // Check every 5 seconds
    private static final double CRAFTING_TABLE_RANGE = 16.0; // Search within 16 blocks
    
    // Stuck detection fields
    private BlockPos lastPosition = null;
    private int stuckTicks = 0;
    private static final int STUCK_THRESHOLD = 160; // 8 seconds = 160 ticks
    private BlockPos stuckBreakingBlock = null;
    private int stuckBreakingTicks = 0;
    private int consecutiveStuckAtSamePos = 0; // Count how many times stuck at same position
    private BlockPos lastStuckPosition = null; // Track where bot was last stuck
    
    // Arrow clearing field
    private int arrowClearTicks = 0;
    private static final int ARROW_CLEAR_INTERVAL = 1200; // 60 seconds = 1200 ticks
    
    // Tree breaking fields
    private BlockPos targetLog = null;
    private BlockPos savedTargetLog = null; // Save log position when interrupted by combat
    private int treeBreakingTicks = 0;
    private int targetLogAttemptTicks = 0; // Timeout counter for breaking same log
    private static final int LOG_BREAK_TIMEOUT = 200; // 10 seconds timeout for breaking same log
    private static final int TREE_BREAK_TIME = 60; // 3 seconds to break a log (like a player with bare hands)
    private boolean needsCraftingTable = false;
    private java.util.Set<BlockPos> unreachableLogs = new java.util.HashSet<>(); // Logs that have no path
    private BlockPos lastBrokenLogPos = null;
    private int logPickupTicks = 0;
    private static final int LOG_PICKUP_WAIT = 40; // Navigate to broken log for 2 seconds
    private boolean inCombat = false;
    
    // Crafting table pickup fields
    private BlockPos placedCraftingTablePos = null; // Position where bot placed crafting table
    private int craftingTablePlaceTicks = 0; // Ticks since placing crafting table
    private static final int CRAFTING_TABLE_PICKUP_WINDOW = 200; // 10 seconds = 200 ticks
    private BlockPos breakingCraftingTable = null; // Crafting table currently being broken
    private int craftingTableBreakTicks = 0; // Ticks spent breaking crafting table
    private boolean craftingComplete = false; // True when bot finished crafting sword and pickaxe
    private BlockPos brokenCraftingTablePos = null; // Position where bot broke crafting table (for pickup navigation)
    private int craftingTablePickupTicks = 0; // Ticks spent navigating to broken table
    private static final int CRAFTING_TABLE_PICKUP_WAIT = 100; // Navigate to broken table for 5 seconds
    
    // Stone mining fields
    private BlockPos targetStone = null; // Stone block currently being mined
    private int stoneBreakingTicks = 0; // Ticks spent breaking stone
    private java.util.Set<BlockPos> unreachableStones = new java.util.HashSet<>(); // Stones that have no path
    private static final int COBBLESTONE_TARGET = 20; // Mine 20 cobblestone
    private int skipStoneMiningTicks = 0; // Skip stone mining for X ticks when stuck (to let wander mode take over)
    
    // Breaking control field
    private boolean breakingEnabled = true; // If false, bot will never break blocks (except in wander modes if enabled)
    
    // Death state preservation
    private boolean wasSurvivalModeBeforeDeath = false; // Track survival mode state before death
    
    public FakeServerPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
        
        // Create a fake network connection with an embedded channel
        Connection fakeConnection = new Connection(PacketFlow.SERVERBOUND);
        
        // Initialize the connection with an embedded channel (required for pipeline access)
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            // Try to find and set the channel field (it might be obfuscated)
            boolean fieldSet = false;
            for (java.lang.reflect.Field field : Connection.class.getDeclaredFields()) {
                // Look for Channel type field
                if (field.getType().getName().contains("Channel")) {
                    field.setAccessible(true);
                    field.set(fakeConnection, channel);
                    fieldSet = true;
                    break;
                }
            }
            if (!fieldSet) {
                throw new RuntimeException("Could not find channel field in Connection class");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize fake connection: " + e.getMessage(), e);
        }
        
        // Create packet listener
        this.connection = new ServerGamePacketListenerImpl(server, fakeConnection, this);
        
        // Initialize pathfinder
        this.pathFinder = new PathFinder(level);
    }

    /**
     * Override to prevent the bot from being kicked for timeout
     */
    @Override
    public void tick() {
        super.tick();
        
        // Reset last action time to prevent timeout kicks
        this.resetLastActionTime();
        
        // Decrement attack cooldown
        if (attackCooldownTicks > 0) {
            attackCooldownTicks--;
        }
        
        // Periodic sync of held item (every 20 ticks = 1 second)
        syncTicks++;
        if (syncTicks >= 20) {
            syncTicks = 0;
            // Sync equipment to nearby players without full entity refresh
            for (ServerPlayer player : ((ServerLevel)this.level()).players()) {
                if (player.distanceTo(this) < 64) { // Within render distance
                    // Send equipment update packet
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(
                        this.getId(),
                        java.util.List.of(
                            com.mojang.datafixers.util.Pair.of(net.minecraft.world.entity.EquipmentSlot.MAINHAND, this.getMainHandItem())
                        )
                    ));
                }
            }
        }
        
        // Health regeneration (every 80 ticks = 4 seconds, like peaceful mode)
        healTicks++;
        if (healTicks >= 80) {
            healTicks = 0;
            // Regenerate 1 health point if not at max health
            if (this.getHealth() < this.getMaxHealth() && !this.isDeadOrDying()) {
                this.setHealth(this.getHealth() + 1.0F);
            }
        }
        
        // Clear arrows from body (every 60 seconds)
        arrowClearTicks++;
        if (arrowClearTicks >= ARROW_CLEAR_INTERVAL) {
            arrowClearTicks = 0;
            // Clear all arrows stuck in the bot
            this.setArrowCount(0);
        }
        
        // Pick up nearby items
        pickupNearbyItems();
        
        // Pick up nearby XP orbs
        pickupNearbyXP();
        
        // Attack nearby hostile mobs
        attackNearbyHostiles();
        
        // Check if bot is stuck (in both wander and follow modes)
        if (!this.isDeadOrDying()) {
            checkIfStuck();
        }
        
        // Handle crafting table pickup FIRST (highest priority - runs before everything else)
        // ONLY break table after crafting is complete (sword + pickaxe crafted)
        if (placedCraftingTablePos != null && craftingComplete) {
            // If we just broke the table, navigate to it for pickup
            if (brokenCraftingTablePos != null) {
                craftingTablePickupTicks++;
                
                // Check if bot picked up the table
                if (countItem(net.minecraft.world.item.Items.CRAFTING_TABLE) > 0) {
                    // Bot picked up the table, clear all tracking
                    // System.out.println("[DEBUG] Bot picked up crafting table");
                    placedCraftingTablePos = null;
                    craftingTablePlaceTicks = 0;
                    breakingCraftingTable = null;
                    craftingTableBreakTicks = 0;
                    craftingComplete = false;
                    brokenCraftingTablePos = null;
                    craftingTablePickupTicks = 0;
                } else if (craftingTablePickupTicks >= CRAFTING_TABLE_PICKUP_WAIT) {
                    // 5 seconds passed, give up
                    // System.out.println("[DEBUG] Crafting table pickup timeout (5 seconds)");
                    placedCraftingTablePos = null;
                    craftingTablePlaceTicks = 0;
                    breakingCraftingTable = null;
                    craftingTableBreakTicks = 0;
                    craftingComplete = false;
                    brokenCraftingTablePos = null;
                    craftingTablePickupTicks = 0;
                } else {
                    // Navigate to broken table position using DIRECT MOVEMENT (Option 3)
                    double distanceToTable = Math.sqrt(this.distanceToSqr(Vec3.atCenterOf(brokenCraftingTablePos)));
                    
                    // System.out.println("[DEBUG] Navigating to broken crafting table at " + brokenCraftingTablePos + " (distance: " + String.format("%.2f", distanceToTable) + ", ticks: " + craftingTablePickupTicks + "/" + CRAFTING_TABLE_PICKUP_WAIT + ")");
                    
                    if (distanceToTable > 1.0) {
                        // Use direct movement when close (skip pathfinding)
                        if (distanceToTable <= 5.0) {
                            // Calculate direct vector to broken table
                            double dx = (brokenCraftingTablePos.getX() + 0.5) - this.getX();
                            double dy = brokenCraftingTablePos.getY() - this.getY();
                            double dz = (brokenCraftingTablePos.getZ() + 0.5) - this.getZ();
                            double length = Math.sqrt(dx * dx + dz * dz);
                            
                            if (length > 0.01) {
                                // Normalize direction
                                double dirX = dx / length;
                                double dirZ = dz / length;
                                
                                // Apply sprint speed
                                double moveSpeed = 0.26;
                                double moveX = dirX * moveSpeed;
                                double moveZ = dirZ * moveSpeed;
                                
                                // Look at target
                                float yaw = (float)(Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
                                float pitch = (float)(-Math.atan2(dy, length) * 180.0 / Math.PI);
                                this.setYRot(yaw);
                                this.setXRot(pitch);
                                this.yRotO = yaw;
                                this.xRotO = pitch;
                                this.setYHeadRot(yaw);
                                
                                // Enable sprinting
                                this.setSprinting(true);
                                
                                // Check if need to jump
                                boolean shouldJump = (dy > 0.5 && this.onGround());
                                double verticalVelocity = shouldJump ? 0.42 : (this.onGround() ? 0 : (this.getDeltaMovement().y * 0.98) - 0.08);
                                
                                // Apply movement directly
                                this.setDeltaMovement(moveX, verticalVelocity, moveZ);
                                this.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(moveX, verticalVelocity, moveZ));
                                
                                // System.out.println("[DEBUG] Direct movement to broken table: moveX=" + String.format("%.2f", moveX) + ", moveZ=" + String.format("%.2f", moveZ) + ", jump=" + shouldJump);
                            }
                            
                            // Return early to skip normal movement code
                            return;
                        } else {
                            // Far away (>5 blocks) - use pathfinding
                            if (currentPath == null || wanderTarget != brokenCraftingTablePos) {
                                wanderTarget = brokenCraftingTablePos;
                                currentPath = pathFinder.findPath(this.blockPosition(), brokenCraftingTablePos, 50);
                                pathIndex = 0;
                                // System.out.println("[DEBUG] Created path to broken table, path length: " + (currentPath != null ? currentPath.size() : "null"));
                            }
                            // Don't return - let normal movement code execute
                        }
                    } else {
                        // System.out.println("[DEBUG] Close to broken table, waiting for pickup");
                    }
                }
            } else {
                // Table not broken yet - break it
                craftingTablePlaceTicks++;
                
                // Check if bot has crafting table in inventory
                if (countItem(net.minecraft.world.item.Items.CRAFTING_TABLE) > 0) {
                    // Bot picked up the table, clear tracking
                    placedCraftingTablePos = null;
                    craftingTablePlaceTicks = 0;
                    breakingCraftingTable = null;
                    craftingTableBreakTicks = 0;
                    craftingComplete = false;
                } else if (craftingTablePlaceTicks >= CRAFTING_TABLE_PICKUP_WINDOW) {
                    // 10 seconds passed, stop trying to pick it up
                    // System.out.println("[DEBUG] Crafting table pickup window expired");
                    placedCraftingTablePos = null;
                    craftingTablePlaceTicks = 0;
                    breakingCraftingTable = null;
                    craftingTableBreakTicks = 0;
                    craftingComplete = false;
                } else {
                    // Within 10 second window and no table in inventory - break the bot's own table
                    // ONLY break the table at placedCraftingTablePos (the one bot placed)
                    if (placedCraftingTablePos != null) {
                        double distanceToTable = Math.sqrt(this.distanceToSqr(Vec3.atCenterOf(placedCraftingTablePos)));
                        
                        if (distanceToTable <= 5.0) {
                            // Close enough - break it with proper timing
                            breakCraftingTable(placedCraftingTablePos);
                        } else {
                            // Too far, reset breaking progress
                            if (breakingCraftingTable != null) {
                                // Clear breaking animation
                                ServerLevel serverLevel = (ServerLevel) this.level();
                                for (ServerPlayer player : serverLevel.players()) {
                                    if (player.distanceTo(this) < 64) {
                                        player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                                            this.getId(), breakingCraftingTable, -1
                                        ));
                                    }
                                }
                                breakingCraftingTable = null;
                                craftingTableBreakTicks = 0;
                            }
                        }
                    }
                }
            }
        }
        
        // Handle wander mode (creates random paths) - runs AFTER crafting table pickup
        if (wanderMode && followTarget == null) {
            // System.out.println("[DEBUG] aiStep: Before handleWanderMode - wanderTarget=" + wanderTarget + ", currentPath=" + (currentPath != null ? "exists" : "null") + ", justCreatedPathTicks=" + justCreatedPathTicks);
            handleWanderMode();
            // System.out.println("[DEBUG] aiStep: After handleWanderMode - wanderTarget=" + wanderTarget + ", currentPath=" + (currentPath != null ? "exists" : "null") + ", justCreatedPathTicks=" + justCreatedPathTicks);
        }
        
        // Handle tree breaking and crafting table creation (ONLY in survival mode)
        // Runs AFTER wander mode so it can override random wander paths
        if (survivalMode && wanderMode && !this.isDeadOrDying()) {
            // System.out.println("[DEBUG] aiStep: Before handleTreeBreakingAndCrafting - wanderTarget=" + wanderTarget + ", currentPath=" + (currentPath != null ? "exists" : "null"));
            handleTreeBreakingAndCrafting();
            // System.out.println("[DEBUG] aiStep: After handleTreeBreakingAndCrafting - wanderTarget=" + wanderTarget + ", currentPath=" + (currentPath != null ? "exists" : "null"));
        }
        
        // Handle stone mining (ONLY in survival mode, after getting wooden pickaxe)
        // Runs AFTER tree breaking so it can override tree paths when ready
        // Skip if bot is stuck and needs to escape (skipStoneMiningTicks > 0)
        if (skipStoneMiningTicks > 0) {
            skipStoneMiningTicks--;
            if (this.tickCount % 20 == 0) {
                // System.out.println("[DEBUG] Skipping stone mining for " + skipStoneMiningTicks + " more ticks (letting wander mode take over)");
            }
        } else if (survivalMode && wanderMode && !this.isDeadOrDying() && hasPickaxe()) {
            // System.out.println("[DEBUG] aiStep: Before handleStoneMining - wanderTarget=" + wanderTarget + ", currentPath=" + (currentPath != null ? "exists" : "null"));
            handleStoneMining();
            // System.out.println("[DEBUG] aiStep: After handleStoneMining - wanderTarget=" + wanderTarget + ", currentPath=" + (currentPath != null ? "exists" : "null"));
        }
        
        // Handle crafting in wander mode (always check for weapon crafting if materials available)
        if (wanderMode && followTarget == null && !this.isDeadOrDying()) {
            handleCrafting();
        }
        
        // Handle follow behavior with pathfinding
        double moveX = 0;
        double moveZ = 0;
        boolean shouldJump = false;
        
        // Don't move if ACTIVELY breaking a tree AND close to it
        // Calculate if we're close to target log
        boolean isCloseToLog = false;
        if (targetLog != null && treeBreakingTicks > 0) {
            double distance;
            if (targetLog.getY() > this.blockPosition().getY()) {
                // Log is above - check horizontal distance
                double dx = (targetLog.getX() + 0.5) - this.getX();
                double dz = (targetLog.getZ() + 0.5) - this.getZ();
                distance = Math.sqrt(dx * dx + dz * dz);
                
                // For logs above, need to be VERY close horizontally (directly under)
                double verticalDist = targetLog.getY() - this.getY();
                double maxHorizontalReach = Math.max(0.3, 1.5 - (verticalDist * 0.2));
                isCloseToLog = (distance <= maxHorizontalReach);
            } else {
                // Log is at same level or below - use 3D distance
                distance = Math.sqrt(this.distanceToSqr(Vec3.atCenterOf(targetLog)));
                isCloseToLog = (distance <= 1.5);
            }
        }
        // Block movement if: breaking tree AND close AND no path, OR breaking stuck block, OR breaking crafting table, OR breaking stone
        boolean isBreakingTree = (treeBreakingTicks > 0 && isCloseToLog && currentPath == null);
        boolean isBreakingStuckBlock = (stuckBreakingBlock != null);
        boolean isBreakingCraftingTable = (breakingCraftingTable != null);
        boolean isBreakingStone = (stoneBreakingTicks > 0 && targetStone != null);
        
        if (!isBreakingTree && !isBreakingStuckBlock && !isBreakingCraftingTable && !isBreakingStone && followTarget != null && !followTarget.isRemoved() && !this.isDeadOrDying()) {
            double distance = this.distanceTo(followTarget);
            
            // Enable sprinting when following
            this.setSprinting(distance > FOLLOW_DISTANCE);
            
            // Only navigate if target is far enough
            if (distance > FOLLOW_DISTANCE) {
                // Recalculate path periodically or if no path exists
                pathRecalculateTicks++;
                if (currentPath == null || pathRecalculateTicks >= PATH_RECALCULATE_INTERVAL) {
                    pathRecalculateTicks = 0;
                    BlockPos botPos = this.blockPosition();
                    BlockPos targetPos = followTarget.blockPosition();
                    
                    // Calculate new path (max 50 blocks)
                    currentPath = pathFinder.findPath(botPos, targetPos, 50);
                    pathIndex = 0;
                    
                    // If no path found, clear it
                    if (currentPath == null || currentPath.isEmpty()) {
                        currentPath = null;
                    }
                }
            } else {
                // Close enough, clear path
                currentPath = null;
                pathIndex = 0;
            }
        } else if (wanderMode && !isBreakingTree && !isBreakingStuckBlock && !isBreakingCraftingTable && !isBreakingStone && currentPath != null) {
            // In wander mode with a path - only sprint if bot has a specific task
            // Sprint when: picking up logs, navigating to logs, going to crafting table, OR picking up broken table
            boolean hasTask = (lastBrokenLogPos != null) || (targetLog != null) || (craftingTableTarget != null) || (brokenCraftingTablePos != null);
            this.setSprinting(hasTask);
            
            // Debug for crafting table pickup
            if (brokenCraftingTablePos != null && this.tickCount % 10 == 0) {
                // System.out.println("[DEBUG] Wander mode: has path, sprinting=" + hasTask + ", pathIndex=" + pathIndex + "/" + currentPath.size());
            }
        } else if (!wanderMode && !isBreakingTree && !isBreakingStuckBlock && !isBreakingCraftingTable && !isBreakingStone) {
            // Not following and not wandering - disable sprinting and clear path
            this.setSprinting(false);
            currentPath = null;
            pathIndex = 0;
        }
        
        // Follow the path if it exists (for both follow and wander modes)
        // Debug for crafting table pickup - check conditions ALWAYS when picking up table
        if (brokenCraftingTablePos != null) {
            // System.out.println("[DEBUG] Path follow check: isBreakingTree=" + isBreakingTree + ", isBreakingStuckBlock=" + isBreakingStuckBlock + ", isBreakingCraftingTable=" + isBreakingCraftingTable + ", isBreakingStone=" + isBreakingStone + ", currentPath=" + (currentPath != null ? "exists" : "null") + ", pathIndex=" + pathIndex + "/" + (currentPath != null ? currentPath.size() : "N/A"));
        }
        
        if (!isBreakingTree && !isBreakingStuckBlock && !isBreakingCraftingTable && !isBreakingStone && currentPath != null && pathIndex < currentPath.size() && !this.isDeadOrDying()) {
            // Debug for crafting table pickup
            if (brokenCraftingTablePos != null) {
                // System.out.println("[DEBUG] Movement code EXECUTING");
            }
            
            // Debug: Show path with particles if enabled
            if (debugPath) {
                showPathParticles();
            }
            
            // Debug for crafting table pickup
            if (brokenCraftingTablePos != null && this.tickCount % 10 == 0) {
                // System.out.println("[DEBUG] Following path: pathIndex=" + pathIndex + "/" + currentPath.size() + ", nextWaypoint=" + currentPath.get(pathIndex));
            }
            
            BlockPos nextWaypoint = currentPath.get(pathIndex);
            
            // Check if we reached current waypoint
            double waypointDist = Math.sqrt(
                Math.pow(nextWaypoint.getX() + 0.5 - this.getX(), 2) +
                Math.pow(nextWaypoint.getZ() + 0.5 - this.getZ(), 2)
            );
            
            if (waypointDist < 0.5) {
                // Reached waypoint, move to next
                pathIndex++;
                if (pathIndex < currentPath.size()) {
                    nextWaypoint = currentPath.get(pathIndex);
                } else {
                    // Reached end of path
                    currentPath = null;
                    ticksAwayFromPath = 0; // Reset counter when path completes
                    // If in wander mode, clear target and pick new one immediately (no cooldown)
                    // BUT don't clear if:
                    // 1. Target is broken crafting table (let pickup logic handle it)
                    // 2. Target is a log being broken (let tree breaking logic handle it)
                    // 3. Target is stone being mined (let stone mining logic handle it)
                    if (wanderMode && wanderTarget != null && 
                        wanderTarget != brokenCraftingTablePos &&
                        wanderTarget != targetLog &&
                        wanderTarget != targetStone) {
                        wanderTarget = null;
                        wanderCooldown = 0; // No cooldown - pick new target immediately
                    }
                }
            }
            
            // Check if bot is too far from current waypoint (Baritone-style path validation)
            if (currentPath != null && pathIndex < currentPath.size()) {
                double distToWaypoint = Math.sqrt(this.distanceToSqr(Vec3.atCenterOf(nextWaypoint)));
                
                if (distToWaypoint > MAX_DIST_FROM_PATH) {
                    ticksAwayFromPath++;
                    // System.out.println("[DEBUG] Bot is " + String.format("%.2f", distToWaypoint) + " blocks away from path (tick " + ticksAwayFromPath + "/" + MAX_TICKS_AWAY_FROM_PATH + ")");
                    
                    if (ticksAwayFromPath >= MAX_TICKS_AWAY_FROM_PATH) {
                        // System.out.println("[DEBUG] Bot too far from path for 5 seconds, cancelling path and recalculating");
                        currentPath = null;
                        pathIndex = 0;
                        ticksAwayFromPath = 0;
                        // Don't clear targets - let bot recalculate path to same target
                    }
                } else {
                    ticksAwayFromPath = 0; // Reset when back on path
                }
            }
            
            if (currentPath != null && pathIndex < currentPath.size()) {
                // Calculate direction to next waypoint
                double dx = (nextWaypoint.getX() + 0.5) - this.getX();
                double dy = nextWaypoint.getY() - this.getY();
                double dz = (nextWaypoint.getZ() + 0.5) - this.getZ();
                
                double length = Math.sqrt(dx * dx + dz * dz);
                
                if (length > 0) {
                    // Normalize direction
                    double dirX = dx / length;
                    double dirZ = dz / length;
                    
                    // Apply movement speed
                    double moveSpeed = this.isSprinting() ? 0.26 : 0.2;
                    moveX = dirX * moveSpeed;
                    moveZ = dirZ * moveSpeed;
                    
                    // Check for doors in the path and open them (after moveX/moveZ are set)
                    checkAndOpenDoors(moveX, moveZ);
                    
                    // Check if we need to jump (waypoint is higher)
                    if (dy > 0.5 && this.onGround()) {
                        shouldJump = true;
                    }
                    
                    // Look at waypoint
                    float yaw = (float)(Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
                    float pitch = (float)(-Math.atan2(dy, length) * 180.0 / Math.PI); // Negate for correct pitch direction
                    this.setYRot(yaw);
                    this.setXRot(pitch);
                    this.yRotO = yaw;
                    this.xRotO = pitch;
                    this.setYHeadRot(yaw);
                }
            }
        }
        
        // Check for doors when moving (even without path)
        if ((moveX != 0 || moveZ != 0) && !this.isDeadOrDying()) {
            checkAndOpenDoors(moveX, moveZ);
        }
        
        // Apply movement with proper gravity/swimming/ladder climbing
        double verticalVelocity;
        
        // Check if in water by checking multiple positions (feet, body, eyes)
        BlockPos feetPos = this.blockPosition();
        BlockPos bodyPos = new BlockPos((int)this.getX(), (int)(this.getY() + 0.5), (int)this.getZ());
        BlockPos eyePos = BlockPos.containing(this.getEyePosition());
        
        boolean feetInWater = this.level().getBlockState(feetPos).getFluidState().is(net.minecraft.tags.FluidTags.WATER);
        boolean bodyInWater = this.level().getBlockState(bodyPos).getFluidState().is(net.minecraft.tags.FluidTags.WATER);
        boolean eyesInWater = this.level().getBlockState(eyePos).getFluidState().is(net.minecraft.tags.FluidTags.WATER);
        
        boolean inWater = feetInWater || bodyInWater || eyesInWater || this.isInWater() || this.isUnderWater();
        
        // Check if on ladder
        BlockState feetState = this.level().getBlockState(feetPos);
        BlockState bodyState = this.level().getBlockState(bodyPos);
        boolean onLadder = feetState.getBlock() instanceof net.minecraft.world.level.block.LadderBlock ||
                          bodyState.getBlock() instanceof net.minecraft.world.level.block.LadderBlock ||
                          feetState.getBlock() instanceof net.minecraft.world.level.block.VineBlock ||
                          bodyState.getBlock() instanceof net.minecraft.world.level.block.VineBlock;
        
        if (onLadder) {
            // On ladder - check if we need to climb up or down
            boolean shouldClimbUp = false;
            boolean shouldClimbDown = false;
            boolean atCorrectHeight = false;
            
            if (currentPath != null && pathIndex < currentPath.size()) {
                BlockPos nextWaypoint = currentPath.get(pathIndex);
                double dy = nextWaypoint.getY() - this.getY();
                
                if (dy > 0.5) {
                    shouldClimbUp = true;
                } else if (dy < -0.5) {
                    shouldClimbDown = true;
                } else {
                    atCorrectHeight = true;
                }
            }
            
            if (shouldClimbUp) {
                verticalVelocity = 0.15; // Climb up
                // Slow down horizontal movement while climbing
                moveX *= 0.15;
                moveZ *= 0.15;
            } else if (shouldClimbDown) {
                verticalVelocity = -0.15; // Climb down
                // Slow down horizontal movement while climbing
                moveX *= 0.15;
                moveZ *= 0.15;
            } else if (atCorrectHeight && (moveX != 0 || moveZ != 0)) {
                // At correct height and moving - jump to get off ladder
                verticalVelocity = 0.42; // Jump off ladder
                // Don't slow down horizontal movement - let bot walk off the ladder
            } else {
                // At correct height but not moving
                verticalVelocity = 0;
            }
        } else if (inWater) {
            // Check if there's a solid block in front that we need to jump onto
            boolean needsJumpInWater = false;
            if (moveX != 0 || moveZ != 0) {
                // Calculate position in front of bot
                double dirX = moveX / Math.max(0.01, Math.sqrt(moveX * moveX + moveZ * moveZ));
                double dirZ = moveZ / Math.max(0.01, Math.sqrt(moveX * moveX + moveZ * moveZ));
                
                BlockPos frontPos = new BlockPos(
                    (int)Math.floor(this.getX() + dirX),
                    (int)Math.floor(this.getY()),
                    (int)Math.floor(this.getZ() + dirZ)
                );
                
                BlockState frontBlock = this.level().getBlockState(frontPos);
                BlockState aboveFrontBlock = this.level().getBlockState(frontPos.above());
                
                // If there's a solid block in front (not water), jump to get onto it
                if (!frontBlock.isAir() && !frontBlock.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                    needsJumpInWater = true;
                }
            }
            
            // In water - jump if blocked, otherwise sink slowly
            if (needsJumpInWater) {
                // Jump to get onto block
                verticalVelocity = 0.42;
            } else if (wanderMode) {
                // In wander mode - sink normally but swim up when getting too deep
                // Check if bot is deep underwater (body or eyes in water)
                if (bodyInWater || eyesInWater) {
                    // Too deep, swim up to surface
                    verticalVelocity = 0.1;
                } else {
                    // Near surface, sink slowly
                    verticalVelocity = -0.03;
                }
            } else if (followTarget != null && followTarget.getY() > this.getY() + 0.5) {
                // Following target above - swim up
                verticalVelocity = 0.15;
            } else if (followTarget != null && followTarget.getY() < this.getY() - 0.5) {
                // Following target below - sink faster
                verticalVelocity = -0.1;
            } else {
                // No target or target at same level - sink slowly
                verticalVelocity = -0.03;
            }
        } else if (shouldJump) {
            verticalVelocity = 0.42; // Jump
        } else if (!this.onGround()) {
            // Apply Minecraft physics: air drag first, then gravity
            verticalVelocity = (this.getDeltaMovement().y * 0.98) - 0.08;
            // Cap at terminal velocity
            if (verticalVelocity < -3.92) {
                verticalVelocity = -3.92;
            }
        } else {
            verticalVelocity = 0; // On ground, no vertical movement
        }
        
        // Update velocity state so it persists for next tick
        this.setDeltaMovement(moveX, verticalVelocity, moveZ);
        
        // Apply movement
        Vec3 movement = new Vec3(moveX, verticalVelocity, moveZ);
        this.move(net.minecraft.world.entity.MoverType.SELF, movement);
    }
    
    /**
     * Actively pick up items in range
     */
    private void pickupNearbyItems() {
        if (this.isSpectator() || this.isDeadOrDying()) return;
        
        // Get items within pickup range (1 block radius)
        AABB pickupBox = this.getBoundingBox().inflate(1.0, 0.5, 1.0);
        java.util.List<ItemEntity> items = this.level().getEntitiesOfClass(ItemEntity.class, pickupBox);
        
        for (ItemEntity itemEntity : items) {
            if (!itemEntity.isRemoved() && itemEntity.isAlive()) {
                // Get the item stack
                net.minecraft.world.item.ItemStack itemStack = itemEntity.getItem();
                
                // Try to add to inventory
                if (this.getInventory().add(itemStack)) {
                    // Successfully added, play pickup sound and animation
                    this.take(itemEntity, itemStack.getCount());
                    
                    // Remove the item entity if stack is empty
                    if (itemStack.isEmpty()) {
                        itemEntity.discard();
                    }
                }
            }
        }
    }
    
    /**
     * Pick up nearby XP orbs
     */
    private void pickupNearbyXP() {
        if (this.isSpectator() || this.isDeadOrDying()) return;
        
        // Get XP orbs within pickup range (1 block radius)
        AABB pickupBox = this.getBoundingBox().inflate(1.0, 0.5, 1.0);
        java.util.List<net.minecraft.world.entity.ExperienceOrb> xpOrbs = 
            this.level().getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, pickupBox);
        
        for (net.minecraft.world.entity.ExperienceOrb orb : xpOrbs) {
            if (!orb.isRemoved()) {
                // Add XP to player
                this.giveExperiencePoints(orb.getValue());
                // Play pickup sound
                this.take(orb, 1);
                // Remove orb
                orb.discard();
            }
        }
    }
    
    /**
     * Attack nearby hostile mobs
     */
    private void attackNearbyHostiles() {
        if (this.isSpectator() || this.isDeadOrDying() || attackCooldownTicks > 0) return;
        
        // Find hostile mobs within attack range
        AABB attackBox = this.getBoundingBox().inflate(ATTACK_RANGE);
        java.util.List<Mob> hostiles = this.level().getEntitiesOfClass(Mob.class, attackBox, 
            entity -> entity instanceof Enemy && entity.isAlive() && !entity.isRemoved());
        
        if (hostiles.isEmpty()) {
            // No hostiles, exit combat mode
            if (inCombat) {
                inCombat = false;
                // Restore saved log target after combat
                if (savedTargetLog != null) {
                    targetLog = savedTargetLog;
                    savedTargetLog = null;
                }
            }
            return;
        }
        
        // Find closest hostile that is visible (line of sight)
        Mob closestHostile = null;
        double closestDistance = Double.MAX_VALUE;
        
        for (Mob hostile : hostiles) {
            double distance = this.distanceTo(hostile);
            // Check if within range AND has line of sight
            if (distance < closestDistance && distance <= ATTACK_RANGE && this.hasLineOfSight(hostile)) {
                closestDistance = distance;
                closestHostile = hostile;
            }
        }
        
        if (closestHostile != null) {
            // Entering combat - save log and clear breaking animation
            if (!inCombat && treeBreakingTicks > 0 && targetLog != null) {
                // Clear breaking animation
                ServerLevel serverLevel = (ServerLevel) this.level();
                for (ServerPlayer player : serverLevel.players()) {
                    if (player.distanceTo(this) < 64) {
                        player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                            this.getId(), targetLog, -1 // -1 clears the breaking animation
                        ));
                    }
                }
                // Save the log we were breaking
                savedTargetLog = targetLog;
                targetLog = null;
                treeBreakingTicks = 0;
            }
            
            inCombat = true;
            
            // Equip best weapon
            equipBestWeapon();
            
            // Look at target
            double dx = closestHostile.getX() - this.getX();
            double dy = closestHostile.getY() + closestHostile.getEyeHeight() - this.getEyeY();
            double dz = closestHostile.getZ() - this.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            
            float yaw = (float)(Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
            float pitch = (float)(-Math.atan2(dy, length) * 180.0 / Math.PI); // Negate for correct pitch direction
            this.setYRot(yaw);
            this.setXRot(pitch);
            this.setYHeadRot(yaw);
            
            // Attack the mob
            this.attack(closestHostile);
            
            // Play swing animation
            this.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
            
            // Set cooldown
            attackCooldownTicks = ATTACK_COOLDOWN;
        }
    }
    
    /**
     * Equip the best weapon from inventory
     */
    private void equipBestWeapon() {
        int oldSelected = this.getInventory().selected;
        
        // Find best weapon in hotbar first (slots 0-8)
        int bestSlot = -1;
        double bestDamage = 1.0;
        
        for (int i = 0; i < 9; i++) {
            ItemStack stack = this.getInventory().getItem(i);
            double damage = getWeaponDamage(stack);
            
            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = i;
            }
        }
        
        // If found a better weapon in hotbar, select it
        if (bestSlot != -1 && bestSlot != oldSelected) {
            this.getInventory().selected = bestSlot;
            return;
        }
        
        // Otherwise, look in main inventory and move to hotbar
        for (int i = 9; i < this.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.getInventory().getItem(i);
            double damage = getWeaponDamage(stack);
            
            if (damage > bestDamage) {
                // Move to first hotbar slot
                ItemStack currentHotbar = this.getInventory().getItem(0);
                this.getInventory().setItem(i, currentHotbar);
                this.getInventory().setItem(0, stack);
                this.getInventory().selected = 0;
                return;
            }
        }
    }
    
    /**
     * Override attack to use weapon damage properly
     */
    @Override
    public void attack(Entity target) {
        if (target.isAttackable() && !target.skipAttackInteraction(this)) {
            // Get weapon and calculate damage
            ItemStack weapon = this.getMainHandItem();
            float damage = (float) this.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            
            // Add weapon-specific damage
            if (!weapon.isEmpty()) {
                if (weapon.getItem() instanceof SwordItem sword) {
                    damage += sword.getDamage();
                } else if (weapon.getItem() instanceof AxeItem axe) {
                    damage += axe.getAttackDamage();
                } else if (weapon.getItem() instanceof TridentItem) {
                    damage += 8.0F;
                }
            }
            
            // Apply damage
            if (damage > 0.0F) {
                target.hurt(this.damageSources().playerAttack(this), damage);
                
                // Damage weapon durability
                if (!weapon.isEmpty() && target instanceof net.minecraft.world.entity.LivingEntity) {
                    weapon.hurtAndBreak(1, this, (player) -> {
                        player.broadcastBreakEvent(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
                    });
                }
                
                // Apply knockback
                if (target instanceof net.minecraft.world.entity.LivingEntity livingTarget) {
                    livingTarget.knockback(0.4F, 
                        Math.sin(this.getYRot() * ((float)Math.PI / 180F)), 
                        -Math.cos(this.getYRot() * ((float)Math.PI / 180F)));
                }
            }
        }
    }
    
    /**
     * Get weapon damage value
     */
    private double getWeaponDamage(ItemStack stack) {
        if (stack.isEmpty()) return 1.0; // Fist damage
        
        if (stack.getItem() instanceof SwordItem sword) {
            return sword.getDamage() + 4.0;
        } else if (stack.getItem() instanceof AxeItem axe) {
            return axe.getAttackDamage() + 1.0;
        } else if (stack.getItem() instanceof TridentItem) {
            return 9.0;
        }
        
        return 1.0;
    }
    
    /**
     * Equip the best tool for breaking a specific block type
     * Returns the break speed multiplier for the equipped tool
     */
    private float equipBestToolForBlock(BlockState state) {
        boolean requiresPickaxe = state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE);
        boolean requiresShovel = state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL);
        boolean requiresAxe = state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE);
        
        if (!requiresPickaxe && !requiresShovel && !requiresAxe) {
            // Block doesn't need a tool, use hands
            return 1.0f;
        }
        
        int oldSelected = this.getInventory().selected;
        int bestSlot = -1;
        int bestTier = -1;
        
        // Search hotbar first (slots 0-8)
        for (int i = 0; i < 9; i++) {
            ItemStack stack = this.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            
            int tier = getToolTier(stack, requiresPickaxe, requiresShovel, requiresAxe);
            if (tier > bestTier) {
                bestTier = tier;
                bestSlot = i;
            }
        }
        
        // If found tool in hotbar, select it
        if (bestSlot != -1 && bestSlot != oldSelected) {
            this.getInventory().selected = bestSlot;
            return getToolSpeedMultiplier(bestTier);
        }
        
        // Search main inventory (slots 9-35) and move to hotbar
        for (int i = 9; i < this.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            
            int tier = getToolTier(stack, requiresPickaxe, requiresShovel, requiresAxe);
            if (tier > bestTier) {
                // Move to first hotbar slot
                ItemStack currentHotbar = this.getInventory().getItem(0);
                this.getInventory().setItem(i, currentHotbar);
                this.getInventory().setItem(0, stack);
                this.getInventory().selected = 0;
                return getToolSpeedMultiplier(tier);
            }
        }
        
        // No tool found, using hands
        return 1.0f;
    }
    
    /**
     * Get tool tier: -1 = wrong tool, 0 = wood, 1 = stone, 2 = iron, 3 = diamond, 4 = netherite
     */
    private int getToolTier(ItemStack stack, boolean needsPickaxe, boolean needsShovel, boolean needsAxe) {
        net.minecraft.world.item.Item item = stack.getItem();
        
        // Check pickaxes
        if (needsPickaxe && item instanceof net.minecraft.world.item.PickaxeItem) {
            if (item == net.minecraft.world.item.Items.NETHERITE_PICKAXE) return 4;
            if (item == net.minecraft.world.item.Items.DIAMOND_PICKAXE) return 3;
            if (item == net.minecraft.world.item.Items.IRON_PICKAXE) return 2;
            if (item == net.minecraft.world.item.Items.STONE_PICKAXE) return 1;
            if (item == net.minecraft.world.item.Items.WOODEN_PICKAXE) return 0;
        }
        
        // Check shovels
        if (needsShovel && item instanceof net.minecraft.world.item.ShovelItem) {
            if (item == net.minecraft.world.item.Items.NETHERITE_SHOVEL) return 4;
            if (item == net.minecraft.world.item.Items.DIAMOND_SHOVEL) return 3;
            if (item == net.minecraft.world.item.Items.IRON_SHOVEL) return 2;
            if (item == net.minecraft.world.item.Items.STONE_SHOVEL) return 1;
            if (item == net.minecraft.world.item.Items.WOODEN_SHOVEL) return 0;
        }
        
        // Check axes
        if (needsAxe && item instanceof net.minecraft.world.item.AxeItem) {
            if (item == net.minecraft.world.item.Items.NETHERITE_AXE) return 4;
            if (item == net.minecraft.world.item.Items.DIAMOND_AXE) return 3;
            if (item == net.minecraft.world.item.Items.IRON_AXE) return 2;
            if (item == net.minecraft.world.item.Items.STONE_AXE) return 1;
            if (item == net.minecraft.world.item.Items.WOODEN_AXE) return 0;
        }
        
        return -1; // Wrong tool type
    }
    
    /**
     * Get speed multiplier based on tool tier
     * Minecraft tool speeds: wood=2x, stone=4x, iron=6x, diamond=8x, netherite=9x
     */
    private float getToolSpeedMultiplier(int tier) {
        switch (tier) {
            case 0: return 2.0f;  // Wood
            case 1: return 4.0f;  // Stone
            case 2: return 6.0f;  // Iron
            case 3: return 8.0f;  // Diamond
            case 4: return 9.0f;  // Netherite
            default: return 1.0f; // Hands
        }
    }
    
    /**
     * Set the player this bot should follow
     */
    public void setFollowTarget(ServerPlayer target) {
        this.followTarget = target;
        // Clear path when target changes
        this.currentPath = null;
        this.pathIndex = 0;
    }
    
    /**
     * Get the current follow target
     */
    public ServerPlayer getFollowTarget() {
        return this.followTarget;
    }
    
    /**
     * Stop following
     */
    public void stopFollowing() {
        this.followTarget = null;
        // Clear path when stopping
        this.currentPath = null;
        this.pathIndex = 0;
    }
    
    /**
     * Enable wander mode - bot walks around randomly
     */
    public void setWanderMode(boolean enabled) {
        this.wanderMode = enabled;
        if (!enabled) {
            this.wanderTarget = null;
            this.currentPath = null;
            this.pathIndex = 0;
            this.survivalMode = false; // Disable survival mode when wander is disabled
        }
    }
    
    /**
     * Check if wander mode is enabled
     */
    public boolean isWandering() {
        return this.wanderMode;
    }
    
    /**
     * Enable survival mode - bot breaks trees and crafts weapons
     */
    public void setSurvivalMode(boolean enabled) {
        this.survivalMode = enabled;
        if (enabled) {
            this.wanderMode = true; // Survival mode requires wander mode
        }
    }
    
    /**
     * Check if survival mode is enabled
     */
    public boolean isSurvivalMode() {
        return this.survivalMode;
    }
    
    /**
     * Enable debug path visualization
     */
    public void setDebugPath(boolean enabled) {
        this.debugPath = enabled;
    }
    
    /**
     * Check if debug path is enabled
     */
    public boolean isDebugPath() {
        return this.debugPath;
    }
    
    /**
     * Enable or disable block breaking
     */
    public void setBreakingEnabled(boolean enabled) {
        this.breakingEnabled = enabled;
        if (!enabled) {
            // Clear all breaking state when disabled
            stuckBreakingBlock = null;
            stuckBreakingTicks = 0;
            targetLog = null;
            treeBreakingTicks = 0;
            targetStone = null;
            stoneBreakingTicks = 0;
            breakingCraftingTable = null;
            craftingTableBreakTicks = 0;
        }
    }
    
    /**
     * Check if block breaking is enabled
     */
    public boolean isBreakingEnabled() {
        return this.breakingEnabled;
    }
    
    /**
     * Check for doors in front of bot and open them
     */
    private void checkAndOpenDoors(double moveX, double moveZ) {
        // Calculate positions to check (in front of bot)
        double dirLength = Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (dirLength < 0.01) return;
        
        double dirX = moveX / dirLength;
        double dirZ = moveZ / dirLength;
        
        // Check 1-2 blocks in front AND adjacent blocks (wider detection)
        for (int dist = 1; dist <= 2; dist++) {
            BlockPos checkPos = new BlockPos(
                (int)Math.floor(this.getX() + dirX * dist),
                (int)Math.floor(this.getY()),
                (int)Math.floor(this.getZ() + dirZ * dist)
            );
            
            // Check main position and adjacent positions (left/right)
            BlockPos[] positionsToCheck = {
                checkPos,
                checkPos.north(),
                checkPos.south(),
                checkPos.east(),
                checkPos.west()
            };
            
            for (BlockPos pos : positionsToCheck) {
                // Check at feet level
                BlockState state = this.level().getBlockState(pos);
                if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
                    openDoor(pos, state);
                }
                
                // Check one block above (for tall doors)
                BlockPos posAbove = pos.above();
                BlockState stateAbove = this.level().getBlockState(posAbove);
                if (stateAbove.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
                    openDoor(posAbove, stateAbove);
                }
                
                // Check if it's a fence gate
                if (state.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock) {
                    openFenceGate(pos, state);
                }
            }
        }
    }
    
    /**
     * Check if bot is stuck and break obstacles if stuck
     * Only checks horizontal movement (X, Z) to detect stuck jumping
     * ONLY breaks blocks in wander modes when breaking is enabled
     */
    private void checkIfStuck() {
        BlockPos currentPos = this.blockPosition();
        
        // Check if horizontal position changed (ignore Y to catch stuck jumping)
        if (lastPosition != null) {
            // Calculate horizontal distance moved
            double horizontalDist = Math.sqrt(
                Math.pow(currentPos.getX() - lastPosition.getX(), 2) +
                Math.pow(currentPos.getZ() - lastPosition.getZ(), 2)
            );
            
            // If moved less than 1 block horizontally, consider stuck
            if (horizontalDist < 1.0) {
                stuckTicks++;
                
                // If stuck for 8 seconds, break blocking obstacles
                // ONLY break if in wander mode AND breaking is enabled
                if (stuckTicks >= STUCK_THRESHOLD && wanderMode && breakingEnabled) {
                    // System.out.println("[DEBUG] Bot stuck for 8 seconds at " + currentPos + ", breaking obstacles");
                    
                    // Track if this is the same position as last stuck event
                    if (lastStuckPosition != null && lastStuckPosition.equals(currentPos)) {
                        consecutiveStuckAtSamePos++;
                        // System.out.println("[DEBUG] Bot stuck at same position " + consecutiveStuckAtSamePos + " times");
                    } else {
                        consecutiveStuckAtSamePos = 1;
                        lastStuckPosition = currentPos;
                    }
                    
                    breakObstaclesInFront();
                    // Don't reset stuckTicks here - let breakBlockSlowly reset it after breaking
                } else if (stuckTicks >= STUCK_THRESHOLD && (!wanderMode || !breakingEnabled)) {
                    // Stuck but can't break - just reset counter and try to continue
                    // System.out.println("[DEBUG] Bot stuck but breaking disabled (wanderMode=" + wanderMode + ", breakingEnabled=" + breakingEnabled + ")");
                    stuckTicks = 0;
                }
            } else {
                // Moved horizontally, reset stuck counter (but not if we're actively breaking)
                if (stuckBreakingBlock == null) {
                    stuckTicks = 0;
                    // Also reset consecutive stuck counter when bot moves
                    consecutiveStuckAtSamePos = 0;
                    lastStuckPosition = null;
                }
                
                // Clear unreachable logs blacklist when bot moves significantly (10+ blocks)
                double totalMovement = Math.sqrt(
                    Math.pow(currentPos.getX() - lastPosition.getX(), 2) +
                    Math.pow(currentPos.getZ() - lastPosition.getZ(), 2)
                );
                if (totalMovement > 10.0) {
                    if (!unreachableLogs.isEmpty()) {
                        // System.out.println("[DEBUG] Bot moved " + String.format("%.2f", totalMovement) + " blocks, clearing unreachable logs blacklist");
                        unreachableLogs.clear();
                    }
                }
            }
            
            // ALWAYS update lastPosition every tick (moved outside the else block)
            lastPosition = currentPos;
        } else {
            // First check, initialize position
            lastPosition = currentPos;
        }
    }
    
    /**
     * Break obstacles (leaves or solid blocks) in front of bot when stuck
     * Uses path direction if available, otherwise uses facing direction
     */
    private void breakObstaclesInFront() {
        double dirX = 0;
        double dirZ = 0;
        
        // If bot has a path, use the direction to next waypoint
        if (currentPath != null && pathIndex < currentPath.size()) {
            BlockPos nextWaypoint = currentPath.get(pathIndex);
            double dx = (nextWaypoint.getX() + 0.5) - this.getX();
            double dz = (nextWaypoint.getZ() + 0.5) - this.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            
            if (length > 0.1) {
                dirX = dx / length;
                dirZ = dz / length;
                // System.out.println("[DEBUG] Breaking obstacles in path direction to waypoint " + nextWaypoint);
            }
        }
        
        // If no path or direction is zero, use bot's facing direction
        if (dirX == 0 && dirZ == 0) {
            double yawRad = Math.toRadians(this.getYRot() + 90);
            dirX = Math.cos(yawRad);
            dirZ = Math.sin(yawRad);
            // System.out.println("[DEBUG] Breaking obstacles in facing direction (no path)");
        }
        
        boolean foundBreakableBlock = false;
        
        // Calculate perpendicular direction for left/right checking
        double perpX = -dirZ; // Perpendicular to direction (90 degrees)
        double perpZ = dirX;
        
        // Check blocks in the movement direction at bot's height and above
        // Check center, left, and right to handle corners properly
        for (int dist = 1; dist <= 2; dist++) {
            // Center (straight ahead)
            BlockPos checkPos = new BlockPos(
                (int)Math.floor(this.getX() + dirX * dist),
                (int)Math.floor(this.getY()),
                (int)Math.floor(this.getZ() + dirZ * dist)
            );
            
            // Left (perpendicular)
            BlockPos checkPosLeft = new BlockPos(
                (int)Math.floor(this.getX() + dirX * dist + perpX * 0.5),
                (int)Math.floor(this.getY()),
                (int)Math.floor(this.getZ() + dirZ * dist + perpZ * 0.5)
            );
            
            // Right (perpendicular)
            BlockPos checkPosRight = new BlockPos(
                (int)Math.floor(this.getX() + dirX * dist - perpX * 0.5),
                (int)Math.floor(this.getY()),
                (int)Math.floor(this.getZ() + dirZ * dist - perpZ * 0.5)
            );
            
            // Check all three positions (center, left, right) at all heights (above2, above, feet)
            BlockPos[] positions = {checkPos, checkPosLeft, checkPosRight};
            
            for (BlockPos pos : positions) {
                BlockPos posAbove = pos.above();
                BlockPos posAbove2 = pos.above(2);
                
                BlockState state = this.level().getBlockState(pos);
                BlockState stateAbove = this.level().getBlockState(posAbove);
                BlockState stateAbove2 = this.level().getBlockState(posAbove2);
                
                // PRIORITIZE EYE LEVEL FIRST (above2, then above, then feet)
                if (isBreakableObstacle(stateAbove2, posAbove2)) {
                    breakBlockSlowly(posAbove2, stateAbove2);
                    foundBreakableBlock = true;
                    return; // Break one at a time
                }
                if (isBreakableObstacle(stateAbove, posAbove)) {
                    breakBlockSlowly(posAbove, stateAbove);
                    foundBreakableBlock = true;
                    return; // Break one at a time
                }
                if (isBreakableObstacle(state, pos)) {
                    breakBlockSlowly(pos, state);
                    foundBreakableBlock = true;
                    return; // Break one at a time
                }
            }
        }
        
        // Also check directly above the bot (no forward offset)
        BlockPos aboveBot = this.blockPosition().above();
        BlockPos aboveBot2 = this.blockPosition().above(2);
        
        BlockState stateAboveBot = this.level().getBlockState(aboveBot);
        BlockState stateAboveBot2 = this.level().getBlockState(aboveBot2);
        
        if (isBreakableObstacle(stateAboveBot2, aboveBot2)) {
            breakBlockSlowly(aboveBot2, stateAboveBot2);
            foundBreakableBlock = true;
            return;
        }
        if (isBreakableObstacle(stateAboveBot, aboveBot)) {
            breakBlockSlowly(aboveBot, stateAboveBot);
            foundBreakableBlock = true;
            return;
        }
        
        if (!foundBreakableBlock) {
            // System.out.println("[DEBUG] No breakable blocks found around bot");
            
            // If stuck at same position 20+ times, blacklist current position and force new target far away
            if (consecutiveStuckAtSamePos >= 20) {
                // System.out.println("[DEBUG] Bot stuck at same position " + consecutiveStuckAtSamePos + " times, blacklisting area and forcing new target");
                
                // Blacklist current position and surrounding area (5 block radius)
                BlockPos currentPos = this.blockPosition();
                for (int x = -5; x <= 5; x++) {
                    for (int z = -5; z <= 5; z++) {
                        BlockPos blacklistPos = currentPos.offset(x, 0, z);
                        unreachableLogs.add(blacklistPos); // Reuse blacklist for all unreachable positions
                    }
                }
                
                // Clear all targets
                targetLog = null;
                targetStone = null;
                wanderTarget = null;
                currentPath = null;
                stuckTicks = 0;
                consecutiveStuckAtSamePos = 0;
                lastStuckPosition = null;
                
                // Force immediate wander cooldown reset so bot picks new target next tick
                wanderCooldown = 0;
                
                // Skip stone mining for 100 ticks (5 seconds) to let wander mode take over
                skipStoneMiningTicks = 100;
                
                // System.out.println("[DEBUG] Blacklisted 5-block radius around stuck position, will pick new target far away");
                // System.out.println("[DEBUG] Skipping stone mining for 100 ticks to force wander mode");
                return;
            }
            
            // Check if bot has important targets
            if (craftingTableTarget != null) {
                // Bot is navigating to crafting table but stuck
                // If stuck at same position 3+ times, give up and place new table
                if (consecutiveStuckAtSamePos >= 3) {
                    // System.out.println("[DEBUG] Bot stuck at crafting table 3+ times, giving up and placing new table");
                    craftingTableTarget = null;
                    wanderTarget = null;
                    currentPath = null;
                    stuckTicks = 0;
                    consecutiveStuckAtSamePos = 0;
                    lastStuckPosition = null;
                    // Trigger crafting table placement
                    needsCraftingTable = true;
                } else {
                    // Just reset stuck counter and let it try again
                    // System.out.println("[DEBUG] Bot has crafting table target, resetting stuck counter");
                    stuckTicks = 0;
                }
            } else {
                // No important targets, clear everything and pick new wander target
                // BUT: Don't clear wander target if we just created a path (give bot time to start moving)
                if (justCreatedPathTicks > 0) {
                    // System.out.println("[DEBUG] Bot stuck but just created path (justCreatedPathTicks=" + justCreatedPathTicks + "), giving bot time to start moving");
                    stuckTicks = 0; // Reset stuck counter to give bot more time
                    return;
                }
                
                // System.out.println("[DEBUG] Clearing targets to resume wander");
                // Blacklist current targets (Baritone-style)
                if (targetLog != null) {
                    // System.out.println("[DEBUG] Blacklisting unreachable log at " + targetLog);
                    unreachableLogs.add(targetLog);
                }
                if (wanderTarget != null) {
                    BlockState wanderState = this.level().getBlockState(wanderTarget);
                    // If wander target is a crafting table, blacklist it
                    if (wanderState.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) {
                        // System.out.println("[DEBUG] Blacklisting unreachable crafting table at " + wanderTarget);
                        unreachableLogs.add(wanderTarget); // Reuse same blacklist for simplicity
                    }
                }
                targetLog = null;
                wanderTarget = null;
                currentPath = null;
                stuckTicks = 0;
            }
        }
    }
    
    /**
     * Check if a block is a breakable obstacle
     * Returns true for solid blocks that can be broken (excludes doors, gates, unbreakable blocks)
     */
    private boolean isBreakableObstacle(BlockState state, BlockPos pos) {
        if (state.isAir()) return false;
        if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return false;
        if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return false;
        if (state.getDestroySpeed(this.level(), pos) < 0) return false; // Unbreakable (bedrock, etc.)
        
        // Skip doors and gates - bot should open them, not break them
        if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) return false;
        if (state.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock) return false;
        
        // Skip passable plants (grass, flowers, etc.) - they don't block movement
        if (state.getBlock() instanceof net.minecraft.world.level.block.BushBlock) return false;
        if (state.getBlock() instanceof net.minecraft.world.level.block.TallGrassBlock) return false;
        if (state.getBlock() instanceof net.minecraft.world.level.block.FlowerBlock) return false;
        if (state.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock) return false;
        
        // Check if block has collision (solid)
        return !state.getCollisionShape(this.level(), pos).isEmpty();
    }
    
    /**
     * Break a block slowly with proper timing and animation (like a real player)
     */
    private void breakBlockSlowly(BlockPos pos, BlockState state) {
        // Check if block is within reach (5.0 blocks from eyes - creative mode reach)
        Vec3 eyePos = this.getEyePosition();
        Vec3 blockCenter = Vec3.atCenterOf(pos);
        double reachDistance = eyePos.distanceTo(blockCenter);
        if (reachDistance > 5.0) {
            // Block is out of reach, ignore it
            // System.out.println("[DEBUG] Block at " + pos + " is out of reach (" + String.format("%.2f", reachDistance) + " blocks)");
            return;
        }
        
        // If we're breaking a different block, reset
        if (stuckBreakingBlock == null || !stuckBreakingBlock.equals(pos)) {
            if (stuckBreakingBlock != null) {
                // Clear old breaking animation
                ServerLevel serverLevel = (ServerLevel) this.level();
                for (ServerPlayer player : serverLevel.players()) {
                    if (player.distanceTo(this) < 64) {
                        player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                            this.getId(), stuckBreakingBlock, -1
                        ));
                    }
                }
            }
            // System.out.println("[DEBUG] Started breaking stuck block at " + pos + " (distance: " + String.format("%.2f", reachDistance) + ")");
            stuckBreakingBlock = pos;
            stuckBreakingTicks = 0;
            
            // Equip best tool for this block type ONCE when starting
            equipBestToolForBlock(state);
        }
        
        // Look at the block
        double dx = (pos.getX() + 0.5) - this.getX();
        double dy = (pos.getY() + 0.5) - this.getEyeY();
        double dz = (pos.getZ() + 0.5) - this.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        
        float yaw = (float)(Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float)(-Math.atan2(dy, length) * 180.0 / Math.PI);
        this.setYRot(yaw);
        this.setXRot(pitch);
        this.setYHeadRot(yaw);
        
        // Swing animation
        this.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        
        stuckBreakingTicks++;
        
        // Check if block requires specific tool
        boolean requiresPickaxe = state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE);
        boolean requiresShovel = state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL);
        boolean requiresAxe = state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE);
        
        // Get block hardness
        float hardness = state.getDestroySpeed(this.level(), pos);
        
        // Check what tool is currently equipped
        ItemStack heldItem = this.getMainHandItem();
        float toolSpeed = heldItem.getDestroySpeed(state);
        
        // Check if using correct tool type
        boolean hasCorrectTool = false;
        if (requiresPickaxe && heldItem.getItem() instanceof net.minecraft.world.item.PickaxeItem) hasCorrectTool = true;
        if (requiresShovel && heldItem.getItem() instanceof net.minecraft.world.item.ShovelItem) hasCorrectTool = true;
        if (requiresAxe && heldItem.getItem() instanceof net.minecraft.world.item.AxeItem) hasCorrectTool = true;
        
        // Check if block REQUIRES a tool to drop (stone, ores, etc.)
        // Most blocks tagged as mineable don't actually REQUIRE the tool, just break faster with it
        boolean requiresToolToDrop = state.requiresCorrectToolForDrops();
        
        // Can harvest if: has correct tool, OR block doesn't require tool to drop
        boolean canHarvest = hasCorrectTool || !requiresToolToDrop;
        
        // Minecraft break time formula: damage per tick = speedMultiplier / hardness / (canHarvest ? 30 : 100)
        float damagePerTick = toolSpeed / hardness / (canHarvest ? 30.0f : 100.0f);
        int breakTime = Math.max(1, (int)Math.ceil(1.0f / damagePerTick));
        
        // Send breaking progress
        int breakStage = Math.min(9, (stuckBreakingTicks * 10) / breakTime);
        ServerLevel serverLevel = (ServerLevel) this.level();
        for (ServerPlayer player : serverLevel.players()) {
            if (player.distanceTo(this) < 64) {
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                    this.getId(), pos, breakStage
                ));
            }
        }
        
        // Break when done
        if (stuckBreakingTicks >= breakTime) {
            // Clear animation
            for (ServerPlayer player : serverLevel.players()) {
                if (player.distanceTo(this) < 64) {
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                        this.getId(), pos, -1
                    ));
                }
            }
            
            // Check if block should drop
            // Blocks that don't require tools always drop (dirt, logs, etc.)
            // Blocks that require tools only drop if correct tool was used
            boolean shouldDrop = canHarvest;
            
            // Break block
            // System.out.println("[DEBUG] Finished breaking stuck block at " + pos + " (drop: " + shouldDrop + ")");
            this.level().destroyBlock(pos, shouldDrop);
            
            // Damage tool durability (1 damage per block)
            if (!heldItem.isEmpty() && hasCorrectTool) {
                heldItem.hurtAndBreak(1, this, (player) -> {
                    player.broadcastBreakEvent(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
                });
            }
            
            stuckBreakingBlock = null;
            stuckBreakingTicks = 0;
            stuckTicks = 0; // Reset stuck counter after breaking
        }
    }
    
    /**
     * Break a leaf block instantly (leaves are easy to break)
     */
    private void breakLeaves(BlockPos pos) {
        // Look at the block being broken
        double dx = (pos.getX() + 0.5) - this.getX();
        double dy = (pos.getY() + 0.5) - this.getEyeY();
        double dz = (pos.getZ() + 0.5) - this.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        
        float yaw = (float)(Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float)(-Math.atan2(dy, length) * 180.0 / Math.PI);
        this.setYRot(yaw);
        this.setXRot(pitch);
        this.setYHeadRot(yaw);
        
        // Play swing animation
        this.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        
        // Break the leaf block instantly
        this.level().destroyBlock(pos, true); // true = drop items
    }
    
    /**
     * Open a door if it's closed
     */
    private void openDoor(BlockPos pos, BlockState state) {
        // Check if door is closed
        boolean isOpen = state.getValue(net.minecraft.world.level.block.DoorBlock.OPEN);
        
        if (!isOpen) {
            // Play swing animation
            this.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
            
            // Open the door by toggling its state
            BlockState newState = state.setValue(net.minecraft.world.level.block.DoorBlock.OPEN, true);
            this.level().setBlock(pos, newState, 3);
            
            // Play door sound properly
            net.minecraft.world.level.block.DoorBlock doorBlock = (net.minecraft.world.level.block.DoorBlock) state.getBlock();
            net.minecraft.sounds.SoundEvent soundEvent = state.getValue(net.minecraft.world.level.block.DoorBlock.OPEN) ? 
                doorBlock.type().doorClose() : doorBlock.type().doorOpen();
            this.level().playSound(null, pos, soundEvent, net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }
    
    /**
     * Open a fence gate if it's closed
     */
    private void openFenceGate(BlockPos pos, BlockState state) {
        // Check if gate is closed
        boolean isOpen = state.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN);
        
        if (!isOpen) {
            // Play swing animation
            this.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
            
            // Open the gate by toggling its state
            BlockState newState = state.setValue(net.minecraft.world.level.block.FenceGateBlock.OPEN, true);
            this.level().setBlock(pos, newState, 3);
            
            // Play gate sound properly
            this.level().playSound(null, pos, net.minecraft.sounds.SoundEvents.FENCE_GATE_OPEN, 
                                  net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    /**
     * Handle tree breaking and crafting table creation
     */
    private void handleTreeBreakingAndCrafting() {
        // CRITICAL: Don't interfere if bot is picking up broken crafting table
        if (brokenCraftingTablePos != null) {
            return;
        }
        
        // CRITICAL: Only break blocks if breaking is enabled
        if (!breakingEnabled) {
            return;
        }
        
        // If we just broke a log, navigate to it briefly for pickup
        if (lastBrokenLogPos != null) {
            logPickupTicks++;
            
            // Navigate to broken log position for 2 seconds
            if (logPickupTicks < LOG_PICKUP_WAIT) {
                double distanceToBreakPos = Math.sqrt(this.distanceToSqr(Vec3.atCenterOf(lastBrokenLogPos)));
                
                if (distanceToBreakPos > 1.5) {
                    // Navigate to broken log position
                    if (currentPath == null || wanderTarget != lastBrokenLogPos) {
                        wanderTarget = lastBrokenLogPos;
                        currentPath = pathFinder.findPath(this.blockPosition(), lastBrokenLogPos, 50);
                        pathIndex = 0;
                    }
                }
                return; // Keep navigating for 2 seconds
            } else {
                // Time's up, clear log pickup and continue
                // System.out.println("[DEBUG] handleTreeBreakingAndCrafting: Log pickup timer expired, clearing lastBrokenLogPos");
                lastBrokenLogPos = null;
                logPickupTicks = 0;
                
                // Only clear wander target if bot is still in log breaking mode (doesn't have weapon+pickaxe yet)
                // If bot has finished crafting, preserve wander target for normal wandering
                if (!hasWeapon() || !hasPickaxe()) {
                    // System.out.println("[DEBUG] handleTreeBreakingAndCrafting: Bot still needs logs/tools, clearing wander target");
                    wanderTarget = null;
                    currentPath = null;
                } else {
                    // System.out.println("[DEBUG] handleTreeBreakingAndCrafting: Bot has weapon+pickaxe, preserving wander target");
                }
            }
        }
        
        // Count total logs in inventory
        int totalLogs = countItem(net.minecraft.world.item.Items.OAK_LOG) +
                       countItem(net.minecraft.world.item.Items.SPRUCE_LOG) +
                       countItem(net.minecraft.world.item.Items.BIRCH_LOG) +
                       countItem(net.minecraft.world.item.Items.JUNGLE_LOG) +
                       countItem(net.minecraft.world.item.Items.ACACIA_LOG) +
                       countItem(net.minecraft.world.item.Items.DARK_OAK_LOG);
        
        // Count total planks in inventory
        int totalPlanks = countItem(net.minecraft.world.item.Items.OAK_PLANKS) +
                         countItem(net.minecraft.world.item.Items.SPRUCE_PLANKS) +
                         countItem(net.minecraft.world.item.Items.BIRCH_PLANKS) +
                         countItem(net.minecraft.world.item.Items.JUNGLE_PLANKS) +
                         countItem(net.minecraft.world.item.Items.ACACIA_PLANKS) +
                         countItem(net.minecraft.world.item.Items.DARK_OAK_PLANKS);
        
        boolean hasWeapon = hasWeapon();
        
        // Calculate total wood resources (1 log = 4 planks)
        int totalWoodValue = (totalLogs * 4) + totalPlanks;
        
        // If has enough wood and no weapon, process crafting
        // Need at least 12 planks worth (3 logs) for: table (4) + sticks (2) + weapon (2) = 8 planks minimum
        boolean shouldCraft = false;
        if (totalWoodValue >= 8 && !hasWeapon) {
            shouldCraft = true;
        } else if (totalWoodValue >= 4 && totalWoodValue < 8 && !hasWeapon) {
            // Check if there are logs nearby to break
            BlockPos nearbyLog = findNearbyLog();
            if (nearbyLog == null) {
                // No logs nearby, craft with what we have
                shouldCraft = true;
            }
        } else if (hasWeapon && !hasPickaxe() && totalWoodValue >= 5) {
            // Has sword but no pickaxe - craft pickaxe if we have enough wood (5 planks for sticks + pickaxe)
            shouldCraft = true;
        }
        
        if (shouldCraft) {
            // Check if there's a crafting table nearby
            BlockPos nearbyCraftingTable = findNearbyCraftingTable();
            
            if (nearbyCraftingTable == null) {
                // No crafting table nearby, craft one
                craftPlanksAndTable();
                return; // Return after crafting/placing table, continue next tick
            } else {
                // Crafting table exists, craft sticks and weapon
                craftSticksAndWeapon();
                return; // Return after attempting to craft, continue next tick
            }
        }
        
        // Look for trees only if we need logs
        // Continue breaking logs until bot has BOTH sword AND pickaxe
        if (targetLog == null && !hasWeapon && totalLogs < 4) {
            targetLog = findNearbyLog();
            if (targetLog != null) {
                // System.out.println("[DEBUG] Found log at " + targetLog + ", total logs: " + totalLogs);
                targetLogAttemptTicks = 0; // Reset timeout counter for new log
            }
        } else if (targetLog == null && hasWeapon && !hasPickaxe() && totalWoodValue < 20) {
            // Has sword but no pickaxe - keep breaking logs until we have enough for pickaxe
            // Need 20 planks total (5 logs) for: table(4) + sticks(4) + sword(2) + pickaxe(3) + extra sticks(2) = 15 planks minimum
            targetLog = findNearbyLog();
            if (targetLog != null) {
                // System.out.println("[DEBUG] Found log for pickaxe at " + targetLog + ", total wood value: " + totalWoodValue);
                targetLogAttemptTicks = 0; // Reset timeout counter for new log
            }
        }
        
        // If found a log and need to break it
        // Break logs if: (no weapon and less than 4 logs) OR (has weapon but no pickaxe and not enough wood)
        boolean needsMoreLogs = (!hasWeapon && totalLogs < 4) || (hasWeapon && !hasPickaxe() && totalWoodValue < 20);
        if (targetLog != null && needsMoreLogs) {
            // Calculate distance based on log position
            double distance;
            if (targetLog.getY() > this.blockPosition().getY()) {
                // Log is above - use HORIZONTAL distance only
                double dx = (targetLog.getX() + 0.5) - this.getX();
                double dz = (targetLog.getZ() + 0.5) - this.getZ();
                distance = Math.sqrt(dx * dx + dz * dz);
            } else {
                // Log is at same level or below - use 3D distance
                distance = Math.sqrt(this.distanceToSqr(Vec3.atCenterOf(targetLog)));
            }
            
            // TWO-PHASE: Navigate first, break only when path complete AND close
            double breakDistance = 3.5;
            
            if (distance <= breakDistance && currentPath == null) {
                // Close AND navigation complete - break it
                // System.out.println("[DEBUG] Breaking log at " + targetLog + " (distance: " + String.format("%.2f", distance) + ")");
                breakLog(targetLog);
            } else {
                // Too far or still navigating - reset breaking progress
                if (treeBreakingTicks > 0) {
                    // Clear breaking animation
                    ServerLevel serverLevel = (ServerLevel) this.level();
                    for (ServerPlayer player : serverLevel.players()) {
                        if (player.distanceTo(this) < 64) {
                            player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                                this.getId(), targetLog, -1
                            ));
                        }
                    }
                    treeBreakingTicks = 0;
                }
                
                // Don't navigate if bot is breaking stuck blocks - let it finish breaking first
                if (stuckBreakingBlock != null) {
                    return;
                }
                
                // Navigate to position BELOW floating logs
                BlockPos targetPosition = targetLog;
                boolean navigatingToGround = false;
                
                if (targetLog.getY() > this.blockPosition().getY()) {
                    BlockPos groundPos = targetLog;
                    for (int i = 0; i < 20; i++) {
                        groundPos = groundPos.below();
                        BlockState groundState = this.level().getBlockState(groundPos);
                        if (!groundState.isAir() && groundState.blocksMotion()) {
                            targetPosition = groundPos.above();
                            navigatingToGround = true;
                            break;
                        }
                    }
                }
                
                // Check for leaves only if navigating directly to log
                if (!navigatingToGround && checkAndBreakLeavesInPath(targetLog)) {
                    return;
                }
                
                // Navigate to target position
                if (currentPath == null) {
                    wanderTarget = targetPosition;
                    currentPath = pathFinder.findPath(this.blockPosition(), targetPosition, 50, true);
                    pathIndex = 0;
                    
                    if (currentPath == null || currentPath.isEmpty()) {
                        // System.out.println("[DEBUG] No path found to log at " + targetLog + ", marking as unreachable");
                        unreachableLogs.add(targetLog); // Blacklist this log
                        targetLog = null;
                    } else {
                        // System.out.println("[DEBUG] Navigating to log at " + targetLog + " (distance: " + String.format("%.2f", distance) + ", path length: " + currentPath.size() + ")");
                    }
                } else if (wanderTarget == null || !wanderTarget.equals(targetPosition)) {
                    wanderTarget = targetPosition;
                    currentPath = pathFinder.findPath(this.blockPosition(), targetPosition, 50, true);
                    pathIndex = 0;
                    
                    if (currentPath == null || currentPath.isEmpty()) {
                        // System.out.println("[DEBUG] No path found to log at " + targetLog);
                        targetLog = null;
                    }
                }
            }
        } else if (hasWeapon && hasPickaxe()) {
            // Has BOTH weapon AND pickaxe, clear all tree-related targets
            // DON'T clear wanderTarget/currentPath - let wander mode handle navigation
            targetLog = null;
            treeBreakingTicks = 0;
            lastBrokenLogPos = null;
            logPickupTicks = 0;
            // wanderTarget and currentPath are preserved for normal wandering
            // wanderCooldown is preserved so bot doesn't spam new targets
        }
        // If no log found and no weapon, bot will continue to normal wander mode to search for logs
    }
    
    /**
     * Find nearby log block (PRIORITIZES ground-level logs, then closest reachable log)
     */
    private BlockPos findNearbyLog() {
        BlockPos botPos = this.blockPosition();
        int range = 16;
        
        BlockPos closestGroundLog = null;
        double closestGroundDistance = Double.MAX_VALUE;
        BlockPos closestFloatingLog = null;
        double closestFloatingDistance = Double.MAX_VALUE;
        
        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos checkPos = botPos.offset(x, y, z);
                    
                    // Skip blacklisted logs
                    if (unreachableLogs.contains(checkPos)) {
                        continue;
                    }
                    
                    BlockState state = this.level().getBlockState(checkPos);
                    
                    if (state.getBlock() instanceof net.minecraft.world.level.block.RotatedPillarBlock &&
                        (state.is(net.minecraft.world.level.block.Blocks.OAK_LOG) ||
                         state.is(net.minecraft.world.level.block.Blocks.SPRUCE_LOG) ||
                         state.is(net.minecraft.world.level.block.Blocks.BIRCH_LOG) ||
                         state.is(net.minecraft.world.level.block.Blocks.JUNGLE_LOG) ||
                         state.is(net.minecraft.world.level.block.Blocks.ACACIA_LOG) ||
                         state.is(net.minecraft.world.level.block.Blocks.DARK_OAK_LOG))) {
                        
                        // Check if log is reachable
                        boolean isReachable = false;
                        boolean isGroundLevel = false;
                        
                        if (checkPos.getY() <= botPos.getY() + 1) {
                            // Log is at ground level or 1 block above - check if within 5 blocks
                            double distance = Math.sqrt(this.distanceToSqr(Vec3.atCenterOf(checkPos)));
                            if (distance <= 5.0) {
                                isReachable = true;
                                isGroundLevel = true;
                            }
                        } else {
                            // Log is floating - check if bot can navigate underneath it
                            // Find ground position below the log
                            BlockPos groundPos = checkPos;
                            boolean foundGround = false;
                            for (int i = 0; i < 20; i++) {
                                groundPos = groundPos.below();
                                BlockState groundState = this.level().getBlockState(groundPos);
                                if (!groundState.isAir() && groundState.blocksMotion()) {
                                    foundGround = true;
                                    break;
                                }
                            }
                            
                            if (foundGround) {
                                // Check if log would be reachable from ground position
                                BlockPos standPos = groundPos.above();
                                // Calculate reach from standing position (add 1.62 for eye height)
                                double dx = (checkPos.getX() + 0.5) - (standPos.getX() + 0.5);
                                double dy = (checkPos.getY() + 0.5) - (standPos.getY() + 1.62);
                                double dz = (checkPos.getZ() + 0.5) - (standPos.getZ() + 0.5);
                                double reachFromGround = Math.sqrt(dx * dx + dy * dy + dz * dz);
                                
                                if (reachFromGround <= 5.0) {
                                    isReachable = true;
                                    isGroundLevel = false;
                                }
                            }
                        }
                        
                        if (isReachable) {
                            // Calculate distance to this log
                            double distance = Math.sqrt(this.distanceToSqr(Vec3.atCenterOf(checkPos)));
                            
                            // Prioritize ground-level logs
                            if (isGroundLevel) {
                                if (distance < closestGroundDistance) {
                                    closestGroundDistance = distance;
                                    closestGroundLog = checkPos;
                                }
                            } else {
                                if (distance < closestFloatingDistance) {
                                    closestFloatingDistance = distance;
                                    closestFloatingLog = checkPos;
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Return ground-level log if found, otherwise floating log
        return closestGroundLog != null ? closestGroundLog : closestFloatingLog;
    }
    
    /**
     * Check for leaves blocking the path to target log and break them
     * Returns true if breaking leaves, false otherwise
     */
    private boolean checkAndBreakLeavesInPath(BlockPos targetLog) {
        // Calculate direction to target log
        double dx = (targetLog.getX() + 0.5) - this.getX();
        double dy = (targetLog.getY() + 0.5) - this.getY();
        double dz = (targetLog.getZ() + 0.5) - this.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        
        if (length < 0.1) return false; // Too close
        
        // Normalize direction
        double dirX = dx / length;
        double dirZ = dz / length;
        
        // Check blocks in front (1-3 blocks away) at bot height and above
        for (int dist = 1; dist <= 3; dist++) {
            BlockPos checkPos = new BlockPos(
                (int)Math.floor(this.getX() + dirX * dist),
                (int)Math.floor(this.getY()),
                (int)Math.floor(this.getZ() + dirZ * dist)
            );
            
            // Check at bot height, 1 block above, and 2 blocks above
            for (int yOffset = 0; yOffset <= 2; yOffset++) {
                BlockPos leafPos = checkPos.above(yOffset);
                BlockState state = this.level().getBlockState(leafPos);
                
                if (state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                    // Found leaves in the way, break them instantly (leaves are quick)
                    breakLeaves(leafPos);
                    return true; // Breaking leaves
                }
            }
        }
        
        return false; // No leaves blocking
    }
    
    /**
     * Break a log block
     */
    private void breakLog(BlockPos pos) {
        // System.out.println("[DEBUG] breakLog() called for pos=" + pos);
        
        // OPTION 1: Validate block is still a log before breaking
        BlockState blockState = this.level().getBlockState(pos);
        // System.out.println("[DEBUG] breakLog: blockState=" + blockState + ", isAir=" + blockState.isAir() + ", block=" + blockState.getBlock().getClass().getSimpleName());
        
        if (blockState.isAir() || !(blockState.getBlock() instanceof net.minecraft.world.level.block.RotatedPillarBlock)) {
            // Block is air or not a log - clear target and move on
            // System.out.println("[DEBUG] Target log at " + pos + " is no longer a log (air or wrong block), clearing target");
            targetLog = null;
            treeBreakingTicks = 0;
            targetLogAttemptTicks = 0;
            return;
        }
        
        // OPTION 2: Timeout check - if trying to break same log for too long, give up
        targetLogAttemptTicks++;
        // System.out.println("[DEBUG] breakLog: targetLogAttemptTicks=" + targetLogAttemptTicks + "/" + LOG_BREAK_TIMEOUT);
        if (targetLogAttemptTicks >= LOG_BREAK_TIMEOUT) {
            // System.out.println("[DEBUG] Timeout breaking log at " + pos + " after " + targetLogAttemptTicks + " ticks, marking unreachable");
            unreachableLogs.add(pos);
            targetLog = null;
            treeBreakingTicks = 0;
            targetLogAttemptTicks = 0;
            return;
        }
        
        // Check if log is within reach (5.5 blocks from eyes - slightly more than survival reach to handle vertical logs)
        Vec3 eyePos = this.getEyePosition();
        Vec3 blockCenter = Vec3.atCenterOf(pos);
        double reachDistance = eyePos.distanceTo(blockCenter);
        // System.out.println("[DEBUG] breakLog: eyePos=" + String.format("%.2f,%.2f,%.2f", eyePos.x, eyePos.y, eyePos.z) + ", blockCenter=" + String.format("%.2f,%.2f,%.2f", blockCenter.x, blockCenter.y, blockCenter.z) + ", reachDistance=" + String.format("%.2f", reachDistance));
        if (reachDistance > 5.5) {
            // Log is out of reach, clear target and stop trying
            // System.out.println("[DEBUG] Log at " + pos + " is out of reach (" + String.format("%.2f", reachDistance) + " blocks from eyes), clearing target");
            targetLog = null;
            treeBreakingTicks = 0;
            targetLogAttemptTicks = 0;
            return;
        }
        
        // Look at the log
        double dx = (pos.getX() + 0.5) - this.getX();
        double dy = (pos.getY() + 0.5) - this.getEyeY();
        double dz = (pos.getZ() + 0.5) - this.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        
        float yaw = (float)(Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float)(-Math.atan2(dy, length) * 180.0 / Math.PI);
        this.setYRot(yaw);
        this.setXRot(pitch);
        this.setYHeadRot(yaw);
        
        // Play swing animation
        this.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        
        treeBreakingTicks++;
        
        // Get block state and hardness
        BlockState logState = this.level().getBlockState(pos);
        float hardness = logState.getDestroySpeed(this.level(), pos);
        
        // Check what tool is currently equipped
        ItemStack heldItem = this.getMainHandItem();
        float toolSpeed = heldItem.getDestroySpeed(logState);
        
        // Check if using axe (correct tool for logs)
        boolean hasAxe = heldItem.getItem() instanceof net.minecraft.world.item.AxeItem;
        
        // Minecraft break time formula: damage per tick = speedMultiplier / hardness / (canHarvest ? 30 : 100)
        float damagePerTick;
        if (hasAxe) {
            // Using axe - correct tool
            damagePerTick = toolSpeed / hardness / 30.0f;
        } else {
            // Using hands or wrong tool - logs don't require axe but it's faster
            damagePerTick = toolSpeed / hardness / 30.0f;
        }
        
        int breakTime = Math.max(1, (int)Math.ceil(1.0f / damagePerTick));
        
        // Send block breaking progress to nearby players (0-9 stages)
        int breakStage = Math.min(9, (treeBreakingTicks * 10) / breakTime);
        ServerLevel serverLevel = (ServerLevel) this.level();
        for (ServerPlayer player : serverLevel.players()) {
            if (player.distanceTo(this) < 64) { // Within render distance
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                    this.getId(), pos, breakStage
                ));
            }
        }
        
        if (treeBreakingTicks >= breakTime) {
            // Clear breaking animation
            for (ServerPlayer player : serverLevel.players()) {
                if (player.distanceTo(this) < 64) {
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                        this.getId(), pos, -1 // -1 clears the breaking animation
                    ));
                }
            }
            
            // Break the log
            // System.out.println("[DEBUG] Finished breaking log at " + pos);
            this.level().destroyBlock(pos, true);
            
            // Clear unreachable logs blacklist after successful break
            if (!unreachableLogs.isEmpty()) {
                // System.out.println("[DEBUG] Cleared unreachable logs blacklist after successful break");
                unreachableLogs.clear();
            }
            
            lastBrokenLogPos = pos; // Navigate to this position briefly
            targetLog = null;
            treeBreakingTicks = 0;
            targetLogAttemptTicks = 0; // Reset timeout counter
            logPickupTicks = 0;
        }
    }
    
    /**
     * Break a crafting table block with proper timing (faster with axe)
     */
    private void breakCraftingTable(BlockPos pos) {
        // Check if this is a new block to break
        if (breakingCraftingTable == null || !breakingCraftingTable.equals(pos)) {
            // Starting to break a new crafting table
            breakingCraftingTable = pos;
            craftingTableBreakTicks = 0;
        }
        
        // Check if table is within reach (5.0 blocks from eyes)
        Vec3 eyePos = this.getEyePosition();
        Vec3 blockCenter = Vec3.atCenterOf(pos);
        double reachDistance = eyePos.distanceTo(blockCenter);
        if (reachDistance > 5.0) {
            // Table is out of reach, clear target
            breakingCraftingTable = null;
            craftingTableBreakTicks = 0;
            return;
        }
        
        // Look at the crafting table
        double dx = (pos.getX() + 0.5) - this.getX();
        double dy = (pos.getY() + 0.5) - this.getEyeY();
        double dz = (pos.getZ() + 0.5) - this.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        
        float yaw = (float)(Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float)(-Math.atan2(dy, length) * 180.0 / Math.PI);
        this.setYRot(yaw);
        this.setXRot(pitch);
        this.setYHeadRot(yaw);
        
        // Play swing animation
        this.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        
        craftingTableBreakTicks++;
        
        // Get block state and hardness
        BlockState tableState = this.level().getBlockState(pos);
        float hardness = tableState.getDestroySpeed(this.level(), pos);
        
        // Check what tool is currently equipped
        ItemStack heldItem = this.getMainHandItem();
        float toolSpeed = heldItem.getDestroySpeed(tableState);
        
        // Check if using axe (correct tool for crafting tables)
        boolean hasAxe = heldItem.getItem() instanceof net.minecraft.world.item.AxeItem;
        
        // Minecraft break time formula: damage per tick = speedMultiplier / hardness / (canHarvest ? 30 : 100)
        float damagePerTick;
        if (hasAxe) {
            // Using axe - correct tool
            damagePerTick = toolSpeed / hardness / 30.0f;
        } else {
            // Using hands or wrong tool
            damagePerTick = toolSpeed / hardness / 30.0f;
        }
        
        int breakTime = Math.max(1, (int)Math.ceil(1.0f / damagePerTick));
        
        // Send block breaking progress to nearby players (0-9 stages)
        int breakStage = Math.min(9, (craftingTableBreakTicks * 10) / breakTime);
        ServerLevel serverLevel = (ServerLevel) this.level();
        for (ServerPlayer player : serverLevel.players()) {
            if (player.distanceTo(this) < 64) { // Within render distance
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                    this.getId(), pos, breakStage
                ));
            }
        }
        
        if (craftingTableBreakTicks >= breakTime) {
            // Clear breaking animation
            for (ServerPlayer player : serverLevel.players()) {
                if (player.distanceTo(this) < 64) {
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                        this.getId(), pos, -1 // -1 clears the breaking animation
                    ));
                }
            }
            
            // Break the crafting table
            // System.out.println("[DEBUG] Finished breaking crafting table at " + pos);
            this.level().destroyBlock(pos, true);
            
            // Damage tool durability (1 damage per block) - axes break crafting tables faster
            if (!heldItem.isEmpty() && hasAxe) {
                heldItem.hurtAndBreak(1, this, (player) -> {
                    player.broadcastBreakEvent(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
                });
            }
            
            // Set broken position for pickup navigation
            brokenCraftingTablePos = pos;
            craftingTablePickupTicks = 0;
            
            // Clear breaking tracking
            breakingCraftingTable = null;
            craftingTableBreakTicks = 0;
        }
    }
    
    /**
     * Handle stone mining and stone tool crafting
     */
    private void handleStoneMining() {
        // CRITICAL: Don't interfere if bot is picking up broken crafting table
        if (brokenCraftingTablePos != null) {
            return;
        }
        
        // CRITICAL: Only break blocks if breaking is enabled
        if (!breakingEnabled) {
            return;
        }
        
        // Count cobblestone in inventory
        int cobblestoneCount = countItem(net.minecraft.world.item.Items.COBBLESTONE);
        
        // Check if bot has stone tools already
        boolean hasStoneTools = hasStonePickaxe() && hasStoneSword() && hasStoneShovel();
        
        // Debug output every 100 ticks
        if (this.tickCount % 100 == 0) {
            // System.out.println("[DEBUG] Stone mining: cobblestone=" + cobblestoneCount + "/20, hasStoneTools=" + hasStoneTools + ", hasPickaxe=" + hasPickaxe());
        }
        
        // If has 20+ cobblestone and no stone tools, craft them
        if (cobblestoneCount >= COBBLESTONE_TARGET && !hasStoneTools) {
            craftStoneTools();
            return;
        }
        
        // If already has stone tools, stop mining
        // BUT if bot is stuck with no path, don't return - let wander mode handle it
        if (hasStoneTools) {
            targetStone = null;
            stoneBreakingTicks = 0;
            // Only return if bot has a path (not stuck)
            if (currentPath != null) {
                return;
            }
            // If no path (stuck), fall through to let wander mode run
        }
        
        // Look for stone blocks only if we need more cobblestone
        if (targetStone == null && cobblestoneCount < COBBLESTONE_TARGET) {
            targetStone = findVisibleStone();
            if (targetStone != null) {
                // System.out.println("[DEBUG] Found visible stone at " + targetStone + ", cobblestone: " + cobblestoneCount + "/20");
                
                // IMMEDIATELY create path to stone (don't wait for next tick)
                // This prevents handleWanderMode from creating a random path
                wanderTarget = targetStone;
                currentPath = pathFinder.findPath(this.blockPosition(), targetStone, 50, true);
                pathIndex = 0;
                
                if (currentPath == null || currentPath.isEmpty()) {
                    // System.out.println("[DEBUG] No path to stone at " + targetStone + ", marking unreachable");
                    unreachableStones.add(targetStone);
                    targetStone = null;
                    wanderTarget = null;
                } else {
                    double distance = Math.sqrt(this.distanceToSqr(Vec3.atCenterOf(targetStone)));
                    // System.out.println("[DEBUG] Navigating to stone at " + targetStone + " (distance: " + String.format("%.2f", distance) + ", path length: " + currentPath.size() + ")");
                }
            } else if (this.tickCount % 100 == 0) {
                // System.out.println("[DEBUG] No visible stone found, cobblestone: " + cobblestoneCount + "/20");
            }
        }
        
        // If found stone and need to break it
        if (targetStone != null && cobblestoneCount < COBBLESTONE_TARGET) {
            double distance = Math.sqrt(this.distanceToSqr(Vec3.atCenterOf(targetStone)));
            
            // TWO-PHASE: Navigate first, break only when path complete AND close (SAME as logs)
            double breakDistance = 3.5;
            
            // Debug output
            if (this.tickCount % 20 == 0) {
                // System.out.println("[DEBUG] Stone check: distance=" + String.format("%.2f", distance) + ", breakDistance=" + breakDistance + ", currentPath=" + (currentPath == null ? "null" : "exists") + ", breaking=" + (stoneBreakingTicks > 0));
            }
            
            if (distance <= breakDistance && currentPath == null) {
                // Close AND navigation complete - break it
                if (stoneBreakingTicks == 0) {
                    // System.out.println("[DEBUG] Breaking stone at " + targetStone + " (distance: " + String.format("%.2f", distance) + ")");
                }
                breakStone(targetStone);
            } else {
                // Too far or still navigating - reset breaking progress
                if (stoneBreakingTicks > 0) {
                    ServerLevel serverLevel = (ServerLevel) this.level();
                    for (ServerPlayer player : serverLevel.players()) {
                        if (player.distanceTo(this) < 64) {
                            player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                                this.getId(), targetStone, -1
                            ));
                        }
                    }
                    stoneBreakingTicks = 0;
                }
                
                // Don't navigate if breaking stuck blocks
                if (stuckBreakingBlock != null) {
                    // System.out.println("[DEBUG] Stone mining: Not navigating because breaking stuck blocks");
                    return;
                }
                
                // Navigate to stone
                if (currentPath == null) {
                    // System.out.println("[DEBUG] Stone mining: Creating path to stone at " + targetStone);
                    wanderTarget = targetStone;
                    currentPath = pathFinder.findPath(this.blockPosition(), targetStone, 50, true);
                    pathIndex = 0;
                    
                    if (currentPath == null || currentPath.isEmpty()) {
                        // System.out.println("[DEBUG] No path to stone at " + targetStone + ", marking unreachable");
                        unreachableStones.add(targetStone);
                        targetStone = null;
                        wanderTarget = null;
                    } else {
                        // System.out.println("[DEBUG] Navigating to stone at " + targetStone + " (distance: " + String.format("%.2f", distance) + ", path length: " + currentPath.size() + ")");
                    }
                } else if (wanderTarget == null || !wanderTarget.equals(targetStone)) {
                    // System.out.println("[DEBUG] Stone mining: Recreating path because wanderTarget changed");
                    wanderTarget = targetStone;
                    currentPath = pathFinder.findPath(this.blockPosition(), targetStone, 50, true);
                    pathIndex = 0;
                    
                    if (currentPath == null || currentPath.isEmpty()) {
                        // System.out.println("[DEBUG] No path found to stone at " + targetStone);
                        targetStone = null;
                        wanderTarget = null;
                    }
                } else {
                    // System.out.println("[DEBUG] Stone mining: Already have path to stone, continuing navigation");
                }
            }
        } else if (hasStoneTools || cobblestoneCount >= COBBLESTONE_TARGET) {
            // Has stone tools or enough cobblestone, clear targets
            targetStone = null;
            stoneBreakingTicks = 0;
        }
    }
    
    /**
     * Find nearby stone block that is VISIBLE (line of sight from bot's eyes)
     * Only mines stones the bot can actually see
     */
    private BlockPos findVisibleStone() {
        BlockPos botPos = this.blockPosition();
        Vec3 eyePos = this.getEyePosition();
        int range = 16;
        
        BlockPos closestStone = null;
        double closestDistance = Double.MAX_VALUE;
        
        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos checkPos = botPos.offset(x, y, z);
                    
                    // Skip blacklisted stones
                    if (unreachableStones.contains(checkPos)) {
                        continue;
                    }
                    
                    BlockState state = this.level().getBlockState(checkPos);
                    
                    // Check if it's stone
                    if (state.is(net.minecraft.world.level.block.Blocks.STONE)) {
                        // Check if stone is below bot's feet (prevents mining through floor)
                        // Within 2 blocks horizontally and below bot
                        boolean isBelowBotFeet = (Math.abs(checkPos.getX() - botPos.getX()) <= 2 && 
                                                 Math.abs(checkPos.getZ() - botPos.getZ()) <= 2 && 
                                                 checkPos.getY() < botPos.getY());
                        
                        if (isBelowBotFeet) {
                            continue; // Skip stones below bot
                        }
                        
                        // Check line of sight from bot's eyes to stone center
                        Vec3 stoneCenter = Vec3.atCenterOf(checkPos);
                        Vec3 direction = stoneCenter.subtract(eyePos);
                        double distance = direction.length();
                        
                        // Only check stones within reasonable distance
                        if (distance > 5.0) {
                            continue;
                        }
                        
                        // Perform raycast from eyes to stone
                        net.minecraft.world.level.ClipContext clipContext = new net.minecraft.world.level.ClipContext(
                            eyePos,
                            stoneCenter,
                            net.minecraft.world.level.ClipContext.Block.COLLIDER,
                            net.minecraft.world.level.ClipContext.Fluid.NONE,
                            this
                        );
                        
                        net.minecraft.world.phys.BlockHitResult hitResult = this.level().clip(clipContext);
                        
                        // Check if raycast hit the stone block (not something in between)
                        if (hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK &&
                            hitResult.getBlockPos().equals(checkPos)) {
                            // Bot has clear line of sight to this stone
                            if (distance < closestDistance) {
                                closestDistance = distance;
                                closestStone = checkPos;
                            }
                        } else if (hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                            // Raycast hit something else - stone is blocked
                            // Debug: show what's blocking
                            BlockPos hitPos = hitResult.getBlockPos();
                            BlockState hitState = this.level().getBlockState(hitPos);
                            if (Math.random() < 0.01) { // Only log 1% of the time to avoid spam
                                // System.out.println("[DEBUG] Stone at " + checkPos + " blocked by " + hitState.getBlock().getName().getString() + " at " + hitPos);
                            }
                        }
                    }
                }
            }
        }
        
        return closestStone;
    }
    
    /**
     * Break a stone block
     */
    private void breakStone(BlockPos pos) {
        // Check if stone is within reach
        Vec3 eyePos = this.getEyePosition();
        Vec3 blockCenter = Vec3.atCenterOf(pos);
        double reachDistance = eyePos.distanceTo(blockCenter);
        if (reachDistance > 5.0) {
            targetStone = null;
            stoneBreakingTicks = 0;
            return;
        }
        
        // Look at the stone
        double dx = (pos.getX() + 0.5) - this.getX();
        double dy = (pos.getY() + 0.5) - this.getEyeY();
        double dz = (pos.getZ() + 0.5) - this.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        
        float yaw = (float)(Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float)(-Math.atan2(dy, length) * 180.0 / Math.PI);
        this.setYRot(yaw);
        this.setXRot(pitch);
        this.setYHeadRot(yaw);
        
        // Equip pickaxe if not already breaking this stone
        if (stoneBreakingTicks == 0) {
            BlockState stoneState = this.level().getBlockState(pos);
            equipBestToolForBlock(stoneState);
        }
        
        // Swing animation
        this.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        
        stoneBreakingTicks++;
        
        // Get block state and calculate break time
        BlockState stoneState = this.level().getBlockState(pos);
        float hardness = stoneState.getDestroySpeed(this.level(), pos);
        
        // Check what tool is currently equipped
        ItemStack heldItem = this.getMainHandItem();
        float toolSpeed = heldItem.getDestroySpeed(stoneState);
        
        // Stone requires pickaxe to drop
        boolean hasPickaxe = heldItem.getItem() instanceof net.minecraft.world.item.PickaxeItem;
        
        // Minecraft break time formula
        float damagePerTick = toolSpeed / hardness / (hasPickaxe ? 30.0f : 100.0f);
        int breakTime = Math.max(1, (int)Math.ceil(1.0f / damagePerTick));
        
        // Send breaking progress
        int breakStage = Math.min(9, (stoneBreakingTicks * 10) / breakTime);
        ServerLevel serverLevel = (ServerLevel) this.level();
        for (ServerPlayer player : serverLevel.players()) {
            if (player.distanceTo(this) < 64) {
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                    this.getId(), pos, breakStage
                ));
            }
        }
        
        if (stoneBreakingTicks >= breakTime) {
            // Clear breaking animation
            for (ServerPlayer player : serverLevel.players()) {
                if (player.distanceTo(this) < 64) {
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                        this.getId(), pos, -1
                    ));
                }
            }
            
            // Break the stone (only drops if has pickaxe)
            // System.out.println("[DEBUG] Finished breaking stone at " + pos);
            this.level().destroyBlock(pos, hasPickaxe);
            
            // Damage pickaxe durability (1 damage per block)
            if (!heldItem.isEmpty() && hasPickaxe) {
                heldItem.hurtAndBreak(1, this, (player) -> {
                    player.broadcastBreakEvent(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
                });
            }
            
            // Clear unreachable stones after successful break
            if (!unreachableStones.isEmpty()) {
                unreachableStones.clear();
            }
            
            targetStone = null;
            stoneBreakingTicks = 0;
        }
    }
    
    /**
     * Craft stone tools (sword, pickaxe, shovel)
     */
    private void craftStoneTools() {
        // Find nearby crafting table
        BlockPos nearbyCraftingTable = findNearbyCraftingTable();
        if (nearbyCraftingTable == null) {
            // System.out.println("[DEBUG] No crafting table for stone tools, placing one");
            // No crafting table nearby - place one
            // Check if bot has crafting table in inventory
            if (countItem(net.minecraft.world.item.Items.CRAFTING_TABLE) > 0) {
                needsCraftingTable = true;
                placeCraftingTable();
                return;
            } else {
                // No crafting table in inventory - craft one from planks
                craftPlanksAndTable();
                return;
            }
        }
        
        // Check distance to table
        double distanceToTable = Math.sqrt(this.distanceToSqr(Vec3.atCenterOf(nearbyCraftingTable)));
        
        if (distanceToTable > 4.0) {
            // Navigate to table
            if (currentPath == null || wanderTarget != nearbyCraftingTable) {
                wanderTarget = nearbyCraftingTable;
                currentPath = pathFinder.findPath(this.blockPosition(), nearbyCraftingTable, 50);
                pathIndex = 0;
            }
            return;
        }
        
        // Close enough to craft
        int cobblestoneCount = countItem(net.minecraft.world.item.Items.COBBLESTONE);
        int stickCount = countItem(net.minecraft.world.item.Items.STICK);
        
        // First, craft sticks if needed (need 6 sticks total: 1+2+1 for sword+pickaxe+shovel, but we craft 8)
        if (stickCount < 6) {
            // Count total planks
            int totalPlanks = countItem(net.minecraft.world.item.Items.OAK_PLANKS) +
                             countItem(net.minecraft.world.item.Items.SPRUCE_PLANKS) +
                             countItem(net.minecraft.world.item.Items.BIRCH_PLANKS) +
                             countItem(net.minecraft.world.item.Items.JUNGLE_PLANKS) +
                             countItem(net.minecraft.world.item.Items.ACACIA_PLANKS) +
                             countItem(net.minecraft.world.item.Items.DARK_OAK_PLANKS);
            
            if (totalPlanks >= 4) {
                // Find which plank type we have
                net.minecraft.world.item.Item plankType = null;
                if (countItem(net.minecraft.world.item.Items.OAK_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.OAK_PLANKS;
                else if (countItem(net.minecraft.world.item.Items.SPRUCE_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.SPRUCE_PLANKS;
                else if (countItem(net.minecraft.world.item.Items.BIRCH_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.BIRCH_PLANKS;
                else if (countItem(net.minecraft.world.item.Items.JUNGLE_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.JUNGLE_PLANKS;
                else if (countItem(net.minecraft.world.item.Items.ACACIA_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.ACACIA_PLANKS;
                else if (countItem(net.minecraft.world.item.Items.DARK_OAK_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.DARK_OAK_PLANKS;
                
                if (plankType != null) {
                    // Craft sticks: 2 planks = 4 sticks (craft twice for 8 sticks total)
                    int craftCount = Math.min(2, totalPlanks / 2);
                    for (int i = 0; i < craftCount; i++) {
                        removeItems(plankType, 2);
                        this.getInventory().add(new ItemStack(net.minecraft.world.item.Items.STICK, 4));
                        stickCount += 4;
                    }
                    // System.out.println("[DEBUG] Crafted sticks for stone tools, now have " + stickCount + " sticks");
                }
            }
        }
        
        // Craft stone sword (need 1 stick + 2 cobblestone)
        if (!hasStoneSword() && stickCount >= 1 && cobblestoneCount >= 2) {
            removeItems(net.minecraft.world.item.Items.STICK, 1);
            removeItems(net.minecraft.world.item.Items.COBBLESTONE, 2);
            this.getInventory().add(new ItemStack(net.minecraft.world.item.Items.STONE_SWORD, 1));
            // System.out.println("[DEBUG] Crafted stone sword");
            cobblestoneCount -= 2;
            stickCount -= 1;
            
            // If using bot's own table, mark for pickup
            if (placedCraftingTablePos != null && nearbyCraftingTable.equals(placedCraftingTablePos)) {
                craftingComplete = true;
            }
        }
        
        // Craft stone pickaxe (need 2 sticks + 3 cobblestone)
        if (!hasStonePickaxe() && stickCount >= 2 && cobblestoneCount >= 3) {
            removeItems(net.minecraft.world.item.Items.STICK, 2);
            removeItems(net.minecraft.world.item.Items.COBBLESTONE, 3);
            this.getInventory().add(new ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE, 1));
            // System.out.println("[DEBUG] Crafted stone pickaxe");
            cobblestoneCount -= 3;
            stickCount -= 2;
            
            // If using bot's own table, mark for pickup
            if (placedCraftingTablePos != null && nearbyCraftingTable.equals(placedCraftingTablePos)) {
                craftingComplete = true;
            }
        }
        
        // Craft stone shovel (need 1 stick + 1 cobblestone)
        if (!hasStoneShovel() && stickCount >= 1 && cobblestoneCount >= 1) {
            removeItems(net.minecraft.world.item.Items.STICK, 1);
            removeItems(net.minecraft.world.item.Items.COBBLESTONE, 1);
            this.getInventory().add(new ItemStack(net.minecraft.world.item.Items.STONE_SHOVEL, 1));
            // System.out.println("[DEBUG] Crafted stone shovel");
            
            // If using bot's own table, mark for pickup
            if (placedCraftingTablePos != null && nearbyCraftingTable.equals(placedCraftingTablePos)) {
                craftingComplete = true;
            }
        }
    }
    
    /**
     * Check if bot has stone sword
     */
    private boolean hasStoneSword() {
        return countItem(net.minecraft.world.item.Items.STONE_SWORD) > 0;
    }
    
    /**
     * Check if bot has stone pickaxe
     */
    private boolean hasStonePickaxe() {
        return countItem(net.minecraft.world.item.Items.STONE_PICKAXE) > 0;
    }
    
    /**
     * Check if bot has stone shovel
     */
    private boolean hasStoneShovel() {
        return countItem(net.minecraft.world.item.Items.STONE_SHOVEL) > 0;
    }
    
    /**
     * Craft planks from logs and then crafting table
     */
    private void craftPlanksAndTable() {
        // Check if bot already has a crafting table in inventory - try to place it first
        if (countItem(net.minecraft.world.item.Items.CRAFTING_TABLE) > 0) {
            needsCraftingTable = true;
            placeCraftingTable();
            return;
        }
        
        // Don't craft a new table if we just placed one and are waiting to pick it up
        if (placedCraftingTablePos != null) {
            return;
        }
        
        // Find logs in inventory
        net.minecraft.world.item.Item logType = null;
        int logCount = 0;
        
        if (countItem(net.minecraft.world.item.Items.OAK_LOG) > 0) {
            logType = net.minecraft.world.item.Items.OAK_LOG;
            logCount = countItem(logType);
        } else if (countItem(net.minecraft.world.item.Items.SPRUCE_LOG) > 0) {
            logType = net.minecraft.world.item.Items.SPRUCE_LOG;
            logCount = countItem(logType);
        } else if (countItem(net.minecraft.world.item.Items.BIRCH_LOG) > 0) {
            logType = net.minecraft.world.item.Items.BIRCH_LOG;
            logCount = countItem(logType);
        } else if (countItem(net.minecraft.world.item.Items.JUNGLE_LOG) > 0) {
            logType = net.minecraft.world.item.Items.JUNGLE_LOG;
            logCount = countItem(logType);
        } else if (countItem(net.minecraft.world.item.Items.ACACIA_LOG) > 0) {
            logType = net.minecraft.world.item.Items.ACACIA_LOG;
            logCount = countItem(logType);
        } else if (countItem(net.minecraft.world.item.Items.DARK_OAK_LOG) > 0) {
            logType = net.minecraft.world.item.Items.DARK_OAK_LOG;
            logCount = countItem(logType);
        }
        
        // Check if we have enough planks already
        int totalPlanks = countItem(net.minecraft.world.item.Items.OAK_PLANKS) +
                         countItem(net.minecraft.world.item.Items.SPRUCE_PLANKS) +
                         countItem(net.minecraft.world.item.Items.BIRCH_PLANKS) +
                         countItem(net.minecraft.world.item.Items.JUNGLE_PLANKS) +
                         countItem(net.minecraft.world.item.Items.ACACIA_PLANKS) +
                         countItem(net.minecraft.world.item.Items.DARK_OAK_PLANKS);
        
        // Convert ALL logs to planks (not just 1)
        while (logType != null && logCount > 0) {
            removeItems(logType, 1);
            net.minecraft.world.item.Item plankType = getPlanksFromLog(logType);
            this.getInventory().add(new ItemStack(plankType, 4));
            totalPlanks += 4;
            logCount--;
        }
        
        // System.out.println("[DEBUG] Crafting: totalPlanks=" + totalPlanks + ", hasCraftingTable=" + (countItem(net.minecraft.world.item.Items.CRAFTING_TABLE) > 0));
        
        // Craft crafting table only if we have exactly 4 or more planks and no table yet
        if (totalPlanks >= 4 && countItem(net.minecraft.world.item.Items.CRAFTING_TABLE) == 0) {
            // Craft crafting table
            net.minecraft.world.item.Item plankType = null;
            if (countItem(net.minecraft.world.item.Items.OAK_PLANKS) >= 4) plankType = net.minecraft.world.item.Items.OAK_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.SPRUCE_PLANKS) >= 4) plankType = net.minecraft.world.item.Items.SPRUCE_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.BIRCH_PLANKS) >= 4) plankType = net.minecraft.world.item.Items.BIRCH_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.JUNGLE_PLANKS) >= 4) plankType = net.minecraft.world.item.Items.JUNGLE_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.ACACIA_PLANKS) >= 4) plankType = net.minecraft.world.item.Items.ACACIA_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.DARK_OAK_PLANKS) >= 4) plankType = net.minecraft.world.item.Items.DARK_OAK_PLANKS;
            
            if (plankType != null) {
                // System.out.println("[DEBUG] Crafting crafting table from planks");
                removeItems(plankType, 4);
                this.getInventory().add(new ItemStack(net.minecraft.world.item.Items.CRAFTING_TABLE, 1));
                needsCraftingTable = true;
                placeCraftingTable();
            }
        }
    }
    
    /**
     * Get plank type from log type
     */
    private net.minecraft.world.item.Item getPlanksFromLog(net.minecraft.world.item.Item logType) {
        if (logType == net.minecraft.world.item.Items.OAK_LOG) return net.minecraft.world.item.Items.OAK_PLANKS;
        if (logType == net.minecraft.world.item.Items.SPRUCE_LOG) return net.minecraft.world.item.Items.SPRUCE_PLANKS;
        if (logType == net.minecraft.world.item.Items.BIRCH_LOG) return net.minecraft.world.item.Items.BIRCH_PLANKS;
        if (logType == net.minecraft.world.item.Items.JUNGLE_LOG) return net.minecraft.world.item.Items.JUNGLE_PLANKS;
        if (logType == net.minecraft.world.item.Items.ACACIA_LOG) return net.minecraft.world.item.Items.ACACIA_PLANKS;
        if (logType == net.minecraft.world.item.Items.DARK_OAK_LOG) return net.minecraft.world.item.Items.DARK_OAK_PLANKS;
        return net.minecraft.world.item.Items.OAK_PLANKS;
    }
    
    /**
     * Craft sticks and weapon when crafting table is available
     */
    private void craftSticksAndWeapon() {
        // Find nearby crafting table
        BlockPos nearbyCraftingTable = findNearbyCraftingTable();
        if (nearbyCraftingTable == null) {
            return; // No crafting table nearby, can't craft
        }
        
        // Check if bot is close enough to the crafting table (within 4 blocks)
        double distanceToTable = Math.sqrt(this.distanceToSqr(Vec3.atCenterOf(nearbyCraftingTable)));
        
        // Only navigate if too far AND don't already have a path
        if (distanceToTable > 4.0) {
            if (currentPath == null || wanderTarget != nearbyCraftingTable) {
                wanderTarget = nearbyCraftingTable;
                currentPath = pathFinder.findPath(this.blockPosition(), nearbyCraftingTable, 50);
                pathIndex = 0;
                
                // If pathfinding failed, the table is unreachable - craft a new one
                if (currentPath == null || currentPath.isEmpty()) {
                    craftPlanksAndTable();
                    return;
                }
            }
            return; // Wait until we get closer
        }
        
        // Close enough to craft - proceed with crafting
        
        // First, convert ALL logs to planks
        net.minecraft.world.item.Item logType = null;
        int logCount = 0;
        
        if (countItem(net.minecraft.world.item.Items.OAK_LOG) > 0) {
            logType = net.minecraft.world.item.Items.OAK_LOG;
            logCount = countItem(logType);
        } else if (countItem(net.minecraft.world.item.Items.SPRUCE_LOG) > 0) {
            logType = net.minecraft.world.item.Items.SPRUCE_LOG;
            logCount = countItem(logType);
        } else if (countItem(net.minecraft.world.item.Items.BIRCH_LOG) > 0) {
            logType = net.minecraft.world.item.Items.BIRCH_LOG;
            logCount = countItem(logType);
        } else if (countItem(net.minecraft.world.item.Items.JUNGLE_LOG) > 0) {
            logType = net.minecraft.world.item.Items.JUNGLE_LOG;
            logCount = countItem(logType);
        } else if (countItem(net.minecraft.world.item.Items.ACACIA_LOG) > 0) {
            logType = net.minecraft.world.item.Items.ACACIA_LOG;
            logCount = countItem(logType);
        } else if (countItem(net.minecraft.world.item.Items.DARK_OAK_LOG) > 0) {
            logType = net.minecraft.world.item.Items.DARK_OAK_LOG;
            logCount = countItem(logType);
        }
        
        // Convert ALL logs to planks
        while (logType != null && logCount > 0) {
            removeItems(logType, 1);
            net.minecraft.world.item.Item plankType = getPlanksFromLog(logType);
            this.getInventory().add(new ItemStack(plankType, 4));
            logCount--;
        }
        
        // Count total planks
        int totalPlanks = countItem(net.minecraft.world.item.Items.OAK_PLANKS) +
                         countItem(net.minecraft.world.item.Items.SPRUCE_PLANKS) +
                         countItem(net.minecraft.world.item.Items.BIRCH_PLANKS) +
                         countItem(net.minecraft.world.item.Items.JUNGLE_PLANKS) +
                         countItem(net.minecraft.world.item.Items.ACACIA_PLANKS) +
                         countItem(net.minecraft.world.item.Items.DARK_OAK_PLANKS);
        
        int stickCount = countItem(net.minecraft.world.item.Items.STICK);
        int cobblestoneCount = countItem(net.minecraft.world.item.Items.COBBLESTONE);
        
        // Check if bot should skip wooden tools and go straight to stone tools
        boolean shouldCraftStoneTools = (cobblestoneCount >= COBBLESTONE_TARGET);
        
        // Craft sticks if we have planks and need sticks
        // Need 5 sticks total: 2 for sword + 3 for pickaxe = 5 sticks (craft 2 batches = 8 sticks)
        if (totalPlanks >= 4 && stickCount < 5) {
            // Find which plank type we have
            net.minecraft.world.item.Item plankType = null;
            if (countItem(net.minecraft.world.item.Items.OAK_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.OAK_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.SPRUCE_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.SPRUCE_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.BIRCH_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.BIRCH_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.JUNGLE_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.JUNGLE_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.ACACIA_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.ACACIA_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.DARK_OAK_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.DARK_OAK_PLANKS;
            
            if (plankType != null) {
                // Craft sticks: 2 planks = 4 sticks (craft twice for 8 sticks total)
                int craftCount = Math.min(2, totalPlanks / 2); // Craft up to 2 times
                for (int i = 0; i < craftCount; i++) {
                    removeItems(plankType, 2);
                    this.getInventory().add(new ItemStack(net.minecraft.world.item.Items.STICK, 4));
                    stickCount += 4;
                    totalPlanks -= 2;
                }
            }
        }
        
        // If bot has enough cobblestone for stone tools, craft them instead of wooden tools
        if (shouldCraftStoneTools) {
            // Craft stone tools directly (handled by craftStoneTools method)
            craftStoneTools();
            return;
        }
        
        // Otherwise craft wooden tools (only if not enough cobblestone for stone tools)
        // Craft wooden sword if we have sticks and planks (need 1 stick + 2 planks)
        if (stickCount >= 1 && totalPlanks >= 2 && !hasWeapon()) {
            // Find which plank type we have
            net.minecraft.world.item.Item plankType = null;
            if (countItem(net.minecraft.world.item.Items.OAK_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.OAK_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.SPRUCE_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.SPRUCE_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.BIRCH_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.BIRCH_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.JUNGLE_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.JUNGLE_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.ACACIA_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.ACACIA_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.DARK_OAK_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.DARK_OAK_PLANKS;
            
            if (plankType != null) {
                // Craft wooden sword: 1 stick + 2 planks
                removeItems(net.minecraft.world.item.Items.STICK, 1);
                removeItems(plankType, 2);
                this.getInventory().add(new ItemStack(net.minecraft.world.item.Items.WOODEN_SWORD, 1));
                stickCount -= 1;
                totalPlanks -= 2;
                
                // If using bot's own table, mark crafting complete to trigger pickup
                if (placedCraftingTablePos != null && nearbyCraftingTable.equals(placedCraftingTablePos)) {
                    craftingComplete = true;
                    // System.out.println("[DEBUG] Crafted sword using bot's table - will pick up table");
                }
            }
        }
        
        // Craft wooden pickaxe if we have sticks and planks (need 2 sticks + 3 planks)
        if (stickCount >= 2 && totalPlanks >= 3 && !hasPickaxe()) {
            // Find which plank type we have
            net.minecraft.world.item.Item plankType = null;
            if (countItem(net.minecraft.world.item.Items.OAK_PLANKS) >= 3) plankType = net.minecraft.world.item.Items.OAK_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.SPRUCE_PLANKS) >= 3) plankType = net.minecraft.world.item.Items.SPRUCE_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.BIRCH_PLANKS) >= 3) plankType = net.minecraft.world.item.Items.BIRCH_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.JUNGLE_PLANKS) >= 3) plankType = net.minecraft.world.item.Items.JUNGLE_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.ACACIA_PLANKS) >= 3) plankType = net.minecraft.world.item.Items.ACACIA_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.DARK_OAK_PLANKS) >= 3) plankType = net.minecraft.world.item.Items.DARK_OAK_PLANKS;
            
            if (plankType != null) {
                // Craft wooden pickaxe: 2 sticks + 3 planks
                removeItems(net.minecraft.world.item.Items.STICK, 2);
                removeItems(plankType, 3);
                this.getInventory().add(new ItemStack(net.minecraft.world.item.Items.WOODEN_PICKAXE, 1));
                // DON'T clear wanderTarget/currentPath - preserve wander navigation
                
                // If using bot's own table, mark crafting complete to trigger pickup
                if (placedCraftingTablePos != null && nearbyCraftingTable.equals(placedCraftingTablePos)) {
                    craftingComplete = true;
                    // System.out.println("[DEBUG] Crafted pickaxe using bot's table - will pick up table");
                }
            }
        }
    }
    
    /**
     * Check if bot has any pickaxe
     */
    private boolean hasPickaxe() {
        for (int i = 0; i < this.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.PickaxeItem) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Place crafting table near bot
     */
    private void placeCraftingTable() {
        // Check if there's already a crafting table nearby (within 16 blocks)
        BlockPos nearbyCraftingTable = findNearbyCraftingTable();
        if (nearbyCraftingTable != null) {
            // Already have a crafting table nearby, don't place another
            needsCraftingTable = false;
            return;
        }
        
        // Find crafting table in inventory
        int slot = -1;
        for (int i = 0; i < this.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == net.minecraft.world.item.Items.CRAFTING_TABLE) {
                slot = i;
                break;
            }
        }
        
        if (slot == -1) {
            needsCraftingTable = false;
            return;
        }
        
        // Find empty spot next to bot
        BlockPos placePos = null;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                BlockPos checkPos = this.blockPosition().offset(x, 0, z);
                BlockPos below = checkPos.below();
                
                if (this.level().getBlockState(checkPos).isAir() && 
                    !this.level().getBlockState(below).isAir()) {
                    placePos = checkPos;
                    break;
                }
            }
            if (placePos != null) break;
        }
        
        if (placePos != null) {
            // Look at the placement position
            double dx = (placePos.getX() + 0.5) - this.getX();
            double dy = (placePos.getY() + 0.5) - this.getEyeY();
            double dz = (placePos.getZ() + 0.5) - this.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            
            float yaw = (float)(Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
            float pitch = (float)(-Math.atan2(dy, length) * 180.0 / Math.PI);
            this.setYRot(yaw);
            this.setXRot(pitch);
            this.setYHeadRot(yaw);
            
            // Play swing animation
            this.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
            
            // Place crafting table
            // System.out.println("[DEBUG] Placing crafting table at " + placePos);
            this.level().setBlock(placePos, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
            
            // Remove from inventory
            this.getInventory().getItem(slot).shrink(1);
            
            // Track placement for pickup logic
            placedCraftingTablePos = placePos;
            craftingTablePlaceTicks = 0;
            
            needsCraftingTable = false;
        } else {
            // System.out.println("[DEBUG] Could not find valid position to place crafting table");
        }
    }

    /**
     * Handle crafting logic - craft weapons if needed
     */
    private void handleCrafting() {
        // CRITICAL: Don't interfere if bot is picking up broken crafting table
        if (brokenCraftingTablePos != null) {
            return;
        }
        
        // Decrement crafting check cooldown
        if (craftingCheckCooldown > 0) {
            craftingCheckCooldown--;
            return;
        }
        
        // Check if bot has a weapon
        if (hasWeapon()) {
            craftingTableTarget = null;
            // Don't clear wanderTarget/currentPath - let normal wander mode handle it
            return;
        }
        
        // Check if bot has materials to craft a weapon
        CraftingRecipe recipe = findCraftableWeapon();
        if (recipe == null) {
            // No materials, reset cooldown and continue wandering
            craftingCheckCooldown = CRAFTING_CHECK_INTERVAL;
            craftingTableTarget = null;
            return;
        }
        
        // Find nearby crafting table if we don't have a target
        if (craftingTableTarget == null) {
            craftingTableTarget = findNearbyCraftingTable();
            if (craftingTableTarget == null) {
                // No crafting table found, reset cooldown
                craftingCheckCooldown = CRAFTING_CHECK_INTERVAL;
                wanderTarget = null; // Clear wander target so bot can wander normally
                currentPath = null;
                return;
            }
        }
        
        // Check if we're at the crafting table
        double distanceToCraftingTable = Math.sqrt(this.distanceToSqr(Vec3.atCenterOf(craftingTableTarget)));
        if (distanceToCraftingTable <= 4.0) {
            // At crafting table, craft the weapon
            craftWeapon(recipe);
            craftingTableTarget = null;
            wanderTarget = null; // Clear wander target so bot picks new random target
            currentPath = null; // Clear path so bot starts fresh
            craftingCheckCooldown = CRAFTING_CHECK_INTERVAL;
        } else {
            // Navigate to crafting table
            if (currentPath == null || wanderTarget != craftingTableTarget) {
                wanderTarget = craftingTableTarget;
                currentPath = pathFinder.findPath(this.blockPosition(), craftingTableTarget, 50);
                pathIndex = 0;
                
                if (currentPath == null || currentPath.isEmpty()) {
                    // Can't reach crafting table, clear it
                    craftingTableTarget = null;
                    wanderTarget = null; // Clear wander target so bot can wander normally
                    currentPath = null;
                    craftingCheckCooldown = CRAFTING_CHECK_INTERVAL;
                }
            }
        }
    }
    
    /**
     * Check if bot has any weapon
     */
    private boolean hasWeapon() {
        for (int i = 0; i < this.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.getInventory().getItem(i);
            if (!stack.isEmpty() && (stack.getItem() instanceof SwordItem || 
                                     stack.getItem() instanceof AxeItem || 
                                     stack.getItem() instanceof TridentItem)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Find a craftable weapon recipe based on inventory
     */
    private CraftingRecipe findCraftableWeapon() {
        // Count materials in inventory
        int sticks = countItem(net.minecraft.world.item.Items.STICK);
        int cobblestone = countItem(net.minecraft.world.item.Items.COBBLESTONE);
        int planks = countItem(net.minecraft.world.item.Items.OAK_PLANKS) + 
                     countItem(net.minecraft.world.item.Items.SPRUCE_PLANKS) +
                     countItem(net.minecraft.world.item.Items.BIRCH_PLANKS) +
                     countItem(net.minecraft.world.item.Items.JUNGLE_PLANKS) +
                     countItem(net.minecraft.world.item.Items.ACACIA_PLANKS) +
                     countItem(net.minecraft.world.item.Items.DARK_OAK_PLANKS) +
                     countItem(net.minecraft.world.item.Items.MANGROVE_PLANKS) +
                     countItem(net.minecraft.world.item.Items.CHERRY_PLANKS);
        int ironIngots = countItem(net.minecraft.world.item.Items.IRON_INGOT);
        int diamonds = countItem(net.minecraft.world.item.Items.DIAMOND);
        int gold = countItem(net.minecraft.world.item.Items.GOLD_INGOT);
        
        // Priority: Diamond > Iron > Stone > Wood
        if (diamonds >= 2 && sticks >= 1) {
            return new CraftingRecipe(net.minecraft.world.item.Items.DIAMOND_SWORD, 
                                     net.minecraft.world.item.Items.DIAMOND, 2,
                                     net.minecraft.world.item.Items.STICK, 1);
        }
        if (ironIngots >= 2 && sticks >= 1) {
            return new CraftingRecipe(net.minecraft.world.item.Items.IRON_SWORD,
                                     net.minecraft.world.item.Items.IRON_INGOT, 2,
                                     net.minecraft.world.item.Items.STICK, 1);
        }
        if (gold >= 2 && sticks >= 1) {
            return new CraftingRecipe(net.minecraft.world.item.Items.GOLDEN_SWORD,
                                     net.minecraft.world.item.Items.GOLD_INGOT, 2,
                                     net.minecraft.world.item.Items.STICK, 1);
        }
        if (cobblestone >= 2 && sticks >= 1) {
            return new CraftingRecipe(net.minecraft.world.item.Items.STONE_SWORD,
                                     net.minecraft.world.item.Items.COBBLESTONE, 2,
                                     net.minecraft.world.item.Items.STICK, 1);
        }
        if (planks >= 2 && sticks >= 1) {
            // Find which plank type we have
            net.minecraft.world.item.Item plankType = null;
            if (countItem(net.minecraft.world.item.Items.OAK_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.OAK_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.SPRUCE_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.SPRUCE_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.BIRCH_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.BIRCH_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.JUNGLE_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.JUNGLE_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.ACACIA_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.ACACIA_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.DARK_OAK_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.DARK_OAK_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.MANGROVE_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.MANGROVE_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.CHERRY_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.CHERRY_PLANKS;
            
            if (plankType != null) {
                return new CraftingRecipe(net.minecraft.world.item.Items.WOODEN_SWORD,
                                         plankType, 2,
                                         net.minecraft.world.item.Items.STICK, 1);
            }
        }
        
        return null;
    }
    
    /**
     * Count how many of a specific item the bot has
     */
    private int countItem(net.minecraft.world.item.Item item) {
        int count = 0;
        for (int i = 0; i < this.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }
    
    /**
     * Find nearby crafting table
     */
    private BlockPos findNearbyCraftingTable() {
        BlockPos botPos = this.blockPosition();
        int range = (int) CRAFTING_TABLE_RANGE;
        
        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos checkPos = botPos.offset(x, y, z);
                    
                    // Skip blacklisted positions
                    if (unreachableLogs.contains(checkPos)) {
                        continue;
                    }
                    
                    BlockState state = this.level().getBlockState(checkPos);
                    
                    if (state.getBlock() == net.minecraft.world.level.block.Blocks.CRAFTING_TABLE) {
                        return checkPos;
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Craft a weapon at the crafting table
     */
    private void craftWeapon(CraftingRecipe recipe) {
        // Remove materials from inventory
        removeItems(recipe.material1, recipe.material1Count);
        removeItems(recipe.material2, recipe.material2Count);
        
        // Add crafted weapon to inventory
        ItemStack craftedWeapon = new ItemStack(recipe.result, 1);
        this.getInventory().add(craftedWeapon);
    }
    
    /**
     * Remove items from inventory
     */
    private void removeItems(net.minecraft.world.item.Item item, int count) {
        int remaining = count;
        for (int i = 0; i < this.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = this.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                int toRemove = Math.min(remaining, stack.getCount());
                stack.shrink(toRemove);
                remaining -= toRemove;
            }
        }
    }
    
    /**
     * Simple crafting recipe holder
     */
    private static class CraftingRecipe {
        final net.minecraft.world.item.Item result;
        final net.minecraft.world.item.Item material1;
        final int material1Count;
        final net.minecraft.world.item.Item material2;
        final int material2Count;
        
        CraftingRecipe(net.minecraft.world.item.Item result,
                      net.minecraft.world.item.Item material1, int material1Count,
                      net.minecraft.world.item.Item material2, int material2Count) {
            this.result = result;
            this.material1 = material1;
            this.material1Count = material1Count;
            this.material2 = material2;
            this.material2Count = material2Count;
        }
    }

    /**
     * Handle wander mode logic
     */
    private void handleWanderMode() {
        // Decrement justCreatedPathTicks counter FIRST (before any early returns)
        if (justCreatedPathTicks > 0) {
            justCreatedPathTicks--;
            if (this.tickCount % 20 == 0) {
                // System.out.println("[DEBUG] WanderMode: Waiting for bot to start moving (justCreatedPathTicks=" + justCreatedPathTicks + ")");
            }
        }
        
        // Don't interfere with wander target if bot has a specific task
        // Tasks: picking up items, ACTIVELY breaking logs (not just spotting them), breaking stones, going to crafting table, OR picking up broken crafting table
        // Bot is actively breaking logs if:
        // 1. No weapon and needs logs (totalLogs < 4)
        // 2. Has weapon but no pickaxe and needs more wood (totalWoodValue < 20)
        int totalLogs = countItem(net.minecraft.world.item.Items.OAK_LOG) +
                       countItem(net.minecraft.world.item.Items.SPRUCE_LOG) +
                       countItem(net.minecraft.world.item.Items.BIRCH_LOG) +
                       countItem(net.minecraft.world.item.Items.JUNGLE_LOG) +
                       countItem(net.minecraft.world.item.Items.ACACIA_LOG) +
                       countItem(net.minecraft.world.item.Items.DARK_OAK_LOG);
        int totalPlanks = countItem(net.minecraft.world.item.Items.OAK_PLANKS) +
                         countItem(net.minecraft.world.item.Items.SPRUCE_PLANKS) +
                         countItem(net.minecraft.world.item.Items.BIRCH_PLANKS) +
                         countItem(net.minecraft.world.item.Items.JUNGLE_PLANKS) +
                         countItem(net.minecraft.world.item.Items.ACACIA_PLANKS) +
                         countItem(net.minecraft.world.item.Items.DARK_OAK_PLANKS);
        int totalWoodValue = (totalLogs * 4) + totalPlanks;
        
        boolean activelyBreakingLogs = targetLog != null && ((!hasWeapon() && totalLogs < 4) || (hasWeapon() && !hasPickaxe() && totalWoodValue < 20));
        boolean activelyBreakingStone = (targetStone != null && countItem(net.minecraft.world.item.Items.COBBLESTONE) < COBBLESTONE_TARGET);
        boolean pickingUpCraftingTable = (brokenCraftingTablePos != null); // NEW: Don't override crafting table pickup
        boolean hasTask = (lastBrokenLogPos != null) || activelyBreakingLogs || activelyBreakingStone || (craftingTableTarget != null) || pickingUpCraftingTable;
        
        // Debug output every 100 ticks
        if (this.tickCount % 100 == 0) {
            // System.out.println("[DEBUG] WanderMode: hasTask=" + hasTask + ", activelyBreakingLogs=" + activelyBreakingLogs + ", activelyBreakingStone=" + activelyBreakingStone + ", wanderTarget=" + wanderTarget + ", currentPath=" + (currentPath != null ? "exists" : "null") + ", justCreatedPathTicks=" + justCreatedPathTicks);
        }
        
        if (hasTask) {
            if (this.tickCount % 100 == 0) {
                // System.out.println("[DEBUG] WanderMode: Skipping because bot has task");
            }
            return; // Let the task logic handle navigation
        }
        
        // Decrement cooldown
        if (wanderCooldown > 0) {
            wanderCooldown--;
            return;
        }
        
        // Check if we need a new wander target (no target OR close to current target)
        // BUT: Don't pick new target if we just created a path (prevents immediate re-picking)
        if (justCreatedPathTicks == 0 && (wanderTarget == null || this.distanceToSqr(Vec3.atCenterOf(wanderTarget)) < 4.0)) {
            // System.out.println("[DEBUG] WanderMode: Picking new wander target (current=" + wanderTarget + ")");
            
            // Pick a random position within range (try up to 10 times to avoid blacklisted areas)
            net.minecraft.util.RandomSource random = this.level().random;
            int attempts = 0;
            boolean foundValidTarget = false;
            
            while (attempts < 10 && !foundValidTarget) {
                int offsetX = random.nextInt(WANDER_RANGE * 2) - WANDER_RANGE;
                int offsetZ = random.nextInt(WANDER_RANGE * 2) - WANDER_RANGE;
                
                BlockPos newTarget = this.blockPosition().offset(offsetX, 0, offsetZ);
                
                // Check if this area is blacklisted (check 3x3 area around target)
                boolean isBlacklisted = false;
                for (int x = -1; x <= 1 && !isBlacklisted; x++) {
                    for (int z = -1; z <= 1 && !isBlacklisted; z++) {
                        if (unreachableLogs.contains(newTarget.offset(x, 0, z))) {
                            isBlacklisted = true;
                        }
                    }
                }
                
                if (isBlacklisted) {
                    // System.out.println("[DEBUG] WanderMode: Target " + newTarget + " is blacklisted, trying another (attempt " + (attempts + 1) + "/10)");
                    attempts++;
                    continue; // Try another random position
                }
                
                // Find ground level at target position
                wanderTarget = null; // Clear old target first
                for (int y = 10; y >= -10; y--) {
                    BlockPos checkPos = newTarget.offset(0, y, 0);
                    if (!this.level().getBlockState(checkPos).isAir() && 
                        this.level().getBlockState(checkPos.above()).isAir() &&
                        this.level().getBlockState(checkPos.above(2)).isAir()) {
                        wanderTarget = checkPos.above();
                        foundValidTarget = true;
                        break;
                    }
                }
                
                attempts++;
            }
            
            // Calculate path to wander target
            if (wanderTarget != null) {
                // System.out.println("[DEBUG] WanderMode: Found valid target at " + wanderTarget + ", calculating path");
                currentPath = pathFinder.findPath(this.blockPosition(), wanderTarget, 50);
                pathIndex = 0;
                
                // System.out.println("[DEBUG] WanderMode: Path result: " + (currentPath != null ? ("path with " + currentPath.size() + " nodes") : "null"));
                
                // If path found successfully, set justCreatedPathTicks to prevent immediate re-picking
                if (currentPath != null && !currentPath.isEmpty()) {
                    justCreatedPathTicks = 20; // Wait 1 second (20 ticks) before allowing new target picking
                    // System.out.println("[DEBUG] WanderMode: Path created successfully, setting justCreatedPathTicks=20 to prevent immediate re-picking");
                    // System.out.println("[DEBUG] WanderMode: wanderTarget is now " + wanderTarget + ", currentPath has " + currentPath.size() + " nodes");
                } else {
                    // If no path found, clear target and try again immediately (no cooldown)
                    // System.out.println("[DEBUG] WanderMode: No path to target " + wanderTarget + ", will try again");
                    wanderTarget = null;
                    wanderCooldown = 0;
                }
            } else {
                // No valid ground found, try again immediately
                wanderCooldown = 0;
            }
        }
    }

    /**
     * Bots don't sleep
     */
    @Override
    public boolean isSleeping() {
        return false;
    }

    /**
     * Mark as a fake player for identification
     */
    public boolean isFakePlayer() {
        return true;
    }
    
    /**
     * Override to allow item pickup
     */
    @Override
    public boolean canTakeItem(net.minecraft.world.item.ItemStack stack) {
        return true;
    }
    
    /**
     * Override to ensure bot can pick up items
     */
    @Override
    public boolean isSpectator() {
        return false;
    }
    
    /**
     * Override to ensure bot is not invulnerable to item pickup
     */
    @Override
    public boolean isInvulnerable() {
        return false;
    }
    
    /**
     * Override die() to clean up breaking animation when bot dies
     */
    @Override
    public void die(net.minecraft.world.damagesource.DamageSource damageSource) {
        // Save survival mode state before death
        wasSurvivalModeBeforeDeath = this.survivalMode;
        
        // Clean up breaking animation if bot was breaking a log
        if (treeBreakingTicks > 0 && targetLog != null) {
            // Clear breaking animation for all nearby players
            ServerLevel serverLevel = (ServerLevel) this.level();
            for (ServerPlayer player : serverLevel.players()) {
                if (player.distanceTo(this) < 64) {
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                        this.getId(), targetLog, -1 // -1 clears the breaking animation
                    ));
                }
            }
        }
        
        // Reset all tree breaking state
        targetLog = null;
        savedTargetLog = null;
        treeBreakingTicks = 0;
        lastBrokenLogPos = null;
        logPickupTicks = 0;
        inCombat = false;
        
        // Call parent die() method
        super.die(damageSource);
    }
    
    /**
     * Override to prevent death animation from playing
     */
    @Override
    public boolean isDeadOrDying() {
        // If health is full, we're definitely not dead
        if (this.getHealth() >= this.getMaxHealth()) {
            return false;
        }
        return super.isDeadOrDying();
    }
    
    /**
     * Revive the bot from death state
     */
    public void revive() {
        // Reset all death-related fields
        this.setHealth(this.getMaxHealth());
        this.deathTime = 0;
        this.hurtTime = 0;
        this.hurtDuration = 0;
        
        // Restore survival mode state from before death
        if (wasSurvivalModeBeforeDeath) {
            this.setSurvivalMode(true);
            // System.out.println("[DEBUG] Bot respawned in survival mode");
        } else if (wanderMode) {
            // Was in normal wander mode, restore it
            this.setWanderMode(true);
            this.setSurvivalMode(false);
            // System.out.println("[DEBUG] Bot respawned in wander mode");
        }
        
        // Force clients to refresh entity state by removing and re-adding to level
        ServerLevel level = (ServerLevel) this.level();
        level.getChunkSource().removeEntity(this);
        level.getChunkSource().addEntity(this);
    }
    
    /**
     * Show path with particles for debugging
     */
    private void showPathParticles() {
        if (currentPath == null || currentPath.isEmpty()) return;
        
        ServerLevel serverLevel = (ServerLevel) this.level();
        
        // Show current waypoint (green particles)
        if (pathIndex < currentPath.size()) {
            BlockPos currentWaypoint = currentPath.get(pathIndex);
            serverLevel.sendParticles(
                net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                currentWaypoint.getX() + 0.5,
                currentWaypoint.getY() + 0.5,
                currentWaypoint.getZ() + 0.5,
                3, 0.3, 0.3, 0.3, 0.0
            );
        }
        
        // Show future waypoints (purple particles)
        for (int i = pathIndex + 1; i < Math.min(pathIndex + 10, currentPath.size()); i++) {
            BlockPos waypoint = currentPath.get(i);
            serverLevel.sendParticles(
                net.minecraft.core.particles.ParticleTypes.PORTAL,
                waypoint.getX() + 0.5,
                waypoint.getY() + 0.5,
                waypoint.getZ() + 0.5,
                2, 0.2, 0.2, 0.2, 0.0
            );
        }
        
        // Show wander target if exists (flame particles)
        if (wanderTarget != null) {
            serverLevel.sendParticles(
                net.minecraft.core.particles.ParticleTypes.FLAME,
                wanderTarget.getX() + 0.5,
                wanderTarget.getY() + 0.5,
                wanderTarget.getZ() + 0.5,
                5, 0.3, 0.3, 0.3, 0.0
            );
        }
        
        // Show target log if exists (end rod particles)
        if (targetLog != null) {
            serverLevel.sendParticles(
                net.minecraft.core.particles.ParticleTypes.END_ROD,
                targetLog.getX() + 0.5,
                targetLog.getY() + 0.5,
                targetLog.getZ() + 0.5,
                5, 0.3, 0.3, 0.3, 0.0
            );
        }
    }
}
