package dev.openoneblock.persistence.sqlite;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteImmediateTransactionsTest {
  @TempDir Path temporaryDirectory;

  @Test
  void retriesBusyWriterWithBoundedBackoffUntilLockIsReleased() throws Exception {
    SqliteConnectionFactory factory =
        new SqliteConnectionFactory(temporaryDirectory.resolve("busy-retry.db"), 250);
    SqliteImmediateTransactions transactions = new SqliteImmediateTransactions(factory, 500, 2, 5);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch workerStarted = new CountDownLatch(1);

    try (Connection blocker = factory.open();
        Statement blockerStatement = blocker.createStatement()) {
      blockerStatement.execute("BEGIN IMMEDIATE");

      SqliteImmediateTransactions singleAttempt = new SqliteImmediateTransactions(factory, 1, 0, 0);
      SQLException busyFailure =
          assertThrows(
              SQLException.class,
              () -> singleAttempt.execute(connection -> 0),
              "the competing writer must observe the held write lock");
      assertTrue(
          busyFailure.getErrorCode() == 5
              || (busyFailure.getMessage() != null
                  && busyFailure.getMessage().contains("SQLITE_BUSY")));

      Future<Integer> result =
          executor.submit(
              () -> {
                workerStarted.countDown();
                return transactions.execute(
                    connection -> {
                      try (Statement statement = connection.createStatement()) {
                        statement.execute("CREATE TABLE after_retry (id INTEGER PRIMARY KEY)");
                      }
                      return 42;
                    });
              });

      assertTrue(workerStarted.await(5, SECONDS));
      Thread.sleep(25);
      blockerStatement.execute("COMMIT");
      assertEquals(42, result.get(5, SECONDS));
    } finally {
      executor.shutdownNow();
    }

    try (Connection connection = factory.open();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE name = 'after_retry'")) {
      assertTrue(result.next());
      assertEquals(1, result.getInt(1));
    }
  }
}
