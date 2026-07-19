package dev.openoneblock.core.team;

import dev.openoneblock.api.event.IslandMembershipChangedEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Post-commit membership event boundary. */
@FunctionalInterface
public interface IslandMembershipEventPublisher {
  /** Delivers one immutable committed event. */
  CompletionStage<Void> publish(IslandMembershipChangedEvent event);

  /** Publisher used when no external event system is installed. */
  IslandMembershipEventPublisher NO_OP = ignored -> CompletableFuture.completedFuture(null);
}
