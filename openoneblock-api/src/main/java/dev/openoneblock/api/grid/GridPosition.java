package dev.openoneblock.api.grid;

/**
 * Logical cell coordinates shared by all dimensions in a shard group.
 *
 * @param gridX signed cell coordinate on the X axis
 * @param gridZ signed cell coordinate on the Z axis
 */
public record GridPosition(int gridX, int gridZ) {}
