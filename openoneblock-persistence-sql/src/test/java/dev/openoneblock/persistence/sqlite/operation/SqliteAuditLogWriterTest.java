package dev.openoneblock.persistence.sqlite.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.core.operation.AuditEntry;
import dev.openoneblock.core.operation.AuditOutcome;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.migration.SqliteSchemaMigrator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteAuditLogWriterTest {
  @TempDir Path temporaryDirectory;

  @Test
  void appendsEveryNormalizedCorrelationField() throws Exception {
    SqliteConnectionFactory factory = migrated("audit.db");
    SqliteAuditLogWriter writer = new SqliteAuditLogWriter(factory, Runnable::run);

    writer.append(entry()).toCompletableFuture().join();

    try (Connection connection = factory.open();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT * FROM audit_log")) {
      result.next();
      assertEquals("00000000-0000-0000-0000-000000000141", result.getString("operation_id"));
      assertEquals("00000000-0000-0000-0000-000000000142", result.getString("island_id"));
      assertEquals(17, result.getLong("magicblock_sequence"));
      assertEquals("server:diamond_milestone", result.getString("rule_id"));
      assertEquals("00000000-0000-0000-0000-000000000143", result.getString("player_id"));
      assertEquals("RECOVERY_ISLAND_RESET", result.getString("event_type"));
      assertEquals("FAILED", result.getString("outcome"));
      assertEquals("world UUID drift", result.getString("detail"));
      assertFalse(result.next());
    }
  }

  @Test
  void dispatchesAppendToTheDatabaseExecutor() throws Exception {
    SqliteConnectionFactory factory = migrated("async-audit.db");
    AtomicReference<Runnable> queued = new AtomicReference<>();
    SqliteAuditLogWriter writer = new SqliteAuditLogWriter(factory, queued::set);

    var completion = writer.append(entry());

    assertFalse(completion.toCompletableFuture().isDone());
    queued.get().run();
    completion.toCompletableFuture().join();
  }

  @Test
  void supportsServerScopeWithoutSyntheticOperationOrIslandIdentities() throws Exception {
    SqliteConnectionFactory factory = migrated("server-audit.db");
    SqliteAuditLogWriter writer = new SqliteAuditLogWriter(factory, Runnable::run);
    AuditEntry serverEntry =
        new AuditEntry(
            Optional.empty(),
            Optional.empty(),
            OptionalLong.empty(),
            Optional.empty(),
            Optional.empty(),
            "SERVER_STARTED",
            Instant.parse("2026-07-19T02:00:00Z"),
            AuditOutcome.SUCCEEDED,
            Optional.empty());

    writer.append(serverEntry).toCompletableFuture().join();

    try (Connection connection = factory.open();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery("SELECT operation_id, island_id, event_type FROM audit_log")) {
      result.next();
      assertNull(result.getString("operation_id"));
      assertNull(result.getString("island_id"));
      assertEquals("SERVER_STARTED", result.getString("event_type"));
    }
  }

  private SqliteConnectionFactory migrated(String fileName) {
    SqliteConnectionFactory factory =
        new SqliteConnectionFactory(temporaryDirectory.resolve(fileName), 2_000);
    new SqliteSchemaMigrator(factory).migrate();
    return factory;
  }

  private static AuditEntry entry() {
    return new AuditEntry(
        Optional.of(OperationId.parse("00000000-0000-0000-0000-000000000141")),
        Optional.of(IslandId.parse("00000000-0000-0000-0000-000000000142")),
        OptionalLong.of(17),
        Optional.of(NamespacedId.parse("server:diamond_milestone")),
        Optional.of(PlayerId.of(UUID.fromString("00000000-0000-0000-0000-000000000143"))),
        "RECOVERY_ISLAND_RESET",
        Instant.parse("2026-07-19T02:00:00Z"),
        AuditOutcome.FAILED,
        Optional.of("world UUID drift"));
  }
}
