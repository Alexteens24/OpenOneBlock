package dev.openoneblock.core.island;

import java.util.Objects;

/**
 * Successful active island result of a reset.
 *
 * @param island committed active aggregate
 * @param replay whether the operation had already completed
 */
public record IslandResetResult(IslandAggregateSnapshot island, boolean replay) {
  /** Validates active reset output. */
  public IslandResetResult {
    Objects.requireNonNull(island, "island");
    if (island.lifecycleState() != dev.openoneblock.api.island.IslandLifecycleState.ACTIVE) {
      throw new IllegalArgumentException("reset result must be ACTIVE");
    }
  }
}
