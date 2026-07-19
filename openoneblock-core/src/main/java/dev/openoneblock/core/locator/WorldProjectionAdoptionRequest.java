package dev.openoneblock.core.locator;

import dev.openoneblock.api.id.OperationId;
import java.time.Instant;
import java.util.Objects;

/**
 * Explicit idempotent admin adoption of replacement world identity.
 *
 * @param operationId deduplication identity
 * @param replacement observed replacement definition
 * @param expectedVersion authoritative version inspected by the admin
 * @param requestedAt audit timestamp
 */
public record WorldProjectionAdoptionRequest(
    OperationId operationId,
    WorldProjectionDefinition replacement,
    long expectedVersion,
    Instant requestedAt) {
  /** Validates adoption preconditions. */
  public WorldProjectionAdoptionRequest {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(replacement, "replacement");
    Objects.requireNonNull(requestedAt, "requestedAt");
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion must be non-negative");
    }
  }
}
