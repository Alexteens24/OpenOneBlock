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

    assertEquals(6, migrator.currentVersion());
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
                    'island_progression', 'magic_blocks'
                )
              """)) {
        assertTrue(result.next());
        assertEquals(13, result.getInt(1));
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
    assertEquals(6, migrator.currentVersion());
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

  private SqliteConnectionFactory factory(String fileName) {
    return new SqliteConnectionFactory(temporaryDirectory.resolve(fileName), 50);
  }
}
