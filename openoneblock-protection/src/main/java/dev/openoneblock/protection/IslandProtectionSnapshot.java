package dev.openoneblock.protection;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.island.IslandLifecycleState;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Minimal immutable island projection required by gameplay protection.
 *
 * @param islandId stable island identity
 * @param lifecycleState committed lifecycle state
 * @param shardGroupId owning shard group
 * @param gridPosition owning logical cell
 * @param currentBorderSize current playable border width
 * @param islandVersion committed aggregate version
 * @param activeMemberships active player-to-role mapping
 * @param magicBlocks registered immutable Magic Block locations
 */
public record IslandProtectionSnapshot(
    IslandId islandId,
    IslandLifecycleState lifecycleState,
    ShardGroupId shardGroupId,
    GridPosition gridPosition,
    int currentBorderSize,
    long islandVersion,
    Map<PlayerId, NamespacedId> activeMemberships,
    Set<ProtectionPosition> magicBlocks) {
  /** Validates and defensively copies projection state. */
  public IslandProtectionSnapshot {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(lifecycleState, "lifecycleState");
    Objects.requireNonNull(shardGroupId, "shardGroupId");
    Objects.requireNonNull(gridPosition, "gridPosition");
    if (currentBorderSize <= 0) {
      throw new IllegalArgumentException("currentBorderSize must be positive");
    }
    if (islandVersion < 0) {
      throw new IllegalArgumentException("islandVersion must be non-negative");
    }
    activeMemberships = Map.copyOf(activeMemberships);
    magicBlocks = Set.copyOf(magicBlocks);
  }
}
