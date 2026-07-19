package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.core.world.IslandCleanup;
import java.time.Instant;
import java.util.Objects;

/**
 * Optimistic terminal creation cleanup evidence used to release or quarantine the slot.
 *
 * @param islandId failed creation
 * @param operationId creation operation
 * @param expectedIslandVersion observed broken-island version
 * @param expectedSlotVersion observed cleaning-slot version
 * @param status explicit cleanup verification outcome
 * @param diagnostic stable cleanup evidence
 * @param completedAt caller-supplied completion time
 */
public record IslandCreationCleanupCompletionRequest(
    IslandId islandId,
    OperationId operationId,
    long expectedIslandVersion,
    long expectedSlotVersion,
    IslandCleanup.Status status,
    String diagnostic,
    Instant completedAt) {
  /** Validates complete terminal cleanup evidence. */
  public IslandCreationCleanupCompletionRequest {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(diagnostic, "diagnostic");
    Objects.requireNonNull(completedAt, "completedAt");
    if (expectedIslandVersion < 0 || expectedSlotVersion < 0) {
      throw new IllegalArgumentException("expected versions must be non-negative");
    }
    if (diagnostic.isBlank()) {
      throw new IllegalArgumentException("cleanup diagnostic must not be blank");
    }
  }
}
