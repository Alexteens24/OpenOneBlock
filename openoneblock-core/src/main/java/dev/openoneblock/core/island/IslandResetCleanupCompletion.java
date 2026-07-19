package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.core.world.IslandCleanup;
import java.time.Instant;
import java.util.Objects;

/**
 * Verified multi-dimension cleanup evidence for one reset stage.
 *
 * @param islandId resetting island
 * @param operationId durable reset operation
 * @param cleanupStage initial or failure cleanup stage
 * @param expectedIslandVersion observed resetting aggregate version
 * @param expectedSlotVersion observed cleaning slot version
 * @param status combined cleanup status across every dimension
 * @param diagnostic combined cleanup evidence
 * @param completedAt caller clock instant
 */
public record IslandResetCleanupCompletion(
    IslandId islandId,
    OperationId operationId,
    IslandResetProgress.Stage cleanupStage,
    long expectedIslandVersion,
    long expectedSlotVersion,
    IslandCleanup.Status status,
    String diagnostic,
    Instant completedAt) {
  /** Validates optimistic cleanup evidence. */
  public IslandResetCleanupCompletion {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(cleanupStage, "cleanupStage");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(diagnostic, "diagnostic");
    Objects.requireNonNull(completedAt, "completedAt");
    if (cleanupStage != IslandResetProgress.Stage.CLEANING_INITIAL
        && cleanupStage != IslandResetProgress.Stage.CLEANING_FAILURE) {
      throw new IllegalArgumentException("completion must identify a cleanup reset stage");
    }
    if (expectedIslandVersion < 0 || expectedSlotVersion < 0) {
      throw new IllegalArgumentException("expected versions must be non-negative");
    }
    if (diagnostic.isBlank()) {
      throw new IllegalArgumentException("cleanup diagnostic must not be blank");
    }
  }
}
