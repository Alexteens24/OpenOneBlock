package dev.openoneblock.core.operation;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.core.slot.SlotId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Immutable non-loading administrative projection of one durable operation. */
public record IslandOperationSnapshot(
    OperationId operationId,
    IslandId islandId,
    String kind,
    String phase,
    Optional<SlotId> slotId,
    OptionalLong expectedIslandVersion,
    OptionalLong expectedSlotVersion,
    Optional<String> requestFingerprint,
    Optional<String> outcomeState,
    Optional<String> outcomePayload,
    OperationRetryClassification retryClassification,
    Optional<String> errorCode,
    Optional<String> diagnosticContext,
    Optional<OperationEffectEvidence> lastEffect,
    Instant createdAt,
    Instant updatedAt,
    Optional<Instant> completedAt) {
  /** Validates the immutable operation projection. */
  public IslandOperationSnapshot {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(islandId, "islandId");
    kind = requireText(kind, "kind");
    phase = requireText(phase, "phase");
    Objects.requireNonNull(slotId, "slotId");
    Objects.requireNonNull(expectedIslandVersion, "expectedIslandVersion");
    Objects.requireNonNull(expectedSlotVersion, "expectedSlotVersion");
    requireNonNegative(expectedIslandVersion, "expectedIslandVersion");
    requireNonNegative(expectedSlotVersion, "expectedSlotVersion");
    Objects.requireNonNull(requestFingerprint, "requestFingerprint");
    Objects.requireNonNull(outcomeState, "outcomeState");
    Objects.requireNonNull(outcomePayload, "outcomePayload");
    Objects.requireNonNull(retryClassification, "retryClassification");
    Objects.requireNonNull(errorCode, "errorCode");
    Objects.requireNonNull(diagnosticContext, "diagnosticContext");
    Objects.requireNonNull(lastEffect, "lastEffect");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    Objects.requireNonNull(completedAt, "completedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
    if (completedAt.isPresent() && completedAt.orElseThrow().isBefore(createdAt)) {
      throw new IllegalArgumentException("completedAt must not precede createdAt");
    }
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static void requireNonNegative(OptionalLong value, String name) {
    if (value.isPresent() && value.orElseThrow() < 0) {
      throw new IllegalArgumentException(name + " must be non-negative");
    }
  }
}
