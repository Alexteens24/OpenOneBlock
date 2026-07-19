package dev.openoneblock.core.island;

import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.locator.WorldProjectionRegistry;
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
   * @param destinationPreparer ownership-aware chunk preparation
   * @param teleporter ownership-aware player teleport
   */
  public IslandHomeService(
      IslandQueryRepository repository,
      WorldProjectionRegistry worlds,
      Function<ShardGroupId, GridGeometry> geometryByShard,
      int minimumY,
      int maximumYExclusive,
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
    var destination = home.destination();
    var projection =
        worlds
            .resolve(destination.worldId())
            .orElseThrow(() -> new UnsafeIslandHomeException(home.islandId(), "unverified-world"));
    if (!projection.shardGroupId().equals(home.shardGroupId())) {
      return CompletableFuture.failedFuture(
          new UnsafeIslandHomeException(home.islandId(), "world-shard-mismatch"));
    }
    var block = destination.feetBlock();
    GridGeometry geometry = geometryByShard.apply(home.shardGroupId());
    if (!geometry
        .border(home.gridPosition(), home.currentBorderSize())
        .contains(block.x(), block.z())) {
      return CompletableFuture.failedFuture(
          new UnsafeIslandHomeException(home.islandId(), "outside-current-border"));
    }
    if (block.y() < minimumY || block.y() >= maximumYExclusive) {
      return CompletableFuture.failedFuture(
          new UnsafeIslandHomeException(home.islandId(), "outside-build-height"));
    }
    return destinationPreparer
        .prepare(destination, operationId)
        .thenCompose(ignored -> teleporter.teleport(playerId, destination, operationId))
        .thenApply(
            ignored -> new IslandHomeResult(home.islandId(), operationId, home.islandVersion()));
  }
}
