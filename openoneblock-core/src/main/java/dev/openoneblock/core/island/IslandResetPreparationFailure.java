package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import java.time.Instant;
import java.util.Objects;

/**
 * Optimistic request to enter cleanup after starter preparation did not verify.
 *
 * @param islandId resetting island
 * @param operationId durable reset operation
 * @param expectedIslandVersion observed aggregate version
 * @param expectedSlotVersion observed preparing slot version
 * @param diagnostic preparation failure evidence
 * @param failedAt caller clock instant
 */
public record IslandResetPreparationFailure(
    IslandId islandId,
    OperationId operationId,
    long expectedIslandVersion,
    long expectedSlotVersion,
    String diagnostic,
    Instant failedAt) {
  /** Validates failure evidence. */
  public IslandResetPreparationFailure {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(diagnostic, "diagnostic");
    Objects.requireNonNull(failedAt, "failedAt");
    if (expectedIslandVersion < 0 || expectedSlotVersion < 0) {
      throw new IllegalArgumentException("expected versions must be non-negative");
    }
    if (diagnostic.isBlank()) {
      throw new IllegalArgumentException("failure diagnostic must not be blank");
    }
  }
}
