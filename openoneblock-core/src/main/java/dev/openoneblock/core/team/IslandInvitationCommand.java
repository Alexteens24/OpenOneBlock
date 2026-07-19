package dev.openoneblock.core.team;

import dev.openoneblock.api.id.InvitationId;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import java.time.Instant;
import java.util.Objects;

/** Exact-version command to invite one player into an island membership. */
public record IslandInvitationCommand(
    IslandId islandId,
    OperationId operationId,
    InvitationId invitationId,
    PlayerId actorPlayerId,
    PlayerId invitedPlayerId,
    NamespacedId proposedRoleId,
    long expectedIslandVersion,
    int maximumTeamSize,
    Instant requestedAt,
    Instant expiresAt) {
  /** Validates command bounds. */
  public IslandInvitationCommand {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(invitationId, "invitationId");
    Objects.requireNonNull(actorPlayerId, "actorPlayerId");
    Objects.requireNonNull(invitedPlayerId, "invitedPlayerId");
    Objects.requireNonNull(proposedRoleId, "proposedRoleId");
    Objects.requireNonNull(requestedAt, "requestedAt");
    Objects.requireNonNull(expiresAt, "expiresAt");
    if (expectedIslandVersion < 0 || maximumTeamSize < 1 || !expiresAt.isAfter(requestedAt)) {
      throw new IllegalArgumentException("invalid version, team size, or invitation expiry");
    }
  }
}
