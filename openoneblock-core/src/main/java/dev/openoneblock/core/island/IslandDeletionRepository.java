package dev.openoneblock.core.island;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Authoritative durable deletion transaction and recovery boundary. */
public interface IslandDeletionRepository {
  /**
   * Begins or exactly replays a deletion intent.
   *
   * @param request durable deletion intent
   * @return cleaning or terminal replay progress
   */
  CompletionStage<IslandDeletionProgress> beginDeletion(IslandDeletionRequest request);

  /**
   * Begins or exactly replays an administrative retry of failed deletion cleanup.
   *
   * @param request durable cleanup retry intent
   * @return cleaning or terminal replay progress
   */
  CompletionStage<IslandDeletionProgress> beginCleanupRetry(IslandCleanupRetryRequest request);

  /**
   * Commits verified release or mandatory quarantine from explicit cleanup evidence.
   *
   * @param completion cleanup evidence and optimistic versions
   * @return terminal durable progress
   */
  CompletionStage<IslandDeletionProgress> completeDeletion(IslandDeletionCompletion completion);

  /**
   * Returns every durable deletion still awaiting cleanup recovery.
   *
   * @return deterministic immutable pending requests
   */
  CompletionStage<List<IslandDeletionRequest>> findPendingDeletions();

  /**
   * Returns durable cleanup retries still awaiting world verification.
   *
   * @return deterministic immutable pending requests
   */
  CompletionStage<List<IslandCleanupRetryRequest>> findPendingCleanupRetries();
}
