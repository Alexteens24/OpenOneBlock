package dev.openoneblock.persistence.sqlite.operation;

import dev.openoneblock.core.operation.AuditEntry;
import dev.openoneblock.core.operation.AuditLogWriter;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.SqlitePersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;

/** Asynchronous append-only SQLite operational audit writer. */
public final class SqliteAuditLogWriter implements AuditLogWriter {
  private static final String INSERT_SQL =
      """
      INSERT INTO audit_log (
          operation_id, island_id, magicblock_sequence, rule_id, player_id,
          event_type, occurred_at, outcome, detail
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private final SqliteConnectionFactory connectionFactory;
  private final Executor databaseExecutor;

  /** Creates a writer that dispatches every append to the reserved database executor. */
  public SqliteAuditLogWriter(
      SqliteConnectionFactory connectionFactory, Executor databaseExecutor) {
    this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<Void> append(AuditEntry entry) {
    Objects.requireNonNull(entry, "entry");
    try {
      return CompletableFuture.runAsync(() -> appendNow(entry), databaseExecutor);
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  private void appendNow(AuditEntry entry) {
    try (Connection connection = connectionFactory.open();
        PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
      setNullable(statement, 1, entry.operationId().map(Object::toString).orElse(null));
      setNullable(statement, 2, entry.islandId().map(Object::toString).orElse(null));
      if (entry.magicBlockSequence().isPresent()) {
        statement.setLong(3, entry.magicBlockSequence().orElseThrow());
      } else {
        statement.setNull(3, Types.BIGINT);
      }
      setNullable(statement, 4, entry.ruleId().map(Object::toString).orElse(null));
      setNullable(statement, 5, entry.playerId().map(Object::toString).orElse(null));
      statement.setString(6, entry.eventType());
      statement.setString(7, entry.occurredAt().toString());
      statement.setString(8, entry.outcome().name());
      setNullable(statement, 9, entry.detail().orElse(null));
      if (statement.executeUpdate() != 1) {
        throw new SQLException("Audit append affected an unexpected number of rows");
      }
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to append operational audit entry", exception);
    }
  }

  private static void setNullable(PreparedStatement statement, int index, @Nullable String value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.VARCHAR);
    } else {
      statement.setString(index, value);
    }
  }
}
