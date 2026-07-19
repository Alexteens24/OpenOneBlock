package dev.openoneblock.core.runtime;

import dev.openoneblock.api.id.IslandId;
import java.util.concurrent.CompletionStage;

/** Idempotent reference to one island activity reason. */
public interface IslandActivityLease {
  /**
   * Returns the retained island.
   *
   * @return island identity
   */
  IslandId islandId();

  /**
   * Returns the retained reason.
   *
   * @return activity reason
   */
  IslandActivityReason reason();

  /**
   * Releases this reference and unloads tickets only when it is the final reference.
   *
   * @return release completion
   */
  CompletionStage<Void> release();
}
