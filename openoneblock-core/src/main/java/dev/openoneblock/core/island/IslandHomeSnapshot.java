package dev.openoneblock.core.island;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.core.world.WorldSpawnPosition;
import java.util.Objects;

/**
 * Minimal immutable authoritative state required to validate an island home teleport.
 *
 * @param islandId active island identity
 * @param shardGroupId owning shard group
 * @param gridPosition owning cell
 * @param currentBorderSize current playable border width
 * @param islandVersion optimistic aggregate version observed by the query
 * @param destination persisted primary spawn
 */
public record IslandHomeSnapshot(
    IslandId islandId,
    ShardGroupId shardGroupId,
    GridPosition gridPosition,
    int currentBorderSize,
    long islandVersion,
    WorldSpawnPosition destination) {
  /** Validates complete home state. */
  public IslandHomeSnapshot {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(shardGroupId, "shardGroupId");
    Objects.requireNonNull(gridPosition, "gridPosition");
    Objects.requireNonNull(destination, "destination");
    if (currentBorderSize <= 0) {
      throw new IllegalArgumentException("currentBorderSize must be positive");
    }
    if (islandVersion < 0) {
      throw new IllegalArgumentException("islandVersion must be non-negative");
    }
  }
}
