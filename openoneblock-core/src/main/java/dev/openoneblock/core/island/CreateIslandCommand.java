package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import java.util.Objects;

/**
 * Caller-owned stable identities and selected content for one island creation intent.
 *
 * @param islandId new or replayed island identity
 * @param operationId idempotency identity
 * @param ownerId initial owner
 * @param shardGroupId selected shard
 * @param primaryWorldId verified primary dimension world
 * @param profileId selected Magic Block profile
 * @param phaseId selected initial phase
 */
public record CreateIslandCommand(
    IslandId islandId,
    OperationId operationId,
    PlayerId ownerId,
    ShardGroupId shardGroupId,
    WorldId primaryWorldId,
    NamespacedId profileId,
    NamespacedId phaseId) {
  /** Validates complete caller intent. */
  public CreateIslandCommand {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(ownerId, "ownerId");
    Objects.requireNonNull(shardGroupId, "shardGroupId");
    Objects.requireNonNull(primaryWorldId, "primaryWorldId");
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(phaseId, "phaseId");
  }
}
