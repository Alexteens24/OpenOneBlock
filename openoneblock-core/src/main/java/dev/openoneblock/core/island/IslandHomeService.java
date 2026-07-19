package dev.openoneblock.core.island;

import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.locator.WorldProjection;
import dev.openoneblock.core.locator.WorldProjectionRegistry;
import dev.openoneblock.core.world.WorldSpawnPosition;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Validates and executes non-mutating player home teleports from authoritative SQL snapshots. */
public final class IslandHomeService {
  private final IslandQueryRepository repository;
  private final WorldProjectionRegistry worlds;
  private final Function<ShardGroupId, GridGeometry> geometryByShard;
  private final int minimumY;
  private final int maximumYExclusive;
  private final int fallbackFeetY;
  private final IslandDestinationPreparer destinationPreparer;
  private final IslandPlayerTeleporter teleporter;

  /**
   * Creates the home application service.
   *
   * @param repository asynchronous authoritative queries
   * @param worlds verified runtime world registry
   * @param geometryByShard grid lookup
   * @param minimumY inclusive configured build minimum
   * @param maximumYExclusive exclusive configured build maximum
   * @param fallbackFeetY deterministic starter-center fallback feet height
   * @param destinationPreparer ownership-aware chunk preparation
   * @param teleporter ownership-aware player teleport
   */
  public IslandHomeService(
      IslandQueryRepository repository,
      WorldProjectionRegistry worlds,
      Function<ShardGroupId, GridGeometry> geometryByShard,
      int minimumY,
      int maximumYExclusive,
      int fallbackFeetY,
      IslandDestinationPreparer destinationPreparer,
      IslandPlayerTeleporter teleporter) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.worlds = Objects.requireNonNull(worlds, "worlds");
    this.geometryByShard = Objects.requireNonNull(geometryByShard, "geometryByShard");
    if (minimumY >= maximumYExclusive) {
      throw new IllegalArgumentException("build height must not be empty");
    }
    this.minimumY = minimumY;
    this.maximumYExclusive = maximumYExclusive;
    if (fallbackFeetY < minimumY || fallbackFeetY >= maximumYExclusive) {
      throw new IllegalArgumentException("fallbackFeetY must fit build height");
    }
    this.fallbackFeetY = fallbackFeetY;
    this.destinationPreparer = Objects.requireNonNull(destinationPreparer, "destinationPreparer");
    this.teleporter = Objects.requireNonNull(teleporter, "teleporter");
  }

  /**
   * Resolves, validates, prepares, and teleports without mutating island state.
   *
   * @param playerId requesting player
   * @param operationId trace identity
   * @return successful teleport result
   */
  public CompletionStage<IslandHomeResult> home(PlayerId playerId, OperationId operationId) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(operationId, "operationId");
    return repository
        .findActiveHome(playerId)
        .thenCompose(
            optional ->
                optional
                    .map(home -> validatedTeleport(playerId, operationId, home))
                    .orElseGet(
                        () ->
                            CompletableFuture.failedFuture(
                                new PlayerIslandNotFoundException(playerId))));
  }

  private CompletionStage<IslandHomeResult> validatedTeleport(
      PlayerId playerId, OperationId operationId, IslandHomeSnapshot home) {
    WorldSpawnPosition destination = resolveDestination(home);
    return destinationPreparer
        .prepare(destination, operationId)
        .thenCompose(ignored -> teleporter.teleport(playerId, destination, operationId))
        .thenApply(
            ignored -> new IslandHomeResult(home.islandId(), operationId, home.islandVersion()));
  }

  private WorldSpawnPosition resolveDestination(IslandHomeSnapshot home) {
    GridGeometry geometry =
        Objects.requireNonNull(geometryByShard.apply(home.shardGroupId()), "geometry");
    if (invalidReason(home, home.destination(), geometry) == null) {
      return home.destination();
    }
    WorldProjection fallbackWorld =
        worlds.projectionsForShard(home.shardGroupId()).stream()
            .filter(projection -> projection.dimensionId().value().value().equals("overworld"))
            .findFirst()
            .or(() -> worlds.projectionsForShard(home.shardGroupId()).stream().findFirst())
            .orElseThrow(
                () -> new UnsafeIslandHomeException(home.islandId(), "no-verified-fallback-world"));
    int centerX;
    int centerZ;
    try {
      centerX =
          Math.toIntExact(
              Math.multiplyExact(
                  (long) home.gridPosition().gridX(), geometry.configuration().cellSize()));
      centerZ =
          Math.toIntExact(
              Math.multiplyExact(
                  (long) home.gridPosition().gridZ(), geometry.configuration().cellSize()));
    } catch (ArithmeticException failure) {
      throw new UnsafeIslandHomeException(home.islandId(), "fallback-coordinate-overflow");
    }
    WorldSpawnPosition fallback =
        new WorldSpawnPosition(
            fallbackWorld.worldId(), centerX + 0.5, fallbackFeetY, centerZ + 0.5, 0, 0);
    String invalid = invalidReason(home, fallback, geometry);
    if (invalid != null) {
      throw new UnsafeIslandHomeException(home.islandId(), "fallback-" + invalid);
    }
    return fallback;
  }

  private String invalidReason(
      IslandHomeSnapshot home, WorldSpawnPosition destination, GridGeometry geometry) {
    var projection = worlds.resolve(destination.worldId()).orElse(null);
    if (projection == null) {
      return "unverified-world";
    }
    if (!projection.shardGroupId().equals(home.shardGroupId())) {
      return "world-shard-mismatch";
    }
    var block = destination.feetBlock();
    if (!geometry
        .border(home.gridPosition(), home.currentBorderSize())
        .contains(block.x(), block.z())) {
      return "outside-current-border";
    }
    if (block.y() < minimumY || block.y() >= maximumYExclusive) {
      return "outside-build-height";
    }
    return null;
  }
}
