package dev.openoneblock.persistence.sqlite.island;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.migration.SqliteSchemaMigrator;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteIslandQueryRepositoryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void queryIsAlwaysDispatchedToTheDatabaseExecutor() throws Exception {
    SqliteConnectionFactory factory =
        new SqliteConnectionFactory(temporaryDirectory.resolve("queries.db"), 2_000);
    new SqliteSchemaMigrator(factory).migrate();
    AtomicReference<Runnable> queued = new AtomicReference<>();
    SqliteIslandQueryRepository repository =
        new SqliteIslandQueryRepository(
            factory,
            task -> {
              if (!queued.compareAndSet(null, task)) {
                throw new AssertionError("only one task expected");
              }
            });

    CompletionStage<?> result = repository.findActiveHome(PlayerId.of(UUID.randomUUID()));

    assertFalse(result.toCompletableFuture().isDone());
    Thread databaseThread = new Thread(queued.get(), "openoneblock-test-sql");
    databaseThread.start();
    databaseThread.join();
    assertTrue(
        result.toCompletableFuture().join() instanceof java.util.Optional<?> optional
            && optional.isEmpty());
  }
}
