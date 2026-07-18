package dev.openoneblock.api.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity used to deduplicate a recoverable logical operation.
 *
 * @param value underlying persistent UUID
 */
public record OperationId(UUID value) implements Comparable<OperationId> {
  /** Validates and creates an operation identifier. */
  public OperationId {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Returns a new UUID version 4 operation identifier.
   *
   * @return a new operation identifier
   */
  public static OperationId generate() {
    return new OperationId(UUID.randomUUID());
  }

  /**
   * Parses an imported or persisted UUID.
   *
   * @param value canonical UUID text
   * @return the parsed identifier
   */
  public static OperationId parse(String value) {
    Objects.requireNonNull(value, "value");
    return new OperationId(UUID.fromString(value));
  }

  @Override
  public int compareTo(OperationId other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
