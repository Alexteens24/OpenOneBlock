package dev.openoneblock.core.team;

import dev.openoneblock.api.id.InvitationId;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import java.time.Instant;
import java.util.Objects;

/** Exact-version invitation acceptance or decline command. */
public record IslandInvitationResponseCommand(
    IslandId islandId,
    OperationId operationId,
    InvitationId invitationId,
    PlayerId invitedPlayerId,
    long expectedIslandVersion,
    int maximumTeamSize,
    boolean accept,
    Instant respondedAt) {
  /** Validates command bounds. */
  public IslandInvitationResponseCommand {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(invitationId, "invitationId");
    Objects.requireNonNull(invitedPlayerId, "invitedPlayerId");
    Objects.requireNonNull(respondedAt, "respondedAt");
    if (expectedIslandVersion < 0 || maximumTeamSize < 1) {
      throw new IllegalArgumentException("invalid version or team size");
    }
  }
}
