package dev.openoneblock.core.slot;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.ShardGroupId;
import java.util.Objects;

/**
 * Immutable authoritative outcome of a committed slot allocation.
 *
 * @param slotId stable slot identity
 * @param shardGroupId owning shard group
 * @param ordinal deterministic allocator ordinal
 * @param gridPosition logical cell position
 * @param state committed slot state
 * @param ownerIslandId owning island
 * @param version non-negative slot state version
 */
public record AllocatedSlot(
    SlotId slotId,
    ShardGroupId shardGroupId,
    long ordinal,
    GridPosition gridPosition,
    SlotState state,
    IslandId ownerIslandId,
    long version) {
  /** Validates a committed allocation outcome. */
  public AllocatedSlot {
    Objects.requireNonNull(slotId, "slotId");
    Objects.requireNonNull(shardGroupId, "shardGroupId");
    Objects.requireNonNull(gridPosition, "gridPosition");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(ownerIslandId, "ownerIslandId");
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must be non-negative");
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must be non-negative");
    }
    if (state == SlotState.FREE) {
      throw new IllegalArgumentException("an allocated slot must not be FREE");
    }
  }
}
