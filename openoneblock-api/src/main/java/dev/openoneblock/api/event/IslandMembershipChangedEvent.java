package dev.openoneblock.api.event;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Immutable event emitted only after a membership transaction commits. */
public record IslandMembershipChangedEvent(
    IslandId islandId,
    OperationId operationId,
    MembershipMutationKind kind,
    PlayerId actorPlayerId,
    PlayerId subjectPlayerId,
    Optional<NamespacedId> roleId,
    long committedIslandVersion,
    Instant committedAt) {
  /** Validates event data. */
  public IslandMembershipChangedEvent {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(actorPlayerId, "actorPlayerId");
    Objects.requireNonNull(subjectPlayerId, "subjectPlayerId");
    roleId = Objects.requireNonNull(roleId, "roleId");
    Objects.requireNonNull(committedAt, "committedAt");
    if (committedIslandVersion < 0) {
      throw new IllegalArgumentException("committedIslandVersion must be non-negative");
    }
  }
}
