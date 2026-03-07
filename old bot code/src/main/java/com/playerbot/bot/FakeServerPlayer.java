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
    
    // Wander mode fields
    private boolean wanderMode = false;
    private boolean survivalMode = false; // If true, bot breaks trees and crafts weapons
    private BlockPos wanderTarget = null;
    private int wanderCooldown = 0;
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
    
    // Arrow clearing field
    private int arrowClearTicks = 0;
    private static final int ARROW_CLEAR_INTERVAL = 1200; // 60 seconds = 1200 ticks
    
    // Tree breaking fields
    private BlockPos targetLog = null;
    private BlockPos savedTargetLog = null; // Save log position when interrupted by combat
    private int treeBreakingTicks = 0;
    private static final int TREE_BREAK_TIME = 60; // 3 seconds to break a log (like a player with bare hands)
    private boolean needsCraftingTable = false;
    private BlockPos lastBrokenLogPos = null;
    private int logPickupTicks = 0;
    private static final int LOG_PICKUP_WAIT = 40; // Navigate to broken log for 2 seconds
    private boolean inCombat = false;
    
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
        
        // Check if bot is stuck (in wander mode)
        if (wanderMode && !this.isDeadOrDying()) {
            checkIfStuck();
        }
        
        // Handle tree breaking and crafting table creation (ONLY in survival mode)
        if (survivalMode && wanderMode && !this.isDeadOrDying()) {
            handleTreeBreakingAndCrafting();
        }
        
        // Handle crafting in wander mode (always check for weapon crafting if materials available)
        if (wanderMode && followTarget == null && !this.isDeadOrDying()) {
            handleCrafting();
        }
        
        // Handle wander mode
        if (wanderMode && followTarget == null) {
            handleWanderMode();
        }
        
        // Handle follow behavior with pathfinding
        double moveX = 0;
        double moveZ = 0;
        boolean shouldJump = false;
        
        // Don't move if ACTIVELY breaking a tree (must be breaking AND close to log)
        boolean isBreakingTree = (treeBreakingTicks > 0);
        
        if (!isBreakingTree && followTarget != null && !followTarget.isRemoved() && !this.isDeadOrDying()) {
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
        } else if (wanderMode && !isBreakingTree && currentPath != null) {
            // In wander mode with a path - only sprint if bot has a specific task
            // Sprint when: picking up logs, navigating to logs, or going to crafting table
            boolean hasTask = (lastBrokenLogPos != null) || (targetLog != null) || (craftingTableTarget != null);
            this.setSprinting(hasTask);
        } else if (!wanderMode && !isBreakingTree) {
            // Not following and not wandering - disable sprinting and clear path
            this.setSprinting(false);
            currentPath = null;
            pathIndex = 0;
        }
        
        // Follow the path if it exists (for both follow and wander modes)
        if (!isBreakingTree && currentPath != null && pathIndex < currentPath.size() && !this.isDeadOrDying()) {
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
                    // If in wander mode, clear target and pick new one immediately (no cooldown)
                    if (wanderMode && wanderTarget != null) {
                        wanderTarget = null;
                        wanderCooldown = 0; // No cooldown - pick new target immediately
                    }
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
     * Check for doors in front of bot and open them
     */
    private void checkAndOpenDoors(double moveX, double moveZ) {
        // Calculate positions to check (in front of bot)
        double dirLength = Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (dirLength < 0.01) return;
        
        double dirX = moveX / dirLength;
        double dirZ = moveZ / dirLength;
        
        // Check 1-2 blocks in front
        for (int dist = 1; dist <= 2; dist++) {
            BlockPos checkPos = new BlockPos(
                (int)Math.floor(this.getX() + dirX * dist),
                (int)Math.floor(this.getY()),
                (int)Math.floor(this.getZ() + dirZ * dist)
            );
            
            // Also check one block above (for tall doors)
            BlockPos checkPosAbove = checkPos.above();
            
            BlockState state = this.level().getBlockState(checkPos);
            BlockState stateAbove = this.level().getBlockState(checkPosAbove);
            
            // Check if it's a door
            if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
                openDoor(checkPos, state);
            }
            if (stateAbove.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
                openDoor(checkPosAbove, stateAbove);
            }
            
            // Check if it's a fence gate
            if (state.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock) {
                openFenceGate(checkPos, state);
            }
        }
    }
    
    /**
     * Check if bot is stuck and break leaves if stuck
     */
    private void checkIfStuck() {
        BlockPos currentPos = this.blockPosition();
        
        // Check if position changed
        if (lastPosition != null && lastPosition.equals(currentPos)) {
            stuckTicks++;
            
            // If stuck for 3 seconds, try to break leaves in front
            if (stuckTicks >= STUCK_THRESHOLD) {
                breakLeavesInFront();
                stuckTicks = 0; // Reset after attempting to break
            }
        } else {
            // Position changed, reset stuck counter
            stuckTicks = 0;
            lastPosition = currentPos;
        }
    }
    
    /**
     * Break leaf blocks in front of bot when stuck
     */
    private void breakLeavesInFront() {
        // Check blocks in front at bot's height and above
        for (int dist = 1; dist <= 2; dist++) {
            // Calculate forward direction based on bot's yaw
            double yawRad = Math.toRadians(this.getYRot() + 90); // +90 to convert from Minecraft yaw
            double dirX = Math.cos(yawRad);
            double dirZ = Math.sin(yawRad);
            
            BlockPos checkPos = new BlockPos(
                (int)Math.floor(this.getX() + dirX * dist),
                (int)Math.floor(this.getY()),
                (int)Math.floor(this.getZ() + dirZ * dist)
            );
            BlockPos checkPosAbove = checkPos.above();
            BlockPos checkPosAbove2 = checkPos.above(2);
            
            BlockState state = this.level().getBlockState(checkPos);
            BlockState stateAbove = this.level().getBlockState(checkPosAbove);
            BlockState stateAbove2 = this.level().getBlockState(checkPosAbove2);
            
            // Break leaves if found (prioritize lower blocks first)
            if (state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                breakLeaves(checkPos);
                return; // Break one at a time
            }
            if (stateAbove.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                breakLeaves(checkPosAbove);
                return; // Break one at a time
            }
            if (stateAbove2.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                breakLeaves(checkPosAbove2);
                return; // Break one at a time
            }
        }
        
        // Also check directly above the bot (no forward offset)
        BlockPos aboveBot = this.blockPosition().above();
        BlockPos aboveBot2 = this.blockPosition().above(2);
        
        BlockState stateAboveBot = this.level().getBlockState(aboveBot);
        BlockState stateAboveBot2 = this.level().getBlockState(aboveBot2);
        
        if (stateAboveBot.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
            breakLeaves(aboveBot);
            return;
        }
        if (stateAboveBot2.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
            breakLeaves(aboveBot2);
            return;
        }
    }
    
    /**
     * Break a leaf block
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
        
        // Break the leaf block
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
        // If bot has weapon, don't do anything - let normal wander mode handle everything
        if (hasWeapon()) {
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
                // Time's up, clear and continue breaking logs
                lastBrokenLogPos = null;
                logPickupTicks = 0;
                wanderTarget = null;
                currentPath = null;
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
        
        // Look for trees only if we need logs (no weapon and less than 3 logs)
        if (targetLog == null && !hasWeapon && totalLogs < 3) {
            targetLog = findNearbyLog();
        }
        
        // If found a log and need to break it (less than 3 logs and no weapon)
        if (targetLog != null && totalLogs < 3 && !hasWeapon) {
            double distance = Math.sqrt(this.distanceToSqr(Vec3.atCenterOf(targetLog)));
            
            if (distance <= 4.5) {
                // Close enough to break (increased range to reach logs above)
                breakLog(targetLog);
            } else {
                // Too far to break - reset breaking progress if we were breaking
                if (treeBreakingTicks > 0) {
                    // Clear breaking animation
                    ServerLevel serverLevel = (ServerLevel) this.level();
                    for (ServerPlayer player : serverLevel.players()) {
                        if (player.distanceTo(this) < 64) {
                            player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                                this.getId(), targetLog, -1 // -1 clears the breaking animation
                            ));
                        }
                    }
                    treeBreakingTicks = 0;
                }
                
                // Check for leaves blocking the path and break them
                if (checkAndBreakLeavesInPath(targetLog)) {
                    // Breaking leaves, don't navigate yet
                    return;
                }
                
                // Navigate to log (with ignoreLeaves=true so bot can path through leaves when gathering wood)
                if (currentPath == null || wanderTarget != targetLog) {
                    wanderTarget = targetLog;
                    currentPath = pathFinder.findPath(this.blockPosition(), targetLog, 50, true);
                    pathIndex = 0;
                    
                    // If pathfinding failed, clear this log and try to find another one next tick
                    if (currentPath == null || currentPath.isEmpty()) {
                        targetLog = null; // Clear unreachable log
                    }
                }
            }
        } else if (hasWeapon || totalLogs >= 3) {
            // Has weapon or enough logs, clear all tree-related targets and resume normal wander
            targetLog = null;
            treeBreakingTicks = 0;
            lastBrokenLogPos = null;
            logPickupTicks = 0;
            wanderTarget = null; // Clear wander target so bot picks new random target
            currentPath = null; // Clear path so bot can start fresh
            wanderCooldown = 0; // Clear cooldown so bot starts wandering immediately
        }
        // If no log found and no weapon, bot will continue to normal wander mode to search for logs
    }
    
    /**
     * Find nearby log block (returns the CLOSEST log)
     */
    private BlockPos findNearbyLog() {
        BlockPos botPos = this.blockPosition();
        int range = 16;
        
        BlockPos closestLog = null;
        double closestDistance = Double.MAX_VALUE;
        
        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos checkPos = botPos.offset(x, y, z);
                    BlockState state = this.level().getBlockState(checkPos);
                    
                    if (state.getBlock() instanceof net.minecraft.world.level.block.RotatedPillarBlock &&
                        (state.is(net.minecraft.world.level.block.Blocks.OAK_LOG) ||
                         state.is(net.minecraft.world.level.block.Blocks.SPRUCE_LOG) ||
                         state.is(net.minecraft.world.level.block.Blocks.BIRCH_LOG) ||
                         state.is(net.minecraft.world.level.block.Blocks.JUNGLE_LOG) ||
                         state.is(net.minecraft.world.level.block.Blocks.ACACIA_LOG) ||
                         state.is(net.minecraft.world.level.block.Blocks.DARK_OAK_LOG))) {
                        
                        // Calculate distance to this log
                        double distance = Math.sqrt(this.distanceToSqr(Vec3.atCenterOf(checkPos)));
                        
                        // Keep track of closest log
                        if (distance < closestDistance) {
                            closestDistance = distance;
                            closestLog = checkPos;
                        }
                    }
                }
            }
        }
        
        return closestLog;
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
                    // Found leaves in the way, break them
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
        
        // Send block breaking progress to nearby players (0-9 stages)
        int breakStage = Math.min(9, (treeBreakingTicks * 10) / TREE_BREAK_TIME);
        ServerLevel serverLevel = (ServerLevel) this.level();
        for (ServerPlayer player : serverLevel.players()) {
            if (player.distanceTo(this) < 64) { // Within render distance
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                    this.getId(), pos, breakStage
                ));
            }
        }
        
        if (treeBreakingTicks >= TREE_BREAK_TIME) {
            // Clear breaking animation
            for (ServerPlayer player : serverLevel.players()) {
                if (player.distanceTo(this) < 64) {
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                        this.getId(), pos, -1 // -1 clears the breaking animation
                    ));
                }
            }
            
            // Break the log
            this.level().destroyBlock(pos, true);
            lastBrokenLogPos = pos; // Navigate to this position briefly
            targetLog = null;
            treeBreakingTicks = 0;
            logPickupTicks = 0;
        }
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
        if (distanceToTable > 4.0) {
            // Too far from crafting table, navigate to it
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
        
        // First, convert logs to planks if needed
        net.minecraft.world.item.Item logType = null;
        if (countItem(net.minecraft.world.item.Items.OAK_LOG) > 0) {
            logType = net.minecraft.world.item.Items.OAK_LOG;
        } else if (countItem(net.minecraft.world.item.Items.SPRUCE_LOG) > 0) {
            logType = net.minecraft.world.item.Items.SPRUCE_LOG;
        } else if (countItem(net.minecraft.world.item.Items.BIRCH_LOG) > 0) {
            logType = net.minecraft.world.item.Items.BIRCH_LOG;
        } else if (countItem(net.minecraft.world.item.Items.JUNGLE_LOG) > 0) {
            logType = net.minecraft.world.item.Items.JUNGLE_LOG;
        } else if (countItem(net.minecraft.world.item.Items.ACACIA_LOG) > 0) {
            logType = net.minecraft.world.item.Items.ACACIA_LOG;
        } else if (countItem(net.minecraft.world.item.Items.DARK_OAK_LOG) > 0) {
            logType = net.minecraft.world.item.Items.DARK_OAK_LOG;
        }
        
        // Convert logs to planks
        if (logType != null) {
            removeItems(logType, 1);
            net.minecraft.world.item.Item plankType = getPlanksFromLog(logType);
            this.getInventory().add(new ItemStack(plankType, 4));
        }
        
        // Count total planks
        int totalPlanks = countItem(net.minecraft.world.item.Items.OAK_PLANKS) +
                         countItem(net.minecraft.world.item.Items.SPRUCE_PLANKS) +
                         countItem(net.minecraft.world.item.Items.BIRCH_PLANKS) +
                         countItem(net.minecraft.world.item.Items.JUNGLE_PLANKS) +
                         countItem(net.minecraft.world.item.Items.ACACIA_PLANKS) +
                         countItem(net.minecraft.world.item.Items.DARK_OAK_PLANKS);
        
        int stickCount = countItem(net.minecraft.world.item.Items.STICK);
        
        // Craft sticks if we have planks and need sticks (need at least 1 stick for weapon)
        if (totalPlanks >= 2 && stickCount < 1) {
            // Find which plank type we have
            net.minecraft.world.item.Item plankType = null;
            if (countItem(net.minecraft.world.item.Items.OAK_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.OAK_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.SPRUCE_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.SPRUCE_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.BIRCH_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.BIRCH_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.JUNGLE_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.JUNGLE_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.ACACIA_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.ACACIA_PLANKS;
            else if (countItem(net.minecraft.world.item.Items.DARK_OAK_PLANKS) >= 2) plankType = net.minecraft.world.item.Items.DARK_OAK_PLANKS;
            
            if (plankType != null) {
                // Craft sticks: 2 planks = 4 sticks
                removeItems(plankType, 2);
                this.getInventory().add(new ItemStack(net.minecraft.world.item.Items.STICK, 4));
                stickCount += 4;
                totalPlanks -= 2;
            }
        }
        
        // Craft weapon if we have sticks and planks (need 1 stick + 2 planks for wooden sword)
        if (stickCount >= 1 && totalPlanks >= 2) {
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
                // Clear navigation targets after crafting weapon
                wanderTarget = null;
                currentPath = null;
            }
        }
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
            this.level().setBlock(placePos, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
            
            // Remove from inventory
            this.getInventory().getItem(slot).shrink(1);
            
            needsCraftingTable = false;
        }
    }

    /**
     * Handle crafting logic - craft weapons if needed
     */
    private void handleCrafting() {
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
        // Don't interfere with wander target if bot has a specific task
        // Tasks: picking up items, ACTIVELY breaking logs (not just spotting them), or going to crafting table
        // Only consider targetLog a task if bot is actually breaking logs (needs logs and no weapon)
        int totalLogs = countItem(net.minecraft.world.item.Items.OAK_LOG) +
                       countItem(net.minecraft.world.item.Items.SPRUCE_LOG) +
                       countItem(net.minecraft.world.item.Items.BIRCH_LOG) +
                       countItem(net.minecraft.world.item.Items.JUNGLE_LOG) +
                       countItem(net.minecraft.world.item.Items.ACACIA_LOG) +
                       countItem(net.minecraft.world.item.Items.DARK_OAK_LOG);
        boolean activelyBreakingLogs = (targetLog != null && totalLogs < 3 && !hasWeapon());
        boolean hasTask = (lastBrokenLogPos != null) || activelyBreakingLogs || (craftingTableTarget != null);
        if (hasTask) {
            return; // Let the task logic handle navigation
        }
        
        // Decrement cooldown first
        if (wanderCooldown > 0) {
            wanderCooldown--;
            return;
        }
        
        // Check if we need a new wander target (no target OR close to current target)
        if (wanderTarget == null || this.distanceToSqr(Vec3.atCenterOf(wanderTarget)) < 4.0) {
            // Pick a random position within range
            net.minecraft.util.RandomSource random = this.level().random;
            int offsetX = random.nextInt(WANDER_RANGE * 2) - WANDER_RANGE;
            int offsetZ = random.nextInt(WANDER_RANGE * 2) - WANDER_RANGE;
            
            BlockPos newTarget = this.blockPosition().offset(offsetX, 0, offsetZ);
            
            // Find ground level at target position
            wanderTarget = null; // Clear old target first
            for (int y = 10; y >= -10; y--) {
                BlockPos checkPos = newTarget.offset(0, y, 0);
                if (!this.level().getBlockState(checkPos).isAir() && 
                    this.level().getBlockState(checkPos.above()).isAir() &&
                    this.level().getBlockState(checkPos.above(2)).isAir()) {
                    wanderTarget = checkPos.above();
                    break;
                }
            }
            
            // Calculate path to wander target
            if (wanderTarget != null) {
                currentPath = pathFinder.findPath(this.blockPosition(), wanderTarget, 50);
                pathIndex = 0;
                
                // If no path found, clear target and try again immediately (no cooldown)
                if (currentPath == null || currentPath.isEmpty()) {
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
        
        // Force clients to refresh entity state by removing and re-adding to level
        ServerLevel level = (ServerLevel) this.level();
        level.getChunkSource().removeEntity(this);
        level.getChunkSource().addEntity(this);
    }
}
