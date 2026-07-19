package dev.openoneblock.persistence.sqlite.island;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.id.DimensionId;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.execution.IslandExecutionLanes;
import dev.openoneblock.core.grid.CoordinateRange;
import dev.openoneblock.core.grid.GridConfiguration;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.island.CreateIslandCommand;
import dev.openoneblock.core.island.CreateIslandRejectedException;
import dev.openoneblock.core.island.CreateIslandResult;
import dev.openoneblock.core.island.CreateIslandService;
import dev.openoneblock.core.island.DeleteIslandService;
import dev.openoneblock.core.island.IslandAggregateSnapshot;
import dev.openoneblock.core.island.IslandCreationContext;
import dev.openoneblock.core.island.IslandCreationFailedException;
import dev.openoneblock.core.island.IslandCreationFailureRequest;
import dev.openoneblock.core.island.IslandCreationRepository;
import dev.openoneblock.core.island.IslandCreationRequest;
import dev.openoneblock.core.island.IslandCreationStage;
import dev.openoneblock.core.island.IslandCreationTransitionRequest;
import dev.openoneblock.core.island.IslandDeletionConflictException;
import dev.openoneblock.core.island.IslandDeletionFailedException;
import dev.openoneblock.core.island.IslandDeletionRequest;
import dev.openoneblock.core.island.IslandPostActivationDeliveryException;
import dev.openoneblock.core.locator.InMemorySlotLocatorIndex;
import dev.openoneblock.core.locator.WorldProjection;
import dev.openoneblock.core.locator.WorldProjectionRegistry;
import dev.openoneblock.core.runtime.IslandChunkTicketController;
import dev.openoneblock.core.runtime.IslandChunkTicketLease;
import dev.openoneblock.core.runtime.IslandRuntimeManager;
import dev.openoneblock.core.world.IslandCleanup;
import dev.openoneblock.core.world.IslandWorldPreparation;
import dev.openoneblock.core.world.WorldEffectOutcome;
import dev.openoneblock.core.world.WorldEffectPlan;
import dev.openoneblock.core.world.WorldPreparationCoordinator;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.migration.SqliteSchemaMigrator;
import dev.openoneblock.persistence.sqlite.world.SqliteWorldEffectJournal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreateIslandServiceIntegrationTest {
  private static final ShardGroupId SHARD = ShardGroupId.parse("openoneblock:primary");
  private static final WorldId WORLD = WorldId.parse("00000000-0000-0000-0000-0000000000c6");
  private static final Instant NOW = Instant.parse("2026-07-19T08:00:00Z");
  private static final GridGeometry GEOMETRY =
      new GridGeometry(GridConfiguration.DEFAULT, new CoordinateRange(-30_000_000, 30_000_001));

  @TempDir Path temporaryDirectory;

  @Test
  void createsCompleteActiveIslandAndDuplicateReplayHasNoSideEffects() throws Exception {
    RecordingWorld world = new RecordingWorld();
    RecordingTickets tickets = new RecordingTickets();
    TestContext context = context("successful.db", world, tickets);
    CreateIslandCommand command = command(player("3e3ac656-cacc-426f-9144-c1666ee52b36"));

    CreateIslandResult created = await(context.service().create(command));
    CreateIslandResult replayed = await(context.service().create(command));

    assertEquals(IslandLifecycleState.ACTIVE, created.island().lifecycleState());
    assertTrue(!created.replay());
    assertTrue(replayed.replay());
    assertEquals(created.island(), replayed.island());
    assertEquals(3, world.executions.get());
    assertEquals(1, tickets.acquisitions.get());
    assertEquals(1, tickets.releases.get());
    assertEquals(0, context.runtimes().loadedChunkCount());
    assertEquals(1, count(context.factory(), "island_spawn_points"));
    assertEquals(1, count(context.factory(), "island_progression"));
    assertEquals(1, count(context.factory(), "island_phase_history"));
    assertEquals(2, count(context.factory(), "counters"));
    assertEquals(1, count(context.factory(), "magic_blocks"));
    assertEquals(3, count(context.factory(), "world_effect_receipts"));
    assertEquals(1, context.delivery().teleports.get());
    assertEquals(1, context.delivery().events.size());
    assertEquals(false, context.delivery().events.getFirst().recovered());
    SqliteIslandQueryRepository queries =
        new SqliteIslandQueryRepository(context.factory(), Runnable::run);
    var home = await(queries.findActiveHome(command.ownerId())).orElseThrow();
    var info = await(queries.findActiveInfo(command.ownerId())).orElseThrow();
    assertEquals(created.island().islandId(), home.islandId());
    assertEquals(WORLD, home.destination().worldId());
    assertEquals(created.island().version(), home.islandVersion());
    assertEquals(created.island().islandId(), info.islandId());
    assertEquals(NamespacedId.parse("openoneblock:plains"), info.phaseId());
    assertEquals(NamespacedId.parse("openoneblock:owner"), info.requesterRoleId());
    assertEquals(0, info.totalBreaks());
    assertEquals(0, info.magicBlockSequence());
    assertEquals(1, info.activeMemberCount());
  }

  @Test
  void restartResumesOriginalDurableIntentFromAllocatingAndCreating() throws Exception {
    for (boolean beginPreparation : List.of(false, true)) {
      String database = beginPreparation ? "resume-creating.db" : "resume-allocating.db";
      RecordingWorld initialWorld = new RecordingWorld();
      RecordingTickets initialTickets = new RecordingTickets();
      TestContext initial = context(database, initialWorld, initialTickets);
      IslandCreationRequest request = request(player(UUID.randomUUID().toString()));
      IslandAggregateSnapshot pending = await(initial.repository().createAllocation(request));
      if (beginPreparation) {
        pending =
            await(
                initial
                    .repository()
                    .advanceCreation(
                        new IslandCreationTransitionRequest(
                            pending.islandId(),
                            request.operationId(),
                            IslandCreationStage.BEGIN_PREPARATION,
                            pending.version(),
                            pending.primarySlot().orElseThrow().version(),
                            NOW.plusSeconds(1))));
      }
      assertTrue(pending.lifecycleState() != IslandLifecycleState.ACTIVE);

      RecordingWorld restartedWorld = new RecordingWorld();
      RecordingTickets restartedTickets = new RecordingTickets();
      TestContext restarted = context(database, restartedWorld, restartedTickets);
      List<IslandCreationRequest> recovered =
          await(restarted.repository().findPendingCreationRequests());

      assertEquals(List.of(request), recovered);
      CreateIslandResult result = await(restarted.service().resume(recovered.getFirst()));
      assertEquals(IslandLifecycleState.ACTIVE, result.island().lifecycleState());
      assertEquals(3, restartedWorld.executions.get());
      assertEquals(1, restartedTickets.releases.get());
      assertEquals(0, restarted.delivery().teleports.get());
      assertEquals(true, restarted.delivery().events.getFirst().recovered());
      assertTrue(await(restarted.repository().findPendingCreationRequests()).isEmpty());
    }
  }

  @Test
  void verifiedWorldFailureCleansArchivesAndReleasesSlotForSafeReuse() throws Exception {
    RecordingWorld world = new RecordingWorld();
    world.failEffectIndex = 1;
    RecordingTickets tickets = new RecordingTickets();
    RecordingCleanup cleanup = new RecordingCleanup(IslandCleanup.Status.VERIFIED_CLEAN);
    TestContext context = context("world-failure.db", world, tickets, cleanup);
    CreateIslandCommand command = command(player("3f058b52-a160-4c4f-9e00-2c2713810c58"));

    CompletionException failure =
        assertThrows(
            CompletionException.class,
            () -> context.service().create(command).toCompletableFuture().join());

    assertInstanceOf(IslandCreationFailedException.class, failure.getCause());
    IslandAggregateSnapshot persisted =
        await(context.repository().findById(command.islandId())).orElseThrow();
    assertEquals(IslandLifecycleState.ARCHIVED, persisted.lifecycleState());
    assertTrue(persisted.primarySlot().isEmpty());
    assertEquals(1, tickets.acquisitions.get());
    assertEquals(1, tickets.releases.get());
    assertEquals(1, cleanup.calls.get());
    assertEquals(0, count(context.factory(), "magic_blocks"));
    assertEquals(0, count(context.factory(), "island_spawn_points"));

    world.failEffectIndex = -1;
    CreateIslandResult retried = await(context.service().create(command(command.ownerId())));
    assertEquals(IslandLifecycleState.ACTIVE, retried.island().lifecycleState());
    assertEquals(0, retried.island().primarySlot().orElseThrow().ordinal());
  }

  @Test
  void ambiguousCleanupQuarantinesSlotAndNeverReusesIt() throws Exception {
    RecordingWorld world = new RecordingWorld();
    world.failEffectIndex = 1;
    RecordingCleanup cleanup = new RecordingCleanup(IslandCleanup.Status.AMBIGUOUS);
    TestContext context = context("ambiguous-cleanup.db", world, new RecordingTickets(), cleanup);
    CreateIslandCommand failed = command(player("9d267704-a60f-4197-8957-60bbd72ed2a2"));

    CompletionException failure =
        assertThrows(
            CompletionException.class,
            () -> context.service().create(failed).toCompletableFuture().join());

    IslandCreationFailedException terminal =
        assertInstanceOf(IslandCreationFailedException.class, failure.getCause());
    assertEquals(IslandLifecycleState.BROKEN, terminal.island().lifecycleState());
    assertEquals(
        dev.openoneblock.core.slot.SlotState.QUARANTINED,
        terminal.island().primarySlot().orElseThrow().state());
    assertTrue(terminal.island().pendingOperationId().isEmpty());

    CompletionException replay =
        assertThrows(
            CompletionException.class,
            () -> context.service().create(failed).toCompletableFuture().join());
    IslandCreationFailedException stored =
        assertInstanceOf(IslandCreationFailedException.class, replay.getCause());
    assertEquals(terminal.island(), stored.island());
    assertEquals(1, cleanup.calls.get());

    world.failEffectIndex = -1;
    CreateIslandResult another =
        await(context.service().create(command(player("784b2708-62af-4f37-9c45-6d46a82531dd"))));
    assertEquals(1, another.island().primarySlot().orElseThrow().ordinal());
  }

  @Test
  void ticketFailureBeforeWorldWorkArchivesAndReleasesWithoutCleanup() throws Exception {
    RecordingWorld world = new RecordingWorld();
    RecordingTickets tickets = new RecordingTickets();
    tickets.failAcquisition = true;
    RecordingCleanup cleanup = new RecordingCleanup(IslandCleanup.Status.VERIFIED_CLEAN);
    TestContext context = context("ticket-failure.db", world, tickets, cleanup);
    PlayerId owner = player("ab578ee4-a2aa-422a-992e-4a6de24a8dbd");
    CreateIslandCommand failed = command(owner);

    CompletionException failure =
        assertThrows(
            CompletionException.class,
            () -> context.service().create(failed).toCompletableFuture().join());

    IslandCreationFailedException terminal =
        assertInstanceOf(IslandCreationFailedException.class, failure.getCause());
    assertEquals(IslandLifecycleState.ARCHIVED, terminal.island().lifecycleState());
    assertEquals(0, world.executions.get());
    assertEquals(0, cleanup.calls.get());
    assertEquals(0, tickets.releases.get());

    tickets.failAcquisition = false;
    CreateIslandResult retried = await(context.service().create(command(owner)));
    assertEquals(0, retried.island().primarySlot().orElseThrow().ordinal());
  }

  @Test
  void restartDuringCleaningResumesCleanupBeforeRecoveryCompletes() throws Exception {
    TestContext initial =
        context(
            "resume-cleanup.db",
            new RecordingWorld(),
            new RecordingTickets(),
            new RecordingCleanup(IslandCleanup.Status.VERIFIED_CLEAN));
    IslandCreationRequest request = request(player("aa98f311-cea6-47f9-8c08-3452eb76292b"));
    IslandAggregateSnapshot allocated = await(initial.repository().createAllocation(request));
    IslandAggregateSnapshot creating =
        await(
            initial
                .repository()
                .advanceCreation(
                    new IslandCreationTransitionRequest(
                        allocated.islandId(),
                        request.operationId(),
                        IslandCreationStage.BEGIN_PREPARATION,
                        allocated.version(),
                        allocated.primarySlot().orElseThrow().version(),
                        NOW.plusSeconds(1))));
    WorldEffectPlan mutation =
        new WorldEffectPlan.SetVanillaBlock(
            new dev.openoneblock.core.world.WorldEffectKey(request.operationId(), 0),
            request.islandId(),
            new dev.openoneblock.core.world.WorldBlockPosition(WORLD, 0, 64, 0),
            NamespacedId.parse("minecraft:grass_block"));
    SqliteWorldEffectJournal journal =
        new SqliteWorldEffectJournal(initial.factory(), Runnable::run);
    await(journal.register(mutation, NOW));
    await(journal.markDispatched(mutation, NOW.plusSeconds(1)));
    IslandAggregateSnapshot cleaning =
        await(
            initial
                .repository()
                .beginCreationCleanup(
                    new IslandCreationFailureRequest(
                        creating.islandId(),
                        request.operationId(),
                        creating.version(),
                        creating.primarySlot().orElseThrow().version(),
                        "simulated crash after cleanup intent",
                        NOW.plusSeconds(2))));
    assertEquals(
        dev.openoneblock.core.slot.SlotState.CLEANING,
        cleaning.primarySlot().orElseThrow().state());

    RecordingCleanup restartedCleanup = new RecordingCleanup(IslandCleanup.Status.VERIFIED_CLEAN);
    TestContext restarted =
        context(
            "resume-cleanup.db", new RecordingWorld(), new RecordingTickets(), restartedCleanup);
    List<IslandCreationRequest> pending =
        await(restarted.repository().findPendingCreationRequests());

    assertEquals(List.of(request), pending);
    await(restarted.service().recoverPending(request));
    assertEquals(1, restartedCleanup.calls.get());
    assertEquals(
        IslandLifecycleState.ARCHIVED,
        await(restarted.repository().findById(request.islandId())).orElseThrow().lifecycleState());
    assertTrue(await(restarted.repository().findPendingCreationRequests()).isEmpty());
  }

  @Test
  void simultaneousCreatesForOneOwnerYieldOneActiveIslandWithoutLeakedSlot() throws Exception {
    TestContext context = context("service-race.db", new RecordingWorld(), new RecordingTickets());
    PlayerId owner = player("156e4345-967f-48e6-8111-17e120fe915d");
    CreateIslandCommand first = command(owner);
    CreateIslandCommand second = command(owner);
    var callers = Executors.newFixedThreadPool(2);
    try {
      CompletableFuture<CreateIslandResult> firstResult =
          CompletableFuture.supplyAsync(
              () -> context.service().create(first).toCompletableFuture().join(), callers);
      CompletableFuture<CreateIslandResult> secondResult =
          CompletableFuture.supplyAsync(
              () -> context.service().create(second).toCompletableFuture().join(), callers);
      List<CompletableFuture<CreateIslandResult>> attempts = List.of(firstResult, secondResult);

      long successes =
          attempts.stream()
              .filter(
                  attempt -> {
                    try {
                      attempt.join();
                      return true;
                    } catch (CompletionException ignored) {
                      return false;
                    }
                  })
              .count();

      assertEquals(1, successes);
      assertEquals(1, count(context.factory(), "islands"));
      assertEquals(1, count(context.factory(), "magic_blocks"));
      assertEquals(1, count(context.factory(), "island_spawn_points"));
      assertTrue(await(context.repository().findByActiveMember(owner)).isPresent());
    } finally {
      callers.shutdownNow();
    }
  }

  @Test
  void unknownWorldAndStoppedLaneRejectBeforeAllocation() throws Exception {
    TestContext context = context("admission.db", new RecordingWorld(), new RecordingTickets());
    CreateIslandCommand base = command(player("f705316a-8fe7-4619-bb0b-96207119fcb5"));
    CreateIslandCommand unknown =
        new CreateIslandCommand(
            base.islandId(),
            base.operationId(),
            base.ownerId(),
            base.shardGroupId(),
            WorldId.of(UUID.randomUUID()),
            base.profileId(),
            base.phaseId());

    assertThrows(
        CompletionException.class,
        () -> context.service().create(unknown).toCompletableFuture().join());
    assertEquals(0, count(context.factory(), "islands"));

    await(context.lanes().shutdownGracefully());
    CompletionException stopped =
        assertThrows(
            CompletionException.class,
            () ->
                context
                    .service()
                    .create(command(player("1ec50621-5073-44ef-aad0-239d51d04418")))
                    .toCompletableFuture()
                    .join());
    assertInstanceOf(CreateIslandRejectedException.class, stopped.getCause());
    assertEquals(0, count(context.factory(), "islands"));
  }

  @Test
  void deliveryFailurePreservesActiveIslandAndReplayNeverRedelivers() throws Exception {
    RecordingDelivery delivery = new RecordingDelivery();
    delivery.failTeleport = true;
    TestContext context =
        context(
            "delivery-failure.db",
            new RecordingWorld(),
            new RecordingTickets(),
            new RecordingCleanup(IslandCleanup.Status.VERIFIED_CLEAN),
            delivery);
    CreateIslandCommand command = command(player("58200765-072a-4fd4-8d15-93f7306371c3"));

    CompletionException failure =
        assertThrows(
            CompletionException.class,
            () -> context.service().create(command).toCompletableFuture().join());

    IslandPostActivationDeliveryException deliveryFailure =
        assertInstanceOf(IslandPostActivationDeliveryException.class, failure.getCause());
    assertEquals(IslandLifecycleState.ACTIVE, deliveryFailure.result().island().lifecycleState());
    assertEquals(
        IslandLifecycleState.ACTIVE,
        await(context.repository().findById(command.islandId())).orElseThrow().lifecycleState());
    assertEquals(1, delivery.teleports.get());
    assertEquals(1, delivery.events.size());

    delivery.failTeleport = false;
    CreateIslandResult replay = await(context.service().create(command));
    assertTrue(replay.replay());
    assertEquals(1, delivery.teleports.get());
    assertEquals(1, delivery.events.size());
  }

  @Test
  void verifiedDeletionCleansEveryDimensionArchivesAndReleasesForExactReplay() throws Exception {
    TestContext context =
        context("delete-success.db", new RecordingWorld(), new RecordingTickets());
    PlayerId owner = player("4f7963c6-0cfb-4534-bbe0-a063a420a863");
    CreateIslandResult created = await(context.service().create(command(owner)));
    RecordingCleanup cleanup = new RecordingCleanup(IslandCleanup.Status.VERIFIED_CLEAN);
    WorldId nether = WorldId.of(UUID.randomUUID());
    WorldProjectionRegistry dimensions =
        new WorldProjectionRegistry(
            List.of(
                new WorldProjection(WORLD, SHARD, DimensionId.parse("openoneblock:overworld")),
                new WorldProjection(nether, SHARD, DimensionId.parse("openoneblock:nether"))));
    SqliteIslandDeletionRepository repository =
        new SqliteIslandDeletionRepository(context.factory(), context.locator(), Runnable::run);
    DeleteIslandService service =
        new DeleteIslandService(
            repository,
            context.lanes(),
            context.runtimes(),
            dimensions,
            ignored -> GEOMETRY,
            cleanup,
            Clock.fixed(NOW.plusSeconds(20), ZoneOffset.UTC));
    IslandDeletionRequest request = deletion(created, owner);

    var result = await(service.delete(request));
    var replay = await(service.delete(request));

    assertEquals(created.island().islandId(), result.islandId());
    assertTrue(!result.replay());
    assertTrue(replay.replay());
    assertEquals(2, cleanup.calls.get());
    assertEquals(
        IslandLifecycleState.ARCHIVED,
        await(context.repository().findById(created.island().islandId()))
            .orElseThrow()
            .lifecycleState());
    assertEquals(0, activeMemberships(context.factory(), created.island().islandId()));
    assertEquals(1, freeSlots(context.factory()));
    assertEquals(0, count(context.factory(), "magic_blocks"));
    assertEquals(0, count(context.factory(), "island_spawn_points"));
    assertInstanceOf(
        dev.openoneblock.core.locator.SlotLocatorLookup.Empty.class,
        context
            .locator()
            .lookup(SHARD, created.island().primarySlot().orElseThrow().gridPosition()));
  }

  @Test
  void ambiguousDeletionQuarantinesAndReplayNeverCleansAgain() throws Exception {
    TestContext context =
        context("delete-ambiguous.db", new RecordingWorld(), new RecordingTickets());
    PlayerId owner = player("12e5058a-52fd-4c32-aed3-76e9ce26caeb");
    CreateIslandResult created = await(context.service().create(command(owner)));
    RecordingCleanup cleanup = new RecordingCleanup(IslandCleanup.Status.AMBIGUOUS);
    SqliteIslandDeletionRepository repository =
        new SqliteIslandDeletionRepository(context.factory(), context.locator(), Runnable::run);
    DeleteIslandService service = deleteService(context, repository, cleanup);
    IslandDeletionRequest request = deletion(created, owner);

    CompletionException failure =
        assertThrows(
            CompletionException.class, () -> service.delete(request).toCompletableFuture().join());
    CompletionException replay =
        assertThrows(
            CompletionException.class, () -> service.delete(request).toCompletableFuture().join());

    assertInstanceOf(IslandDeletionFailedException.class, failure.getCause());
    assertInstanceOf(IslandDeletionFailedException.class, replay.getCause());
    assertEquals(1, cleanup.calls.get());
    var broken = await(context.repository().findById(created.island().islandId())).orElseThrow();
    assertEquals(IslandLifecycleState.BROKEN, broken.lifecycleState());
    assertEquals(
        dev.openoneblock.core.slot.SlotState.QUARANTINED,
        broken.primarySlot().orElseThrow().state());
    assertEquals(1, activeMemberships(context.factory(), created.island().islandId()));
  }

  @Test
  void restartRecoveryFindsAndCompletesDurableDeletingIntent() throws Exception {
    TestContext context =
        context("delete-recovery.db", new RecordingWorld(), new RecordingTickets());
    PlayerId owner = player("d9e9954e-bc7d-49c0-9532-c9a24a99c1e1");
    CreateIslandResult created = await(context.service().create(command(owner)));
    SqliteIslandDeletionRepository repository =
        new SqliteIslandDeletionRepository(context.factory(), context.locator(), Runnable::run);
    IslandDeletionRequest request = deletion(created, owner);
    await(repository.beginDeletion(request));

    SqliteIslandDeletionRepository restartedRepository =
        new SqliteIslandDeletionRepository(context.factory(), context.locator(), Runnable::run);
    List<IslandDeletionRequest> pending = await(restartedRepository.findPendingDeletions());
    RecordingCleanup cleanup = new RecordingCleanup(IslandCleanup.Status.VERIFIED_CLEAN);
    DeleteIslandService restarted = deleteService(context, restartedRepository, cleanup);

    assertEquals(List.of(request), pending);
    await(restarted.recoverPending(pending.getFirst()));
    assertEquals(1, cleanup.calls.get());
    assertTrue(await(restartedRepository.findPendingDeletions()).isEmpty());
    assertEquals(
        IslandLifecycleState.ARCHIVED,
        await(context.repository().findById(created.island().islandId()))
            .orElseThrow()
            .lifecycleState());
  }

  @Test
  void deletionRejectsWrongOwnerAndStaleConfirmationBeforeCleanup() throws Exception {
    TestContext context =
        context("delete-authority.db", new RecordingWorld(), new RecordingTickets());
    PlayerId owner = player("cf8f71ab-c183-48f6-bc61-52b1d90d2eb0");
    CreateIslandResult created = await(context.service().create(command(owner)));
    RecordingCleanup cleanup = new RecordingCleanup(IslandCleanup.Status.VERIFIED_CLEAN);
    SqliteIslandDeletionRepository repository =
        new SqliteIslandDeletionRepository(context.factory(), context.locator(), Runnable::run);
    DeleteIslandService service = deleteService(context, repository, cleanup);
    IslandDeletionRequest valid = deletion(created, owner);
    IslandDeletionRequest wrongOwner =
        new IslandDeletionRequest(
            valid.islandId(),
            OperationId.generate(),
            PlayerId.of(UUID.randomUUID()),
            valid.expectedIslandVersion(),
            valid.minimumY(),
            valid.maximumYExclusive(),
            valid.requestedAt());
    IslandDeletionRequest stale =
        new IslandDeletionRequest(
            valid.islandId(),
            OperationId.generate(),
            owner,
            valid.expectedIslandVersion() - 1,
            valid.minimumY(),
            valid.maximumYExclusive(),
            valid.requestedAt());

    assertInstanceOf(
        IslandDeletionConflictException.class,
        assertThrows(
                CompletionException.class,
                () -> service.delete(wrongOwner).toCompletableFuture().join())
            .getCause());
    assertInstanceOf(
        IslandDeletionConflictException.class,
        assertThrows(
                CompletionException.class, () -> service.delete(stale).toCompletableFuture().join())
            .getCause());
    assertEquals(0, cleanup.calls.get());
    assertEquals(
        IslandLifecycleState.ACTIVE,
        await(context.repository().findById(created.island().islandId()))
            .orElseThrow()
            .lifecycleState());
  }

  private TestContext context(String databaseName, RecordingWorld world, RecordingTickets tickets) {
    return context(
        databaseName, world, tickets, new RecordingCleanup(IslandCleanup.Status.VERIFIED_CLEAN));
  }

  private TestContext context(
      String databaseName,
      RecordingWorld world,
      RecordingTickets tickets,
      RecordingCleanup cleanup) {
    return context(databaseName, world, tickets, cleanup, new RecordingDelivery());
  }

  private TestContext context(
      String databaseName,
      RecordingWorld world,
      RecordingTickets tickets,
      RecordingCleanup cleanup,
      RecordingDelivery delivery) {
    SqliteConnectionFactory factory =
        new SqliteConnectionFactory(temporaryDirectory.resolve(databaseName), 100);
    new SqliteSchemaMigrator(factory).migrate();
    InMemorySlotLocatorIndex locator = new InMemorySlotLocatorIndex();
    IslandCreationRepository repository =
        new SqliteIslandCreationRepository(factory, ignored -> GEOMETRY, locator, Runnable::run);
    SqliteWorldEffectJournal journal = new SqliteWorldEffectJournal(factory, Runnable::run);
    IslandExecutionLanes lanes = new IslandExecutionLanes(Runnable::run, 8);
    IslandRuntimeManager runtimes = new IslandRuntimeManager(tickets, Duration.ofSeconds(2));
    WorldProjectionRegistry projections =
        new WorldProjectionRegistry(
            List.of(
                new WorldProjection(WORLD, SHARD, DimensionId.parse("openoneblock:overworld"))));
    WorldPreparationCoordinator preparation =
        new WorldPreparationCoordinator(journal, world, Clock.fixed(NOW, ZoneOffset.UTC));
    CreateIslandService service =
        new CreateIslandService(
            repository,
            lanes,
            runtimes,
            projections,
            ignored -> GEOMETRY,
            NamespacedId.parse("minecraft:grass_block"),
            64,
            -64,
            320,
            preparation,
            cleanup,
            delivery::teleport,
            delivery::publish,
            Clock.fixed(NOW, ZoneOffset.UTC));
    return new TestContext(
        factory, repository, lanes, runtimes, service, delivery, locator, projections);
  }

  private static DeleteIslandService deleteService(
      TestContext context, SqliteIslandDeletionRepository repository, RecordingCleanup cleanup) {
    return new DeleteIslandService(
        repository,
        context.lanes(),
        context.runtimes(),
        context.projections(),
        ignored -> GEOMETRY,
        cleanup,
        Clock.fixed(NOW.plusSeconds(20), ZoneOffset.UTC));
  }

  private static IslandDeletionRequest deletion(CreateIslandResult created, PlayerId owner) {
    return new IslandDeletionRequest(
        created.island().islandId(),
        OperationId.generate(),
        owner,
        created.island().version(),
        -64,
        320,
        NOW.plusSeconds(10));
  }

  private static CreateIslandCommand command(PlayerId owner) {
    return new CreateIslandCommand(
        IslandId.generate(),
        OperationId.generate(),
        owner,
        SHARD,
        WORLD,
        NamespacedId.parse("openoneblock:default"),
        NamespacedId.parse("openoneblock:plains"));
  }

  private static IslandCreationRequest request(PlayerId owner) {
    CreateIslandCommand command = command(owner);
    return new IslandCreationRequest(
        command.islandId(),
        owner,
        SHARD,
        command.operationId(),
        64,
        384,
        new IslandCreationContext(
            WORLD,
            command.profileId(),
            command.phaseId(),
            NamespacedId.parse("minecraft:grass_block"),
            64,
            -64,
            320),
        NOW);
  }

  private static PlayerId player(String uuid) {
    return PlayerId.of(UUID.fromString(uuid));
  }

  private static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(5, SECONDS);
  }

  private static int count(SqliteConnectionFactory factory, String table) throws Exception {
    if (!List.of(
            "islands",
            "island_spawn_points",
            "island_progression",
            "island_phase_history",
            "counters",
            "magic_blocks",
            "world_effect_receipts")
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

  private static int activeMemberships(SqliteConnectionFactory factory, IslandId islandId)
      throws Exception {
    try (Connection connection = factory.open();
        var statement =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM island_memberships WHERE island_id = ? AND active = 1")) {
      statement.setString(1, islandId.toString());
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getInt(1);
      }
    }
  }

  private static int freeSlots(SqliteConnectionFactory factory) throws Exception {
    try (Connection connection = factory.open();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery("SELECT COUNT(*) FROM slots WHERE state = 'FREE'")) {
      assertTrue(result.next());
      return result.getInt(1);
    }
  }

  private record TestContext(
      SqliteConnectionFactory factory,
      IslandCreationRepository repository,
      IslandExecutionLanes lanes,
      IslandRuntimeManager runtimes,
      CreateIslandService service,
      RecordingDelivery delivery,
      InMemorySlotLocatorIndex locator,
      WorldProjectionRegistry projections) {}

  private static final class RecordingWorld implements IslandWorldPreparation {
    private final AtomicInteger executions = new AtomicInteger();
    private int failEffectIndex = -1;

    @Override
    public CompletionStage<WorldEffectOutcome> execute(WorldEffectPlan effect) {
      executions.incrementAndGet();
      if (effect.key().effectIndex() == failEffectIndex) {
        return CompletableFuture.completedFuture(
            new WorldEffectOutcome(
                WorldEffectOutcome.Status.VERIFIED_FAILURE,
                effect.kind().mutatesWorld() || effect instanceof WorldEffectPlan.VerifyCleanRegion,
                "intentional test failure"));
      }
      return CompletableFuture.completedFuture(
          new WorldEffectOutcome(
              WorldEffectOutcome.Status.VERIFIED_SUCCESS, false, "test effect verified"));
    }

    @Override
    public CompletionStage<WorldEffectOutcome> verify(WorldEffectPlan effect) {
      return CompletableFuture.completedFuture(
          new WorldEffectOutcome(
              WorldEffectOutcome.Status.NOT_APPLIED, false, "test effect not applied"));
    }
  }

  private static final class RecordingTickets implements IslandChunkTicketController {
    private final AtomicInteger acquisitions = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();
    private boolean failAcquisition;

    @Override
    public CompletionStage<IslandChunkTicketLease> acquire(
        dev.openoneblock.core.runtime.IslandChunkTicketRequest request) {
      acquisitions.incrementAndGet();
      if (failAcquisition) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("intentional ticket acquisition failure"));
      }
      AtomicBoolean released = new AtomicBoolean();
      return CompletableFuture.completedFuture(
          new IslandChunkTicketLease() {
            @Override
            public int chunkCount() {
              return request.chunks().size();
            }

            @Override
            public CompletionStage<Void> release() {
              if (released.compareAndSet(false, true)) {
                releases.incrementAndGet();
              }
              return CompletableFuture.completedFuture(null);
            }
          });
    }
  }

  private static final class RecordingCleanup implements IslandCleanup {
    private final Status status;
    private final AtomicInteger calls = new AtomicInteger();

    private RecordingCleanup(Status status) {
      this.status = status;
    }

    @Override
    public CompletionStage<Result> cleanup(Plan plan) {
      calls.incrementAndGet();
      return CompletableFuture.completedFuture(
          new Result(status, "test cleanup " + status.name().toLowerCase()));
    }
  }

  private static final class RecordingDelivery {
    private final AtomicInteger teleports = new AtomicInteger();
    private final List<dev.openoneblock.api.event.IslandCreatedEvent> events = new ArrayList<>();
    private boolean failTeleport;

    private CompletionStage<Void> teleport(
        PlayerId ownerId,
        dev.openoneblock.core.world.WorldSpawnPosition destination,
        OperationId operationId) {
      teleports.incrementAndGet();
      return failTeleport
          ? CompletableFuture.failedFuture(
              new IllegalStateException("intentional teleport failure"))
          : CompletableFuture.completedFuture(null);
    }

    private CompletionStage<Void> publish(dev.openoneblock.api.event.IslandCreatedEvent event) {
      events.add(event);
      return CompletableFuture.completedFuture(null);
    }
  }
}
