package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import java.time.Instant;
import java.util.Objects;

/**
 * Optimistic durable transition request for a creation failure before or after world mutation.
 *
 * @param islandId failed creation
 * @param operationId creation operation
 * @param expectedIslandVersion observed island version
 * @param expectedSlotVersion observed primary-slot version
 * @param diagnostic stable failure evidence
 * @param failedAt caller-supplied failure time
 */
public record IslandCreationFailureRequest(
    IslandId islandId,
    OperationId operationId,
    long expectedIslandVersion,
    long expectedSlotVersion,
    String diagnostic,
    Instant failedAt) {
  /** Validates complete failure evidence. */
  public IslandCreationFailureRequest {
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
