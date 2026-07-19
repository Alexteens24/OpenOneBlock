package dev.openoneblock.core.runtime;

import dev.openoneblock.api.id.IslandId;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable diagnostics for one transient island runtime entry.
 *
 * @param islandId island identity
 * @param state transient runtime state
 * @param activityCounts positive reference counts by reason
 * @param loadedChunkCount acquired ticket count
 */
public record IslandRuntimeSnapshot(
    IslandId islandId,
    IslandRuntimeState state,
    Map<IslandActivityReason, Integer> activityCounts,
    int loadedChunkCount) {
  /** Validates and defensively copies diagnostics. */
  public IslandRuntimeSnapshot {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(state, "state");
    activityCounts = Map.copyOf(activityCounts);
    if (loadedChunkCount < 0) {
      throw new IllegalArgumentException("loadedChunkCount must be non-negative");
    }
  }
}
