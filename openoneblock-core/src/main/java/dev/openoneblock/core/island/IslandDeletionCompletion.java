package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.core.world.IslandCleanup;
import java.time.Instant;
import java.util.Objects;

/**
 * Explicit cleanup evidence used to release or quarantine a deleting island.
 *
 * @param islandId deleting island
 * @param operationId deletion operation
 * @param expectedIslandVersion observed deleting aggregate version
 * @param expectedSlotVersion observed cleaning slot version
 * @param status combined cleanup status across every configured dimension
 * @param diagnostic combined evidence
 * @param completedAt caller clock instant
 */
public record IslandDeletionCompletion(
    IslandId islandId,
    OperationId operationId,
    long expectedIslandVersion,
    long expectedSlotVersion,
    IslandCleanup.Status status,
    String diagnostic,
    Instant completedAt) {
  /** Validates complete terminal evidence. */
  public IslandDeletionCompletion {
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
