package dev.openoneblock.persistence.sqlite.world;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.core.world.WorldEffectJournal;
import dev.openoneblock.core.world.WorldEffectKey;
import dev.openoneblock.core.world.WorldEffectPlan;
import dev.openoneblock.core.world.WorldEffectReceipt;
import dev.openoneblock.core.world.WorldEffectState;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.SqliteImmediateTransactions;
import dev.openoneblock.persistence.sqlite.SqlitePersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** SQLite authority for durable-before-dispatch world effect evidence and restart recovery. */
public final class SqliteWorldEffectJournal implements WorldEffectJournal {
  private final SqliteConnectionFactory connectionFactory;
  private final SqliteImmediateTransactions transactions;
  private final Executor databaseExecutor;

  /**
   * Creates an asynchronous journal using the shared bounded database executor.
   *
   * @param connectionFactory SQLite connection source
   * @param databaseExecutor bounded database executor
   */
  public SqliteWorldEffectJournal(
      SqliteConnectionFactory connectionFactory, Executor databaseExecutor) {
    this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    this.transactions = new SqliteImmediateTransactions(connectionFactory, 12, 2, 30);
    this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<WorldEffectReceipt> register(WorldEffectPlan effect, Instant recordedAt) {
    Objects.requireNonNull(effect, "effect");
    Objects.requireNonNull(recordedAt, "recordedAt");
    return supplyAsync(() -> registerNow(effect, recordedAt));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<WorldEffectReceipt> markDispatched(
      WorldEffectPlan effect, Instant dispatchedAt) {
    Objects.requireNonNull(effect, "effect");
    Objects.requireNonNull(dispatchedAt, "dispatchedAt");
    return supplyAsync(() -> markDispatchedNow(effect, dispatchedAt));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<WorldEffectReceipt> recordOutcome(
      WorldEffectPlan effect, WorldEffectState outcome, String diagnostic, Instant completedAt) {
    Objects.requireNonNull(effect, "effect");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(diagnostic, "diagnostic");
    Objects.requireNonNull(completedAt, "completedAt");
    if (!outcome.terminal() || diagnostic.isBlank()) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("effect outcome must be terminal with a diagnostic"));
    }
    return supplyAsync(() -> recordOutcomeNow(effect, outcome, diagnostic, completedAt));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<Optional<WorldEffectReceipt>> find(WorldEffectKey key) {
    Objects.requireNonNull(key, "key");
    return supplyAsync(
        () -> {
          try (Connection connection = connectionFactory.open()) {
            return Optional.ofNullable(find(connection, key));
          } catch (SQLException exception) {
            throw new SqlitePersistenceException("Failed to read SQLite world effect", exception);
          }
        });
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<List<WorldEffectReceipt>> findByOperation(OperationId operationId) {
    Objects.requireNonNull(operationId, "operationId");
    return supplyAsync(() -> findByOperationNow(operationId));
  }

  private WorldEffectReceipt registerNow(WorldEffectPlan effect, Instant recordedAt) {
    try {
      return transactions.execute(
          connection -> {
            WorldEffectReceipt existing = find(connection, effect.key());
            if (existing != null) {
              requireSameIntent(existing, effect);
              return existing;
            }
            try (PreparedStatement statement =
                connection.prepareStatement(
                    """
                    INSERT INTO world_effect_receipts (
                        operation_id, effect_index, island_id, effect_kind, safety,
                        plan_descriptor, fingerprint, state, dispatch_attempts, diagnostic,
                        created_at, dispatched_at, completed_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'NOT_STARTED', 0, NULL, ?, NULL, NULL, ?)
                    """)) {
              bindKey(statement, effect.key());
              statement.setString(3, effect.islandId().toString());
              statement.setString(4, effect.kind().name());
              statement.setString(5, effect.safety().name());
              statement.setString(6, effect.descriptor());
              statement.setString(7, effect.fingerprint());
              statement.setString(8, recordedAt.toString());
              statement.setString(9, recordedAt.toString());
              statement.executeUpdate();
            }
            return requireFound(connection, effect.key());
          });
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to register SQLite world effect", exception);
    }
  }

  private WorldEffectReceipt markDispatchedNow(WorldEffectPlan effect, Instant dispatchedAt) {
    try {
      return transactions.execute(
          connection -> {
            WorldEffectReceipt current = requireFound(connection, effect.key());
            requireSameIntent(current, effect);
            if (current.state() == WorldEffectState.DISPATCHED) {
              return current;
            }
            if (current.state() != WorldEffectState.NOT_STARTED) {
              throw conflict(effect.key(), "cannot dispatch terminal effect " + current.state());
            }
            try (PreparedStatement statement =
                connection.prepareStatement(
                    """
                    UPDATE world_effect_receipts
                    SET state = 'DISPATCHED',
                        dispatch_attempts = dispatch_attempts + 1,
                        dispatched_at = ?,
                        updated_at = ?
                    WHERE operation_id = ? AND effect_index = ?
                      AND fingerprint = ? AND state = 'NOT_STARTED'
                    """)) {
              statement.setString(1, dispatchedAt.toString());
              statement.setString(2, dispatchedAt.toString());
              statement.setString(3, effect.key().operationId().toString());
              statement.setInt(4, effect.key().effectIndex());
              statement.setString(5, effect.fingerprint());
              if (statement.executeUpdate() != 1) {
                throw conflict(effect.key(), "effect changed before dispatch commit");
              }
            }
            return requireFound(connection, effect.key());
          });
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to dispatch SQLite world effect", exception);
    }
  }

  private WorldEffectReceipt recordOutcomeNow(
      WorldEffectPlan effect, WorldEffectState outcome, String diagnostic, Instant completedAt) {
    try {
      return transactions.execute(
          connection -> {
            WorldEffectReceipt current = requireFound(connection, effect.key());
            requireSameIntent(current, effect);
            if (current.state().terminal()) {
              if (current.state() == outcome
                  && current.diagnostic().filter(diagnostic::equals).isPresent()) {
                return current;
              }
              throw conflict(effect.key(), "terminal effect outcome cannot be replaced");
            }
            if (current.state() != WorldEffectState.DISPATCHED) {
              throw conflict(effect.key(), "effect outcome was recorded before dispatch");
            }
            try (PreparedStatement statement =
                connection.prepareStatement(
                    """
                    UPDATE world_effect_receipts
                    SET state = ?, diagnostic = ?, completed_at = ?, updated_at = ?
                    WHERE operation_id = ? AND effect_index = ?
                      AND fingerprint = ? AND state = 'DISPATCHED'
                    """)) {
              statement.setString(1, outcome.name());
              statement.setString(2, diagnostic);
              statement.setString(3, completedAt.toString());
              statement.setString(4, completedAt.toString());
              statement.setString(5, effect.key().operationId().toString());
              statement.setInt(6, effect.key().effectIndex());
              statement.setString(7, effect.fingerprint());
              if (statement.executeUpdate() != 1) {
                throw conflict(effect.key(), "effect changed before outcome commit");
              }
            }
            return requireFound(connection, effect.key());
          });
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to complete SQLite world effect", exception);
    }
  }

  private List<WorldEffectReceipt> findByOperationNow(OperationId operationId) {
    try (Connection connection = connectionFactory.open();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT * FROM world_effect_receipts
                WHERE operation_id = ?
                ORDER BY effect_index
                """)) {
      statement.setString(1, operationId.toString());
      try (ResultSet result = statement.executeQuery()) {
        List<WorldEffectReceipt> receipts = new ArrayList<>();
        while (result.next()) {
          receipts.add(map(result));
        }
        return List.copyOf(receipts);
      }
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to list SQLite world effects", exception);
    }
  }

  private static WorldEffectReceipt find(Connection connection, WorldEffectKey key)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT * FROM world_effect_receipts
            WHERE operation_id = ? AND effect_index = ?
            """)) {
      bindKey(statement, key);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? map(result) : null;
      }
    }
  }

  private static WorldEffectReceipt requireFound(Connection connection, WorldEffectKey key)
      throws SQLException {
    WorldEffectReceipt receipt = find(connection, key);
    if (receipt == null) {
      throw conflict(key, "effect receipt does not exist");
    }
    return receipt;
  }

  private static void requireSameIntent(WorldEffectReceipt receipt, WorldEffectPlan effect) {
    if (!receipt.islandId().equals(effect.islandId())
        || receipt.kind() != effect.kind()
        || receipt.safety() != effect.safety()
        || !receipt.descriptor().equals(effect.descriptor())
        || !receipt.fingerprint().equals(effect.fingerprint())) {
      throw conflict(effect.key(), "stable effect key was reused for different intent");
    }
  }

  private static WorldEffectReceipt map(ResultSet result) throws SQLException {
    String diagnostic = result.getString("diagnostic");
    String dispatchedAt = result.getString("dispatched_at");
    String completedAt = result.getString("completed_at");
    return new WorldEffectReceipt(
        new WorldEffectKey(
            OperationId.parse(result.getString("operation_id")), result.getInt("effect_index")),
        IslandId.parse(result.getString("island_id")),
        WorldEffectPlan.Kind.valueOf(result.getString("effect_kind")),
        WorldEffectPlan.Safety.valueOf(result.getString("safety")),
        result.getString("plan_descriptor"),
        result.getString("fingerprint"),
        WorldEffectState.valueOf(result.getString("state")),
        result.getInt("dispatch_attempts"),
        Optional.ofNullable(diagnostic),
        Instant.parse(result.getString("created_at")),
        optionalInstant(dispatchedAt),
        optionalInstant(completedAt),
        Instant.parse(result.getString("updated_at")));
  }

  private static Optional<Instant> optionalInstant(String value) {
    return value == null ? Optional.empty() : Optional.of(Instant.parse(value));
  }

  private static void bindKey(PreparedStatement statement, WorldEffectKey key) throws SQLException {
    statement.setString(1, key.operationId().toString());
    statement.setInt(2, key.effectIndex());
  }

  private static WorldEffectJournalConflictException conflict(
      WorldEffectKey key, String description) {
    return new WorldEffectJournalConflictException(key + ": " + description);
  }

  private <T> CompletionStage<T> supplyAsync(java.util.function.Supplier<T> supplier) {
    try {
      return CompletableFuture.supplyAsync(supplier, databaseExecutor);
    } catch (RuntimeException exception) {
      return CompletableFuture.failedFuture(exception);
    }
  }
}
