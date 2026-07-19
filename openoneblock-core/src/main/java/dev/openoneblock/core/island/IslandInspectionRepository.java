package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Read-only persistence boundary for admin island inspection. */
public interface IslandInspectionRepository {
  /**
   * Finds one island without loading its world or runtime.
   *
   * @param islandId island identity
   * @return optional immutable durable diagnostics
   */
  CompletionStage<Optional<IslandInspectionSnapshot>> findInspection(IslandId islandId);
}
