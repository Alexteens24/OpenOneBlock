package dev.openoneblock.api.id;

import java.util.Objects;

/**
 * Identifier of a group of shared-world dimension projections.
 *
 * @param value underlying namespaced identifier
 */
public record ShardGroupId(NamespacedId value) implements Comparable<ShardGroupId> {
  /** Validates and creates a shard-group identifier. */
  public ShardGroupId {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Creates an identifier from separate components.
   *
   * @param namespace provider or configuration namespace
   * @param value shard-group value
   * @return the validated identifier
   */
  public static ShardGroupId of(String namespace, String value) {
    return new ShardGroupId(NamespacedId.of(namespace, value));
  }

  /**
   * Parses a strict namespaced identifier.
   *
   * @param value canonical namespaced text
   * @return the parsed identifier
   */
  public static ShardGroupId parse(String value) {
    return new ShardGroupId(NamespacedId.parse(value));
  }

  @Override
  public int compareTo(ShardGroupId other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
