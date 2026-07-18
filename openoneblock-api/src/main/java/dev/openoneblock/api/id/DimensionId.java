package dev.openoneblock.api.id;

import java.util.Objects;

/**
 * Identifier of a configured world dimension projection.
 *
 * @param value underlying namespaced identifier
 */
public record DimensionId(NamespacedId value) implements Comparable<DimensionId> {
  /** Validates and creates a dimension identifier. */
  public DimensionId {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Creates an identifier from separate components.
   *
   * @param namespace provider or configuration namespace
   * @param value dimension value
   * @return the validated identifier
   */
  public static DimensionId of(String namespace, String value) {
    return new DimensionId(NamespacedId.of(namespace, value));
  }

  /**
   * Parses a strict namespaced identifier.
   *
   * @param value canonical namespaced text
   * @return the parsed identifier
   */
  public static DimensionId parse(String value) {
    return new DimensionId(NamespacedId.parse(value));
  }

  @Override
  public int compareTo(DimensionId other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
