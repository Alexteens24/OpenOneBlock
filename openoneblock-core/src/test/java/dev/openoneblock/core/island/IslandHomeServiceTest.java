package dev.openoneblock.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.DimensionId;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.grid.CoordinateRange;
import dev.openoneblock.core.grid.GridConfiguration;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.locator.WorldProjection;
import dev.openoneblock.core.locator.WorldProjectionRegistry;
import dev.openoneblock.core.world.WorldSpawnPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class IslandHomeServiceTest {
  private static final IslandId ISLAND = IslandId.generate();
  private static final PlayerId PLAYER = PlayerId.of(UUID.randomUUID());
  private static final OperationId OPERATION = OperationId.generate();
  private static final ShardGroupId SHARD = ShardGroupId.parse("openoneblock:primary");
  private static final WorldId WORLD = WorldId.of(UUID.randomUUID());
  private static final GridGeometry GEOMETRY =
      new GridGeometry(GridConfiguration.DEFAULT, new CoordinateRange(-30_000_000, 30_000_001));

  @Test
  void validatesThenPreparesThenTeleports() {
    List<String> order = new ArrayList<>();
    IslandHomeSnapshot home = home(new WorldSpawnPosition(WORLD, 0.5, 65, 0.5, 0, 0));
    IslandQueryRepository queries = queries(Optional.of(home));
    IslandHomeService service =
        service(
            queries,
            (destination, operationId) -> {
              order.add("prepare");
              return CompletableFuture.completedFuture(null);
            },
            (playerId, destination, operationId) -> {
              order.add("teleport");
              return CompletableFuture.completedFuture(null);
            });

    IslandHomeResult result = service.home(PLAYER, OPERATION).toCompletableFuture().join();

    assertEquals(List.of("prepare", "teleport"), order);
    assertEquals(new IslandHomeResult(ISLAND, OPERATION, 7), result);
  }

  @Test
  void unsafePersistedHomeFailsBeforePlatformEffects() {
    List<String> effects = new ArrayList<>();
    IslandHomeSnapshot home = home(new WorldSpawnPosition(WORLD, 100.5, 65, 0.5, 0, 0));
    IslandHomeService service =
        service(
            queries(Optional.of(home)),
            (destination, operationId) -> {
              effects.add("prepare");
              return CompletableFuture.completedFuture(null);
            },
            (playerId, destination, operationId) -> {
              effects.add("teleport");
              return CompletableFuture.completedFuture(null);
            });

    CompletionException failure =
        assertThrows(
            CompletionException.class,
            () -> service.home(PLAYER, OPERATION).toCompletableFuture().join());

    assertInstanceOf(UnsafeIslandHomeException.class, failure.getCause());
    assertEquals(List.of(), effects);
  }

  @Test
  void teleportFailurePropagatesWithoutAnyRepositoryMutationPort() {
    AtomicQueryRepository queries = new AtomicQueryRepository(Optional.of(home(defaultSpawn())));
    IslandHomeService service =
        service(
            queries,
            (destination, operationId) -> CompletableFuture.completedFuture(null),
            (playerId, destination, operationId) ->
                CompletableFuture.failedFuture(new IllegalStateException("teleport rejected")));

    assertThrows(
        CompletionException.class,
        () -> service.home(PLAYER, OPERATION).toCompletableFuture().join());

    assertEquals(1, queries.reads);
  }

  @Test
  void missingMembershipHasNoPlatformEffects() {
    IslandHomeService service =
        service(
            queries(Optional.empty()),
            (destination, operationId) ->
                CompletableFuture.failedFuture(new AssertionError("unexpected preparation")),
            (playerId, destination, operationId) ->
                CompletableFuture.failedFuture(new AssertionError("unexpected teleport")));

    CompletionException failure =
        assertThrows(
            CompletionException.class,
            () -> service.home(PLAYER, OPERATION).toCompletableFuture().join());

    assertInstanceOf(PlayerIslandNotFoundException.class, failure.getCause());
  }

  private static IslandHomeService service(
      IslandQueryRepository queries,
      IslandDestinationPreparer preparer,
      IslandPlayerTeleporter teleporter) {
    return new IslandHomeService(
        queries,
        new WorldProjectionRegistry(
            List.of(
                new WorldProjection(WORLD, SHARD, DimensionId.parse("openoneblock:overworld")))),
        ignored -> GEOMETRY,
        -64,
        320,
        preparer,
        teleporter);
  }

  private static IslandHomeSnapshot home(WorldSpawnPosition spawn) {
    return new IslandHomeSnapshot(ISLAND, SHARD, new GridPosition(0, 0), 64, 7, spawn);
  }

  private static WorldSpawnPosition defaultSpawn() {
    return new WorldSpawnPosition(WORLD, 0.5, 65, 0.5, 0, 0);
  }

  private static IslandQueryRepository queries(Optional<IslandHomeSnapshot> home) {
    return new AtomicQueryRepository(home);
  }

  private static final class AtomicQueryRepository implements IslandQueryRepository {
    private final Optional<IslandHomeSnapshot> home;
    private int reads;

    private AtomicQueryRepository(Optional<IslandHomeSnapshot> home) {
      this.home = home;
    }

    @Override
    public CompletionStage<Optional<IslandHomeSnapshot>> findActiveHome(PlayerId playerId) {
      reads++;
      return CompletableFuture.completedFuture(home);
    }

    @Override
    public CompletionStage<Optional<IslandInfoSnapshot>> findActiveInfo(PlayerId playerId) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
  }
}
