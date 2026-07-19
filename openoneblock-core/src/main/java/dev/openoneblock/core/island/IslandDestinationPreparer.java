package dev.openoneblock.core.island;

import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.core.world.WorldSpawnPosition;
import java.util.concurrent.CompletionStage;

/** Ownership-aware boundary that prepares the exact destination chunk before teleport. */
@FunctionalInterface
public interface IslandDestinationPreparer {
  /**
   * Loads and verifies the chunk containing a destination.
   *
   * @param destination verified persistent destination
   * @param operationId trace identity
   * @return preparation completion
   */
  CompletionStage<Void> prepare(WorldSpawnPosition destination, OperationId operationId);
}
