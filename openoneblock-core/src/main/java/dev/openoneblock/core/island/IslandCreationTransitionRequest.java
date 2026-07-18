package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import java.time.Instant;
import java.util.Objects;

/**
 * Optimistic, idempotent request to advance one durable island creation operation.
 *
 * @param islandId island being created
 * @param operationId durable creation operation
 * @param stage requested state-machine stage
 * @param expectedIslandVersion aggregate version observed by the caller
 * @param expectedSlotVersion primary slot version observed by the caller
 * @param requestedAt caller-supplied instant from an injected clock
 */
public record IslandCreationTransitionRequest(
    IslandId islandId,
    OperationId operationId,
    IslandCreationStage stage,
    long expectedIslandVersion,
    long expectedSlotVersion,
    Instant requestedAt) {
  /** Validates transition metadata before persistence work begins. */
  public IslandCreationTransitionRequest {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(requestedAt, "requestedAt");
    if (expectedIslandVersion < 0 || expectedSlotVersion < 0) {
      throw new IllegalArgumentException("expected versions must be non-negative");
    }
  }
}
