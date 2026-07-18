package dev.openoneblock.core.slot;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.ShardGroupId;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable request to reserve one logical slot for an island.
 *
 * @param islandId island receiving the slot
 * @param shardGroupId target shard group
 * @param operationId idempotency identifier for the allocation
 * @param requestedAt caller-supplied UTC instant from an injected clock
 */
public record SlotAllocationRequest(
    IslandId islandId, ShardGroupId shardGroupId, OperationId operationId, Instant requestedAt) {
  /** Validates allocation request metadata. */
  public SlotAllocationRequest {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(shardGroupId, "shardGroupId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(requestedAt, "requestedAt");
  }
}
