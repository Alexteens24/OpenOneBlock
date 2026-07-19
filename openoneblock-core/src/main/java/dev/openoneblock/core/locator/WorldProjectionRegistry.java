package dev.openoneblock.core.locator;

import dev.openoneblock.api.id.DimensionId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable world UUID projection registry built before gameplay listeners are enabled. */
public final class WorldProjectionRegistry {
  private final Map<WorldId, WorldProjection> byWorldId;

  /**
   * Builds and validates a complete projection registry.
   *
   * @param projections configured world projections
   */
  public WorldProjectionRegistry(Collection<WorldProjection> projections) {
    Objects.requireNonNull(projections, "projections");
    Map<WorldId, WorldProjection> worlds = new HashMap<>();
    Map<ShardDimension, WorldId> dimensions = new HashMap<>();
    for (WorldProjection projection : projections) {
      Objects.requireNonNull(projection, "projection");
      WorldProjection existingWorld = worlds.putIfAbsent(projection.worldId(), projection);
      if (existingWorld != null) {
        throw new IllegalArgumentException(
            "World UUID has multiple projections: " + projection.worldId());
      }
      ShardDimension shardDimension =
          new ShardDimension(projection.shardGroupId(), projection.dimensionId());
      WorldId existingDimension = dimensions.putIfAbsent(shardDimension, projection.worldId());
      if (existingDimension != null) {
        throw new IllegalArgumentException(
            "Shard dimension has multiple world UUIDs: " + shardDimension);
      }
    }
    this.byWorldId = Map.copyOf(worlds);
  }

  /**
   * Resolves immutable metadata without querying Bukkit, configuration, or persistence.
   *
   * @param worldId platform world UUID
   * @return configured projection if this is an OpenOneBlock world
   */
  public Optional<WorldProjection> resolve(WorldId worldId) {
    Objects.requireNonNull(worldId, "worldId");
    return Optional.ofNullable(byWorldId.get(worldId));
  }

  /**
   * Returns the configured world projection count.
   *
   * @return immutable registry size
   */
  public int size() {
    return byWorldId.size();
  }

  /**
   * Returns every verified dimension projection belonging to a shard in deterministic order.
   *
   * @param shardGroupId target shard
   * @return immutable projections ordered by dimension then world UUID
   */
  public List<WorldProjection> projectionsForShard(ShardGroupId shardGroupId) {
    Objects.requireNonNull(shardGroupId, "shardGroupId");
    return byWorldId.values().stream()
        .filter(projection -> projection.shardGroupId().equals(shardGroupId))
        .sorted(
            Comparator.comparing(WorldProjection::dimensionId)
                .thenComparing(WorldProjection::worldId))
        .toList();
  }

  private record ShardDimension(ShardGroupId shardGroupId, DimensionId dimensionId) {}
}
