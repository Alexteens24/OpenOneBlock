package dev.openoneblock.core.world;

import dev.openoneblock.api.id.IslandId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable durable evidence for one fingerprinted world effect.
 *
 * @param key stable effect key
 * @param islandId owning island
 * @param kind effect category
 * @param safety recovery classification
 * @param descriptor canonical plan descriptor
 * @param fingerprint SHA-256 plan identity
 * @param state durable evidence state
 * @param dispatchAttempts dispatch count
 * @param diagnostic terminal diagnostic when present
 * @param createdAt receipt creation
 * @param dispatchedAt first dispatch time
 * @param completedAt terminal evidence time
 * @param updatedAt last durable update
 */
public record WorldEffectReceipt(
    WorldEffectKey key,
    IslandId islandId,
    WorldEffectPlan.Kind kind,
    WorldEffectPlan.Safety safety,
    String descriptor,
    String fingerprint,
    WorldEffectState state,
    int dispatchAttempts,
    Optional<String> diagnostic,
    Instant createdAt,
    Optional<Instant> dispatchedAt,
    Optional<Instant> completedAt,
    Instant updatedAt) {
  /** Validates timestamps and state evidence. */
  public WorldEffectReceipt {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(safety, "safety");
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(fingerprint, "fingerprint");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(diagnostic, "diagnostic");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(dispatchedAt, "dispatchedAt");
    Objects.requireNonNull(completedAt, "completedAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (descriptor.isBlank() || !fingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("effect descriptor or fingerprint is invalid");
    }
    if (dispatchAttempts < 0 || updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("effect attempts or timestamps are invalid");
    }
    if (state == WorldEffectState.NOT_STARTED
        && (dispatchAttempts != 0 || dispatchedAt.isPresent() || completedAt.isPresent())) {
      throw new IllegalArgumentException("not-started receipt cannot contain dispatch evidence");
    }
    if (state != WorldEffectState.NOT_STARTED
        && (dispatchAttempts == 0 || dispatchedAt.isEmpty())) {
      throw new IllegalArgumentException("dispatched receipt requires dispatch evidence");
    }
    if (state.terminal() != completedAt.isPresent()) {
      throw new IllegalArgumentException("terminal receipt completion timestamp is inconsistent");
    }
  }
}
