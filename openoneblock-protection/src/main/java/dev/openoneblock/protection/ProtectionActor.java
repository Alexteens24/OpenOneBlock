package dev.openoneblock.protection;

import dev.openoneblock.api.id.PlayerId;
import java.util.Objects;
import java.util.Optional;

/**
 * Actor identity; an empty player denotes an environmental or mechanical cause.
 *
 * @param playerId player identity, or empty for environment mechanics
 * @param administrator explicit final-policy bypass flag
 */
public record ProtectionActor(Optional<PlayerId> playerId, boolean administrator) {
  /** Validates the optional player identity. */
  public ProtectionActor {
    Objects.requireNonNull(playerId, "playerId");
    if (administrator && playerId.isEmpty()) {
      throw new IllegalArgumentException("environment actors cannot have administrator bypass");
    }
  }

  /**
   * Creates a normal player actor.
   *
   * @param playerId player identity
   * @return non-administrator player actor
   */
  public static ProtectionActor player(PlayerId playerId) {
    return new ProtectionActor(Optional.of(playerId), false);
  }

  /**
   * Creates an environmental actor.
   *
   * @return environment actor without bypass
   */
  public static ProtectionActor environment() {
    return new ProtectionActor(Optional.empty(), false);
  }
}
