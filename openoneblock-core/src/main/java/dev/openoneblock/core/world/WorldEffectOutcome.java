package dev.openoneblock.core.world;

import java.util.Objects;

/**
 * Explicit platform execution or recovery-verification result.
 *
 * @param status explicit outcome category
 * @param cleanupRequired whether residue may require cleanup
 * @param diagnostic stable diagnostic evidence
 */
public record WorldEffectOutcome(Status status, boolean cleanupRequired, String diagnostic) {
  /** Validates a useful diagnostic. */
  public WorldEffectOutcome {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(diagnostic, "diagnostic");
    if (diagnostic.isBlank()) {
      throw new IllegalArgumentException("effect outcome diagnostic must not be blank");
    }
  }

  /** Outcome categories; {@link #NOT_APPLIED} is valid only during recovery verification. */
  public enum Status {
    /** Expected target state is present. */
    VERIFIED_SUCCESS,
    /** Effect provably did not run, so an idempotent effect may be dispatched. */
    NOT_APPLIED,
    /** Execution provably failed and did not produce the required target. */
    VERIFIED_FAILURE,
    /** Execution may have partially or fully happened and needs reconciliation. */
    AMBIGUOUS
  }
}
