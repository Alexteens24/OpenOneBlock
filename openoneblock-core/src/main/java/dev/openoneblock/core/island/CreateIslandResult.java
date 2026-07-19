package dev.openoneblock.core.island;

import java.util.Objects;

/**
 * Post-commit creation outcome safe for teleport/event adapters.
 *
 * @param island committed active island
 * @param replay whether a prior completed operation was replayed
 */
public record CreateIslandResult(IslandAggregateSnapshot island, boolean replay) {
  /** Validates the committed snapshot. */
  public CreateIslandResult {
    Objects.requireNonNull(island, "island");
    if (island.lifecycleState() != dev.openoneblock.api.island.IslandLifecycleState.ACTIVE) {
      throw new IllegalArgumentException("creation result must contain an active island");
    }
  }
}
