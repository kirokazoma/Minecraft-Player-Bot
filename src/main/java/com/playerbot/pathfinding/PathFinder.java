package com.playerbot.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.tags.FluidTags;

import java.util.*;

/**
 * A* pathfinding implementation for bot navigation
 */
public class PathFinder {
    
    private final Level level;
    private static final int MAX_ITERATIONS = 1000; // Prevent infinite loops
    private static final double WALK_COST = 1.0;
    private static final double JUMP_COST = 1.5;
    private static final double FALL_COST = 0.5;
    
    private boolean ignoreLeavesForPathing = false; // Flag to ignore leaves during pathfinding
    
    public PathFinder(Level level) {
        this.level = level;
    }
    
    /**
     * Find a path from start to goal using A* algorithm
     */
    public List<BlockPos> findPath(BlockPos start, BlockPos goal, int maxDistance) {
        return findPath(start, goal, maxDistance, false);
    }
    
    /**
     * Find a path from start to goal using A* algorithm with option to ignore leaves
     */
    public List<BlockPos> findPath(BlockPos start, BlockPos goal, int maxDistance, boolean ignoreLeaves) {
        this.ignoreLeavesForPathing = ignoreLeaves;
        
        // Don't pathfind if too far
        if (start.distManhattan(goal) > maxDistance) {
            return null;
        }
        
        // SPECIAL CASE: If goal is a log within reach distance, return path to current position
        // Bot can break logs above it without moving (shaft mining)
        BlockState goalState = level.getBlockState(goal);
        boolean isGoalALog = goalState.getBlock() instanceof net.minecraft.world.level.block.RotatedPillarBlock &&
                            (goalState.is(net.minecraft.world.level.block.Blocks.OAK_LOG) ||
                             goalState.is(net.minecraft.world.level.block.Blocks.SPRUCE_LOG) ||
                             goalState.is(net.minecraft.world.level.block.Blocks.BIRCH_LOG) ||
                             goalState.is(net.minecraft.world.level.block.Blocks.JUNGLE_LOG) ||
                             goalState.is(net.minecraft.world.level.block.Blocks.ACACIA_LOG) ||
                             goalState.is(net.minecraft.world.level.block.Blocks.DARK_OAK_LOG));
        
        if (isGoalALog) {
            double reachDistance = Math.sqrt(start.distSqr(goal));
            if (reachDistance <= 4.5) {
                // Log is within reach - return path to current position (don't move)
                List<BlockPos> directPath = new ArrayList<>();
                directPath.add(start);
                return directPath;
            }
        }
        
        PriorityQueue<PathNode> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.totalCost));
        Map<BlockPos, PathNode> allNodes = new HashMap<>();
        Set<BlockPos> closedSet = new HashSet<>();
        
        PathNode startNode = new PathNode(start, goal);
        startNode.cost = 0;
        startNode.totalCost = startNode.heuristic;
        
        openSet.add(startNode);
        allNodes.put(start, startNode);
        
        int iterations = 0;
        
        while (!openSet.isEmpty() && iterations < MAX_ITERATIONS) {
            iterations++;
            
            PathNode current = openSet.poll();
            
            // Reached goal
            if (current.pos.distManhattan(goal) <= 2) {
                return reconstructPath(current);
            }
            
            closedSet.add(current.pos);
            
            // Check all neighbors
            for (BlockPos neighbor : getNeighbors(current.pos)) {
                if (closedSet.contains(neighbor)) {
                    continue;
                }
                
                double moveCost = calculateMoveCost(current.pos, neighbor);
                if (moveCost >= Double.MAX_VALUE) {
                    continue; // Can't move here
                }
                
                double tentativeCost = current.cost + moveCost;
                
                PathNode neighborNode = allNodes.get(neighbor);
                if (neighborNode == null) {
                    neighborNode = new PathNode(neighbor, goal);
                    allNodes.put(neighbor, neighborNode);
                }
                
                if (tentativeCost < neighborNode.cost) {
                    neighborNode.parent = current;
                    neighborNode.cost = tentativeCost;
                    neighborNode.totalCost = tentativeCost + neighborNode.heuristic;
                    
                    // Re-add to open set with updated priority
                    openSet.remove(neighborNode);
                    openSet.add(neighborNode);
                }
            }
        }
        
        return null; // No path found
    }
    
    /**
     * Get valid neighboring positions
     */
    private List<BlockPos> getNeighbors(BlockPos pos) {
        List<BlockPos> neighbors = new ArrayList<>();
        
        // Horizontal moves (N, S, E, W)
        neighbors.add(pos.north());
        neighbors.add(pos.south());
        neighbors.add(pos.east());
        neighbors.add(pos.west());
        
        // Diagonal moves
        neighbors.add(pos.north().east());
        neighbors.add(pos.north().west());
        neighbors.add(pos.south().east());
        neighbors.add(pos.south().west());
        
        // Up (jump)
        neighbors.add(pos.above());
        neighbors.add(pos.north().above());
        neighbors.add(pos.south().above());
        neighbors.add(pos.east().above());
        neighbors.add(pos.west().above());
        
        // Down (fall/descend)
        neighbors.add(pos.below());
        neighbors.add(pos.north().below());
        neighbors.add(pos.south().below());
        neighbors.add(pos.east().below());
        neighbors.add(pos.west().below());
        
        return neighbors;
    }
    
    /**
     * Calculate cost of moving from one position to another
     */
    private double calculateMoveCost(BlockPos from, BlockPos to) {
        // Check if destination is walkable
        if (!isWalkable(to)) {
            return Double.MAX_VALUE;
        }
        
        int dy = to.getY() - from.getY();
        
        if (dy > 1) {
            // Can't jump more than 1 block
            return Double.MAX_VALUE;
        } else if (dy == 1) {
            // Jumping up
            return JUMP_COST;
        } else if (dy < 0) {
            // Falling down
            if (dy < -3) {
                // Don't fall more than 3 blocks (would take damage)
                return Double.MAX_VALUE;
            }
            return FALL_COST;
        } else {
            // Same level
            return WALK_COST;
        }
    }
    
    /**
     * Check if a position is walkable
     */
    private boolean isWalkable(BlockPos pos) {
        // Check if position is loaded
        if (!level.isLoaded(pos)) {
            return false;
        }
        
        BlockState feetBlock = level.getBlockState(pos);
        BlockState headBlock = level.getBlockState(pos.above());
        BlockState groundBlock = level.getBlockState(pos.below());
        
        // Check if on ladder or vine
        boolean onLadder = feetBlock.getBlock() instanceof net.minecraft.world.level.block.LadderBlock ||
                          feetBlock.getBlock() instanceof net.minecraft.world.level.block.VineBlock;
        
        // If on ladder, we can climb up without solid ground
        if (onLadder) {
            boolean headPassable = canWalkThrough(headBlock) || isWater(pos.above());
            return headPassable;
        }
        
        // Must have solid ground below (or water)
        boolean hasSolidGround = !groundBlock.isAir() || isWater(pos.below());
        if (!hasSolidGround) {
            return false;
        }
        
        // Feet and head positions must be passable
        boolean feetPassable = canWalkThrough(feetBlock) || isWater(pos);
        boolean headPassable = canWalkThrough(headBlock) || isWater(pos.above());
        
        return feetPassable && headPassable;
    }
    
    /**
     * Check if block can be walked through
     */
    private boolean canWalkThrough(BlockState state) {
        // Air is always passable
        if (state.isAir()) {
            return true;
        }
        
        // Ladders and vines are passable
        if (state.getBlock() instanceof net.minecraft.world.level.block.LadderBlock ||
            state.getBlock() instanceof net.minecraft.world.level.block.VineBlock) {
            return true;
        }
        
        // Leaves are passable ONLY when ignoreLeavesForPathing flag is set (when gathering wood)
        if (state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
            return ignoreLeavesForPathing;
        }
        
        // Doors are always passable (bot will open them if closed)
        if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
            return true;
        }
        
        // Fence gates are always passable (bot will open them if closed)
        if (state.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock) {
            return true;
        }
        
        // Non-solid blocks are passable
        return !state.blocksMotion();
    }
    
    /**
     * Check if position contains water
     */
    private boolean isWater(BlockPos pos) {
        FluidState fluidState = level.getFluidState(pos);
        return fluidState.is(FluidTags.WATER);
    }
    
    /**
     * Reconstruct path from goal to start
     */
    private List<BlockPos> reconstructPath(PathNode goalNode) {
        List<BlockPos> path = new ArrayList<>();
        PathNode current = goalNode;
        
        while (current != null) {
            path.add(current.pos);
            current = current.parent;
        }
        
        Collections.reverse(path);
        return path;
    }
}
