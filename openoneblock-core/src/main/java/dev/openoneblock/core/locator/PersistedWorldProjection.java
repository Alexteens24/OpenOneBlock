package dev.openoneblock.core.locator;

import java.time.Instant;
import java.util.Objects;

/**
 * Authoritative persisted world projection row.
 *
 * @param definition configured and observed identity
 * @param state durable verification state
 * @param version optimistic aggregate version
 * @param createdAt original registration time
 * @param updatedAt latest verified or adopted mutation time
 */
public record PersistedWorldProjection(
    WorldProjectionDefinition definition,
    WorldProjectionState state,
    long version,
    Instant createdAt,
    Instant updatedAt) {
  /** Validates authoritative projection metadata. */
  public PersistedWorldProjection {
    Objects.requireNonNull(definition, "definition");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (version < 0) {
      throw new IllegalArgumentException("version must be non-negative");
    }
  }
}
