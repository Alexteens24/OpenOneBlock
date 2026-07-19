package dev.openoneblock.api.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable UUID of a configured Minecraft world projection.
 *
 * @param value underlying world UUID
 */
public record WorldId(UUID value) implements Comparable<WorldId> {
  /** Validates and creates a world identifier. */
  public WorldId {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Creates a typed identifier from a platform world UUID.
   *
   * @param value world UUID
   * @return typed world identifier
   */
  public static WorldId of(UUID value) {
    return new WorldId(value);
  }

  /**
   * Parses a persisted world UUID.
   *
   * @param value canonical UUID text
   * @return parsed world identifier
   */
  public static WorldId parse(String value) {
    Objects.requireNonNull(value, "value");
    return new WorldId(UUID.fromString(value));
  }

  @Override
  public int compareTo(WorldId other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
