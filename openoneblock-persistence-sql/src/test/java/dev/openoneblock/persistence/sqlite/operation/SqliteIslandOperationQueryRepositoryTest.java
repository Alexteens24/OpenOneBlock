package dev.openoneblock.persistence.sqlite.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.core.operation.OperationRetryClassification;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.migration.SqliteSchemaMigrator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteIslandOperationQueryRepositoryTest {
  private static final IslandId ISLAND_ID = IslandId.parse("00000000-0000-0000-0000-000000000131");
  private static final OperationId OPERATION_ID =
      OperationId.parse("00000000-0000-0000-0000-000000000132");

  @TempDir Path temporaryDirectory;

  @Test
  void projectsExpectedVersionsAndLatestWorldEffectWithoutLoadingAnIsland() throws Exception {
    SqliteConnectionFactory factory = migrated("projection.db");
    seedRepairOperation(factory);
    SqliteIslandOperationQueryRepository repository =
        new SqliteIslandOperationQueryRepository(factory, Runnable::run);

    var operation = repository.find(OPERATION_ID).toCompletableFuture().join().orElseThrow();

    assertEquals(ISLAND_ID, operation.islandId());
    assertEquals("ISLAND_REPAIR", operation.kind());
    assertEquals("VERIFYING", operation.phase());
    assertEquals(7, operation.expectedIslandVersion().orElseThrow());
    assertEquals(9, operation.expectedSlotVersion().orElseThrow());
    assertEquals(OperationRetryClassification.AUTOMATIC, operation.retryClassification());
    assertEquals(1, operation.lastEffect().orElseThrow().effectIndex());
    assertEquals("AMBIGUOUS", operation.lastEffect().orElseThrow().state());
  }

  @Test
  void listsNewestOperationsWithAnOptionalIslandFilterAndBoundedLimit() throws Exception {
    SqliteConnectionFactory factory = migrated("list.db");
    seedRepairOperation(factory);
    try (Connection connection = factory.open();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          """
          INSERT INTO operations (
              operation_id, island_id, kind, state, outcome_state,
              created_at, updated_at, completed_at
          ) VALUES (
              '00000000-0000-0000-0000-000000000133',
              '00000000-0000-0000-0000-000000000134',
              'ISLAND_CREATE', 'COMPLETED', 'SUCCEEDED',
              '2026-07-19T01:00:00Z', '2026-07-19T01:00:01Z', '2026-07-19T01:00:01Z'
          )
          """);
    }
    SqliteIslandOperationQueryRepository repository =
        new SqliteIslandOperationQueryRepository(factory, Runnable::run);

    var all = repository.list(Optional.empty(), 1).toCompletableFuture().join();
    var filtered = repository.list(Optional.of(ISLAND_ID), 10).toCompletableFuture().join();

    assertEquals(1, all.size());
    assertEquals(OperationRetryClassification.NONE, all.getFirst().retryClassification());
    assertEquals(1, filtered.size());
    assertEquals(OPERATION_ID, filtered.getFirst().operationId());
  }

  @Test
  void dispatchesQueriesToTheDatabaseExecutor() throws Exception {
    SqliteConnectionFactory factory = migrated("async.db");
    AtomicReference<Runnable> queued = new AtomicReference<>();
    SqliteIslandOperationQueryRepository repository =
        new SqliteIslandOperationQueryRepository(factory, queued::set);

    var result = repository.find(OPERATION_ID);

    assertFalse(result.toCompletableFuture().isDone());
    assertTrue(queued.get() != null);
    queued.get().run();
    assertTrue(result.toCompletableFuture().join().isEmpty());
  }

  private SqliteConnectionFactory migrated(String fileName) {
    SqliteConnectionFactory factory =
        new SqliteConnectionFactory(temporaryDirectory.resolve(fileName), 2_000);
    new SqliteSchemaMigrator(factory).migrate();
    return factory;
  }

  private static void seedRepairOperation(SqliteConnectionFactory factory) throws Exception {
    try (Connection connection = factory.open();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          """
          INSERT INTO slots (
              slot_id, shard_group_id, ordinal, grid_x, grid_z, state,
              owner_island_id, ownership_role, version, created_at, updated_at
          ) VALUES (
              'openoneblock:primary:0', 'openoneblock:primary', 0, 0, 0, 'QUARANTINED',
              '00000000-0000-0000-0000-000000000131', 'PRIMARY', 9,
              '2026-07-19T00:00:00Z', '2026-07-19T00:00:00Z'
          )
          """);
      statement.executeUpdate(
          """
          INSERT INTO operations (
              operation_id, island_id, kind, state, created_at, updated_at
          ) VALUES (
              '00000000-0000-0000-0000-000000000132',
              '00000000-0000-0000-0000-000000000131',
              'ISLAND_REPAIR', 'VERIFYING',
              '2026-07-19T00:00:00Z', '2026-07-19T00:00:02Z'
          )
          """);
      statement.executeUpdate(
          """
          INSERT INTO islands (
              island_id, owner_player_id, lifecycle_state, primary_slot_id,
              current_border_size, maximum_border_size, version,
              pending_operation_id, created_at, updated_at
          ) VALUES (
              '00000000-0000-0000-0000-000000000131', 'owner', 'BROKEN',
              'openoneblock:primary:0', 64, 384, 7,
              '00000000-0000-0000-0000-000000000132',
              '2026-07-19T00:00:00Z', '2026-07-19T00:00:00Z'
          )
          """);
      statement.executeUpdate(
          """
          INSERT INTO island_repair_contexts (
              operation_id, requested_by_player_id, expected_island_version,
              expected_slot_version, minimum_y, maximum_y_exclusive, requested_at
          ) VALUES (
              '00000000-0000-0000-0000-000000000132', 'admin', 7, 9,
              -64, 320, '2026-07-19T00:00:00Z'
          )
          """);
      statement.executeUpdate(
          """
          INSERT INTO world_effect_receipts (
              operation_id, effect_index, island_id, effect_kind, safety,
              plan_descriptor, fingerprint, state, dispatch_attempts,
              created_at, dispatched_at, completed_at, updated_at
          ) VALUES
              ('00000000-0000-0000-0000-000000000132', 0,
               '00000000-0000-0000-0000-000000000131', 'VERIFY_CLEAN_REGION',
               'DETECTABLY_IDEMPOTENT', 'first',
               'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
               'VERIFIED_SUCCESS', 1, '2026-07-19T00:00:00Z',
               '2026-07-19T00:00:01Z', '2026-07-19T00:00:01Z', '2026-07-19T00:00:01Z'),
              ('00000000-0000-0000-0000-000000000132', 1,
               '00000000-0000-0000-0000-000000000131', 'VERIFY_SAFE_SPAWN',
               'DETECTABLY_IDEMPOTENT', 'second',
               'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
               'AMBIGUOUS', 2, '2026-07-19T00:00:00Z',
               '2026-07-19T00:00:01Z', '2026-07-19T00:00:02Z', '2026-07-19T00:00:02Z')
          """);
    }
  }
}
