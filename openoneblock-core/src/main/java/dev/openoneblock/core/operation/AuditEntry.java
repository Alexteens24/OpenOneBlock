package dev.openoneblock.core.operation;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Immutable normalized event appended to the operational audit log. */
public record AuditEntry(
    Optional<OperationId> operationId,
    Optional<IslandId> islandId,
    OptionalLong magicBlockSequence,
    Optional<NamespacedId> ruleId,
    Optional<PlayerId> playerId,
    String eventType,
    Instant occurredAt,
    AuditOutcome outcome,
    Optional<String> detail) {
  /** Validates the bounded structured audit event. */
  public AuditEntry {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(magicBlockSequence, "magicBlockSequence");
    if (magicBlockSequence.isPresent() && magicBlockSequence.orElseThrow() < 0) {
      throw new IllegalArgumentException("magicBlockSequence must be non-negative");
    }
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(playerId, "playerId");
    eventType = requireBoundedText(eventType, "eventType", 128);
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(detail, "detail");
    detail = detail.map(value -> requireBoundedText(value, "detail", 2_048));
  }

  private static String requireBoundedText(String value, String name, int maximumLength) {
    Objects.requireNonNull(value, name);
    if (value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(
          name + " must contain between 1 and " + maximumLength + " characters");
    }
    return value;
  }
}
