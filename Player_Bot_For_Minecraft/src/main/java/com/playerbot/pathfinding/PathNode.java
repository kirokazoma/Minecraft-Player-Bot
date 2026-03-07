package com.playerbot.pathfinding;

import net.minecraft.core.BlockPos;

/**
 * A node in the pathfinding graph
 */
public class PathNode {
    public final BlockPos pos;
    public final double heuristic; // Estimated cost to goal
    public double cost; // Actual cost from start
    public double totalCost; // cost + heuristic
    public PathNode parent;
    
    public PathNode(BlockPos pos, BlockPos goal) {
        this.pos = pos;
        this.heuristic = calculateHeuristic(pos, goal);
        this.cost = Double.MAX_VALUE;
        this.totalCost = Double.MAX_VALUE;
        this.parent = null;
    }
    
    private double calculateHeuristic(BlockPos from, BlockPos to) {
        // Manhattan distance for heuristic
        int dx = Math.abs(to.getX() - from.getX());
        int dy = Math.abs(to.getY() - from.getY());
        int dz = Math.abs(to.getZ() - from.getZ());
        return dx + dy + dz;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof PathNode)) return false;
        PathNode other = (PathNode) obj;
        return pos.equals(other.pos);
    }
    
    @Override
    public int hashCode() {
        return pos.hashCode();
    }
}
