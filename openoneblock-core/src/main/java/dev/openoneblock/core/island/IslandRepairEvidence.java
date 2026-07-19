package dev.openoneblock.core.island;

import dev.openoneblock.api.id.WorldId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable external projection and locator evidence for one repair attempt.
 *
 * @param status verification outcome
 * @param verifiedWorldIds complete verified shard projection UUIDs
 * @param diagnostic bounded operator-readable evidence
 * @param observedAt verification time
 */
public record IslandRepairEvidence(
    Status status, List<WorldId> verifiedWorldIds, String diagnostic, Instant observedAt) {
  /** Validates coherent repair evidence. */
  public IslandRepairEvidence {
    Objects.requireNonNull(status, "status");
    verifiedWorldIds = List.copyOf(Objects.requireNonNull(verifiedWorldIds, "verifiedWorldIds"));
    Objects.requireNonNull(diagnostic, "diagnostic");
    Objects.requireNonNull(observedAt, "observedAt");
    if (diagnostic.isBlank() || diagnostic.length() > 2_048) {
      throw new IllegalArgumentException("repair diagnostic must contain at most 2048 characters");
    }
    if (status == Status.VERIFIED && verifiedWorldIds.isEmpty()) {
      throw new IllegalArgumentException("verified repair requires at least one world projection");
    }
  }

  /** External verification classification. */
  public enum Status {
    /** Locator and every configured shard projection agree. */
    VERIFIED,
    /** A deterministic mismatch was observed. */
    FAILED,
    /** Verification could not prove a safe answer. */
    AMBIGUOUS
  }
}
