package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable intent to atomically establish an island, owner membership, and reserved slot.
 *
 * @param islandId new stable island identity
 * @param ownerId initial owner identity
 * @param shardGroupId target shared-world shard group
 * @param operationId idempotency and recovery identity
 * @param initialBorderSize initial playable border size
 * @param maximumBorderSize maximum reserved border size
 * @param requestedAt caller-supplied instant from an injected clock
 */
public record IslandCreationRequest(
    IslandId islandId,
    PlayerId ownerId,
    ShardGroupId shardGroupId,
    OperationId operationId,
    int initialBorderSize,
    int maximumBorderSize,
    Instant requestedAt) {
  /** Validates creation metadata before persistence work begins. */
  public IslandCreationRequest {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(ownerId, "ownerId");
    Objects.requireNonNull(shardGroupId, "shardGroupId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(requestedAt, "requestedAt");
    if (initialBorderSize <= 0 || maximumBorderSize < initialBorderSize) {
      throw new IllegalArgumentException("invalid island border sizes");
    }
  }
}
