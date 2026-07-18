package dev.openoneblock.core.operation;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable metadata captured before an island mutation enters its execution lane.
 *
 * @param islandId target island
 * @param operationId idempotency and trace identifier
 * @param expectedVersion aggregate version observed by the caller
 * @param submittedAt caller-supplied UTC instant from an injected clock
 * @param operationClass gameplay-admission behavior of the operation
 */
public record IslandOperationRequest(
    IslandId islandId,
    OperationId operationId,
    long expectedVersion,
    Instant submittedAt,
    IslandOperationClass operationClass) {
  /** Validates immutable operation metadata. */
  public IslandOperationRequest {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(submittedAt, "submittedAt");
    Objects.requireNonNull(operationClass, "operationClass");
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion must be non-negative");
    }
  }
}
