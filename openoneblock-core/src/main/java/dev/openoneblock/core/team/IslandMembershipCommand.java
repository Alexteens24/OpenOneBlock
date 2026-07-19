package dev.openoneblock.core.team;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Exact-version direct membership/access mutation. */
public record IslandMembershipCommand(
    IslandId islandId,
    OperationId operationId,
    MembershipCommandKind kind,
    PlayerId actorPlayerId,
    PlayerId subjectPlayerId,
    Optional<NamespacedId> targetRoleId,
    long expectedIslandVersion,
    Instant requestedAt) {
  /** Validates command shape. */
  public IslandMembershipCommand {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(actorPlayerId, "actorPlayerId");
    Objects.requireNonNull(subjectPlayerId, "subjectPlayerId");
    targetRoleId = Objects.requireNonNull(targetRoleId, "targetRoleId");
    Objects.requireNonNull(requestedAt, "requestedAt");
    if (expectedIslandVersion < 0) {
      throw new IllegalArgumentException("expectedIslandVersion must be non-negative");
    }
    boolean roleRequired = kind == MembershipCommandKind.PROMOTE || kind == MembershipCommandKind.DEMOTE;
    if (roleRequired != targetRoleId.isPresent()) {
      throw new IllegalArgumentException("target role is required only for promote/demote");
    }
    if (kind == MembershipCommandKind.LEAVE && !actorPlayerId.equals(subjectPlayerId)) {
      throw new IllegalArgumentException("leave command subject must be the actor");
    }
  }
}
