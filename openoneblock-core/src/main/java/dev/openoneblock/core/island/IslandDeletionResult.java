package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import java.util.Objects;

/**
 * Successful verified island archival result.
 *
 * @param islandId archived island
 * @param operationId deletion operation
 * @param replay whether the result was already durable
 */
public record IslandDeletionResult(IslandId islandId, OperationId operationId, boolean replay) {
  /** Validates complete result identity. */
  public IslandDeletionResult {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(operationId, "operationId");
  }
}
