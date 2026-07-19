package dev.openoneblock.core.island;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Durable transaction boundary for crash-safe island reset. */
public interface IslandResetRepository {
  /**
   * Begins or replays an owner-authorized reset.
   *
   * @param request complete durable reset intent
   * @return current reset progress
   */
  CompletionStage<IslandResetProgress> beginReset(IslandResetRequest request);

  /**
   * Applies cleanup evidence and advances to preparation or terminal quarantine.
   *
   * @param completion optimistic verified cleanup evidence
   * @return advanced reset progress
   */
  CompletionStage<IslandResetProgress> completeCleanup(IslandResetCleanupCompletion completion);

  /**
   * Moves failed preparation into the durable failure-cleanup stage.
   *
   * @param failure optimistic preparation failure evidence
   * @return failure-cleanup progress
   */
  CompletionStage<IslandResetProgress> beginPreparationFailure(
      IslandResetPreparationFailure failure);

  /**
   * Replaces resettable projections and atomically reactivates the island and slot.
   *
   * @param activation verified activation projection
   * @return successful active progress
   */
  CompletionStage<IslandResetProgress> activateReset(IslandResetActivation activation);

  /**
   * Returns complete replay inputs for all non-terminal reset operations.
   *
   * @return immutable pending reset requests
   */
  CompletionStage<List<IslandResetRequest>> findPendingResets();
}
