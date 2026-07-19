package dev.openoneblock.core.platform;

import java.util.concurrent.CompletionStage;

/** Opaque platform handle that follows an entity across regions and dimensions. */
@FunctionalInterface
public interface EntityTaskHandle {
  /**
   * Dispatches work to the region currently owning the live entity.
   *
   * @param work entity-owned operation
   * @param <T> result type
   * @return execution completion or failure if the entity retires
   */
  <T> CompletionStage<T> schedule(ScheduledWork<T> work);
}
