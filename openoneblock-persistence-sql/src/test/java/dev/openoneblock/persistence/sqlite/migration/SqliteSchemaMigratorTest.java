package dev.openoneblock.persistence.sqlite.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.SqlitePersistenceException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteSchemaMigratorTest {
  @TempDir Path temporaryDirectory;

  @Test
  void migratesFileDatabaseInWalModeAndIsIdempotent() throws Exception {
    SqliteConnectionFactory factory = factory("schema.db");
    SqliteSchemaMigrator migrator = new SqliteSchemaMigrator(factory);

    assertEquals(0, migrator.currentVersion());
    migrator.migrate();
    migrator.migrate();

    assertEquals(11, migrator.currentVersion());
    try (Connection connection = factory.open();
        Statement statement = connection.createStatement()) {
      try (ResultSet result = statement.executeQuery("PRAGMA journal_mode")) {
        assertTrue(result.next());
        assertEquals("wal", result.getString(1).toLowerCase());
      }
      try (ResultSet result =
          statement.executeQuery(
              """
              SELECT COUNT(*)
              FROM sqlite_master
              WHERE type = 'table'
                AND name IN (
                    'schema_migrations', 'shard_allocators', 'slots', 'operations',
                    'islands', 'island_memberships', 'world_projections',
                    'world_projection_repairs', 'world_effect_receipts',
                    'island_creation_contexts', 'island_spawn_points',
                    'island_progression', 'magic_blocks', 'counters', 'typed_variables',
                    'island_lifecycle_operation_contexts', 'island_upgrades',
                    'island_phase_history', 'island_invitations', 'island_access_records',
                    'team_mutation_receipts'
                    , 'island_cleanup_retry_contexts'
                )
              """)) {
        assertTrue(result.next());
        assertEquals(22, result.getInt(1));
      }
    }
  }

  @Test
  void refusesChecksumDriftWithoutChangingAppliedSchema() throws Exception {
    SqliteConnectionFactory factory = factory("checksum.db");
    SqliteSchemaMigrator migrator = new SqliteSchemaMigrator(factory);
    migrator.migrate();
    try (Connection connection = factory.open();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "UPDATE schema_migrations SET checksum = 'tampered' WHERE version = 1");
    }

    assertThrows(SqlitePersistenceException.class, migrator::migrate);
    assertEquals(11, migrator.currentVersion());
  }

  @Test
  void rollsBackAllPendingStatementsWhenMigrationFails() throws Exception {
    SqliteConnectionFactory factory = factory("rollback.db");
    SqlMigration broken =
        new SqlMigration(
            1,
            "broken migration",
            List.of("CREATE TABLE should_rollback (id INTEGER PRIMARY KEY)", "INVALID SQL"));
    SqliteSchemaMigrator migrator = new SqliteSchemaMigrator(factory, List.of(broken));

    assertThrows(SqlitePersistenceException.class, migrator::migrate);
    assertEquals(0, migrator.currentVersion());
    try (Connection connection = factory.open();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE name = 'should_rollback'")) {
      assertTrue(result.next());
      assertEquals(0, result.getInt(1));
    }
  }

  @Test
  void typedVariableSchemaAcceptsExactlyOneValueColumn() throws Exception {
    SqliteConnectionFactory factory = factory("typed-variables.db");
    new SqliteSchemaMigrator(factory).migrate();
    try (Connection connection = factory.open();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          """
          INSERT INTO typed_variables (
              scope_type, scope_id, variable_id, value_type,
              duration_millis, version, created_at, updated_at
          ) VALUES (
              'ISLAND', 'island-1', 'server:cooldown', 'DURATION',
              5000, 0, '2026-07-19T00:00:00Z', '2026-07-19T00:00:00Z'
          )
          """);

      assertThrows(
          java.sql.SQLException.class,
          () ->
              statement.executeUpdate(
                  """
                  INSERT INTO typed_variables (
                      scope_type, scope_id, variable_id, value_type,
                      integer_value, string_value, version, created_at, updated_at
                  ) VALUES (
                      'ISLAND', 'island-1', 'server:invalid', 'INTEGER',
                      1, 'also-set', 0, '2026-07-19T00:00:00Z', '2026-07-19T00:00:00Z'
                  )
                  """));
    }
  }

  @Test
  void teamSchemaSeparatesMembershipInvitationsAndAccessState() throws Exception {
    SqliteConnectionFactory factory = factory("team.db");
    new SqliteSchemaMigrator(factory).migrate();
    try (Connection connection = factory.open();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          """
          INSERT INTO slots (
              slot_id, shard_group_id, ordinal, grid_x, grid_z, state,
              owner_island_id, ownership_role, version, created_at, updated_at
          ) VALUES (
              'slot-1', 'shard-1', 0, 0, 0, 'ACTIVE',
              'island-1', 'PRIMARY', 0,
              '2026-07-19T00:00:00Z', '2026-07-19T00:00:00Z'
          )
          """);
      statement.executeUpdate(
          """
          INSERT INTO islands (
              island_id, owner_player_id, lifecycle_state, primary_slot_id,
              current_border_size, maximum_border_size, version, created_at, updated_at
          ) VALUES (
              'island-1', 'owner-1', 'ACTIVE', 'slot-1',
              64, 384, 0, '2026-07-19T00:00:00Z', '2026-07-19T00:00:00Z'
          )
          """);
      statement.executeUpdate(
          """
          INSERT INTO island_invitations (
              invitation_id, island_id, invited_player_id, invited_by_player_id,
              proposed_role_id, state, expires_at, version, created_at, updated_at
          ) VALUES (
              'invite-1', 'island-1', 'player-1', 'owner-1',
              'member', 'PENDING', '2026-07-20T00:00:00Z', 0,
              '2026-07-19T00:00:00Z', '2026-07-19T00:00:00Z'
          )
          """);

      assertThrows(
          java.sql.SQLException.class,
          () ->
              statement.executeUpdate(
                  """
                  INSERT INTO island_invitations (
                      invitation_id, island_id, invited_player_id, invited_by_player_id,
                      proposed_role_id, state, expires_at, version, created_at, updated_at
                  ) VALUES (
                      'invite-2', 'island-1', 'player-1', 'owner-1',
                      'member', 'PENDING', '2026-07-20T00:00:00Z', 0,
                      '2026-07-19T00:00:00Z', '2026-07-19T00:00:00Z'
                  )
                  """));

      statement.executeUpdate(
          """
          INSERT INTO island_access_records (
              island_id, player_id, access_state, role_id, version, created_at, updated_at
          ) VALUES (
              'island-1', 'player-1', 'TRUSTED', 'trusted', 0,
              '2026-07-19T00:00:00Z', '2026-07-19T00:00:00Z'
          )
          """);
      assertThrows(
          java.sql.SQLException.class,
          () ->
              statement.executeUpdate(
                  """
                  INSERT INTO island_access_records (
                      island_id, player_id, access_state, version, created_at, updated_at
                  ) VALUES (
                      'island-1', 'player-1', 'BANNED', 0,
                      '2026-07-19T00:00:00Z', '2026-07-19T00:00:00Z'
                  )
                  """));
    }
  }

  private SqliteConnectionFactory factory(String fileName) {
    return new SqliteConnectionFactory(temporaryDirectory.resolve(fileName), 50);
  }
}
