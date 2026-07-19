package dev.openoneblock.api.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.PlayerId;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable public view of one persisted island membership.
 *
 * @param islandId island identity
 * @param playerId member identity
 * @param roleId compiled role identity
 * @param owner whether this is the authoritative owner membership
 * @param joinedAt original membership creation instant
 * @param updatedAt last membership mutation instant
 */
public record MemberView(
    IslandId islandId,
    PlayerId playerId,
    NamespacedId roleId,
    boolean owner,
    Instant joinedAt,
    Instant updatedAt) {
  /** Validates immutable membership data. */
  public MemberView {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(roleId, "roleId");
    Objects.requireNonNull(joinedAt, "joinedAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(joinedAt)) {
      throw new IllegalArgumentException("updatedAt must not precede joinedAt");
    }
    if (owner && !roleId.equals(NamespacedId.of("openoneblock", "owner"))) {
      throw new IllegalArgumentException("owner membership must use openoneblock:owner");
    }
  }
}
