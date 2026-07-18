package dev.openoneblock.persistence.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/** Executes bounded-retry {@code BEGIN IMMEDIATE} SQLite write transactions. */
public final class SqliteImmediateTransactions {
  private static final int SQLITE_BUSY = 5;

  private final SqliteConnectionFactory connectionFactory;
  private final int maximumAttempts;
  private final long minimumBackoffMillis;
  private final long maximumBackoffMillis;

  /**
   * Creates a transaction executor with bounded jittered busy retry.
   *
   * @param connectionFactory SQLite connection source
   * @param maximumAttempts total attempts including the first attempt
   * @param minimumBackoffMillis minimum retry delay
   * @param maximumBackoffMillis inclusive maximum retry delay
   */
  public SqliteImmediateTransactions(
      SqliteConnectionFactory connectionFactory,
      int maximumAttempts,
      long minimumBackoffMillis,
      long maximumBackoffMillis) {
    this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    if (maximumAttempts <= 0) {
      throw new IllegalArgumentException("maximumAttempts must be positive");
    }
    if (minimumBackoffMillis < 0
        || maximumBackoffMillis < minimumBackoffMillis
        || maximumBackoffMillis == Long.MAX_VALUE) {
      throw new IllegalArgumentException("invalid backoff range");
    }
    this.maximumAttempts = maximumAttempts;
    this.minimumBackoffMillis = minimumBackoffMillis;
    this.maximumBackoffMillis = maximumBackoffMillis;
  }

  /**
   * Executes work inside an immediate write transaction.
   *
   * @param work transaction body
   * @param <T> result type
   * @return committed transaction result
   * @throws SQLException after non-busy failure or exhausted retries
   */
  public <T> T execute(TransactionWork<T> work) throws SQLException {
    Objects.requireNonNull(work, "work");
    SQLException lastBusy = null;
    for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
      try {
        return executeAttempt(work);
      } catch (SQLException exception) {
        if (!isBusy(exception) || attempt == maximumAttempts) {
          throw exception;
        }
        lastBusy = exception;
      }
      sleepBeforeRetry();
    }
    throw Objects.requireNonNull(lastBusy, "last busy failure");
  }

  private <T> T executeAttempt(TransactionWork<T> work) throws SQLException {
    try (Connection connection = connectionFactory.open()) {
      boolean begun = false;
      try {
        executeControl(connection, "BEGIN IMMEDIATE");
        begun = true;
        T result = work.execute(connection);
        executeControl(connection, "COMMIT");
        return result;
      } catch (SQLException exception) {
        if (begun) {
          rollback(connection, exception);
        }
        throw exception;
      } catch (RuntimeException | Error exception) {
        if (begun) {
          rollback(connection, exception);
        }
        throw exception;
      }
    }
  }

  private static void executeControl(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void rollback(Connection connection, Throwable original) {
    try {
      executeControl(connection, "ROLLBACK");
    } catch (SQLException rollbackFailure) {
      original.addSuppressed(rollbackFailure);
    }
  }

  private static boolean isBusy(SQLException exception) {
    for (SQLException current = exception; current != null; current = current.getNextException()) {
      if (current.getErrorCode() == SQLITE_BUSY
          || (current.getMessage() != null && current.getMessage().contains("SQLITE_BUSY"))) {
        return true;
      }
    }
    return false;
  }

  private void sleepBeforeRetry() throws SQLException {
    long delay =
        minimumBackoffMillis == maximumBackoffMillis
            ? minimumBackoffMillis
            : ThreadLocalRandom.current().nextLong(minimumBackoffMillis, maximumBackoffMillis + 1);
    try {
      Thread.sleep(delay);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new SQLException("Interrupted while waiting to retry SQLite transaction", exception);
    }
  }

  /**
   * Non-blocking-JDBC-thread transaction body.
   *
   * @param <T> committed result type
   */
  @FunctionalInterface
  public interface TransactionWork<T> {
    /**
     * Executes against the active write transaction.
     *
     * @param connection transaction connection
     * @return transaction result
     * @throws SQLException on database failure
     */
    T execute(Connection connection) throws SQLException;
  }
}
