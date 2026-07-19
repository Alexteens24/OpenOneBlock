package dev.openoneblock.core.locator;

import dev.openoneblock.api.id.DimensionId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import java.util.Objects;

/**
 * Immutable mapping from one platform world to its logical shard and dimension.
 *
 * @param worldId platform world UUID
 * @param shardGroupId logical shard group shared across dimension projections
 * @param dimensionId configured dimension identity
 */
public record WorldProjection(WorldId worldId, ShardGroupId shardGroupId, DimensionId dimensionId) {
  /** Validates projection metadata. */
  public WorldProjection {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(shardGroupId, "shardGroupId");
    Objects.requireNonNull(dimensionId, "dimensionId");
  }
}
