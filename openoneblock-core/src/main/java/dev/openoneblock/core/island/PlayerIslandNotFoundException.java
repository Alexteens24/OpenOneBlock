package dev.openoneblock.core.island;

import dev.openoneblock.api.id.PlayerId;
import java.io.Serial;
import java.util.Objects;

/** Raised when a player has no active island membership for a requested command. */
public final class PlayerIslandNotFoundException extends IllegalStateException {
  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the missing-membership result.
   *
   * @param playerId queried player
   */
  public PlayerIslandNotFoundException(PlayerId playerId) {
    super("Player has no active island: " + Objects.requireNonNull(playerId, "playerId"));
  }
}
