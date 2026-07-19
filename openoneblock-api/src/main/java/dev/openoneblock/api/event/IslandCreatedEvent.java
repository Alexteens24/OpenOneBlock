package dev.openoneblock.api.event;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.WorldId;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable post-commit notification that a complete island became active.
 *
 * @param islandId activated island
 * @param ownerId initial owner
 * @param operationId idempotent creation operation
 * @param primaryWorldId activated primary world projection
 * @param occurredAt committed delivery time
 * @param recovered whether activation completed during startup recovery
 */
public record IslandCreatedEvent(
    IslandId islandId,
    PlayerId ownerId,
    OperationId operationId,
    WorldId primaryWorldId,
    Instant occurredAt,
    boolean recovered) {
  /** Validates complete event identity. */
  public IslandCreatedEvent {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(ownerId, "ownerId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(primaryWorldId, "primaryWorldId");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
