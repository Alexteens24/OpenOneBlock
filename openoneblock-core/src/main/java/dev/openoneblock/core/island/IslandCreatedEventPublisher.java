package dev.openoneblock.core.island;

import dev.openoneblock.api.event.IslandCreatedEvent;
import java.util.concurrent.CompletionStage;

/** Platform event publication port invoked only after island activation commits. */
@FunctionalInterface
public interface IslandCreatedEventPublisher {
  /**
   * Publishes one immutable creation event through the platform's supported event lifecycle.
   *
   * @param event immutable post-commit event
   * @return publication completion
   */
  CompletionStage<Void> publish(IslandCreatedEvent event);
}
