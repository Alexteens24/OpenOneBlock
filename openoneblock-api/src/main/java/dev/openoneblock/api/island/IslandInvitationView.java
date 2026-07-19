package dev.openoneblock.api.island;

import dev.openoneblock.api.id.InvitationId;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.PlayerId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Immutable public invitation view. */
public record IslandInvitationView(
    InvitationId invitationId,
    IslandId islandId,
    PlayerId invitedPlayerId,
    PlayerId invitedByPlayerId,
    NamespacedId proposedRoleId,
    InvitationState state,
    Instant expiresAt,
    long version,
    Instant createdAt,
    Instant updatedAt,
    Optional<Instant> respondedAt) {
  /** Validates persisted invitation semantics. */
  public IslandInvitationView {
    Objects.requireNonNull(invitationId, "invitationId");
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(invitedPlayerId, "invitedPlayerId");
    Objects.requireNonNull(invitedByPlayerId, "invitedByPlayerId");
    Objects.requireNonNull(proposedRoleId, "proposedRoleId");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    respondedAt = Objects.requireNonNull(respondedAt, "respondedAt");
    if (version < 0 || expiresAt.isBefore(createdAt) || updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("invalid invitation version or timestamp ordering");
    }
    if ((state == InvitationState.PENDING) == respondedAt.isPresent()) {
      throw new IllegalArgumentException("only terminal invitations have a response timestamp");
    }
  }
}
