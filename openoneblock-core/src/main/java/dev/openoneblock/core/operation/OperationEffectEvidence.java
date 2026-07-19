package dev.openoneblock.core.operation;

import java.time.Instant;
import java.util.Objects;

/** Immutable summary of the most recently updated durable world-effect receipt. */
public record OperationEffectEvidence(
    int effectIndex, String effectKind, String state, int dispatchAttempts, Instant updatedAt) {
  /** Validates the persisted effect summary. */
  public OperationEffectEvidence {
    if (effectIndex < 0) {
      throw new IllegalArgumentException("effectIndex must be non-negative");
    }
    effectKind = requireText(effectKind, "effectKind");
    state = requireText(state, "state");
    if (dispatchAttempts < 0) {
      throw new IllegalArgumentException("dispatchAttempts must be non-negative");
    }
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
