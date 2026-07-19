package dev.openoneblock.core.island;

import java.util.concurrent.CompletionStage;

/** Read-only external evidence port used before a broken island can enter maintenance lock. */
@FunctionalInterface
public interface IslandRepairVerifier {
  /**
   * Verifies runtime locator and configured world projections without mutating island state.
   *
   * @param request durable repair intent
   * @param island exact durable snapshot placed into verification
   * @return immutable evidence
   */
  CompletionStage<IslandRepairEvidence> verify(
      IslandRepairRequest request, IslandAggregateSnapshot island);
}
