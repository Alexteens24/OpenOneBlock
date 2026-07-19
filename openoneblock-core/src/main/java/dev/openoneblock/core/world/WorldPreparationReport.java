package dev.openoneblock.core.world;

import java.util.List;
import java.util.Objects;

/**
 * Immutable operation-level result including all durable evidence observed by the coordinator.
 *
 * @param status operation-level outcome
 * @param receipts ordered durable evidence
 * @param cleanupRequired whether world cleanup is required
 * @param diagnostic stable outcome diagnostic
 */
public record WorldPreparationReport(
    Status status, List<WorldEffectReceipt> receipts, boolean cleanupRequired, String diagnostic) {
  /** Defensively copies evidence and validates a useful diagnostic. */
  public WorldPreparationReport {
    Objects.requireNonNull(status, "status");
    receipts = List.copyOf(receipts);
    Objects.requireNonNull(diagnostic, "diagnostic");
    if (diagnostic.isBlank()) {
      throw new IllegalArgumentException("preparation diagnostic must not be blank");
    }
  }

  /** Overall preparation outcome. */
  public enum Status {
    /** Every required effect is durably verified. */
    VERIFIED_SUCCESS,
    /** At least one required effect has a verified failure. */
    VERIFIED_FAILURE,
    /** At least one effect has an ambiguous outcome. */
    RECONCILIATION_REQUIRED
  }
}
