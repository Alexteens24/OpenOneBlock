package dev.openoneblock.core.locator;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotState;
import java.util.Objects;

/**
 * Minimal immutable locator projection for one non-free slot.
 *
 * @param shardGroupId owning shard group
 * @param gridPosition logical cell position
 * @param slotId stable slot identity
 * @param islandId owning island
 * @param slotState committed slot state
 * @param slotVersion non-negative slot state version
 */
public record SlotLocatorEntry(
    ShardGroupId shardGroupId,
    GridPosition gridPosition,
    SlotId slotId,
    IslandId islandId,
    SlotState slotState,
    long slotVersion) {
  /** Validates a non-free committed locator entry. */
  public SlotLocatorEntry {
    Objects.requireNonNull(shardGroupId, "shardGroupId");
    Objects.requireNonNull(gridPosition, "gridPosition");
    Objects.requireNonNull(slotId, "slotId");
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(slotState, "slotState");
    if (slotState == SlotState.FREE) {
      throw new IllegalArgumentException("locator entries must not expose FREE slots");
    }
    if (slotVersion < 0) {
      throw new IllegalArgumentException("slotVersion must be non-negative");
    }
  }
}
