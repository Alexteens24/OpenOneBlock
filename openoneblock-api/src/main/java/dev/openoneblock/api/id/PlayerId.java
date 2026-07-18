package dev.openoneblock.api.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable Minecraft player identity used at OpenOneBlock module boundaries.
 *
 * @param value underlying player UUID
 */
public record PlayerId(UUID value) implements Comparable<PlayerId> {
  /** Validates and creates a player identifier. */
  public PlayerId {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Creates an identifier from a Bukkit or proxy UUID.
   *
   * @param value player UUID
   * @return typed player identifier
   */
  public static PlayerId of(UUID value) {
    return new PlayerId(value);
  }

  /**
   * Parses a persisted UUID.
   *
   * @param value canonical UUID text
   * @return parsed player identifier
   */
  public static PlayerId parse(String value) {
    Objects.requireNonNull(value, "value");
    return new PlayerId(UUID.fromString(value));
  }

  @Override
  public int compareTo(PlayerId other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
