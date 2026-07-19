package dev.openoneblock.persistence.sqlite.island;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.island.IslandAggregateSnapshot;
import dev.openoneblock.core.island.IslandResetActivation;
import dev.openoneblock.core.island.IslandResetCleanupCompletion;
import dev.openoneblock.core.island.IslandResetConflictException;
import dev.openoneblock.core.island.IslandResetPreparationFailure;
import dev.openoneblock.core.island.IslandResetProgress;
import dev.openoneblock.core.island.IslandResetRepository;
import dev.openoneblock.core.island.IslandResetRequest;
import dev.openoneblock.core.locator.CommittedSlotPublisher;
import dev.openoneblock.core.locator.LocatorPublishDecision;
import dev.openoneblock.core.locator.SlotLocatorEntry;
import dev.openoneblock.core.slot.AllocatedSlot;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotState;
import dev.openoneblock.core.world.IslandCleanup;
import dev.openoneblock.core.world.WorldEffectState;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.SqliteImmediateTransactions;
import dev.openoneblock.persistence.sqlite.SqlitePersistenceException;
import dev.openoneblock.persistence.sqlite.slot.CommittedSlotPublicationException;
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
import java.util.function.Supplier;

/** SQLite implementation of exact-version, crash-recoverable island reset. */
public final class SqliteIslandResetRepository implements IslandResetRepository {
  private static final String RESET_KIND = "ISLAND_RESET";
  private static final String SNAPSHOT_COLUMNS =
      """
      i.island_id, i.owner_player_id, i.lifecycle_state, i.current_border_size,
      i.maximum_border_size, i.version AS island_version, i.pending_operation_id,
      i.created_at AS island_created_at, i.updated_at AS island_updated_at,
      s.slot_id, s.shard_group_id, s.ordinal, s.grid_x, s.grid_z,
      s.state AS slot_state, s.owner_island_id, s.version AS slot_version
      """;

  private final SqliteImmediateTransactions transactions;
  private final CommittedSlotPublisher locator;
  private final Executor databaseExecutor;

  /**
   * Creates a reset repository.
   *
   * @param connectionFactory SQLite connection source
   * @param locator post-commit runtime slot projection
   * @param databaseExecutor executor reserved for SQL work
   */
  public SqliteIslandResetRepository(
      SqliteConnectionFactory connectionFactory,
      CommittedSlotPublisher locator,
      Executor databaseExecutor) {
    this.transactions =
        new SqliteImmediateTransactions(
            Objects.requireNonNull(connectionFactory, "connectionFactory"), 12, 2, 30);
    this.locator = Objects.requireNonNull(locator, "locator");
    this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<IslandResetProgress> beginReset(IslandResetRequest request) {
    Objects.requireNonNull(request, "request");
    return supplyAsync(() -> beginAndPublish(request));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<IslandResetProgress> completeCleanup(
      IslandResetCleanupCompletion completion) {
    Objects.requireNonNull(completion, "completion");
    return supplyAsync(() -> cleanupAndPublish(completion));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<IslandResetProgress> beginPreparationFailure(
      IslandResetPreparationFailure failure) {
    Objects.requireNonNull(failure, "failure");
    return supplyAsync(() -> preparationFailureAndPublish(failure));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<IslandResetProgress> activateReset(IslandResetActivation activation) {
    Objects.requireNonNull(activation, "activation");
    return supplyAsync(() -> activateAndPublish(activation));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<List<IslandResetRequest>> findPendingResets() {
    return supplyAsync(this::findPending);
  }

  private IslandResetProgress beginAndPublish(IslandResetRequest request) {
    try {
      Transition transition = transactions.execute(connection -> begin(connection, request));
      publish(transition.progress().island().primarySlot().orElseThrow());
      return transition.progress();
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to begin SQLite island reset", exception);
    }
  }

  private IslandResetProgress cleanupAndPublish(IslandResetCleanupCompletion completion) {
    try {
      Transition transition =
          transactions.execute(connection -> completeCleanup(connection, completion));
      publish(transition.progress().island().primarySlot().orElseThrow());
      return transition.progress();
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to advance SQLite reset cleanup", exception);
    }
  }

  private IslandResetProgress preparationFailureAndPublish(IslandResetPreparationFailure failure) {
    try {
      Transition transition =
          transactions.execute(connection -> beginPreparationFailure(connection, failure));
      publish(transition.progress().island().primarySlot().orElseThrow());
      return transition.progress();
    } catch (SQLException exception) {
      throw new SqlitePersistenceException(
          "Failed to begin SQLite reset failure cleanup", exception);
    }
  }

  private IslandResetProgress activateAndPublish(IslandResetActivation activation) {
    try {
      Transition transition = transactions.execute(connection -> activate(connection, activation));
      publish(transition.progress().island().primarySlot().orElseThrow());
      return transition.progress();
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to activate SQLite island reset", exception);
    }
  }

  private static Transition begin(Connection connection, IslandResetRequest request)
      throws SQLException {
    Optional<OperationRow> existing = findOperation(connection, request.operationId());
    if (existing.isPresent()) {
      OperationRow operation = existing.orElseThrow();
      validateExisting(operation, request);
      return new Transition(progress(connection, operation, true));
    }
    IslandAggregateSnapshot current =
        findSnapshot(connection, request.islandId())
            .orElseThrow(() -> new IslandResetConflictException("Unknown island"));
    if (!current.ownerId().equals(request.requestedBy())
        || !hasActiveOwnerMembership(connection, request.islandId(), request.requestedBy())) {
      throw new IslandResetConflictException("Reset requires the active island owner");
    }
    if (current.lifecycleState() != IslandLifecycleState.ACTIVE) {
      throw new IslandResetConflictException("Only an ACTIVE island can begin reset");
    }
    if (current.version() != request.expectedIslandVersion()) {
      throw new IslandResetConflictException("Confirmation island version is stale");
    }
    AllocatedSlot slot = current.primarySlot().orElseThrow();
    if (slot.state() != SlotState.ACTIVE) {
      throw new IslandResetConflictException("Active island slot is not ACTIVE");
    }
    insertOperation(connection, request, slot.slotId());
    insertContext(connection, request);
    updateIslandState(
        connection,
        request.islandId(),
        request.operationId(),
        IslandLifecycleState.ACTIVE,
        IslandLifecycleState.RESETTING,
        request.expectedIslandVersion(),
        "openoneblock:reset",
        request.requestedAt());
    updateSlotState(
        connection,
        slot,
        request.islandId(),
        SlotState.ACTIVE,
        SlotState.CLEANING,
        slot.version(),
        request.requestedAt());
    return new Transition(
        new IslandResetProgress(
            findSnapshot(connection, request.islandId()).orElseThrow(),
            IslandResetProgress.Stage.CLEANING_INITIAL,
            false,
            ""));
  }

  private static Transition completeCleanup(
      Connection connection, IslandResetCleanupCompletion completion) throws SQLException {
    OperationRow operation = requireOperation(connection, completion.operationId());
    requireOperationIdentity(operation, completion.islandId());
    String expectedState =
        completion.cleanupStage() == IslandResetProgress.Stage.CLEANING_INITIAL
            ? "CLEANING_WORLD"
            : "CLEANING_FAILED_WORLD";
    if (!operation.state().equals(expectedState)) {
      IslandResetProgress replay = progress(connection, operation, true);
      if (replay.stage() != completion.cleanupStage()) {
        return new Transition(replay);
      }
      throw new IslandResetConflictException("Reset cleanup operation changed unexpectedly");
    }
    IslandAggregateSnapshot current = requireResettingSnapshot(connection, completion.islandId());
    AllocatedSlot slot = current.primarySlot().orElseThrow();
    requireVersions(
        current, slot, completion.expectedIslandVersion(), completion.expectedSlotVersion());
    if (slot.state() != SlotState.CLEANING
        || current.pendingOperationId().filter(completion.operationId()::equals).isEmpty()) {
      throw new IslandResetConflictException("Reset is not awaiting cleanup evidence");
    }

    if (completion.cleanupStage() == IslandResetProgress.Stage.CLEANING_INITIAL
        && completion.status() == IslandCleanup.Status.VERIFIED_CLEAN) {
      updateIslandVersionOnly(connection, completion);
      updateSlotState(
          connection,
          slot,
          completion.islandId(),
          SlotState.CLEANING,
          SlotState.PREPARING,
          completion.expectedSlotVersion(),
          completion.completedAt());
      updateOperationStage(
          connection,
          completion.operationId(),
          "CLEANING_WORLD",
          "PREPARING_WORLD",
          null,
          completion.completedAt());
      return new Transition(
          new IslandResetProgress(
              requireResettingSnapshot(connection, completion.islandId()),
              IslandResetProgress.Stage.PREPARING,
              false,
              ""));
    }

    String outcome = completion.status() == IslandCleanup.Status.AMBIGUOUS ? "AMBIGUOUS" : "FAILED";
    quarantine(connection, current, slot, completion, expectedState, outcome);
    return new Transition(
        progress(connection, requireOperation(connection, completion.operationId()), false));
  }

  private static Transition beginPreparationFailure(
      Connection connection, IslandResetPreparationFailure failure) throws SQLException {
    OperationRow operation = requireOperation(connection, failure.operationId());
    requireOperationIdentity(operation, failure.islandId());
    if (!operation.state().equals("PREPARING_WORLD")) {
      IslandResetProgress replay = progress(connection, operation, true);
      if (replay.stage() == IslandResetProgress.Stage.CLEANING_FAILURE || replay.terminal()) {
        return new Transition(replay);
      }
      throw new IslandResetConflictException("Reset is not in preparation");
    }
    IslandAggregateSnapshot current = requireResettingSnapshot(connection, failure.islandId());
    AllocatedSlot slot = current.primarySlot().orElseThrow();
    requireVersions(current, slot, failure.expectedIslandVersion(), failure.expectedSlotVersion());
    if (slot.state() != SlotState.PREPARING
        || current.pendingOperationId().filter(failure.operationId()::equals).isEmpty()) {
      throw new IslandResetConflictException("Reset preparation state changed");
    }
    updateIslandVersionAndLock(
        connection,
        failure.islandId(),
        failure.operationId(),
        failure.expectedIslandVersion(),
        "openoneblock:reset-preparation-failed",
        failure.failedAt());
    updateSlotState(
        connection,
        slot,
        failure.islandId(),
        SlotState.PREPARING,
        SlotState.CLEANING,
        failure.expectedSlotVersion(),
        failure.failedAt());
    updateOperationStage(
        connection,
        failure.operationId(),
        "PREPARING_WORLD",
        "CLEANING_FAILED_WORLD",
        failure.diagnostic(),
        failure.failedAt());
    return new Transition(
        new IslandResetProgress(
            requireResettingSnapshot(connection, failure.islandId()),
            IslandResetProgress.Stage.CLEANING_FAILURE,
            false,
            failure.diagnostic()));
  }

  private static Transition activate(Connection connection, IslandResetActivation activation)
      throws SQLException {
    OperationRow operation = requireOperation(connection, activation.operationId());
    requireOperationIdentity(operation, activation.islandId());
    if (operation.completedWith("SUCCEEDED")) {
      IslandResetProgress replay = progress(connection, operation, true);
      verifyActivationProjection(connection, activation);
      return new Transition(replay);
    }
    if (!operation.state().equals("PREPARING_WORLD") || operation.outcomeState() != null) {
      throw new IslandResetConflictException("Reset is not awaiting activation");
    }
    IslandAggregateSnapshot current = requireResettingSnapshot(connection, activation.islandId());
    AllocatedSlot slot = current.primarySlot().orElseThrow();
    requireVersions(
        current, slot, activation.expectedIslandVersion(), activation.expectedSlotVersion());
    if (slot.state() != SlotState.PREPARING
        || current.pendingOperationId().filter(activation.operationId()::equals).isEmpty()) {
      throw new IslandResetConflictException("Reset preparation state changed before activation");
    }
    verifyRequiredEffects(connection, activation);
    deleteResettableProjections(connection, activation.islandId());
    insertActivationProjections(connection, activation);
    activateIsland(connection, activation);
    activateSlot(connection, activation, slot);
    completeSuccessOperation(connection, activation);
    IslandResetProgress completed =
        progress(connection, requireOperation(connection, activation.operationId()), false);
    verifyActivationProjection(connection, activation);
    return new Transition(completed);
  }

  private List<IslandResetRequest> findPending() {
    try {
      return transactions.execute(SqliteIslandResetRepository::findPendingInTransaction);
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to query pending island resets", exception);
    }
  }

  private static List<IslandResetRequest> findPendingInTransaction(Connection connection)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT c.operation_id, o.island_id, c.requested_by_player_id,
                   c.expected_island_version, c.target_world_id, c.target_phase_id,
                   c.target_profile_id, c.starter_block_id, c.magic_block_y,
                   c.minimum_y, c.maximum_y_exclusive, c.requested_at
            FROM island_lifecycle_operation_contexts c
            JOIN operations o ON o.operation_id = c.operation_id
            JOIN islands i ON i.island_id = o.island_id
            JOIN slots s ON s.slot_id = i.primary_slot_id
            WHERE c.operation_kind = 'ISLAND_RESET' AND o.kind = 'ISLAND_RESET'
              AND o.state IN ('CLEANING_WORLD', 'PREPARING_WORLD', 'CLEANING_FAILED_WORLD')
              AND o.outcome_state IS NULL
              AND i.lifecycle_state = 'RESETTING'
              AND s.state IN ('CLEANING', 'PREPARING')
            ORDER BY c.requested_at, c.operation_id
            """)) {
      try (ResultSet result = statement.executeQuery()) {
        List<IslandResetRequest> requests = new ArrayList<>();
        while (result.next()) {
          requests.add(readRequest(result));
        }
        return List.copyOf(requests);
      }
    } catch (IllegalArgumentException exception) {
      throw new SQLException("Invalid persisted reset recovery context", exception);
    }
  }

  private static IslandResetRequest readRequest(ResultSet result) throws SQLException {
    return new IslandResetRequest(
        IslandId.parse(result.getString("island_id")),
        OperationId.parse(result.getString("operation_id")),
        PlayerId.parse(result.getString("requested_by_player_id")),
        result.getLong("expected_island_version"),
        WorldId.parse(result.getString("target_world_id")),
        NamespacedId.parse(result.getString("target_phase_id")),
        NamespacedId.parse(result.getString("target_profile_id")),
        NamespacedId.parse(result.getString("starter_block_id")),
        result.getInt("magic_block_y"),
        result.getInt("minimum_y"),
        result.getInt("maximum_y_exclusive"),
        Instant.parse(result.getString("requested_at")));
  }

  private static void insertOperation(
      Connection connection, IslandResetRequest request, SlotId slotId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO operations (
                operation_id, island_id, kind, state, slot_id, request_fingerprint,
                outcome_state, outcome_payload, completed_at, created_at, updated_at
            ) VALUES (?, ?, 'ISLAND_RESET', 'CLEANING_WORLD', ?, ?, NULL, NULL, NULL, ?, ?)
            """)) {
      statement.setString(1, request.operationId().toString());
      statement.setString(2, request.islandId().toString());
      statement.setString(3, slotId.toString());
      statement.setString(4, request.fingerprint());
      statement.setString(5, request.requestedAt().toString());
      statement.setString(6, request.requestedAt().toString());
      requireOne(statement, "Reset operation was not inserted");
    }
  }

  private static void insertContext(Connection connection, IslandResetRequest request)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO island_lifecycle_operation_contexts (
                operation_id, operation_kind, requested_by_player_id,
                expected_island_version, minimum_y, maximum_y_exclusive,
                target_world_id, target_phase_id, target_profile_id,
                starter_block_id, magic_block_y, requested_at
            ) VALUES (?, 'ISLAND_RESET', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
      statement.setString(1, request.operationId().toString());
      statement.setString(2, request.requestedBy().toString());
      statement.setLong(3, request.expectedIslandVersion());
      statement.setInt(4, request.minimumY());
      statement.setInt(5, request.maximumYExclusive());
      statement.setString(6, request.primaryWorldId().toString());
      statement.setString(7, request.phaseId().toString());
      statement.setString(8, request.profileId().toString());
      statement.setString(9, request.starterBlockId().toString());
      statement.setInt(10, request.magicBlockY());
      statement.setString(11, request.requestedAt().toString());
      requireOne(statement, "Reset context was not inserted");
    }
  }

  private static void updateIslandState(
      Connection connection,
      IslandId islandId,
      OperationId operationId,
      IslandLifecycleState from,
      IslandLifecycleState to,
      long expectedVersion,
      String lockReason,
      Instant at)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE islands SET lifecycle_state = ?, pending_operation_id = ?,
                lifecycle_lock_reason = ?, version = version + 1, updated_at = ?
            WHERE island_id = ? AND lifecycle_state = ? AND version = ?
            """)) {
      statement.setString(1, to.name());
      statement.setString(2, operationId.toString());
      statement.setString(3, lockReason);
      statement.setString(4, at.toString());
      statement.setString(5, islandId.toString());
      statement.setString(6, from.name());
      statement.setLong(7, expectedVersion);
      requireOne(statement, "Island changed before reset transition");
    }
  }

  private static void updateIslandVersionOnly(
      Connection connection, IslandResetCleanupCompletion completion) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE islands SET version = version + 1, updated_at = ?
            WHERE island_id = ? AND lifecycle_state = 'RESETTING'
              AND pending_operation_id = ? AND version = ?
            """)) {
      statement.setString(1, completion.completedAt().toString());
      statement.setString(2, completion.islandId().toString());
      statement.setString(3, completion.operationId().toString());
      statement.setLong(4, completion.expectedIslandVersion());
      requireOne(statement, "Island changed after initial reset cleanup");
    }
  }

  private static void updateIslandVersionAndLock(
      Connection connection,
      IslandId islandId,
      OperationId operationId,
      long expectedVersion,
      String reason,
      Instant at)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE islands SET lifecycle_lock_reason = ?, version = version + 1, updated_at = ?
            WHERE island_id = ? AND lifecycle_state = 'RESETTING'
              AND pending_operation_id = ? AND version = ?
            """)) {
      statement.setString(1, reason);
      statement.setString(2, at.toString());
      statement.setString(3, islandId.toString());
      statement.setString(4, operationId.toString());
      statement.setLong(5, expectedVersion);
      requireOne(statement, "Island changed during reset preparation failure");
    }
  }

  private static void updateSlotState(
      Connection connection,
      AllocatedSlot slot,
      IslandId islandId,
      SlotState from,
      SlotState to,
      long expectedVersion,
      Instant at)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE slots SET state = ?, version = version + 1, updated_at = ?
            WHERE slot_id = ? AND owner_island_id = ? AND state = ? AND version = ?
            """)) {
      statement.setString(1, to.name());
      statement.setString(2, at.toString());
      statement.setString(3, slot.slotId().toString());
      statement.setString(4, islandId.toString());
      statement.setString(5, from.name());
      statement.setLong(6, expectedVersion);
      requireOne(statement, "Slot changed during reset transition");
    }
  }

  private static void updateOperationStage(
      Connection connection,
      OperationId operationId,
      String from,
      String to,
      String diagnostic,
      Instant at)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE operations SET state = ?, outcome_payload = ?, updated_at = ?
            WHERE operation_id = ? AND kind = 'ISLAND_RESET' AND state = ?
              AND outcome_state IS NULL
            """)) {
      statement.setString(1, to);
      statement.setString(2, diagnostic);
      statement.setString(3, at.toString());
      statement.setString(4, operationId.toString());
      statement.setString(5, from);
      requireOne(statement, "Reset operation changed during stage transition");
    }
  }

  private static void quarantine(
      Connection connection,
      IslandAggregateSnapshot island,
      AllocatedSlot slot,
      IslandResetCleanupCompletion completion,
      String operationState,
      String outcome)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE islands SET lifecycle_state = 'BROKEN', pending_operation_id = NULL,
                lifecycle_lock_reason = 'openoneblock:reset-failed',
                version = version + 1, updated_at = ?
            WHERE island_id = ? AND lifecycle_state = 'RESETTING'
              AND pending_operation_id = ? AND version = ?
            """)) {
      statement.setString(1, completion.completedAt().toString());
      statement.setString(2, completion.islandId().toString());
      statement.setString(3, completion.operationId().toString());
      statement.setLong(4, island.version());
      requireOne(statement, "Island changed while quarantining reset");
    }
    updateSlotState(
        connection,
        slot,
        completion.islandId(),
        SlotState.CLEANING,
        SlotState.QUARANTINED,
        slot.version(),
        completion.completedAt());
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE operations SET state = 'COMPLETED', outcome_state = ?, outcome_payload = ?,
                completed_at = ?, updated_at = ?
            WHERE operation_id = ? AND island_id = ? AND kind = 'ISLAND_RESET'
              AND state = ? AND outcome_state IS NULL
            """)) {
      statement.setString(1, outcome);
      statement.setString(2, completion.diagnostic());
      statement.setString(3, completion.completedAt().toString());
      statement.setString(4, completion.completedAt().toString());
      statement.setString(5, completion.operationId().toString());
      statement.setString(6, completion.islandId().toString());
      statement.setString(7, operationState);
      requireOne(statement, "Reset operation changed while quarantining");
    }
  }

  private static void verifyRequiredEffects(Connection connection, IslandResetActivation activation)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT island_id, state FROM world_effect_receipts
            WHERE operation_id = ? AND effect_index = ?
            """)) {
      for (var key : activation.requiredEffects()) {
        statement.setString(1, key.operationId().toString());
        statement.setInt(2, key.effectIndex());
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next()
              || !activation.islandId().toString().equals(result.getString("island_id"))
              || !WorldEffectState.VERIFIED_SUCCESS.name().equals(result.getString("state"))) {
            throw new IslandResetConflictException(
                "Reset activation requires every world effect to be verified");
          }
        }
      }
    }
  }

  private static void deleteResettableProjections(Connection connection, IslandId islandId)
      throws SQLException {
    for (String sql :
        List.of(
            "DELETE FROM magic_blocks WHERE island_id = ?",
            "DELETE FROM island_spawn_points WHERE island_id = ?",
            "DELETE FROM island_progression WHERE island_id = ?",
            "DELETE FROM island_phase_history WHERE island_id = ?",
            "DELETE FROM counters WHERE scope_type = 'ISLAND' AND scope_id = ?",
            "DELETE FROM typed_variables WHERE scope_type = 'ISLAND' AND scope_id = ?")) {
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, islandId.toString());
        statement.executeUpdate();
      }
    }
  }

  private static void insertActivationProjections(
      Connection connection, IslandResetActivation activation) throws SQLException {
    var spawn = activation.primarySpawn();
    var spawnPosition = spawn.position();
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO island_spawn_points (
                island_id, spawn_id, world_id, x, y, z, yaw, pitch,
                primary_spawn, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
            """)) {
      statement.setString(1, activation.islandId().toString());
      statement.setString(2, spawn.spawnId().toString());
      statement.setString(3, spawnPosition.worldId().toString());
      statement.setDouble(4, spawnPosition.x());
      statement.setDouble(5, spawnPosition.y());
      statement.setDouble(6, spawnPosition.z());
      statement.setFloat(7, spawnPosition.yaw());
      statement.setFloat(8, spawnPosition.pitch());
      statement.setString(9, activation.activatedAt().toString());
      statement.setString(10, activation.activatedAt().toString());
      requireOne(statement, "Reset spawn was not inserted");
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO island_progression (
                island_id, current_phase_id, version, created_at, updated_at
            ) VALUES (?, ?, 0, ?, ?)
            """)) {
      statement.setString(1, activation.islandId().toString());
      statement.setString(2, activation.initialPhaseId().toString());
      statement.setString(3, activation.activatedAt().toString());
      statement.setString(4, activation.activatedAt().toString());
      requireOne(statement, "Reset progression was not inserted");
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO island_phase_history (
                island_id, sequence, phase_id, entered_at, left_at, transition_operation_id
            ) VALUES (?, 0, ?, ?, NULL, ?)
            """)) {
      statement.setString(1, activation.islandId().toString());
      statement.setString(2, activation.initialPhaseId().toString());
      statement.setString(3, activation.activatedAt().toString());
      statement.setString(4, activation.operationId().toString());
      requireOne(statement, "Reset phase history was not inserted");
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO counters (
                scope_type, scope_id, counter_id, value, version, created_at, updated_at
            ) VALUES ('ISLAND', ?, ?, 0, 0, ?, ?)
            """)) {
      for (String counter : List.of("openoneblock:total_breaks", "openoneblock:phase_breaks")) {
        statement.setString(1, activation.islandId().toString());
        statement.setString(2, counter);
        statement.setString(3, activation.activatedAt().toString());
        statement.setString(4, activation.activatedAt().toString());
        statement.addBatch();
      }
      if (statement.executeBatch().length != 2) {
        throw new SQLException("Reset counters were not inserted completely");
      }
    }
    var magic = activation.magicBlock();
    var magicPosition = magic.position();
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO magic_blocks (
                island_id, magic_block_id, world_id, block_x, block_y, block_z,
                profile_id, current_content_id, state, sequence, last_persisted_sequence,
                cooldown_until, version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'READY', 0, 0, NULL, 0, ?, ?)
            """)) {
      statement.setString(1, activation.islandId().toString());
      statement.setString(2, magic.magicBlockId().toString());
      statement.setString(3, magicPosition.worldId().toString());
      statement.setInt(4, magicPosition.x());
      statement.setInt(5, magicPosition.y());
      statement.setInt(6, magicPosition.z());
      statement.setString(7, magic.profileId().toString());
      statement.setString(8, magic.currentContentId().toString());
      statement.setString(9, activation.activatedAt().toString());
      statement.setString(10, activation.activatedAt().toString());
      requireOne(statement, "Reset Magic Block was not inserted");
    }
  }

  private static void activateIsland(Connection connection, IslandResetActivation activation)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE islands SET lifecycle_state = 'ACTIVE', pending_operation_id = NULL,
                lifecycle_lock_reason = NULL, version = version + 1, updated_at = ?
            WHERE island_id = ? AND lifecycle_state = 'RESETTING'
              AND pending_operation_id = ? AND version = ?
            """)) {
      statement.setString(1, activation.activatedAt().toString());
      statement.setString(2, activation.islandId().toString());
      statement.setString(3, activation.operationId().toString());
      statement.setLong(4, activation.expectedIslandVersion());
      requireOne(statement, "Island changed during reset activation");
    }
  }

  private static void activateSlot(
      Connection connection, IslandResetActivation activation, AllocatedSlot slot)
      throws SQLException {
    updateSlotState(
        connection,
        slot,
        activation.islandId(),
        SlotState.PREPARING,
        SlotState.ACTIVE,
        activation.expectedSlotVersion(),
        activation.activatedAt());
  }

  private static void completeSuccessOperation(
      Connection connection, IslandResetActivation activation) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE operations SET state = 'COMPLETED', outcome_state = 'SUCCEEDED',
                outcome_payload = ?, completed_at = ?, updated_at = ?
            WHERE operation_id = ? AND island_id = ? AND kind = 'ISLAND_RESET'
              AND state = 'PREPARING_WORLD' AND outcome_state IS NULL
            """)) {
      statement.setString(1, activation.islandId().toString());
      statement.setString(2, activation.activatedAt().toString());
      statement.setString(3, activation.activatedAt().toString());
      statement.setString(4, activation.operationId().toString());
      statement.setString(5, activation.islandId().toString());
      requireOne(statement, "Reset operation changed during activation");
    }
  }

  private static void verifyActivationProjection(
      Connection connection, IslandResetActivation activation) throws SQLException {
    verifyRequiredEffects(connection, activation);
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT
              (SELECT COUNT(*) FROM island_spawn_points
               WHERE island_id = ? AND spawn_id = ? AND primary_spawn = 1) AS spawn_count,
              (SELECT COUNT(*) FROM island_progression
               WHERE island_id = ? AND current_phase_id = ?) AS progression_count,
              (SELECT COUNT(*) FROM island_phase_history
               WHERE island_id = ? AND sequence = 0 AND phase_id = ?
                 AND left_at IS NULL AND transition_operation_id = ?) AS history_count,
              (SELECT COUNT(*) FROM counters
               WHERE scope_type = 'ISLAND' AND scope_id = ? AND value = 0
                 AND counter_id IN ('openoneblock:total_breaks', 'openoneblock:phase_breaks'))
                 AS counter_count,
              (SELECT COUNT(*) FROM magic_blocks
               WHERE island_id = ? AND magic_block_id = ? AND sequence = 0
                 AND profile_id = ? AND current_content_id = ?) AS magic_count
            """)) {
      int parameter = 1;
      statement.setString(parameter++, activation.islandId().toString());
      statement.setString(parameter++, activation.primarySpawn().spawnId().toString());
      statement.setString(parameter++, activation.islandId().toString());
      statement.setString(parameter++, activation.initialPhaseId().toString());
      statement.setString(parameter++, activation.islandId().toString());
      statement.setString(parameter++, activation.initialPhaseId().toString());
      statement.setString(parameter++, activation.operationId().toString());
      statement.setString(parameter++, activation.islandId().toString());
      statement.setString(parameter++, activation.islandId().toString());
      statement.setString(parameter++, activation.magicBlock().magicBlockId().toString());
      statement.setString(parameter++, activation.magicBlock().profileId().toString());
      statement.setString(parameter, activation.magicBlock().currentContentId().toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()
            || result.getInt("spawn_count") != 1
            || result.getInt("progression_count") != 1
            || result.getInt("history_count") != 1
            || result.getInt("counter_count") != 2
            || result.getInt("magic_count") != 1) {
          throw new IslandResetConflictException(
              "Reset activation projection does not replay exactly");
        }
      }
    }
  }

  private static IslandResetProgress progress(
      Connection connection, OperationRow operation, boolean replay) throws SQLException {
    IslandAggregateSnapshot island =
        findSnapshot(connection, operation.islandId())
            .orElseThrow(() -> new SQLException("Reset operation island disappeared"));
    IslandResetProgress.Stage stage;
    String diagnostic = Objects.requireNonNullElse(operation.outcomePayload(), "");
    if (operation.state().equals("COMPLETED")) {
      stage =
          switch (Objects.requireNonNull(operation.outcomeState(), "reset outcome")) {
            case "SUCCEEDED" -> IslandResetProgress.Stage.SUCCEEDED;
            case "FAILED" -> IslandResetProgress.Stage.FAILED;
            case "AMBIGUOUS" -> IslandResetProgress.Stage.AMBIGUOUS;
            default -> throw new SQLException("Unsupported reset outcome");
          };
      if (diagnostic.isBlank()) {
        diagnostic =
            stage == IslandResetProgress.Stage.SUCCEEDED ? "reset activated" : "missing evidence";
      }
    } else {
      stage =
          switch (operation.state()) {
            case "CLEANING_WORLD" -> IslandResetProgress.Stage.CLEANING_INITIAL;
            case "PREPARING_WORLD" -> IslandResetProgress.Stage.PREPARING;
            case "CLEANING_FAILED_WORLD" -> IslandResetProgress.Stage.CLEANING_FAILURE;
            default -> throw new SQLException("Unsupported pending reset stage");
          };
    }
    return new IslandResetProgress(island, stage, replay, diagnostic);
  }

  private static OperationRow requireOperation(Connection connection, OperationId operationId)
      throws SQLException {
    return findOperation(connection, operationId)
        .orElseThrow(() -> new IslandResetConflictException("Unknown reset operation"));
  }

  private static Optional<OperationRow> findOperation(
      Connection connection, OperationId operationId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT operation_id, island_id, kind, state, request_fingerprint,
                   outcome_state, outcome_payload
            FROM operations WHERE operation_id = ?
            """)) {
      statement.setString(1, operationId.toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new OperationRow(
                OperationId.parse(result.getString("operation_id")),
                IslandId.parse(result.getString("island_id")),
                result.getString("kind"),
                result.getString("state"),
                result.getString("request_fingerprint"),
                result.getString("outcome_state"),
                result.getString("outcome_payload")));
      }
    }
  }

  private static void validateExisting(OperationRow operation, IslandResetRequest request) {
    if (!operation.kind().equals(RESET_KIND)
        || !operation.islandId().equals(request.islandId())
        || !Objects.equals(operation.fingerprint(), request.fingerprint())) {
      throw new IslandResetConflictException(
          "Operation ID already belongs to a different reset intent");
    }
  }

  private static void requireOperationIdentity(OperationRow operation, IslandId islandId) {
    if (!operation.kind().equals(RESET_KIND) || !operation.islandId().equals(islandId)) {
      throw new IslandResetConflictException("Operation is not this island reset");
    }
  }

  private static IslandAggregateSnapshot requireResettingSnapshot(
      Connection connection, IslandId islandId) throws SQLException {
    IslandAggregateSnapshot snapshot =
        findSnapshot(connection, islandId)
            .orElseThrow(() -> new IslandResetConflictException("Reset island disappeared"));
    if (snapshot.lifecycleState() != IslandLifecycleState.RESETTING) {
      throw new IslandResetConflictException("Island is not RESETTING");
    }
    return snapshot;
  }

  private static Optional<IslandAggregateSnapshot> findSnapshot(
      Connection connection, IslandId islandId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT "
                + SNAPSHOT_COLUMNS
                + " FROM islands i LEFT JOIN slots s ON s.slot_id = i.primary_slot_id"
                + " WHERE i.island_id = ?")) {
      statement.setString(1, islandId.toString());
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? Optional.of(mapSnapshot(result)) : Optional.empty();
      }
    }
  }

  private static IslandAggregateSnapshot mapSnapshot(ResultSet result) throws SQLException {
    String slotId = result.getString("slot_id");
    Optional<AllocatedSlot> slot =
        slotId == null
            ? Optional.empty()
            : Optional.of(
                new AllocatedSlot(
                    SlotId.parse(slotId),
                    ShardGroupId.parse(result.getString("shard_group_id")),
                    result.getLong("ordinal"),
                    new GridPosition(result.getInt("grid_x"), result.getInt("grid_z")),
                    SlotState.valueOf(result.getString("slot_state")),
                    IslandId.parse(result.getString("owner_island_id")),
                    result.getLong("slot_version")));
    String pending = result.getString("pending_operation_id");
    return new IslandAggregateSnapshot(
        IslandId.parse(result.getString("island_id")),
        PlayerId.parse(result.getString("owner_player_id")),
        IslandLifecycleState.valueOf(result.getString("lifecycle_state")),
        slot,
        result.getInt("current_border_size"),
        result.getInt("maximum_border_size"),
        result.getLong("island_version"),
        pending == null ? Optional.empty() : Optional.of(OperationId.parse(pending)),
        Instant.parse(result.getString("island_created_at")),
        Instant.parse(result.getString("island_updated_at")));
  }

  private static void requireVersions(
      IslandAggregateSnapshot island,
      AllocatedSlot slot,
      long expectedIslandVersion,
      long expectedSlotVersion) {
    if (island.version() != expectedIslandVersion || slot.version() != expectedSlotVersion) {
      throw new IslandResetConflictException("Reset evidence uses stale island or slot versions");
    }
  }

  private static boolean hasActiveOwnerMembership(
      Connection connection, IslandId islandId, PlayerId playerId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT 1 FROM island_memberships
            WHERE island_id = ? AND player_id = ? AND active = 1 AND owner = 1
            """)) {
      statement.setString(1, islandId.toString());
      statement.setString(2, playerId.toString());
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private <T> CompletionStage<T> supplyAsync(Supplier<T> supplier) {
    try {
      return CompletableFuture.supplyAsync(supplier, databaseExecutor);
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  private void publish(AllocatedSlot slot) {
    try {
      LocatorPublishDecision decision = locator.publishCommitted(locatorEntry(slot));
      if (decision == LocatorPublishDecision.CONFLICTED) {
        throw new IllegalStateException("Committed reset slot conflicts with locator");
      }
    } catch (RuntimeException failure) {
      throw new CommittedSlotPublicationException(slot, failure);
    }
  }

  private static SlotLocatorEntry locatorEntry(AllocatedSlot slot) {
    return new SlotLocatorEntry(
        slot.shardGroupId(),
        slot.gridPosition(),
        slot.slotId(),
        slot.ownerIslandId(),
        slot.state(),
        slot.version());
  }

  private static void requireOne(PreparedStatement statement, String message) throws SQLException {
    if (statement.executeUpdate() != 1) {
      throw new IslandResetConflictException(message);
    }
  }

  private record OperationRow(
      OperationId operationId,
      IslandId islandId,
      String kind,
      String state,
      String fingerprint,
      String outcomeState,
      String outcomePayload) {
    private boolean completedWith(String outcome) {
      return state.equals("COMPLETED") && Objects.equals(outcomeState, outcome);
    }
  }

  private record Transition(IslandResetProgress progress) {}
}
