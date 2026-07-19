package dev.openoneblock.persistence.sqlite.island;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.grid.CoordinateRange;
import dev.openoneblock.core.grid.GridConfiguration;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.island.IslandAggregateSnapshot;
import dev.openoneblock.core.island.IslandCreationActivationRequest;
import dev.openoneblock.core.island.IslandCreationContext;
import dev.openoneblock.core.island.IslandCreationRepository;
import dev.openoneblock.core.island.IslandCreationRequest;
import dev.openoneblock.core.island.IslandCreationStage;
import dev.openoneblock.core.island.IslandCreationTransitionRequest;
import dev.openoneblock.core.island.IslandMembershipConflictException;
import dev.openoneblock.core.island.IslandOptimisticLockException;
import dev.openoneblock.core.island.IslandSpawnPoint;
import dev.openoneblock.core.locator.InMemorySlotLocatorIndex;
import dev.openoneblock.core.locator.LocatorPublishDecision;
import dev.openoneblock.core.magic.InitialMagicBlock;
import dev.openoneblock.core.slot.SlotState;
import dev.openoneblock.core.world.WorldBlockPosition;
import dev.openoneblock.core.world.WorldEffectKey;
import dev.openoneblock.core.world.WorldEffectPlan;
import dev.openoneblock.core.world.WorldEffectState;
import dev.openoneblock.core.world.WorldSpawnPosition;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.migration.SqliteSchemaMigrator;
import dev.openoneblock.persistence.sqlite.slot.CommittedSlotPublicationException;
import dev.openoneblock.persistence.sqlite.world.SqliteWorldEffectJournal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteIslandCreationRepositoryTest {
  private static final ShardGroupId SHARD = ShardGroupId.parse("openoneblock:primary");
  private static final Instant REQUESTED_AT = Instant.parse("2026-07-19T03:00:00Z");
  private static final GridGeometry GEOMETRY =
      new GridGeometry(GridConfiguration.DEFAULT, new CoordinateRange(-30_000_000, 30_000_001));
  private static final WorldId WORLD = WorldId.parse("00000000-0000-0000-0000-0000000000a1");

  @TempDir Path temporaryDirectory;

  private final List<ExecutorService> executors = new ArrayList<>();

  @AfterEach
  void stopExecutors() {
    executors.forEach(ExecutorService::shutdownNow);
  }

  @Test
  void atomicallyCreatesIslandOwnerOperationAndReservedSlot() throws Exception {
    TestContext context = context("create.db", new InMemorySlotLocatorIndex(), GEOMETRY);
    IslandCreationRequest request = request(player("79ba87ef-21c8-4ff3-b57e-f055a5fa8afc"));

    IslandAggregateSnapshot created = await(context.repository().createAllocation(request));

    assertEquals(request.islandId(), created.islandId());
    assertEquals(request.ownerId(), created.ownerId());
    assertEquals(IslandLifecycleState.ALLOCATING, created.lifecycleState());
    assertEquals(request.operationId(), created.pendingOperationId().orElseThrow());
    assertEquals(SlotState.RESERVED, created.primarySlot().orElseThrow().state());
    assertEquals(new GridPosition(0, 0), created.primarySlot().orElseThrow().gridPosition());
    assertEquals(created, await(context.repository().findById(request.islandId())).orElseThrow());
    assertEquals(
        created, await(context.repository().findByActiveMember(request.ownerId())).orElseThrow());
    assertEquals(1, context.locator().size());
    assertEquals(1, count(context.factory(), "islands"));
    assertEquals(1, count(context.factory(), "island_memberships"));
    assertEquals(1, count(context.factory(), "slots"));
    assertEquals(1, count(context.factory(), "operations"));
    assertEquals(1, count(context.factory(), "island_creation_contexts"));
    assertEquals(List.of(request), await(context.repository().findPendingCreationRequests()));
  }

  @Test
  void retryingOperationReturnsExistingOutcomeWithoutNewRows() throws Exception {
    TestContext context = context("idempotent.db", new InMemorySlotLocatorIndex(), GEOMETRY);
    IslandCreationRequest request = request(player("855053fa-dbd8-4240-a37e-4ce182cd4265"));

    IslandAggregateSnapshot first = await(context.repository().createAllocation(request));
    IslandAggregateSnapshot duplicate = await(context.repository().createAllocation(request));

    assertEquals(first, duplicate);
    assertEquals(1, count(context.factory(), "islands"));
    assertEquals(1, count(context.factory(), "slots"));
    assertEquals(1, count(context.factory(), "operations"));
  }

  @Test
  void advancesCreationAtomicallyAndMakesEachStageIdempotent() throws Exception {
    TestContext context = context("creation-stages.db", new InMemorySlotLocatorIndex(), GEOMETRY);
    IslandCreationRequest request = request(player("ad7dfeee-d959-4fe0-a13a-bbe818f36c4e"));
    IslandAggregateSnapshot allocated = await(context.repository().createAllocation(request));
    assertEquals(List.of(allocated), await(context.repository().findPendingCreations()));

    IslandCreationTransitionRequest begin =
        transition(allocated, request.operationId(), IslandCreationStage.BEGIN_PREPARATION);
    IslandAggregateSnapshot preparing = await(context.repository().advanceCreation(begin));
    IslandAggregateSnapshot duplicatePreparing = await(context.repository().advanceCreation(begin));

    assertEquals(preparing, duplicatePreparing);
    assertEquals(IslandLifecycleState.CREATING, preparing.lifecycleState());
    assertEquals(1, preparing.version());
    assertEquals(SlotState.PREPARING, preparing.primarySlot().orElseThrow().state());
    assertEquals(1, preparing.primarySlot().orElseThrow().version());
    assertEquals(request.operationId(), preparing.pendingOperationId().orElseThrow());
    assertEquals(List.of(preparing), await(context.repository().findPendingCreations()));

    IslandCreationActivationRequest activate = activation(context, preparing, request, true);
    IslandAggregateSnapshot active = await(context.repository().activateCreation(activate));
    IslandAggregateSnapshot duplicateActive =
        await(context.repository().activateCreation(activate));

    assertEquals(active, duplicateActive);
    assertEquals(IslandLifecycleState.ACTIVE, active.lifecycleState());
    assertEquals(2, active.version());
    assertEquals(SlotState.ACTIVE, active.primarySlot().orElseThrow().state());
    assertEquals(2, active.primarySlot().orElseThrow().version());
    assertTrue(active.pendingOperationId().isEmpty());
    assertTrue(await(context.repository().findPendingCreations()).isEmpty());
    assertEquals("COMPLETED", operationState(context.factory(), request.operationId()));
    assertEquals("SUCCEEDED", operationOutcome(context.factory(), request.operationId()));
    assertEquals(1, count(context.factory(), "islands"));
    assertEquals(1, count(context.factory(), "slots"));
    assertEquals(1, count(context.factory(), "island_spawn_points"));
    assertEquals(1, count(context.factory(), "island_progression"));
    assertEquals(2, count(context.factory(), "counters"));
    assertEquals(1, count(context.factory(), "magic_blocks"));
  }

  @Test
  void rejectsStaleVersionsAndOutOfOrderCreationStagesWithoutMutation() throws Exception {
    TestContext context = context("transition-guards.db", new InMemorySlotLocatorIndex(), GEOMETRY);
    IslandCreationRequest request = request(player("17e0ad75-b821-4cd1-a5d4-c0779a53f088"));
    IslandAggregateSnapshot allocated = await(context.repository().createAllocation(request));
    IslandCreationActivationRequest prematureActivation =
        activation(context, allocated, request, false);

    CompletionException outOfOrder =
        assertThrows(
            CompletionException.class,
            () ->
                context
                    .repository()
                    .activateCreation(prematureActivation)
                    .toCompletableFuture()
                    .join());
    assertInstanceOf(IslandCreationTransitionConflictException.class, outOfOrder.getCause());

    IslandAggregateSnapshot preparing =
        await(
            context
                .repository()
                .advanceCreation(
                    transition(
                        allocated, request.operationId(), IslandCreationStage.BEGIN_PREPARATION)));
    IslandCreationActivationRequest valid = activation(context, preparing, request, true);
    IslandCreationActivationRequest stale =
        new IslandCreationActivationRequest(
            request.islandId(),
            request.operationId(),
            0,
            0,
            valid.primarySpawn(),
            valid.magicBlock(),
            valid.initialPhaseId(),
            valid.requiredEffects(),
            REQUESTED_AT.plusSeconds(2));

    CompletionException staleFailure =
        assertThrows(
            CompletionException.class,
            () -> context.repository().activateCreation(stale).toCompletableFuture().join());
    assertInstanceOf(IslandOptimisticLockException.class, staleFailure.getCause());
    assertEquals(preparing, await(context.repository().findById(request.islandId())).orElseThrow());
    assertEquals("PREPARING_WORLD", operationState(context.factory(), request.operationId()));
  }

  @Test
  void operationIdentityCannotBeReusedForAnotherCreation() throws Exception {
    TestContext context =
        context("operation-conflict.db", new InMemorySlotLocatorIndex(), GEOMETRY);
    OperationId operationId = OperationId.generate();
    IslandCreationRequest first =
        request(player("fa8bdd56-e3bd-4480-8947-bbce8f09fbfb"), IslandId.generate(), operationId);
    await(context.repository().createAllocation(first));
    IslandCreationRequest conflicting =
        request(player("8475c405-cd64-4cb4-bdd2-6d185d007b4e"), IslandId.generate(), operationId);

    CompletionException failure =
        assertThrows(
            CompletionException.class,
            () -> context.repository().createAllocation(conflicting).toCompletableFuture().join());

    assertInstanceOf(IslandCreationOperationConflictException.class, failure.getCause());
    assertEquals(1, count(context.factory(), "islands"));
    assertEquals(1, count(context.factory(), "slots"));
  }

  @Test
  void simultaneousCreatesForOnePlayerCommitExactlyOneIslandAndDoNotLeakSlot() throws Exception {
    TestContext context = context("membership-race.db", new InMemorySlotLocatorIndex(), GEOMETRY);
    PlayerId player = player("fa97ee4d-d869-4dfc-89d3-4b1dc6d49b32");
    IslandCreationRequest first = request(player);
    IslandCreationRequest second = request(player);
    assertNotEquals(first.islandId(), second.islandId());

    CompletionStage<IslandAggregateSnapshot> firstStage =
        context.repository().createAllocation(first);
    CompletionStage<IslandAggregateSnapshot> secondStage =
        context.repository().createAllocation(second);
    List<IslandAggregateSnapshot> successes = new ArrayList<>();
    List<Throwable> failures = new ArrayList<>();
    collect(firstStage, successes, failures);
    collect(secondStage, successes, failures);

    assertEquals(1, successes.size());
    assertEquals(1, failures.size());
    IslandMembershipConflictException conflict =
        assertInstanceOf(IslandMembershipConflictException.class, failures.getFirst());
    assertEquals(successes.getFirst().islandId(), conflict.existingIslandId());
    assertEquals(1, count(context.factory(), "islands"));
    assertEquals(1, count(context.factory(), "island_memberships"));
    assertEquals(1, count(context.factory(), "slots"));
    assertEquals(1, count(context.factory(), "operations"));
    assertEquals(1, context.locator().size());
  }

  @Test
  void failedGeometryValidationRollsBackEveryCreationRowAndLocatorPublication() throws Exception {
    GridGeometry impossible =
        new GridGeometry(GridConfiguration.DEFAULT, new CoordinateRange(-10, 11));
    TestContext context =
        context("geometry-rollback.db", new InMemorySlotLocatorIndex(), impossible);

    assertThrows(
        CompletionException.class,
        () ->
            context
                .repository()
                .createAllocation(request(player("291704e1-aef1-4cad-b080-cfab74a7fdce")))
                .toCompletableFuture()
                .join());

    assertEquals(0, count(context.factory(), "islands"));
    assertEquals(0, count(context.factory(), "island_memberships"));
    assertEquals(0, count(context.factory(), "slots"));
    assertEquals(0, count(context.factory(), "operations"));
    assertEquals(0, context.locator().size());
  }

  @Test
  void postCommitPublicationFailureRecoversByIdempotentRetry() throws Exception {
    SqliteConnectionFactory factory = initializedFactory("publication-recovery.db");
    InMemorySlotLocatorIndex locator = new InMemorySlotLocatorIndex();
    AtomicBoolean failFirst = new AtomicBoolean(true);
    IslandCreationRepository repository =
        new SqliteIslandCreationRepository(
            factory,
            ignored -> GEOMETRY,
            entry ->
                failFirst.getAndSet(false)
                    ? LocatorPublishDecision.CONFLICTED
                    : locator.publishCommitted(entry),
            executor());
    IslandCreationRequest request = request(player("b78646ce-4383-4256-9e27-b46a0fc91309"));

    CompletionException failure =
        assertThrows(
            CompletionException.class,
            () -> repository.createAllocation(request).toCompletableFuture().join());
    assertInstanceOf(CommittedSlotPublicationException.class, failure.getCause());
    assertEquals(1, count(factory, "islands"));
    assertEquals(0, locator.size());

    IslandAggregateSnapshot recovered = await(repository.createAllocation(request));
    assertEquals(request.islandId(), recovered.islandId());
    assertEquals(1, count(factory, "islands"));
    assertEquals(1, count(factory, "slots"));
    assertEquals(1, locator.size());
  }

  @Test
  void fileDatabaseSurvivesRestartAndCanRepublishIdempotentOutcome() throws Exception {
    TestContext initial = context("restart.db", new InMemorySlotLocatorIndex(), GEOMETRY);
    IslandCreationRequest request = request(player("49524a7d-7f17-4780-8e90-05d5929812ed"));
    IslandAggregateSnapshot beforeRestart = await(initial.repository().createAllocation(request));

    SqliteConnectionFactory restartedFactory =
        new SqliteConnectionFactory(initial.factory().databaseFile(), 50);
    new SqliteSchemaMigrator(restartedFactory).migrate();
    InMemorySlotLocatorIndex rebuiltLocator = new InMemorySlotLocatorIndex();
    IslandCreationRepository restarted = repository(restartedFactory, rebuiltLocator, GEOMETRY);

    IslandAggregateSnapshot loaded = await(restarted.findById(request.islandId())).orElseThrow();
    IslandAggregateSnapshot retried = await(restarted.createAllocation(request));

    assertEquals(beforeRestart, loaded);
    assertEquals(beforeRestart, retried);
    assertEquals(1, rebuiltLocator.size());
    assertTrue(await(restarted.findByActiveMember(request.ownerId())).isPresent());
    assertEquals(List.of(beforeRestart), await(restarted.findPendingCreations()));
  }

  private TestContext context(
      String fileName, InMemorySlotLocatorIndex locator, GridGeometry geometry) {
    SqliteConnectionFactory factory = initializedFactory(fileName);
    return new TestContext(factory, locator, repository(factory, locator, geometry));
  }

  private IslandCreationRepository repository(
      SqliteConnectionFactory factory, InMemorySlotLocatorIndex locator, GridGeometry geometry) {
    return new SqliteIslandCreationRepository(factory, ignored -> geometry, locator, executor());
  }

  private SqliteConnectionFactory initializedFactory(String fileName) {
    SqliteConnectionFactory factory =
        new SqliteConnectionFactory(temporaryDirectory.resolve(fileName), 50);
    new SqliteSchemaMigrator(factory).migrate();
    return factory;
  }

  private ExecutorService executor() {
    ExecutorService executor = Executors.newFixedThreadPool(4);
    executors.add(executor);
    return executor;
  }

  private static IslandCreationRequest request(PlayerId playerId) {
    return request(playerId, IslandId.generate(), OperationId.generate());
  }

  private static IslandCreationRequest request(
      PlayerId playerId, IslandId islandId, OperationId operationId) {
    return new IslandCreationRequest(
        islandId,
        playerId,
        SHARD,
        operationId,
        64,
        384,
        new IslandCreationContext(
            WORLD,
            NamespacedId.parse("openoneblock:default"),
            NamespacedId.parse("openoneblock:plains"),
            NamespacedId.parse("minecraft:grass_block"),
            64,
            -64,
            320),
        REQUESTED_AT);
  }

  private static PlayerId player(String uuid) {
    return PlayerId.of(UUID.fromString(uuid));
  }

  private static IslandCreationTransitionRequest transition(
      IslandAggregateSnapshot snapshot, OperationId operationId, IslandCreationStage stage) {
    return new IslandCreationTransitionRequest(
        snapshot.islandId(),
        operationId,
        stage,
        snapshot.version(),
        snapshot.primarySlot().orElseThrow().version(),
        snapshot.updatedAt().plusSeconds(1));
  }

  private static IslandCreationActivationRequest activation(
      TestContext context,
      IslandAggregateSnapshot snapshot,
      IslandCreationRequest creation,
      boolean persistEffects) {
    WorldBlockPosition block = new WorldBlockPosition(WORLD, 0, 64, 0);
    WorldSpawnPosition spawn = new WorldSpawnPosition(WORLD, 0.5, 65, 0.5, 0, 0);
    List<WorldEffectPlan> effects =
        List.of(
            new WorldEffectPlan.SetVanillaBlock(
                new WorldEffectKey(creation.operationId(), 0),
                creation.islandId(),
                block,
                NamespacedId.parse("minecraft:grass_block")),
            new WorldEffectPlan.VerifySafeSpawn(
                new WorldEffectKey(creation.operationId(), 1), creation.islandId(), spawn));
    if (persistEffects) {
      SqliteWorldEffectJournal journal =
          new SqliteWorldEffectJournal(context.factory(), Runnable::run);
      for (WorldEffectPlan effect : effects) {
        journal.register(effect, REQUESTED_AT).toCompletableFuture().join();
        journal.markDispatched(effect, REQUESTED_AT.plusSeconds(1)).toCompletableFuture().join();
        journal
            .recordOutcome(
                effect,
                WorldEffectState.VERIFIED_SUCCESS,
                "test effect verified",
                REQUESTED_AT.plusSeconds(2))
            .toCompletableFuture()
            .join();
      }
    }
    return new IslandCreationActivationRequest(
        creation.islandId(),
        creation.operationId(),
        snapshot.version(),
        snapshot.primarySlot().orElseThrow().version(),
        new IslandSpawnPoint(NamespacedId.parse("openoneblock:home"), spawn, true),
        new InitialMagicBlock(
            NamespacedId.parse("openoneblock:main"),
            block,
            NamespacedId.parse("openoneblock:default"),
            NamespacedId.parse("minecraft:grass_block")),
        NamespacedId.parse("openoneblock:plains"),
        effects.stream().map(WorldEffectPlan::key).toList(),
        REQUESTED_AT.plusSeconds(3));
  }

  private static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(10, SECONDS);
  }

  private static void collect(
      CompletionStage<IslandAggregateSnapshot> stage,
      List<IslandAggregateSnapshot> successes,
      List<Throwable> failures) {
    try {
      successes.add(stage.toCompletableFuture().join());
    } catch (CompletionException exception) {
      failures.add(exception.getCause());
    }
  }

  private static int count(SqliteConnectionFactory factory, String table) throws Exception {
    if (!List.of(
            "islands",
            "island_memberships",
            "slots",
            "operations",
            "island_creation_contexts",
            "island_spawn_points",
            "island_progression",
            "counters",
            "magic_blocks")
        .contains(table)) {
      throw new IllegalArgumentException("unexpected table");
    }
    try (Connection connection = factory.open();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      assertTrue(result.next());
      return result.getInt(1);
    }
  }

  private static String operationState(SqliteConnectionFactory factory, OperationId operationId)
      throws Exception {
    try (Connection connection = factory.open();
        var statement =
            connection.prepareStatement("SELECT state FROM operations WHERE operation_id = ?")) {
      statement.setString(1, operationId.toString());
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getString(1);
      }
    }
  }

  private static String operationOutcome(SqliteConnectionFactory factory, OperationId operationId)
      throws Exception {
    try (Connection connection = factory.open();
        var statement =
            connection.prepareStatement(
                "SELECT outcome_state FROM operations WHERE operation_id = ?")) {
      statement.setString(1, operationId.toString());
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getString(1);
      }
    }
  }

  private record TestContext(
      SqliteConnectionFactory factory,
      InMemorySlotLocatorIndex locator,
      IslandCreationRepository repository) {}
}
