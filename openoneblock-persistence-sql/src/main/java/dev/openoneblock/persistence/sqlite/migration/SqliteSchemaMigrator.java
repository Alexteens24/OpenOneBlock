package dev.openoneblock.persistence.sqlite.migration;

import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.SqliteImmediateTransactions;
import dev.openoneblock.persistence.sqlite.SqlitePersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Applies ordered checksummed migrations without replacing a valid existing schema on failure. */
public final class SqliteSchemaMigrator {
  private static final String CREATE_HISTORY =
      """
      CREATE TABLE IF NOT EXISTS schema_migrations (
          version INTEGER PRIMARY KEY CHECK (version > 0),
          description TEXT NOT NULL,
          checksum TEXT NOT NULL,
          installed_at TEXT NOT NULL
      )
      """;

  private final SqliteConnectionFactory connectionFactory;
  private final SqliteImmediateTransactions transactions;
  private final List<SqlMigration> migrations;

  /**
   * Creates a migrator for the built-in OpenOneBlock schema.
   *
   * @param connectionFactory SQLite connection source
   */
  public SqliteSchemaMigrator(SqliteConnectionFactory connectionFactory) {
    this(connectionFactory, OpenOneBlockMigrations.all());
  }

  /**
   * Creates a migrator with an explicit ordered migration set.
   *
   * @param connectionFactory SQLite connection source
   * @param migrations ordered migration definitions
   */
  public SqliteSchemaMigrator(
      SqliteConnectionFactory connectionFactory, List<SqlMigration> migrations) {
    this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    this.transactions = new SqliteImmediateTransactions(connectionFactory, 8, 2, 25);
    this.migrations = validateMigrations(migrations);
  }

  /** Applies every pending migration and verifies checksums of applied versions. */
  public void migrate() {
    enableWal();
    try {
      transactions.execute(
          connection -> {
            execute(connection, CREATE_HISTORY);
            applyMigrations(connection);
            return null;
          });
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to migrate SQLite schema", exception);
    }
  }

  /**
   * Returns the highest applied schema version, or zero for an unmigrated database.
   *
   * @return applied schema version
   */
  public int currentVersion() {
    try (Connection connection = connectionFactory.open()) {
      if (!historyExists(connection)) {
        return 0;
      }
      try (Statement statement = connection.createStatement();
          ResultSet result =
              statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
        return result.next() ? result.getInt(1) : 0;
      }
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to read SQLite schema version", exception);
    }
  }

  private void enableWal() {
    try (Connection connection = connectionFactory.open();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("PRAGMA journal_mode = WAL")) {
      if (!result.next() || !"wal".equalsIgnoreCase(result.getString(1))) {
        throw new SQLException("SQLite refused WAL journal mode");
      }
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to enable SQLite WAL mode", exception);
    }
  }

  private void applyMigrations(Connection connection) throws SQLException {
    for (SqlMigration migration : migrations) {
      AppliedMigration applied = findApplied(connection, migration.version());
      if (applied != null) {
        if (!applied.checksum().equals(migration.checksum())
            || !applied.description().equals(migration.description())) {
          throw new SQLException(
              "Applied migration checksum mismatch at version " + migration.version());
        }
        continue;
      }
      for (String statement : migration.statements()) {
        execute(connection, statement);
      }
      try (PreparedStatement insert =
          connection.prepareStatement(
              """
              INSERT INTO schema_migrations (version, description, checksum, installed_at)
              VALUES (?, ?, ?, ?)
              """)) {
        insert.setInt(1, migration.version());
        insert.setString(2, migration.description());
        insert.setString(3, migration.checksum());
        insert.setString(4, Instant.now().toString());
        insert.executeUpdate();
      }
    }
  }

  private static AppliedMigration findApplied(Connection connection, int version)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT description, checksum FROM schema_migrations WHERE version = ?")) {
      statement.setInt(1, version);
      try (ResultSet result = statement.executeQuery()) {
        return result.next()
            ? new AppliedMigration(result.getString("description"), result.getString("checksum"))
            : null;
      }
    }
  }

  private static boolean historyExists(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'")) {
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static List<SqlMigration> validateMigrations(List<SqlMigration> migrations) {
    Objects.requireNonNull(migrations, "migrations");
    List<SqlMigration> copy = List.copyOf(migrations);
    Set<Integer> versions = new HashSet<>();
    int previous = 0;
    for (SqlMigration migration : copy) {
      Objects.requireNonNull(migration, "migration");
      if (!versions.add(migration.version()) || migration.version() <= previous) {
        throw new IllegalArgumentException("migrations must have unique ascending versions");
      }
      previous = migration.version();
    }
    return copy;
  }

  private record AppliedMigration(String description, String checksum) {}
}
