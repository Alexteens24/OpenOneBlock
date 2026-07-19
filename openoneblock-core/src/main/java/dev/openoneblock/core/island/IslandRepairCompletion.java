package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import java.util.Objects;

/**
 * Exact-state repair evidence submitted for authoritative transactional validation.
 *
 * @param islandId repaired island
 * @param operationId repair operation
 * @param expectedIslandVersion version after repair admission
 * @param expectedSlotVersion quarantined slot version observed by the verifier
 * @param evidence external locator and projection evidence
 */
public record IslandRepairCompletion(
    IslandId islandId,
    OperationId operationId,
    long expectedIslandVersion,
    long expectedSlotVersion,
    IslandRepairEvidence evidence) {
  /** Validates completion evidence. */
  public IslandRepairCompletion {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(evidence, "evidence");
    if (expectedIslandVersion < 0 || expectedSlotVersion < 0) {
      throw new IllegalArgumentException("expected versions must be non-negative");
    }
  }
}
