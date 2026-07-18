package dev.openoneblock.persistence.sqlite.slot;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.core.grid.CoordinateRange;
import dev.openoneblock.core.grid.GridConfiguration;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.locator.InMemorySlotLocatorIndex;
import dev.openoneblock.core.locator.LocatorPublishDecision;
import dev.openoneblock.core.locator.SlotLocatorLookup;
import dev.openoneblock.core.slot.AllocatedSlot;
import dev.openoneblock.core.slot.SlotAllocationRequest;
import dev.openoneblock.core.slot.SlotAllocator;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.SqlitePersistenceException;
import dev.openoneblock.persistence.sqlite.migration.SqliteSchemaMigrator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteSlotAllocatorTest {
  private static final ShardGroupId SHARD = ShardGroupId.parse("openoneblock:primary");
  private static final Instant REQUESTED_AT = Instant.parse("2026-07-19T01:00:00Z");
  private static final GridGeometry GEOMETRY =
      new GridGeometry(GridConfiguration.DEFAULT, new CoordinateRange(-30_000_000, 30_000_001));

  @TempDir Path temporaryDirectory;

  private final List<ExecutorService> executors = new ArrayList<>();

  @AfterEach
  void stopExecutors() {
    executors.forEach(ExecutorService::shutdownNow);
  }

  @Test
  void allocatesSpiralOrdinalsAndReturnsIdempotentOutcome() throws Exception {
    TestContext context = context("sequential.db", new InMemorySlotLocatorIndex(), GEOMETRY);
    SlotAllocationRequest firstRequest = request(IslandId.generate(), OperationId.generate());
    AllocatedSlot first = await(context.allocator().allocate(firstRequest));
    AllocatedSlot duplicate = await(context.allocator().allocate(firstRequest));
    AllocatedSlot second =
        await(context.allocator().allocate(request(IslandId.generate(), OperationId.generate())));

    assertEquals(first, duplicate);
    assertEquals(0, first.ordinal());
    assertEquals(new GridPosition(0, 0), first.gridPosition());
    assertEquals(1, second.ordinal());
    assertEquals(new GridPosition(1, 0), second.gridPosition());
    assertNotEquals(first.slotId(), second.slotId());
    assertEquals(2, count(context.factory(), "slots"));
    assertEquals(2, count(context.factory(), "operations"));
    SlotLocatorLookup.Resolved lookup =
        assertInstanceOf(
            SlotLocatorLookup.Resolved.class,
            context.locator().lookup(SHARD, first.gridPosition()));
    assertEquals(first.slotId(), lookup.entry().slotId());
  }

  @Test
  void concurrentAllocationsNeverShareSlotOrdinalOrGridPosition() throws Exception {
    TestContext context = context("concurrent.db", new InMemorySlotLocatorIndex(), GEOMETRY);
    List<CompletionStage<AllocatedSlot>> stages = new ArrayList<>();
    for (int index = 0; index < 64; index++) {
      stages.add(
          context.allocator().allocate(request(IslandId.generate(), OperationId.generate())));
    }

    List<AllocatedSlot> slots = awaitAll(stages);
    Set<Long> ordinals = new HashSet<>();
    Set<GridPosition> positions = new HashSet<>();
    Set<String> slotIds = new HashSet<>();
    for (AllocatedSlot slot : slots) {
      assertTrue(ordinals.add(slot.ordinal()));
      assertTrue(positions.add(slot.gridPosition()));
      assertTrue(slotIds.add(slot.slotId().toString()));
    }
    assertEquals(64, count(context.factory(), "slots"));
    assertEquals(64, context.locator().size());
  }

  @Test
  void reusesLowestFreeOrdinalWithIncrementedSlotVersion() throws Exception {
    TestContext initial = context("reuse.db", new InMemorySlotLocatorIndex(), GEOMETRY);
    AllocatedSlot first =
        await(initial.allocator().allocate(request(IslandId.generate(), OperationId.generate())));
    AllocatedSlot second =
        await(initial.allocator().allocate(request(IslandId.generate(), OperationId.generate())));
    markFree(initial.factory(), first);

    InMemorySlotLocatorIndex rebuiltLocator = new InMemorySlotLocatorIndex();
    SlotAllocator restarted = allocator(initial.factory(), rebuiltLocator, GEOMETRY);
    AllocatedSlot reused =
        await(restarted.allocate(request(IslandId.generate(), OperationId.generate())));

    assertEquals(first.slotId(), reused.slotId());
    assertEquals(0, reused.ordinal());
    assertEquals(2, reused.version());
    assertEquals(second.slotId().toString(), readSlotIdAtOrdinal(initial.factory(), 1));
    assertEquals(2, count(initial.factory(), "slots"));
  }

  @Test
  void geometryFailureRollsBackTransactionAndDoesNotPublishLocator() throws Exception {
    InMemorySlotLocatorIndex locator = new InMemorySlotLocatorIndex();
    GridGeometry impossible =
        new GridGeometry(GridConfiguration.DEFAULT, new CoordinateRange(-10, 11));
    TestContext context = context("rollback.db", locator, impossible);

    assertThrows(
        CompletionException.class,
        () ->
            context
                .allocator()
                .allocate(request(IslandId.generate(), OperationId.generate()))
                .toCompletableFuture()
                .join());

    assertEquals(0, count(context.factory(), "slots"));
    assertEquals(0, count(context.factory(), "operations"));
    assertEquals(0, count(context.factory(), "shard_allocators"));
    assertEquals(0, locator.size());
  }

  @Test
  void operationIdentityConflictDoesNotAllocateAnotherSlot() throws Exception {
    TestContext context =
        context("operation-conflict.db", new InMemorySlotLocatorIndex(), GEOMETRY);
    OperationId operationId = OperationId.generate();
    await(context.allocator().allocate(request(IslandId.generate(), operationId)));

    CompletionException failure =
        assertThrows(
            CompletionException.class,
            () ->
                context
                    .allocator()
                    .allocate(request(IslandId.generate(), operationId))
                    .toCompletableFuture()
                    .join());

    assertInstanceOf(SlotAllocationOperationConflictException.class, failure.getCause());
    assertEquals(1, count(context.factory(), "slots"));
  }

  @Test
  void databaseRejectsTwoPrimarySlotsForOneIsland() throws Exception {
    TestContext context = context("duplicate-owner.db", new InMemorySlotLocatorIndex(), GEOMETRY);
    IslandId islandId = IslandId.generate();
    await(context.allocator().allocate(request(islandId, OperationId.generate())));

    ExecutionException failure =
        assertThrows(
            ExecutionException.class,
            () -> await(context.allocator().allocate(request(islandId, OperationId.generate()))));

    assertInstanceOf(SqlitePersistenceException.class, failure.getCause());
    assertEquals(1, count(context.factory(), "slots"));
    assertEquals(1, count(context.factory(), "operations"));
  }

  @Test
  void postCommitPublicationFailureIsRecoverableByOperationRetry() throws Exception {
    SqliteConnectionFactory factory = initializedFactory("publication.db");
    InMemorySlotLocatorIndex locator = new InMemorySlotLocatorIndex();
    AtomicBoolean failFirst = new AtomicBoolean(true);
    SlotAllocator allocator =
        new SqliteSlotAllocator(
            factory,
            ignored -> GEOMETRY,
            entry ->
                failFirst.getAndSet(false)
                    ? LocatorPublishDecision.CONFLICTED
                    : locator.publishCommitted(entry),
            executor());
    SlotAllocationRequest request = request(IslandId.generate(), OperationId.generate());

    CompletionException failure =
        assertThrows(
            CompletionException.class,
            () -> allocator.allocate(request).toCompletableFuture().join());
    CommittedSlotPublicationException publicationFailure =
        assertInstanceOf(CommittedSlotPublicationException.class, failure.getCause());
    assertEquals(1, count(factory, "slots"));
    assertEquals(0, locator.size());

    AllocatedSlot recovered = await(allocator.allocate(request));
    assertEquals(publicationFailure.committedSlot(), recovered);
    assertEquals(1, locator.size());
    assertEquals(1, count(factory, "slots"));
  }

  @Test
  void fileDatabaseSurvivesRestartSmokeTest() throws Exception {
    SqliteConnectionFactory firstFactory = initializedFactory("restart-smoke.db");
    InMemorySlotLocatorIndex firstLocator = new InMemorySlotLocatorIndex();
    SlotAllocator firstAllocator = allocator(firstFactory, firstLocator, GEOMETRY);
    SlotAllocationRequest request = request(IslandId.generate(), OperationId.generate());
    AllocatedSlot beforeRestart = await(firstAllocator.allocate(request));

    SqliteConnectionFactory restartedFactory =
        new SqliteConnectionFactory(firstFactory.databaseFile(), 50);
    new SqliteSchemaMigrator(restartedFactory).migrate();
    InMemorySlotLocatorIndex rebuiltLocator = new InMemorySlotLocatorIndex();
    SlotAllocator restartedAllocator = allocator(restartedFactory, rebuiltLocator, GEOMETRY);
    AllocatedSlot afterRestart = await(restartedAllocator.allocate(request));
    AllocatedSlot next =
        await(restartedAllocator.allocate(request(IslandId.generate(), OperationId.generate())));

    assertEquals(beforeRestart, afterRestart);
    assertEquals(1, next.ordinal());
    assertEquals(2, count(restartedFactory, "slots"));
    assertEquals(2, rebuiltLocator.size());
  }

  private TestContext context(
      String fileName, InMemorySlotLocatorIndex locator, GridGeometry geometry) {
    SqliteConnectionFactory factory = initializedFactory(fileName);
    return new TestContext(factory, locator, allocator(factory, locator, geometry));
  }

  private SqliteConnectionFactory initializedFactory(String fileName) {
    SqliteConnectionFactory factory =
        new SqliteConnectionFactory(temporaryDirectory.resolve(fileName), 50);
    new SqliteSchemaMigrator(factory).migrate();
    return factory;
  }

  private SlotAllocator allocator(
      SqliteConnectionFactory factory, InMemorySlotLocatorIndex locator, GridGeometry geometry) {
    return new SqliteSlotAllocator(factory, ignored -> geometry, locator, executor());
  }

  private ExecutorService executor() {
    ExecutorService executor = Executors.newFixedThreadPool(8);
    executors.add(executor);
    return executor;
  }

  private static SlotAllocationRequest request(IslandId islandId, OperationId operationId) {
    return new SlotAllocationRequest(islandId, SHARD, operationId, REQUESTED_AT);
  }

  private static AllocatedSlot await(CompletionStage<AllocatedSlot> stage) throws Exception {
    return stage.toCompletableFuture().get(10, SECONDS);
  }

  private static List<AllocatedSlot> awaitAll(List<CompletionStage<AllocatedSlot>> stages)
      throws Exception {
    List<AllocatedSlot> slots = new ArrayList<>();
    for (CompletionStage<AllocatedSlot> stage : stages) {
      slots.add(await(stage));
    }
    return slots;
  }

  private static int count(SqliteConnectionFactory factory, String table) throws Exception {
    if (!Set.of("slots", "operations", "shard_allocators").contains(table)) {
      throw new IllegalArgumentException("unexpected table");
    }
    try (Connection connection = factory.open();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      assertTrue(result.next());
      return result.getInt(1);
    }
  }

  private static void markFree(SqliteConnectionFactory factory, AllocatedSlot slot)
      throws Exception {
    try (Connection connection = factory.open();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                UPDATE slots
                SET state = 'FREE', owner_island_id = NULL, ownership_role = NULL,
                    version = version + 1
                WHERE slot_id = ?
                """)) {
      statement.setString(1, slot.slotId().toString());
      assertEquals(1, statement.executeUpdate());
    }
  }

  private static String readSlotIdAtOrdinal(SqliteConnectionFactory factory, long ordinal)
      throws Exception {
    try (Connection connection = factory.open();
        PreparedStatement statement =
            connection.prepareStatement("SELECT slot_id FROM slots WHERE ordinal = ?")) {
      statement.setLong(1, ordinal);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getString(1);
      }
    }
  }

  private record TestContext(
      SqliteConnectionFactory factory, InMemorySlotLocatorIndex locator, SlotAllocator allocator) {}
}
