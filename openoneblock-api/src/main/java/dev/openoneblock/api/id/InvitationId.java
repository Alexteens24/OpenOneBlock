package dev.openoneblock.api.id;

import java.util.Objects;
import java.util.UUID;

/** Stable identity for one island invitation. */
public record InvitationId(UUID value) {
  /** Validates the identifier. */
  public InvitationId {
    Objects.requireNonNull(value, "value");
  }

  /** Creates a typed identifier. */
  public static InvitationId of(UUID value) {
    return new InvitationId(value);
  }

  /** Parses persisted canonical UUID text. */
  public static InvitationId parse(String value) {
    return new InvitationId(UUID.fromString(Objects.requireNonNull(value, "value")));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
