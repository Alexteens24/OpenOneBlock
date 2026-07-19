package dev.openoneblock.persistence.sqlite.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.world.WorldBlockPosition;
import dev.openoneblock.core.world.WorldEffectKey;
import dev.openoneblock.core.world.WorldEffectPlan;
import dev.openoneblock.core.world.WorldEffectReceipt;
import dev.openoneblock.core.world.WorldEffectState;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.migration.SqliteSchemaMigrator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteWorldEffectJournalTest {
  private static final OperationId OPERATION =
      OperationId.parse("00000000-0000-0000-0000-000000000031");
  private static final IslandId ISLAND = IslandId.parse("00000000-0000-0000-0000-000000000032");
  private static final WorldId WORLD = WorldId.parse("00000000-0000-0000-0000-000000000033");
  private static final Instant CREATED = Instant.parse("2026-07-19T01:00:00Z");
  private static final Instant DISPATCHED = Instant.parse("2026-07-19T01:00:01Z");
  private static final Instant COMPLETED = Instant.parse("2026-07-19T01:00:02Z");

  @TempDir Path temporaryDirectory;
  private SqliteConnectionFactory factory;

  @BeforeEach
  void migrateAndSeedCreation() throws Exception {
    factory = new SqliteConnectionFactory(temporaryDirectory.resolve("effects.db"), 100);
    new SqliteSchemaMigrator(factory).migrate();
    try (Connection connection = factory.open();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          """
          INSERT INTO slots (
              slot_id, shard_group_id, ordinal, grid_x, grid_z, state,
              owner_island_id, ownership_role, version, created_at, updated_at
          ) VALUES (
              '00000000-0000-0000-0000-000000000034', 'openoneblock:primary',
              0, 0, 0, 'PREPARING', '00000000-0000-0000-0000-000000000032',
              'PRIMARY', 2, '2026-07-19T01:00:00Z', '2026-07-19T01:00:00Z'
          )
          """);
      statement.executeUpdate(
          """
          INSERT INTO operations (
              operation_id, island_id, kind, state, slot_id, created_at, updated_at
          ) VALUES (
              '00000000-0000-0000-0000-000000000031',
              '00000000-0000-0000-0000-000000000032', 'CREATE_ISLAND', 'PREPARING',
              '00000000-0000-0000-0000-000000000034',
              '2026-07-19T01:00:00Z', '2026-07-19T01:00:00Z'
          )
          """);
      statement.executeUpdate(
          """
          INSERT INTO islands (
              island_id, owner_player_id, lifecycle_state, primary_slot_id,
              current_border_size, maximum_border_size, version, pending_operation_id,
              created_at, updated_at
          ) VALUES (
              '00000000-0000-0000-0000-000000000032',
              '00000000-0000-0000-0000-000000000035', 'CREATING',
              '00000000-0000-0000-0000-000000000034', 64, 384, 2,
              '00000000-0000-0000-0000-000000000031',
              '2026-07-19T01:00:00Z', '2026-07-19T01:00:00Z'
          )
          """);
    }
  }

  @Test
  void persistsEveryEvidenceStateAndReplaysExactTransitionsAcrossRestart() {
    SqliteWorldEffectJournal journal = new SqliteWorldEffectJournal(factory, Runnable::run);
    WorldEffectPlan effect = effect(0, 0);

    WorldEffectReceipt registered = join(journal.register(effect, CREATED));
    assertEquals(WorldEffectState.NOT_STARTED, registered.state());
    assertEquals(registered, join(journal.register(effect, CREATED.plusSeconds(30))));

    WorldEffectReceipt dispatched = join(journal.markDispatched(effect, DISPATCHED));
    assertEquals(WorldEffectState.DISPATCHED, dispatched.state());
    assertEquals(1, dispatched.dispatchAttempts());
    assertEquals(dispatched, join(journal.markDispatched(effect, DISPATCHED.plusSeconds(30))));

    SqliteWorldEffectJournal restarted = new SqliteWorldEffectJournal(factory, Runnable::run);
    assertEquals(dispatched, join(restarted.find(effect.key())).orElseThrow());
    WorldEffectReceipt completed =
        join(
            restarted.recordOutcome(
                effect,
                WorldEffectState.VERIFIED_SUCCESS,
                "exact block state verified",
                COMPLETED));

    assertEquals(WorldEffectState.VERIFIED_SUCCESS, completed.state());
    assertEquals(Optional.of(COMPLETED), completed.completedAt());
    assertEquals(
        completed,
        join(
            restarted.recordOutcome(
                effect,
                WorldEffectState.VERIFIED_SUCCESS,
                "exact block state verified",
                COMPLETED.plusSeconds(30))));
  }

  @Test
  void stableKeyCannotBeReusedForDifferentIntentOrTerminalOutcome() {
    SqliteWorldEffectJournal journal = new SqliteWorldEffectJournal(factory, Runnable::run);
    WorldEffectPlan effect = effect(0, 0);
    join(journal.register(effect, CREATED));

    CompletionException intentConflict =
        assertThrows(
            CompletionException.class,
            () -> join(journal.register(effect(0, 1), CREATED.plusSeconds(1))));
    assertInstanceOf(WorldEffectJournalConflictException.class, intentConflict.getCause());

    join(journal.markDispatched(effect, DISPATCHED));
    join(journal.recordOutcome(effect, WorldEffectState.VERIFIED_SUCCESS, "verified", COMPLETED));
    CompletionException outcomeConflict =
        assertThrows(
            CompletionException.class,
            () ->
                join(
                    journal.recordOutcome(
                        effect, WorldEffectState.AMBIGUOUS, "unknown", COMPLETED.plusSeconds(1))));
    assertInstanceOf(WorldEffectJournalConflictException.class, outcomeConflict.getCause());
  }

  @Test
  void refusesOutcomeBeforeDispatchAndListsOperationInStableOrder() {
    SqliteWorldEffectJournal journal = new SqliteWorldEffectJournal(factory, Runnable::run);
    WorldEffectPlan first = effect(0, 0);
    WorldEffectPlan second = effect(1, 1);
    join(journal.register(second, CREATED));
    join(journal.register(first, CREATED));

    CompletionException exception =
        assertThrows(
            CompletionException.class,
            () ->
                join(
                    journal.recordOutcome(
                        first, WorldEffectState.VERIFIED_FAILURE, "not dispatched", COMPLETED)));

    assertInstanceOf(WorldEffectJournalConflictException.class, exception.getCause());
    List<WorldEffectReceipt> receipts = join(journal.findByOperation(OPERATION));
    assertEquals(
        List.of(first.key(), second.key()),
        receipts.stream().map(WorldEffectReceipt::key).toList());
    assertTrue(join(journal.find(new WorldEffectKey(OperationId.generate(), 0))).isEmpty());
  }

  @Test
  void databaseConstraintsRejectImpossibleReceiptEvidence() throws Exception {
    try (Connection connection = factory.open();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                INSERT INTO world_effect_receipts (
                    operation_id, effect_index, island_id, effect_kind, safety,
                    plan_descriptor, fingerprint, state, dispatch_attempts,
                    created_at, dispatched_at, completed_at, updated_at
                ) VALUES (?, 0, ?, 'SET_VANILLA_BLOCK', 'NATURALLY_IDEMPOTENT',
                    'invalid', ?, 'VERIFIED_SUCCESS', 0, ?, NULL, NULL, ?)
                """)) {
      statement.setString(1, OPERATION.toString());
      statement.setString(2, ISLAND.toString());
      statement.setString(3, "0".repeat(64));
      statement.setString(4, CREATED.toString());
      statement.setString(5, CREATED.toString());
      assertThrows(java.sql.SQLException.class, statement::executeUpdate);
    }
  }

  private static WorldEffectPlan effect(int index, int x) {
    return new WorldEffectPlan.SetVanillaBlock(
        new WorldEffectKey(OPERATION, index),
        ISLAND,
        new WorldBlockPosition(WORLD, x, 64, 0),
        NamespacedId.parse("minecraft:grass_block"));
  }

  private static <T> T join(CompletionStage<T> stage) {
    return stage.toCompletableFuture().join();
  }
}
