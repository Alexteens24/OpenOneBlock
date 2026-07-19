package dev.openoneblock.core.island;

import java.util.Objects;

/**
 * Durable deletion operation projection.
 *
 * @param island current aggregate
 * @param status durable operation status
 * @param replay whether the operation already existed
 * @param diagnostic durable terminal evidence or empty while cleaning
 */
public record IslandDeletionProgress(
    IslandAggregateSnapshot island, Status status, boolean replay, String diagnostic) {
  /** Validates lifecycle/status consistency. */
  public IslandDeletionProgress {
    Objects.requireNonNull(island, "island");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(diagnostic, "diagnostic");
    if (status == Status.CLEANING
        && island.lifecycleState() != dev.openoneblock.api.island.IslandLifecycleState.DELETING) {
      throw new IllegalArgumentException("cleaning deletion must retain DELETING lifecycle");
    }
    if (status == Status.SUCCEEDED
        && island.lifecycleState() != dev.openoneblock.api.island.IslandLifecycleState.ARCHIVED) {
      throw new IllegalArgumentException("successful deletion must archive the island");
    }
    if ((status == Status.FAILED || status == Status.AMBIGUOUS)
        && island.lifecycleState() != dev.openoneblock.api.island.IslandLifecycleState.BROKEN) {
      throw new IllegalArgumentException("unsafe deletion must leave a broken island");
    }
    if (status != Status.CLEANING && diagnostic.isBlank()) {
      throw new IllegalArgumentException("terminal deletion requires diagnostic evidence");
    }
  }

  /** Durable deletion states. */
  public enum Status {
    /** Cleanup must run or resume. */
    CLEANING,
    /** Every configured dimension was verified clean and the slot was released. */
    SUCCEEDED,
    /** Cleanup provably failed and the slot was quarantined. */
    FAILED,
    /** Cleanup outcome was uncertain and the slot was quarantined. */
    AMBIGUOUS
  }
}
