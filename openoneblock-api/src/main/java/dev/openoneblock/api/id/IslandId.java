package dev.openoneblock.api.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity of an island, independent of its owner and slot.
 *
 * @param value underlying persistent UUID
 */
public record IslandId(UUID value) implements Comparable<IslandId> {
  /** Validates and creates an island identifier. */
  public IslandId {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Returns a new UUID version 4 island identifier.
   *
   * @return a new island identifier
   */
  public static IslandId generate() {
    return new IslandId(UUID.randomUUID());
  }

  /**
   * Parses an imported or persisted UUID.
   *
   * @param value canonical UUID text
   * @return the parsed identifier
   */
  public static IslandId parse(String value) {
    Objects.requireNonNull(value, "value");
    return new IslandId(UUID.fromString(value));
  }

  @Override
  public int compareTo(IslandId other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
