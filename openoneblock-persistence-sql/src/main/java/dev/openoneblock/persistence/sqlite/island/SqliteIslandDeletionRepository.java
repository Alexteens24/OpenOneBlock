package dev.openoneblock.persistence.sqlite.island;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.island.IslandAggregateSnapshot;
import dev.openoneblock.core.island.IslandDeletionCompletion;
import dev.openoneblock.core.island.IslandDeletionConflictException;
import dev.openoneblock.core.island.IslandDeletionProgress;
import dev.openoneblock.core.island.IslandDeletionRepository;
import dev.openoneblock.core.island.IslandDeletionRequest;
import dev.openoneblock.core.locator.CommittedSlotPublisher;
import dev.openoneblock.core.locator.LocatorPublishDecision;
import dev.openoneblock.core.locator.LocatorRemovalDecision;
import dev.openoneblock.core.locator.SlotLocatorEntry;
import dev.openoneblock.core.slot.AllocatedSlot;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotState;
import dev.openoneblock.core.world.IslandCleanup;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.SqliteImmediateTransactions;
import dev.openoneblock.persistence.sqlite.SqlitePersistenceException;
import dev.openoneblock.persistence.sqlite.slot.CommittedSlotPublicationException;
import dev.openoneblock.protection.CommittedIslandProtectionPublisher;
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

/** SQLite implementation of crash-safe owner-authorized island deletion. */
public final class SqliteIslandDeletionRepository implements IslandDeletionRepository {
  private static final String DELETE_KIND = "ISLAND_DELETE";
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
  private final CommittedIslandProtectionPublisher protectionPublisher;
  private final Executor databaseExecutor;

  /**
   * Creates the deletion repository.
   *
   * @param connectionFactory SQLite connection source
   * @param locator post-commit runtime slot projection
   * @param databaseExecutor executor reserved for SQL work
   */
  public SqliteIslandDeletionRepository(
      SqliteConnectionFactory connectionFactory,
      CommittedSlotPublisher locator,
      Executor databaseExecutor) {
    this(connectionFactory, locator, CommittedIslandProtectionPublisher.NO_OP, databaseExecutor);
  }

  /**
   * Creates a deletion repository with coherent post-commit protection publication.
   *
   * @param connectionFactory SQLite connection source
   * @param locator post-commit runtime slot projection
   * @param protectionPublisher post-commit gameplay projection
   * @param databaseExecutor executor reserved for SQL work
   */
  public SqliteIslandDeletionRepository(
      SqliteConnectionFactory connectionFactory,
      CommittedSlotPublisher locator,
      CommittedIslandProtectionPublisher protectionPublisher,
      Executor databaseExecutor) {
    this.transactions =
        new SqliteImmediateTransactions(
            Objects.requireNonNull(connectionFactory, "connectionFactory"), 12, 2, 30);
    this.locator = Objects.requireNonNull(locator, "locator");
    this.protectionPublisher = Objects.requireNonNull(protectionPublisher, "protectionPublisher");
    this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<IslandDeletionProgress> beginDeletion(IslandDeletionRequest request) {
    Objects.requireNonNull(request, "request");
    return supplyAsync(() -> beginAndPublish(request));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<IslandDeletionProgress> completeDeletion(
      IslandDeletionCompletion completion) {
    Objects.requireNonNull(completion, "completion");
    return supplyAsync(() -> completeAndPublish(completion));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<List<IslandDeletionRequest>> findPendingDeletions() {
    return supplyAsync(this::findPending);
  }

  private IslandDeletionProgress beginAndPublish(IslandDeletionRequest request) {
    try {
      BeginOutcome outcome = transactions.execute(connection -> begin(connection, request));
      if (outcome.publishEntry() != null) {
        publishSlot(outcome.progress().island().primarySlot().orElseThrow());
      }
      protectionPublisher.publishCommitted(outcome.progress().island().islandId());
      return outcome.progress();
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to begin SQLite island deletion", exception);
    }
  }

  private IslandDeletionProgress completeAndPublish(IslandDeletionCompletion completion) {
    try {
      CompleteOutcome outcome =
          transactions.execute(connection -> complete(connection, completion));
      if (outcome.releasedEntry() != null) {
        LocatorRemovalDecision decision = locator.removeCommitted(outcome.releasedEntry());
        if (decision == LocatorRemovalDecision.CONFLICTED
            || decision == LocatorRemovalDecision.UNSUPPORTED) {
          throw new IllegalStateException(
              "Deleted slot could not be removed from locator: " + decision);
        }
      } else if (outcome.publishEntry() != null) {
        publishSlot(outcome.progress().island().primarySlot().orElseThrow());
        protectionPublisher.publishCommitted(outcome.progress().island().islandId());
      }
      if (outcome.progress().island().lifecycleState() == IslandLifecycleState.ARCHIVED) {
        protectionPublisher.removeCommitted(
            outcome.progress().island().islandId(), outcome.progress().island().version());
      }
      return outcome.progress();
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to complete SQLite island deletion", exception);
    }
  }

  private static BeginOutcome begin(Connection connection, IslandDeletionRequest request)
      throws SQLException {
    Optional<OperationRow> existing = findOperation(connection, request.operationId());
    if (existing.isPresent()) {
      OperationRow operation = existing.orElseThrow();
      validateExistingOperation(operation, request);
      IslandDeletionProgress progress = progressForOperation(connection, operation, true);
      return new BeginOutcome(progress, null);
    }

    IslandAggregateSnapshot current =
        findSnapshot(connection, request.islandId())
            .orElseThrow(() -> new IslandDeletionConflictException("Unknown island"));
    if (!current.ownerId().equals(request.requestedBy())
        || !hasActiveOwnerMembership(connection, request.islandId(), request.requestedBy())) {
      throw new IslandDeletionConflictException("Deletion requires the active island owner");
    }
    if (current.lifecycleState() != IslandLifecycleState.ACTIVE) {
      throw new IslandDeletionConflictException("Only an ACTIVE island can begin deletion");
    }
    if (current.version() != request.expectedIslandVersion()) {
      throw new IslandDeletionConflictException("Confirmation island version is stale");
    }
    AllocatedSlot slot = current.primarySlot().orElseThrow();
    if (slot.state() != SlotState.ACTIVE) {
      throw new IslandDeletionConflictException("Active island slot is not ACTIVE");
    }

    insertOperation(connection, request, slot.slotId());
    insertContext(connection, request);
    updateIslandToDeleting(connection, request);
    updateSlotToCleaning(connection, request, slot);
    IslandAggregateSnapshot deleting = findSnapshot(connection, request.islandId()).orElseThrow();
    return new BeginOutcome(
        new IslandDeletionProgress(deleting, IslandDeletionProgress.Status.CLEANING, false, ""),
        locatorEntry(deleting.primarySlot().orElseThrow()));
  }

  private static CompleteOutcome complete(
      Connection connection, IslandDeletionCompletion completion) throws SQLException {
    OperationRow operation =
        findOperation(connection, completion.operationId())
            .orElseThrow(() -> new IslandDeletionConflictException("Unknown deletion operation"));
    if (!operation.kind().equals(DELETE_KIND)
        || !operation.islandId().equals(completion.islandId())) {
      throw new IslandDeletionConflictException("Operation is not this island deletion");
    }
    if (operation.state().equals("COMPLETED")) {
      return new CompleteOutcome(progressForOperation(connection, operation, true), null, null);
    }
    if (!operation.state().equals("CLEANING_WORLD") || operation.outcomeState() != null) {
      throw new IslandDeletionConflictException("Deletion operation is not awaiting cleanup");
    }
    IslandAggregateSnapshot deleting =
        findSnapshot(connection, completion.islandId())
            .orElseThrow(() -> new IslandDeletionConflictException("Deleting island disappeared"));
    AllocatedSlot slot = deleting.primarySlot().orElseThrow();
    if (deleting.lifecycleState() != IslandLifecycleState.DELETING
        || slot.state() != SlotState.CLEANING
        || !deleting.pendingOperationId().equals(Optional.of(completion.operationId()))) {
      throw new IslandDeletionConflictException("Island is not in exact deletion cleanup state");
    }
    if (deleting.version() != completion.expectedIslandVersion()
        || slot.version() != completion.expectedSlotVersion()) {
      throw new IslandDeletionConflictException("Deletion cleanup evidence uses stale versions");
    }

    if (completion.status() == IslandCleanup.Status.VERIFIED_CLEAN) {
      SlotLocatorEntry released = locatorEntry(slot);
      deleteRuntimeProjections(connection, completion.islandId());
      deactivateMemberships(connection, completion);
      archiveIsland(connection, completion);
      releaseSlot(connection, completion, slot);
      completeOperation(connection, completion, "SUCCEEDED");
      IslandAggregateSnapshot archived =
          findSnapshot(connection, completion.islandId()).orElseThrow();
      return new CompleteOutcome(
          new IslandDeletionProgress(
              archived, IslandDeletionProgress.Status.SUCCEEDED, false, completion.diagnostic()),
          released,
          null);
    }

    quarantineIsland(connection, completion);
    quarantineSlot(connection, completion, slot);
    String outcome = completion.status() == IslandCleanup.Status.AMBIGUOUS ? "AMBIGUOUS" : "FAILED";
    completeOperation(connection, completion, outcome);
    IslandAggregateSnapshot broken = findSnapshot(connection, completion.islandId()).orElseThrow();
    return new CompleteOutcome(
        new IslandDeletionProgress(
            broken,
            outcome.equals("AMBIGUOUS")
                ? IslandDeletionProgress.Status.AMBIGUOUS
                : IslandDeletionProgress.Status.FAILED,
            false,
            completion.diagnostic()),
        null,
        locatorEntry(broken.primarySlot().orElseThrow()));
  }

  private List<IslandDeletionRequest> findPending() {
    try {
      return transactions.execute(SqliteIslandDeletionRepository::findPendingInTransaction);
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to query pending island deletions", exception);
    }
  }

  private static List<IslandDeletionRequest> findPendingInTransaction(Connection connection)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT c.operation_id, o.island_id, c.requested_by_player_id,
                   c.expected_island_version, c.minimum_y, c.maximum_y_exclusive,
                   c.requested_at
            FROM island_lifecycle_operation_contexts c
            JOIN operations o ON o.operation_id = c.operation_id
            JOIN islands i ON i.island_id = o.island_id
            JOIN slots s ON s.slot_id = i.primary_slot_id
            WHERE c.operation_kind = 'ISLAND_DELETE'
              AND o.kind = 'ISLAND_DELETE' AND o.state = 'CLEANING_WORLD'
              AND o.outcome_state IS NULL
              AND i.lifecycle_state = 'DELETING' AND s.state = 'CLEANING'
            ORDER BY c.requested_at, c.operation_id
            """)) {
      try (ResultSet result = statement.executeQuery()) {
        List<IslandDeletionRequest> requests = new ArrayList<>();
        while (result.next()) {
          requests.add(
              new IslandDeletionRequest(
                  IslandId.parse(result.getString("island_id")),
                  OperationId.parse(result.getString("operation_id")),
                  PlayerId.parse(result.getString("requested_by_player_id")),
                  result.getLong("expected_island_version"),
                  result.getInt("minimum_y"),
                  result.getInt("maximum_y_exclusive"),
                  Instant.parse(result.getString("requested_at"))));
        }
        return List.copyOf(requests);
      }
    } catch (IllegalArgumentException exception) {
      throw new SQLException("Invalid persisted deletion recovery context", exception);
    }
  }

  private static void insertOperation(
      Connection connection, IslandDeletionRequest request, SlotId slotId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO operations (
                operation_id, island_id, kind, state, slot_id, request_fingerprint,
                outcome_state, outcome_payload, completed_at, created_at, updated_at
            ) VALUES (?, ?, 'ISLAND_DELETE', 'CLEANING_WORLD', ?, ?, NULL, NULL, NULL, ?, ?)
            """)) {
      statement.setString(1, request.operationId().toString());
      statement.setString(2, request.islandId().toString());
      statement.setString(3, slotId.toString());
      statement.setString(4, request.fingerprint());
      statement.setString(5, request.requestedAt().toString());
      statement.setString(6, request.requestedAt().toString());
      statement.executeUpdate();
    }
  }

  private static void insertContext(Connection connection, IslandDeletionRequest request)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO island_lifecycle_operation_contexts (
                operation_id, operation_kind, requested_by_player_id,
                expected_island_version, minimum_y, maximum_y_exclusive,
                target_world_id, target_phase_id, target_profile_id,
                starter_block_id, magic_block_y, requested_at
            ) VALUES (?, 'ISLAND_DELETE', ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, ?)
            """)) {
      statement.setString(1, request.operationId().toString());
      statement.setString(2, request.requestedBy().toString());
      statement.setLong(3, request.expectedIslandVersion());
      statement.setInt(4, request.minimumY());
      statement.setInt(5, request.maximumYExclusive());
      statement.setString(6, request.requestedAt().toString());
      statement.executeUpdate();
    }
  }

  private static void updateIslandToDeleting(Connection connection, IslandDeletionRequest request)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE islands
            SET lifecycle_state = 'DELETING', pending_operation_id = ?,
                lifecycle_lock_reason = 'openoneblock:delete', version = version + 1,
                updated_at = ?
            WHERE island_id = ? AND lifecycle_state = 'ACTIVE' AND version = ?
            """)) {
      statement.setString(1, request.operationId().toString());
      statement.setString(2, request.requestedAt().toString());
      statement.setString(3, request.islandId().toString());
      statement.setLong(4, request.expectedIslandVersion());
      if (statement.executeUpdate() != 1) {
        throw new IslandDeletionConflictException("Island changed before deletion intent commit");
      }
    }
  }

  private static void updateSlotToCleaning(
      Connection connection, IslandDeletionRequest request, AllocatedSlot slot)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE slots
            SET state = 'CLEANING', version = version + 1, updated_at = ?
            WHERE slot_id = ? AND state = 'ACTIVE' AND owner_island_id = ? AND version = ?
            """)) {
      statement.setString(1, request.requestedAt().toString());
      statement.setString(2, slot.slotId().toString());
      statement.setString(3, request.islandId().toString());
      statement.setLong(4, slot.version());
      if (statement.executeUpdate() != 1) {
        throw new IslandDeletionConflictException("Slot changed before deletion intent commit");
      }
    }
  }

  private static void deleteRuntimeProjections(Connection connection, IslandId islandId)
      throws SQLException {
    for (String sql :
        List.of(
            "DELETE FROM magic_blocks WHERE island_id = ?",
            "DELETE FROM island_spawn_points WHERE island_id = ?",
            "DELETE FROM island_progression WHERE island_id = ?",
            "DELETE FROM counters WHERE scope_type = 'ISLAND' AND scope_id = ?",
            "DELETE FROM typed_variables WHERE scope_type = 'ISLAND' AND scope_id = ?")) {
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, islandId.toString());
        statement.executeUpdate();
      }
    }
  }

  private static void deactivateMemberships(
      Connection connection, IslandDeletionCompletion completion) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE island_memberships
            SET active = 0, owner = 0, updated_at = ?
            WHERE island_id = ? AND active = 1
            """)) {
      statement.setString(1, completion.completedAt().toString());
      statement.setString(2, completion.islandId().toString());
      if (statement.executeUpdate() < 1) {
        throw new SQLException("Deleted island had no active memberships to retire");
      }
    }
  }

  private static void archiveIsland(Connection connection, IslandDeletionCompletion completion)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE islands
            SET lifecycle_state = 'ARCHIVED', primary_slot_id = NULL,
                pending_operation_id = NULL, lifecycle_lock_reason = NULL,
                version = version + 1, updated_at = ?
            WHERE island_id = ? AND lifecycle_state = 'DELETING'
              AND pending_operation_id = ? AND version = ?
            """)) {
      statement.setString(1, completion.completedAt().toString());
      statement.setString(2, completion.islandId().toString());
      statement.setString(3, completion.operationId().toString());
      statement.setLong(4, completion.expectedIslandVersion());
      requireOne(statement, "Island changed while archiving deletion");
    }
  }

  private static void releaseSlot(
      Connection connection, IslandDeletionCompletion completion, AllocatedSlot slot)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE slots
            SET state = 'FREE', owner_island_id = NULL, ownership_role = NULL,
                version = version + 1, updated_at = ?
            WHERE slot_id = ? AND state = 'CLEANING' AND owner_island_id = ? AND version = ?
            """)) {
      statement.setString(1, completion.completedAt().toString());
      statement.setString(2, slot.slotId().toString());
      statement.setString(3, completion.islandId().toString());
      statement.setLong(4, completion.expectedSlotVersion());
      requireOne(statement, "Slot changed while releasing deletion");
    }
  }

  private static void quarantineIsland(Connection connection, IslandDeletionCompletion completion)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE islands
            SET lifecycle_state = 'BROKEN', pending_operation_id = NULL,
                lifecycle_lock_reason = 'openoneblock:delete-cleanup-failed',
                version = version + 1, updated_at = ?
            WHERE island_id = ? AND lifecycle_state = 'DELETING'
              AND pending_operation_id = ? AND version = ?
            """)) {
      statement.setString(1, completion.completedAt().toString());
      statement.setString(2, completion.islandId().toString());
      statement.setString(3, completion.operationId().toString());
      statement.setLong(4, completion.expectedIslandVersion());
      requireOne(statement, "Island changed while quarantining deletion");
    }
  }

  private static void quarantineSlot(
      Connection connection, IslandDeletionCompletion completion, AllocatedSlot slot)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE slots
            SET state = 'QUARANTINED', version = version + 1, updated_at = ?
            WHERE slot_id = ? AND state = 'CLEANING' AND owner_island_id = ? AND version = ?
            """)) {
      statement.setString(1, completion.completedAt().toString());
      statement.setString(2, slot.slotId().toString());
      statement.setString(3, completion.islandId().toString());
      statement.setLong(4, completion.expectedSlotVersion());
      requireOne(statement, "Slot changed while quarantining deletion");
    }
  }

  private static void completeOperation(
      Connection connection, IslandDeletionCompletion completion, String outcome)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE operations
            SET state = 'COMPLETED', outcome_state = ?, outcome_payload = ?,
                completed_at = ?, updated_at = ?
            WHERE operation_id = ? AND island_id = ? AND kind = 'ISLAND_DELETE'
              AND state = 'CLEANING_WORLD' AND outcome_state IS NULL
            """)) {
      statement.setString(1, outcome);
      statement.setString(2, completion.diagnostic());
      statement.setString(3, completion.completedAt().toString());
      statement.setString(4, completion.completedAt().toString());
      statement.setString(5, completion.operationId().toString());
      statement.setString(6, completion.islandId().toString());
      requireOne(statement, "Deletion operation changed before completion");
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
    } catch (IllegalArgumentException exception) {
      throw new SQLException("Invalid persisted deletion operation", exception);
    }
  }

  private static void validateExistingOperation(
      OperationRow operation, IslandDeletionRequest request) {
    if (!operation.kind().equals(DELETE_KIND)
        || !operation.islandId().equals(request.islandId())
        || !Objects.equals(operation.fingerprint(), request.fingerprint())) {
      throw new IslandDeletionConflictException(
          "Operation ID already belongs to a different deletion intent");
    }
  }

  private static IslandDeletionProgress progressForOperation(
      Connection connection, OperationRow operation, boolean replay) throws SQLException {
    IslandAggregateSnapshot island =
        findSnapshot(connection, operation.islandId())
            .orElseThrow(() -> new SQLException("Deletion operation island disappeared"));
    if (!operation.state().equals("COMPLETED")) {
      return new IslandDeletionProgress(island, IslandDeletionProgress.Status.CLEANING, replay, "");
    }
    IslandDeletionProgress.Status status =
        switch (Objects.requireNonNull(operation.outcomeState(), "terminal outcome")) {
          case "SUCCEEDED" -> IslandDeletionProgress.Status.SUCCEEDED;
          case "FAILED" -> IslandDeletionProgress.Status.FAILED;
          case "AMBIGUOUS" -> IslandDeletionProgress.Status.AMBIGUOUS;
          default -> throw new SQLException("Unsupported deletion outcome state");
        };
    return new IslandDeletionProgress(
        island,
        status,
        replay,
        Objects.requireNonNullElse(operation.outcomePayload(), "missing deletion diagnostic"));
  }

  private static Optional<IslandAggregateSnapshot> findSnapshot(
      Connection connection, IslandId islandId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT
            """
                + SNAPSHOT_COLUMNS
                + """

                FROM islands i
                LEFT JOIN slots s ON s.slot_id = i.primary_slot_id
                WHERE i.island_id = ?
                """)) {
      statement.setString(1, islandId.toString());
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? Optional.of(mapSnapshot(result)) : Optional.empty();
      }
    } catch (IllegalArgumentException exception) {
      throw new SQLException("Invalid persisted deletion island snapshot", exception);
    }
  }

  private static IslandAggregateSnapshot mapSnapshot(ResultSet result) throws SQLException {
    IslandId islandId = IslandId.parse(result.getString("island_id"));
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
        islandId,
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

  private <T> CompletionStage<T> supplyAsync(Supplier<T> work) {
    try {
      return CompletableFuture.supplyAsync(work, databaseExecutor);
    } catch (RuntimeException exception) {
      return CompletableFuture.failedFuture(exception);
    }
  }

  private void publishSlot(AllocatedSlot slot) {
    try {
      LocatorPublishDecision decision = locator.publishCommitted(locatorEntry(slot));
      if (decision == LocatorPublishDecision.CONFLICTED) {
        throw new IllegalStateException("Committed deletion slot conflicts with locator");
      }
    } catch (RuntimeException exception) {
      throw new CommittedSlotPublicationException(slot, exception);
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
      throw new IslandDeletionConflictException(message);
    }
  }

  private record OperationRow(
      OperationId operationId,
      IslandId islandId,
      String kind,
      String state,
      String fingerprint,
      String outcomeState,
      String outcomePayload) {}

  private record BeginOutcome(IslandDeletionProgress progress, SlotLocatorEntry publishEntry) {}

  private record CompleteOutcome(
      IslandDeletionProgress progress,
      SlotLocatorEntry releasedEntry,
      SlotLocatorEntry publishEntry) {}
}
