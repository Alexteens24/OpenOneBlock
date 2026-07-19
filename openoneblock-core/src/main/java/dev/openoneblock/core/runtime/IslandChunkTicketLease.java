package dev.openoneblock.core.runtime;

import java.util.concurrent.CompletionStage;

/** Idempotent controller-owned lease for one complete acquired island chunk set. */
public interface IslandChunkTicketLease {
  /**
   * Returns the number of acquired plugin chunk tickets.
   *
   * @return ticket count
   */
  int chunkCount();

  /**
   * Releases every acquired ticket exactly once.
   *
   * @return verified release completion
   */
  CompletionStage<Void> release();
}
