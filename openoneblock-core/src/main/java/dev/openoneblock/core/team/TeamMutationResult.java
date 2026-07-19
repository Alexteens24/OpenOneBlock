package dev.openoneblock.core.team;

import dev.openoneblock.api.event.IslandMembershipChangedEvent;
import java.util.Objects;

/** Result returned after one team transaction and projection publication complete. */
public record TeamMutationResult(IslandMembershipChangedEvent event, boolean replayed) {
  /** Validates the result. */
  public TeamMutationResult {
    Objects.requireNonNull(event, "event");
  }
}
