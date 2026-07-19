package dev.openoneblock.core.island;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import java.util.Objects;

/**
 * Immutable persisted player-facing island summary.
 *
 * @param islandId island identity
 * @param ownerId owner identity
 * @param requesterRoleId requester's persisted role
 * @param shardGroupId owning shard
 * @param gridPosition owning cell
 * @param currentBorderSize playable border width
 * @param maximumBorderSize reserved maximum border width
 * @param phaseId current progression phase
 * @param totalBreaks authoritative total-break counter
 * @param magicBlockSequence primary Magic Block sequence
 * @param activeMemberCount active member count
 * @param islandVersion observed aggregate version
 */
public record IslandInfoSnapshot(
    IslandId islandId,
    PlayerId ownerId,
    NamespacedId requesterRoleId,
    ShardGroupId shardGroupId,
    GridPosition gridPosition,
    int currentBorderSize,
    int maximumBorderSize,
    NamespacedId phaseId,
    long totalBreaks,
    long magicBlockSequence,
    int activeMemberCount,
    long islandVersion) {
  /** Validates complete summary state. */
  public IslandInfoSnapshot {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(ownerId, "ownerId");
    Objects.requireNonNull(requesterRoleId, "requesterRoleId");
    Objects.requireNonNull(shardGroupId, "shardGroupId");
    Objects.requireNonNull(gridPosition, "gridPosition");
    Objects.requireNonNull(phaseId, "phaseId");
    if (currentBorderSize <= 0 || maximumBorderSize < currentBorderSize) {
      throw new IllegalArgumentException("invalid border sizes");
    }
    if (totalBreaks < 0 || magicBlockSequence < 0 || islandVersion < 0) {
      throw new IllegalArgumentException("counter, sequence, and version must be non-negative");
    }
    if (activeMemberCount <= 0) {
      throw new IllegalArgumentException("an active island must have an active member");
    }
  }
}
