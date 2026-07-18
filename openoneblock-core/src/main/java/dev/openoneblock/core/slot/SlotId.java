package dev.openoneblock.core.slot;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable internal identity of a reusable logical slot.
 *
 * @param value underlying persistent UUID
 */
public record SlotId(UUID value) implements Comparable<SlotId> {
  /** Validates and creates a slot identifier. */
  public SlotId {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Returns a new UUID version 4 slot identifier.
   *
   * @return a new slot identifier
   */
  public static SlotId generate() {
    return new SlotId(UUID.randomUUID());
  }

  /**
   * Returns an identifier parsed from imported or persisted UUID text.
   *
   * @param value canonical UUID text
   * @return parsed slot identifier
   */
  public static SlotId parse(String value) {
    Objects.requireNonNull(value, "value");
    return new SlotId(UUID.fromString(value));
  }

  @Override
  public int compareTo(SlotId other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
