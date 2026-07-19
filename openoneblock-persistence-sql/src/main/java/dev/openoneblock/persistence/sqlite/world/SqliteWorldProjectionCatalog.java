package dev.openoneblock.persistence.sqlite.world;

import dev.openoneblock.api.id.DimensionId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.locator.PersistedWorldProjection;
import dev.openoneblock.core.locator.WorldEnvironment;
import dev.openoneblock.core.locator.WorldProjectionAdoptionRequest;
import dev.openoneblock.core.locator.WorldProjectionCatalog;
import dev.openoneblock.core.locator.WorldProjectionDefinition;
import dev.openoneblock.core.locator.WorldProjectionDrift;
import dev.openoneblock.core.locator.WorldProjectionDriftKind;
import dev.openoneblock.core.locator.WorldProjectionState;
import dev.openoneblock.core.locator.WorldProjectionVerification;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.SqliteImmediateTransactions;
import dev.openoneblock.persistence.sqlite.SqlitePersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** SQLite authority for atomic world registration, restart drift detection, and admin adoption. */
public final class SqliteWorldProjectionCatalog implements WorldProjectionCatalog {
  private final SqliteImmediateTransactions transactions;
  private final Executor databaseExecutor;
  private final Clock clock;

  /**
   * Creates an asynchronous projection catalog.
   *
   * @param connectionFactory SQLite connection source
   * @param databaseExecutor shared bounded database executor
   * @param clock registration timestamp source
   */
  public SqliteWorldProjectionCatalog(
      SqliteConnectionFactory connectionFactory, Executor databaseExecutor, Clock clock) {
    this.transactions =
        new SqliteImmediateTransactions(
            Objects.requireNonNull(connectionFactory, "connectionFactory"), 12, 2, 30);
    this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<WorldProjectionVerification> verifyOrRegister(
      List<WorldProjectionDefinition> observed) {
    List<WorldProjectionDefinition> candidate;
    try {
      candidate = validateCandidate(observed);
    } catch (RuntimeException exception) {
      return CompletableFuture.failedFuture(exception);
    }
    try {
      return CompletableFuture.supplyAsync(() -> verifyOrRegisterNow(candidate), databaseExecutor);
    } catch (RuntimeException exception) {
      return CompletableFuture.failedFuture(exception);
    }
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<PersistedWorldProjection> adopt(WorldProjectionAdoptionRequest request) {
    Objects.requireNonNull(request, "request");
    try {
      return CompletableFuture.supplyAsync(() -> adoptNow(request), databaseExecutor);
    } catch (RuntimeException exception) {
      return CompletableFuture.failedFuture(exception);
    }
  }

  private WorldProjectionVerification verifyOrRegisterNow(
      List<WorldProjectionDefinition> observed) {
    try {
      return transactions.execute(
          connection -> {
            List<PersistedWorldProjection> persisted = loadAll(connection);
            if (persisted.isEmpty()) {
              Instant registeredAt = clock.instant();
              for (WorldProjectionDefinition definition : observed) {
                insertProjection(connection, definition, registeredAt);
              }
              return new WorldProjectionVerification.Verified(loadAll(connection));
            }
            List<WorldProjectionDrift> drifts = compare(persisted, observed);
            if (!drifts.isEmpty()) {
              return new WorldProjectionVerification.Drifted(persisted, drifts);
            }
            return new WorldProjectionVerification.Verified(persisted);
          });
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to verify SQLite world projections", exception);
    }
  }

  private PersistedWorldProjection adoptNow(WorldProjectionAdoptionRequest request) {
    try {
      return transactions.execute(
          connection -> {
            PersistedWorldProjection replay = findAdoptionReplay(connection, request);
            if (replay != null) {
              return replay;
            }
            WorldProjectionKey key = WorldProjectionKey.of(request.replacement());
            PersistedWorldProjection current = findProjection(connection, key);
            if (current == null) {
              throw new WorldProjectionAdoptionConflictException(
                  "No persisted projection exists for " + key);
            }
            if (current.version() != request.expectedVersion()) {
              throw new WorldProjectionAdoptionConflictException(
                  "Expected projection version "
                      + request.expectedVersion()
                      + ", found "
                      + current.version());
            }
            long outcomeVersion = Math.addExact(current.version(), 1L);
            PersistedWorldProjection outcome =
                new PersistedWorldProjection(
                    request.replacement(),
                    WorldProjectionState.VERIFIED,
                    outcomeVersion,
                    current.createdAt(),
                    request.requestedAt());
            updateProjection(connection, outcome, request.expectedVersion());
            insertRepair(connection, request, outcome);
            return outcome;
          });
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to adopt SQLite world projection", exception);
    }
  }

  private static List<WorldProjectionDefinition> validateCandidate(
      List<WorldProjectionDefinition> observed) {
    Objects.requireNonNull(observed, "observed");
    if (observed.isEmpty()) {
      throw new IllegalArgumentException("at least one world projection is required");
    }
    Set<WorldProjectionKey> keys = new HashSet<>();
    Set<String> names = new HashSet<>();
    Set<WorldId> worldIds = new HashSet<>();
    Map<ShardGroupId, String> geometryByShard = new HashMap<>();
    for (WorldProjectionDefinition definition : observed) {
      Objects.requireNonNull(definition, "definition");
      if (!keys.add(WorldProjectionKey.of(definition))) {
        throw new IllegalArgumentException("duplicate shard/dimension projection");
      }
      if (!names.add(definition.worldName())) {
        throw new IllegalArgumentException("duplicate configured world name");
      }
      if (!worldIds.add(definition.worldId())) {
        throw new IllegalArgumentException("one world UUID has multiple projections");
      }
      String existingGeometry =
          geometryByShard.putIfAbsent(definition.shardGroupId(), definition.geometryFingerprint());
      if (existingGeometry != null && !existingGeometry.equals(definition.geometryFingerprint())) {
        throw new IllegalArgumentException(
            "dimensions in one shard must share the same slot geometry fingerprint");
      }
    }
    return List.copyOf(observed);
  }

  private static List<WorldProjectionDrift> compare(
      List<PersistedWorldProjection> persisted, List<WorldProjectionDefinition> observed) {
    Map<WorldProjectionKey, PersistedWorldProjection> persistedByKey = new LinkedHashMap<>();
    for (PersistedWorldProjection projection : persisted) {
      persistedByKey.put(WorldProjectionKey.of(projection.definition()), projection);
    }
    Map<WorldProjectionKey, WorldProjectionDefinition> observedByKey = new LinkedHashMap<>();
    for (WorldProjectionDefinition definition : observed) {
      observedByKey.put(WorldProjectionKey.of(definition), definition);
    }

    List<WorldProjectionDrift> drifts = new ArrayList<>();
    for (Map.Entry<WorldProjectionKey, WorldProjectionDefinition> entry :
        observedByKey.entrySet()) {
      PersistedWorldProjection authoritative = persistedByKey.get(entry.getKey());
      if (authoritative == null) {
        drifts.add(
            drift(
                entry.getKey(),
                WorldProjectionDriftKind.MISSING_PERSISTED_PROJECTION,
                "persisted projection",
                "missing"));
        continue;
      }
      compareOne(authoritative, entry.getValue(), drifts);
    }
    for (Map.Entry<WorldProjectionKey, PersistedWorldProjection> entry :
        persistedByKey.entrySet()) {
      if (!observedByKey.containsKey(entry.getKey())) {
        drifts.add(
            drift(
                entry.getKey(),
                WorldProjectionDriftKind.UNCONFIGURED_PERSISTED_PROJECTION,
                "configured projection",
                entry.getValue().definition().worldName()));
      }
    }
    return List.copyOf(drifts);
  }

  private static void compareOne(
      PersistedWorldProjection persisted,
      WorldProjectionDefinition observed,
      List<WorldProjectionDrift> drifts) {
    WorldProjectionDefinition expected = persisted.definition();
    WorldProjectionKey key = WorldProjectionKey.of(expected);
    if (persisted.state() != WorldProjectionState.VERIFIED) {
      drifts.add(
          drift(
              key,
              WorldProjectionDriftKind.PROJECTION_BLOCKED,
              WorldProjectionState.VERIFIED.name(),
              persisted.state().name()));
    }
    addMismatch(
        key,
        WorldProjectionDriftKind.WORLD_NAME_CHANGED,
        expected.worldName(),
        observed.worldName(),
        drifts);
    addMismatch(
        key,
        WorldProjectionDriftKind.WORLD_ID_CHANGED,
        expected.worldId().toString(),
        observed.worldId().toString(),
        drifts);
    addMismatch(
        key,
        WorldProjectionDriftKind.ENVIRONMENT_CHANGED,
        expected.environment().name(),
        observed.environment().name(),
        drifts);
    addMismatch(
        key,
        WorldProjectionDriftKind.GEOMETRY_FINGERPRINT_CHANGED,
        expected.geometryFingerprint(),
        observed.geometryFingerprint(),
        drifts);
  }

  private static void addMismatch(
      WorldProjectionKey key,
      WorldProjectionDriftKind kind,
      String expected,
      String actual,
      List<WorldProjectionDrift> drifts) {
    if (!expected.equals(actual)) {
      drifts.add(drift(key, kind, expected, actual));
    }
  }

  private static WorldProjectionDrift drift(
      WorldProjectionKey key, WorldProjectionDriftKind kind, String expected, String actual) {
    return new WorldProjectionDrift(key.shardGroupId(), key.dimensionId(), kind, expected, actual);
  }

  private static void insertProjection(
      Connection connection, WorldProjectionDefinition definition, Instant registeredAt)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO world_projections (
                shard_group_id, dimension_id, configured_world_name, actual_world_id,
                environment, geometry_fingerprint, state, version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, 'VERIFIED', 0, ?, ?)
            """)) {
      statement.setString(1, definition.shardGroupId().toString());
      statement.setString(2, definition.dimensionId().toString());
      statement.setString(3, definition.worldName());
      statement.setString(4, definition.worldId().toString());
      statement.setString(5, definition.environment().name());
      statement.setString(6, definition.geometryFingerprint());
      statement.setString(7, registeredAt.toString());
      statement.setString(8, registeredAt.toString());
      statement.executeUpdate();
    }
  }

  private static List<PersistedWorldProjection> loadAll(Connection connection) throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT * FROM world_projections
            ORDER BY shard_group_id, dimension_id
            """);
        ResultSet result = statement.executeQuery()) {
      List<PersistedWorldProjection> projections = new ArrayList<>();
      while (result.next()) {
        projections.add(readProjection(result));
      }
      return List.copyOf(projections);
    }
  }

  private static PersistedWorldProjection findProjection(
      Connection connection, WorldProjectionKey key) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT * FROM world_projections
            WHERE shard_group_id = ? AND dimension_id = ?
            """)) {
      statement.setString(1, key.shardGroupId().toString());
      statement.setString(2, key.dimensionId().toString());
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? readProjection(result) : null;
      }
    }
  }

  private static PersistedWorldProjection findAdoptionReplay(
      Connection connection, WorldProjectionAdoptionRequest request) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT * FROM world_projection_repairs WHERE operation_id = ?
            """)) {
      statement.setString(1, request.operationId().toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return null;
        }
        WorldProjectionDefinition replacement = request.replacement();
        if (!replacement.shardGroupId().toString().equals(result.getString("shard_group_id"))
            || !replacement.dimensionId().toString().equals(result.getString("dimension_id"))
            || request.expectedVersion() != result.getLong("expected_version")
            || !replacement.worldName().equals(result.getString("adopted_world_name"))
            || !replacement.worldId().toString().equals(result.getString("adopted_world_id"))
            || !replacement.environment().name().equals(result.getString("adopted_environment"))
            || !replacement
                .geometryFingerprint()
                .equals(result.getString("adopted_geometry_fingerprint"))) {
          throw new WorldProjectionAdoptionConflictException(
              "Operation ID already belongs to another world projection adoption");
        }
        return new PersistedWorldProjection(
            replacement,
            WorldProjectionState.VERIFIED,
            result.getLong("outcome_version"),
            Instant.parse(result.getString("outcome_created_at")),
            Instant.parse(result.getString("completed_at")));
      }
    }
  }

  private static void updateProjection(
      Connection connection, PersistedWorldProjection outcome, long expectedVersion)
      throws SQLException {
    WorldProjectionDefinition definition = outcome.definition();
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE world_projections
            SET configured_world_name = ?, actual_world_id = ?, environment = ?,
                geometry_fingerprint = ?, state = 'VERIFIED', version = ?, updated_at = ?
            WHERE shard_group_id = ? AND dimension_id = ? AND version = ?
            """)) {
      statement.setString(1, definition.worldName());
      statement.setString(2, definition.worldId().toString());
      statement.setString(3, definition.environment().name());
      statement.setString(4, definition.geometryFingerprint());
      statement.setLong(5, outcome.version());
      statement.setString(6, outcome.updatedAt().toString());
      statement.setString(7, definition.shardGroupId().toString());
      statement.setString(8, definition.dimensionId().toString());
      statement.setLong(9, expectedVersion);
      if (statement.executeUpdate() != 1) {
        throw new WorldProjectionAdoptionConflictException(
            "Projection changed concurrently during adoption");
      }
    }
  }

  private static void insertRepair(
      Connection connection,
      WorldProjectionAdoptionRequest request,
      PersistedWorldProjection outcome)
      throws SQLException {
    WorldProjectionDefinition replacement = request.replacement();
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO world_projection_repairs (
                operation_id, shard_group_id, dimension_id, expected_version,
                adopted_world_name, adopted_world_id, adopted_environment,
                adopted_geometry_fingerprint, outcome_version, outcome_created_at, completed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
      statement.setString(1, request.operationId().toString());
      statement.setString(2, replacement.shardGroupId().toString());
      statement.setString(3, replacement.dimensionId().toString());
      statement.setLong(4, request.expectedVersion());
      statement.setString(5, replacement.worldName());
      statement.setString(6, replacement.worldId().toString());
      statement.setString(7, replacement.environment().name());
      statement.setString(8, replacement.geometryFingerprint());
      statement.setLong(9, outcome.version());
      statement.setString(10, outcome.createdAt().toString());
      statement.setString(11, outcome.updatedAt().toString());
      statement.executeUpdate();
    }
  }

  private static PersistedWorldProjection readProjection(ResultSet result) throws SQLException {
    WorldProjectionDefinition definition =
        new WorldProjectionDefinition(
            ShardGroupId.parse(result.getString("shard_group_id")),
            DimensionId.parse(result.getString("dimension_id")),
            result.getString("configured_world_name"),
            WorldId.parse(result.getString("actual_world_id")),
            WorldEnvironment.valueOf(result.getString("environment")),
            result.getString("geometry_fingerprint"));
    return new PersistedWorldProjection(
        definition,
        WorldProjectionState.valueOf(result.getString("state")),
        result.getLong("version"),
        Instant.parse(result.getString("created_at")),
        Instant.parse(result.getString("updated_at")));
  }

  private record WorldProjectionKey(ShardGroupId shardGroupId, DimensionId dimensionId) {
    static WorldProjectionKey of(WorldProjectionDefinition definition) {
      return new WorldProjectionKey(definition.shardGroupId(), definition.dimensionId());
    }
  }
}
