package dev.openoneblock.core.island;

import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.slot.SlotState;
import java.util.Objects;

/**
 * Durable reset operation projection used by normal execution and startup recovery.
 *
 * @param island current aggregate snapshot
 * @param stage durable reset stage
 * @param replay whether the operation already existed
 * @param diagnostic failure evidence or empty while healthy work remains
 */
public record IslandResetProgress(
    IslandAggregateSnapshot island, Stage stage, boolean replay, String diagnostic) {
  /** Validates lifecycle, slot, and operation stage consistency. */
  public IslandResetProgress {
    Objects.requireNonNull(island, "island");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(diagnostic, "diagnostic");
    SlotState slotState = island.primarySlot().map(slot -> slot.state()).orElse(null);
    switch (stage) {
      case CLEANING_INITIAL, CLEANING_FAILURE -> {
        if (island.lifecycleState() != IslandLifecycleState.RESETTING
            || slotState != SlotState.CLEANING) {
          throw new IllegalArgumentException("reset cleanup requires RESETTING/CLEANING");
        }
      }
      case PREPARING -> {
        if (island.lifecycleState() != IslandLifecycleState.RESETTING
            || slotState != SlotState.PREPARING) {
          throw new IllegalArgumentException("reset preparation requires RESETTING/PREPARING");
        }
      }
      case SUCCEEDED -> {
        if (island.lifecycleState() != IslandLifecycleState.ACTIVE
            || slotState != SlotState.ACTIVE) {
          throw new IllegalArgumentException("successful reset requires ACTIVE/ACTIVE");
        }
      }
      case FAILED, AMBIGUOUS -> {
        if (island.lifecycleState() != IslandLifecycleState.BROKEN
            || slotState != SlotState.QUARANTINED) {
          throw new IllegalArgumentException("unsafe reset requires BROKEN/QUARANTINED");
        }
      }
    }
    if (terminal() && diagnostic.isBlank()) {
      throw new IllegalArgumentException("terminal reset requires diagnostic evidence");
    }
  }

  /**
   * Returns whether no automatic reset work remains.
   *
   * @return true for success, failure, or ambiguity
   */
  public boolean terminal() {
    return stage == Stage.SUCCEEDED || stage == Stage.FAILED || stage == Stage.AMBIGUOUS;
  }

  /** Durable reset stages. */
  public enum Stage {
    /** Original island contents must be cleared in every dimension. */
    CLEANING_INITIAL,
    /** Starter content can be durably prepared in the primary world. */
    PREPARING,
    /** Partial preparation must be cleared before safe terminal quarantine. */
    CLEANING_FAILURE,
    /** Reset activated with verified world effects and reinitialized projections. */
    SUCCEEDED,
    /** Reset failed with a proven outcome and requires repair. */
    FAILED,
    /** Reset outcome could not be proven and requires repair. */
    AMBIGUOUS
  }
}
