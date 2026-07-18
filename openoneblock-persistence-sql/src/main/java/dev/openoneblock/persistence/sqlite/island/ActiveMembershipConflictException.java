package dev.openoneblock.persistence.sqlite.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.PlayerId;
import java.io.Serial;
import java.util.Objects;

/** Raised when a player already belongs to a non-archived island. */
public final class ActiveMembershipConflictException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  private final transient PlayerId playerId;
  private final transient IslandId existingIslandId;

  /**
   * Creates a conflict with its authoritative existing assignment.
   *
   * @param playerId player whose creation was refused
   * @param existingIslandId authoritative existing island
   */
  public ActiveMembershipConflictException(PlayerId playerId, IslandId existingIslandId) {
    super("Player " + playerId + " already belongs to island " + existingIslandId);
    this.playerId = Objects.requireNonNull(playerId, "playerId");
    this.existingIslandId = Objects.requireNonNull(existingIslandId, "existingIslandId");
  }

  /**
   * Returns the player whose create was refused.
   *
   * @return conflicted player identity
   */
  public PlayerId playerId() {
    return playerId;
  }

  /**
   * Returns the authoritative existing island.
   *
   * @return existing island identity
   */
  public IslandId existingIslandId() {
    return existingIslandId;
  }
}
