package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import java.util.Objects;

/**
 * Completed non-mutating island home teleport.
 *
 * @param islandId destination island
 * @param operationId trace identity
 * @param islandVersion version validated before teleport
 */
public record IslandHomeResult(IslandId islandId, OperationId operationId, long islandVersion) {
  /** Validates a complete result. */
  public IslandHomeResult {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(operationId, "operationId");
    if (islandVersion < 0) {
      throw new IllegalArgumentException("islandVersion must be non-negative");
    }
  }
}
