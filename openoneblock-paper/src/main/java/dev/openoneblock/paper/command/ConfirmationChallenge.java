package dev.openoneblock.paper.command;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.PlayerId;
import java.time.Instant;
import java.util.Objects;

/**
 * One-time destructive command confirmation bound to exact authority and aggregate version.
 *
 * @param token unguessable URL-safe token
 * @param action destructive action
 * @param playerId authorized player
 * @param islandId exact target island
 * @param islandVersion exact expected aggregate version
 * @param expiresAt exclusive expiry instant
 */
public record ConfirmationChallenge(
    String token,
    ConfirmationAction action,
    PlayerId playerId,
    IslandId islandId,
    long islandVersion,
    Instant expiresAt) {
  /** Validates complete challenge state. */
  public ConfirmationChallenge {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(expiresAt, "expiresAt");
    if (token.isBlank()) {
      throw new IllegalArgumentException("confirmation token must not be blank");
    }
    if (islandVersion < 0) {
      throw new IllegalArgumentException("islandVersion must be non-negative");
    }
  }
}
