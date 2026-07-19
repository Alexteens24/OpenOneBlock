package dev.openoneblock.core.island;

import dev.openoneblock.api.island.IslandLifecycleState;
import java.util.Objects;

/**
 * Durable repair progress or terminal replay.
 *
 * @param island exact durable aggregate snapshot
 * @param status repair status
 * @param replay whether an existing operation produced this result
 * @param diagnostic terminal or verification diagnostic
 */
public record IslandRepairProgress(
    IslandAggregateSnapshot island, Status status, boolean replay, String diagnostic) {
  /** Validates lifecycle/status coherence. */
  public IslandRepairProgress {
    Objects.requireNonNull(island, "island");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(diagnostic, "diagnostic");
    if (status == Status.VERIFYING && island.lifecycleState() != IslandLifecycleState.BROKEN) {
      throw new IllegalArgumentException("verifying repair must keep island BROKEN");
    }
    if (status == Status.LOCKED && island.lifecycleState() != IslandLifecycleState.LOCKED) {
      throw new IllegalArgumentException("successful repair must end LOCKED");
    }
    if ((status == Status.FAILED || status == Status.AMBIGUOUS)
        && island.lifecycleState() != IslandLifecycleState.BROKEN) {
      throw new IllegalArgumentException("unsafe repair must remain BROKEN");
    }
  }

  /** Durable repair phase. */
  public enum Status {
    /** Repair is admitted and awaiting fresh external evidence. */
    VERIFYING,
    /** Reconciliation succeeded; explicit unlock is still required. */
    LOCKED,
    /** A deterministic invariant failed. */
    FAILED,
    /** Safety could not be proven. */
    AMBIGUOUS
  }
}
