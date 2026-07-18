package dev.openoneblock.persistence.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/** Opens consistently configured SQLite connections for one database file. */
public final class SqliteConnectionFactory {
  private final Path databaseFile;
  private final String jdbcUrl;
  private final int busyTimeoutMillis;

  /**
   * Creates a connection factory.
   *
   * @param databaseFile SQLite database file
   * @param busyTimeoutMillis per-connection bounded SQLite busy timeout
   */
  public SqliteConnectionFactory(Path databaseFile, int busyTimeoutMillis) {
    this.databaseFile = Objects.requireNonNull(databaseFile, "databaseFile").toAbsolutePath();
    if (busyTimeoutMillis < 0) {
      throw new IllegalArgumentException("busyTimeoutMillis must be non-negative");
    }
    this.busyTimeoutMillis = busyTimeoutMillis;
    this.jdbcUrl = "jdbc:sqlite:" + this.databaseFile;
  }

  /**
   * Opens a new connection with foreign keys, busy timeout, and normal synchronization enabled.
   *
   * @return configured open connection
   * @throws SQLException when the file or SQLite connection cannot be initialized
   */
  public Connection open() throws SQLException {
    createParentDirectory();
    Connection connection = DriverManager.getConnection(jdbcUrl);
    try (Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys = ON");
      statement.execute("PRAGMA busy_timeout = " + busyTimeoutMillis);
      statement.execute("PRAGMA synchronous = NORMAL");
    } catch (SQLException exception) {
      connection.close();
      throw exception;
    }
    return connection;
  }

  /**
   * Returns the normalized database path.
   *
   * @return absolute database file path
   */
  public Path databaseFile() {
    return databaseFile;
  }

  private void createParentDirectory() throws SQLException {
    Path parent = databaseFile.getParent();
    if (parent == null) {
      return;
    }
    try {
      Files.createDirectories(parent);
    } catch (IOException exception) {
      throw new SQLException("Failed to create SQLite database directory: " + parent, exception);
    }
  }
}
