package dev.openoneblock.core.operation;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.island.IslandLifecycleState;
import java.util.Objects;

/**
 * Immutable mutation header captured from an island aggregate inside its lane.
 *
 * @param islandId island represented by the snapshot
 * @param lifecycleState persisted lifecycle state
 * @param version non-negative aggregate version
 */
public record IslandStateSnapshot(
    IslandId islandId, IslandLifecycleState lifecycleState, long version) {
  /** Validates the immutable snapshot header. */
  public IslandStateSnapshot {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(lifecycleState, "lifecycleState");
    if (version < 0) {
      throw new IllegalArgumentException("version must be non-negative");
    }
  }
}
