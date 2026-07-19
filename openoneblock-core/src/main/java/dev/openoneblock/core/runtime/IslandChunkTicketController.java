package dev.openoneblock.core.runtime;

import java.util.concurrent.CompletionStage;

/** Platform port which acquires complete island ticket sets or rolls back partial acquisition. */
@FunctionalInterface
public interface IslandChunkTicketController {
  /**
   * Loads, verifies, and tickets every requested chunk.
   *
   * <p>Failure must release every ticket acquired by this request before completing exceptionally.
   *
   * @param request immutable complete chunk request
   * @return complete lease after every chunk is verified and ticketed
   */
  CompletionStage<IslandChunkTicketLease> acquire(IslandChunkTicketRequest request);
}
