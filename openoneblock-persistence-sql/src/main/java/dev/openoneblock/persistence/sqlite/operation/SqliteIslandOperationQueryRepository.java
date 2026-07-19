package dev.openoneblock.persistence.sqlite.operation;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.core.operation.IslandOperationQueryRepository;
import dev.openoneblock.core.operation.IslandOperationSnapshot;
import dev.openoneblock.core.operation.OperationEffectEvidence;
import dev.openoneblock.core.operation.OperationRetryClassification;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
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
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** SQLite implementation of non-loading durable operation diagnostics. */
public final class SqliteIslandOperationQueryRepository implements IslandOperationQueryRepository {
  private static final int MAXIMUM_LIMIT = 100;
  private static final String PROJECTION =
      """
      SELECT o.operation_id, o.island_id, o.kind, o.state, o.slot_id,
             o.expected_island_version, o.expected_slot_version,
             o.request_fingerprint, o.outcome_state, o.outcome_payload,
             CASE
                 WHEN o.outcome_state = 'SUCCEEDED' THEN 'NONE'
                 WHEN o.outcome_state = 'AMBIGUOUS' THEN 'RECONCILE'
                 WHEN o.outcome_state = 'FAILED' THEN 'MANUAL'
                 ELSE COALESCE(o.retry_classification, 'AUTOMATIC')
             END AS effective_retry_classification,
             o.error_code, o.diagnostic_context,
             o.created_at, o.updated_at, o.completed_at,
             effect.effect_index, effect.effect_kind, effect.state AS effect_state,
             effect.dispatch_attempts, effect.updated_at AS effect_updated_at
      FROM operations o
      LEFT JOIN world_effect_receipts effect
        ON effect.operation_id = o.operation_id
       AND effect.effect_index = (
           SELECT candidate.effect_index
           FROM world_effect_receipts candidate
           WHERE candidate.operation_id = o.operation_id
           ORDER BY candidate.updated_at DESC, candidate.effect_index DESC
           LIMIT 1
       )
      """;

  private final SqliteConnectionFactory connectionFactory;
  private final Executor databaseExecutor;

  /** Creates an asynchronous operation query repository. */
  public SqliteIslandOperationQueryRepository(
      SqliteConnectionFactory connectionFactory, Executor databaseExecutor) {
    this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<Optional<IslandOperationSnapshot>> find(OperationId operationId) {
    Objects.requireNonNull(operationId, "operationId");
    return supplyAsync(() -> findNow(operationId));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<List<IslandOperationSnapshot>> list(
      Optional<IslandId> islandId, int limit) {
    Objects.requireNonNull(islandId, "islandId");
    if (limit < 1 || limit > MAXIMUM_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAXIMUM_LIMIT);
    }
    return supplyAsync(() -> listNow(islandId, limit));
  }

  private Optional<IslandOperationSnapshot> findNow(OperationId operationId) {
    try (Connection connection = connectionFactory.open();
        PreparedStatement statement =
            connection.prepareStatement(PROJECTION + " WHERE o.operation_id = ?")) {
      statement.setString(1, operationId.toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return Optional.empty();
        }
        IslandOperationSnapshot snapshot = map(result);
        if (result.next()) {
          throw new SQLException("Database invariant violation: duplicate operation projection");
        }
        return Optional.of(snapshot);
      }
    } catch (SQLException | IllegalArgumentException exception) {
      throw new SqlitePersistenceException("Failed to query operation " + operationId, exception);
    }
  }

  private List<IslandOperationSnapshot> listNow(Optional<IslandId> islandId, int limit) {
    String filter = islandId.isPresent() ? " WHERE o.island_id = ?" : "";
    String sql = PROJECTION + filter + " ORDER BY o.updated_at DESC, o.operation_id LIMIT ?";
    try (Connection connection = connectionFactory.open();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      int parameter = 1;
      if (islandId.isPresent()) {
        statement.setString(parameter++, islandId.orElseThrow().toString());
      }
      statement.setInt(parameter, limit);
      try (ResultSet result = statement.executeQuery()) {
        List<IslandOperationSnapshot> snapshots = new ArrayList<>();
        while (result.next()) {
          snapshots.add(map(result));
        }
        return List.copyOf(snapshots);
      }
    } catch (SQLException | IllegalArgumentException exception) {
      throw new SqlitePersistenceException("Failed to list durable operations", exception);
    }
  }

  private static IslandOperationSnapshot map(ResultSet result) throws SQLException {
    String slotId = result.getString("slot_id");
    String effectKind = result.getString("effect_kind");
    return new IslandOperationSnapshot(
        OperationId.parse(result.getString("operation_id")),
        IslandId.parse(result.getString("island_id")),
        result.getString("kind"),
        result.getString("state"),
        slotId == null ? Optional.empty() : Optional.of(SlotId.parse(slotId)),
        nullableLong(result, "expected_island_version"),
        nullableLong(result, "expected_slot_version"),
        Optional.ofNullable(result.getString("request_fingerprint")),
        Optional.ofNullable(result.getString("outcome_state")),
        Optional.ofNullable(result.getString("outcome_payload")),
        OperationRetryClassification.valueOf(result.getString("effective_retry_classification")),
        Optional.ofNullable(result.getString("error_code")),
        Optional.ofNullable(result.getString("diagnostic_context")),
        effectKind == null
            ? Optional.empty()
            : Optional.of(
                new OperationEffectEvidence(
                    result.getInt("effect_index"),
                    effectKind,
                    result.getString("effect_state"),
                    result.getInt("dispatch_attempts"),
                    Instant.parse(result.getString("effect_updated_at")))),
        Instant.parse(result.getString("created_at")),
        Instant.parse(result.getString("updated_at")),
        optionalInstant(result.getString("completed_at")));
  }

  private static OptionalLong nullableLong(ResultSet result, String column) throws SQLException {
    long value = result.getLong(column);
    return result.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
  }

  private static Optional<Instant> optionalInstant(String value) {
    return value == null ? Optional.empty() : Optional.of(Instant.parse(value));
  }

  private <T> CompletionStage<T> supplyAsync(Supplier<T> work) {
    try {
      return CompletableFuture.supplyAsync(work, databaseExecutor);
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }
}
