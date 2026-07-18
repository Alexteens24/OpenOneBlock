package dev.openoneblock.persistence.sqlite.island;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.island.IslandAggregateSnapshot;
import dev.openoneblock.core.island.IslandCreationRepository;
import dev.openoneblock.core.island.IslandCreationRequest;
import dev.openoneblock.core.island.IslandCreationStage;
import dev.openoneblock.core.island.IslandCreationTransitionRequest;
import dev.openoneblock.core.island.IslandOptimisticLockException;
import dev.openoneblock.core.lifecycle.IslandLifecyclePolicy;
import dev.openoneblock.core.lifecycle.TransitionDecision;
import dev.openoneblock.core.locator.CommittedSlotPublisher;
import dev.openoneblock.core.locator.LocatorPublishDecision;
import dev.openoneblock.core.locator.SlotLocatorEntry;
import dev.openoneblock.core.slot.AllocatedSlot;
import dev.openoneblock.core.slot.SlotAllocationRequest;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotLifecyclePolicy;
import dev.openoneblock.core.slot.SlotState;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.SqliteImmediateTransactions;
import dev.openoneblock.persistence.sqlite.SqlitePersistenceException;
import dev.openoneblock.persistence.sqlite.slot.CommittedSlotPublicationException;
import dev.openoneblock.persistence.sqlite.slot.SqliteSlotReservations;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** SQLite implementation of atomic island identity, owner membership, and slot establishment. */
public final class SqliteIslandCreationRepository implements IslandCreationRepository {
  private static final String CREATION_KIND = "ISLAND_CREATION";
  private static final String ALLOCATED_STATE = "ALLOCATED";
  private static final String OWNER_ROLE = "openoneblock:owner";

  private static final String SNAPSHOT_COLUMNS =
      """
      i.island_id, i.owner_player_id, i.lifecycle_state, i.current_border_size,
      i.maximum_border_size, i.version AS island_version, i.pending_operation_id,
      i.created_at AS island_created_at, i.updated_at AS island_updated_at,
      s.slot_id, s.shard_group_id, s.ordinal, s.grid_x, s.grid_z,
      s.state AS slot_state, s.owner_island_id, s.version AS slot_version
      """;

  private final SqliteConnectionFactory connectionFactory;
  private final SqliteImmediateTransactions transactions;
  private final SqliteSlotReservations reservations;
  private final CommittedSlotPublisher locatorPublisher;
  private final Executor databaseExecutor;

  /**
   * Creates the repository.
   *
   * @param connectionFactory SQLite connection source
   * @param geometryByShard validated grid geometry lookup
   * @param locatorPublisher post-commit locator projection
   * @param databaseExecutor shared executor reserved for database work
   */
  public SqliteIslandCreationRepository(
      SqliteConnectionFactory connectionFactory,
      Function<ShardGroupId, GridGeometry> geometryByShard,
      CommittedSlotPublisher locatorPublisher,
      Executor databaseExecutor) {
    this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    this.transactions = new SqliteImmediateTransactions(connectionFactory, 12, 2, 30);
    this.reservations = new SqliteSlotReservations(geometryByShard);
    this.locatorPublisher = Objects.requireNonNull(locatorPublisher, "locatorPublisher");
    this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<IslandAggregateSnapshot> createAllocation(IslandCreationRequest request) {
    Objects.requireNonNull(request, "request");
    return supplyAsync(() -> createAndPublish(request));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<IslandAggregateSnapshot> advanceCreation(
      IslandCreationTransitionRequest request) {
    Objects.requireNonNull(request, "request");
    return supplyAsync(() -> advanceAndPublish(request));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<Optional<IslandAggregateSnapshot>> findById(IslandId islandId) {
    Objects.requireNonNull(islandId, "islandId");
    return supplyAsync(
        () -> {
          try (Connection connection = connectionFactory.open()) {
            return findById(connection, islandId);
          } catch (SQLException exception) {
            throw new SqlitePersistenceException("Failed to read SQLite island", exception);
          }
        });
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<Optional<IslandAggregateSnapshot>> findByActiveMember(PlayerId playerId) {
    Objects.requireNonNull(playerId, "playerId");
    return supplyAsync(
        () -> {
          try (Connection connection = connectionFactory.open();
              PreparedStatement statement =
                  connection.prepareStatement(
                      """
                      SELECT %s
                      FROM island_memberships m
                      JOIN islands i ON i.island_id = m.island_id
                      LEFT JOIN slots s ON s.slot_id = i.primary_slot_id
                      WHERE m.player_id = ? AND m.active = 1
                      """
                          .formatted(SNAPSHOT_COLUMNS))) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
              return result.next() ? Optional.of(readSnapshot(result)) : Optional.empty();
            }
          } catch (SQLException exception) {
            throw new SqlitePersistenceException(
                "Failed to read active SQLite island membership", exception);
          }
        });
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<List<IslandAggregateSnapshot>> findPendingCreations() {
    return supplyAsync(
        () -> {
          try (Connection connection = connectionFactory.open();
              PreparedStatement statement =
                  connection.prepareStatement(
                      """
                      SELECT %s
                      FROM islands i
                      LEFT JOIN slots s ON s.slot_id = i.primary_slot_id
                      WHERE i.pending_operation_id IS NOT NULL
                        AND i.lifecycle_state IN ('ALLOCATING', 'CREATING')
                      ORDER BY i.created_at, i.island_id
                      """
                          .formatted(SNAPSHOT_COLUMNS));
              ResultSet result = statement.executeQuery()) {
            List<IslandAggregateSnapshot> pending = new ArrayList<>();
            while (result.next()) {
              pending.add(readSnapshot(result));
            }
            return List.copyOf(pending);
          } catch (SQLException exception) {
            throw new SqlitePersistenceException(
                "Failed to read pending SQLite island creations", exception);
          }
        });
  }

  private IslandAggregateSnapshot createAndPublish(IslandCreationRequest request) {
    IslandAggregateSnapshot committed;
    try {
      committed = transactions.execute(connection -> createInTransaction(connection, request));
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to create SQLite island allocation", exception);
    }

    AllocatedSlot slot =
        committed
            .primarySlot()
            .orElseThrow(() -> new IllegalStateException("new island allocation has no slot"));
    try {
      LocatorPublishDecision decision = locatorPublisher.publishCommitted(toLocatorEntry(slot));
      if (decision == LocatorPublishDecision.CONFLICTED) {
        throw new IllegalStateException("Committed island slot conflicts with locator projection");
      }
    } catch (RuntimeException exception) {
      throw new CommittedSlotPublicationException(slot, exception);
    }
    return committed;
  }

  private IslandAggregateSnapshot advanceAndPublish(IslandCreationTransitionRequest request) {
    IslandAggregateSnapshot committed;
    try {
      committed = transactions.execute(connection -> advanceInTransaction(connection, request));
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to advance SQLite island creation", exception);
    }
    AllocatedSlot slot =
        committed
            .primarySlot()
            .orElseThrow(() -> new IllegalStateException("creation transition has no slot"));
    try {
      LocatorPublishDecision decision = locatorPublisher.publishCommitted(toLocatorEntry(slot));
      if (decision == LocatorPublishDecision.CONFLICTED) {
        throw new IllegalStateException("Committed island slot conflicts with locator projection");
      }
    } catch (RuntimeException exception) {
      throw new CommittedSlotPublicationException(slot, exception);
    }
    return committed;
  }

  private static IslandAggregateSnapshot advanceInTransaction(
      Connection connection, IslandCreationTransitionRequest request) throws SQLException {
    IslandAggregateSnapshot current =
        findById(connection, request.islandId())
            .orElseThrow(
                () ->
                    new IslandCreationTransitionConflictException(
                        "Creation transition refers to an unknown island"));
    AllocatedSlot currentSlot =
        current
            .primarySlot()
            .orElseThrow(
                () ->
                    new IslandCreationTransitionConflictException(
                        "Creation transition island has no primary slot"));
    CreationOperation operation = readCreationOperation(connection, request);
    if (!operation.slotId().equals(currentSlot.slotId())) {
      throw new IslandCreationTransitionConflictException(
          "Creation operation and island reference different slots");
    }

    TransitionSpec transition = TransitionSpec.forStage(request.stage());
    long targetIslandVersion = Math.incrementExact(request.expectedIslandVersion());
    long targetSlotVersion = Math.incrementExact(request.expectedSlotVersion());
    if (current.lifecycleState() == transition.targetIslandState()
        && current.version() == targetIslandVersion
        && currentSlot.state() == transition.targetSlotState()
        && currentSlot.version() == targetSlotVersion
        && operation.state().equals(transition.targetOperationState())) {
      return current;
    }
    if (current.version() != request.expectedIslandVersion()
        || currentSlot.version() != request.expectedSlotVersion()) {
      throw new IslandOptimisticLockException(
          request.islandId(),
          request.expectedIslandVersion(),
          current.version(),
          request.expectedSlotVersion(),
          currentSlot.version());
    }
    if (current.lifecycleState() != transition.sourceIslandState()
        || currentSlot.state() != transition.sourceSlotState()
        || !operation.state().equals(transition.sourceOperationState())) {
      throw new IslandCreationTransitionConflictException(
          "Persisted island, slot, or operation is not ready for " + request.stage());
    }
    if (IslandLifecyclePolicy.evaluate(
            transition.sourceIslandState(), transition.targetIslandState())
        != TransitionDecision.ALLOWED) {
      throw new IllegalStateException("Creation stage contains an illegal island transition");
    }
    if (SlotLifecyclePolicy.evaluate(transition.sourceSlotState(), transition.targetSlotState())
        != TransitionDecision.ALLOWED) {
      throw new IllegalStateException("Creation stage contains an illegal slot transition");
    }

    updateIsland(connection, request, transition, targetIslandVersion);
    updateSlot(connection, request, currentSlot, transition, targetSlotVersion);
    updateOperation(connection, request, transition);
    return findById(connection, request.islandId())
        .orElseThrow(() -> new SQLException("Transitioned island could not be read back"));
  }

  private static CreationOperation readCreationOperation(
      Connection connection, IslandCreationTransitionRequest request) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT island_id, kind, state, slot_id FROM operations WHERE operation_id = ?")) {
      statement.setString(1, request.operationId().toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()
            || !request.islandId().toString().equals(result.getString("island_id"))
            || !CREATION_KIND.equals(result.getString("kind"))
            || result.getString("slot_id") == null) {
          throw new IslandCreationTransitionConflictException(
              "Operation does not identify this island creation");
        }
        return new CreationOperation(
            result.getString("state"), SlotId.parse(result.getString("slot_id")));
      }
    }
  }

  private static void updateIsland(
      Connection connection,
      IslandCreationTransitionRequest request,
      TransitionSpec transition,
      long targetVersion)
      throws SQLException {
    String pendingExpression = transition.clearPendingOperation() ? "NULL" : "pending_operation_id";
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE islands
            SET lifecycle_state = ?, version = ?, pending_operation_id = %s, updated_at = ?
            WHERE island_id = ? AND lifecycle_state = ? AND version = ?
              AND pending_operation_id = ?
            """
                .formatted(pendingExpression))) {
      statement.setString(1, transition.targetIslandState().name());
      statement.setLong(2, targetVersion);
      statement.setString(3, request.requestedAt().toString());
      statement.setString(4, request.islandId().toString());
      statement.setString(5, transition.sourceIslandState().name());
      statement.setLong(6, request.expectedIslandVersion());
      statement.setString(7, request.operationId().toString());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("Island changed during creation transition");
      }
    }
  }

  private static void updateSlot(
      Connection connection,
      IslandCreationTransitionRequest request,
      AllocatedSlot currentSlot,
      TransitionSpec transition,
      long targetVersion)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE slots
            SET state = ?, version = ?, updated_at = ?
            WHERE slot_id = ? AND owner_island_id = ? AND ownership_role = 'PRIMARY'
              AND state = ? AND version = ?
            """)) {
      statement.setString(1, transition.targetSlotState().name());
      statement.setLong(2, targetVersion);
      statement.setString(3, request.requestedAt().toString());
      statement.setString(4, currentSlot.slotId().toString());
      statement.setString(5, request.islandId().toString());
      statement.setString(6, transition.sourceSlotState().name());
      statement.setLong(7, request.expectedSlotVersion());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("Primary slot changed during creation transition");
      }
    }
  }

  private static void updateOperation(
      Connection connection, IslandCreationTransitionRequest request, TransitionSpec transition)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE operations
            SET state = ?, updated_at = ?
            WHERE operation_id = ? AND island_id = ? AND kind = ? AND state = ?
            """)) {
      statement.setString(1, transition.targetOperationState());
      statement.setString(2, request.requestedAt().toString());
      statement.setString(3, request.operationId().toString());
      statement.setString(4, request.islandId().toString());
      statement.setString(5, CREATION_KIND);
      statement.setString(6, transition.sourceOperationState());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("Operation changed during creation transition");
      }
    }
  }

  private IslandAggregateSnapshot createInTransaction(
      Connection connection, IslandCreationRequest request) throws SQLException {
    IslandAggregateSnapshot existing = findExistingOperation(connection, request);
    if (existing != null) {
      return existing;
    }
    rejectExistingMembership(connection, request.ownerId());

    SlotAllocationRequest allocationRequest =
        new SlotAllocationRequest(
            request.islandId(),
            request.shardGroupId(),
            request.operationId(),
            request.requestedAt());
    AllocatedSlot slot = reservations.reserve(connection, allocationRequest);
    insertOperation(connection, request, slot);
    insertIsland(connection, request, slot);
    insertOwnerMembership(connection, request);
    return findById(connection, request.islandId())
        .orElseThrow(() -> new SQLException("Inserted island could not be read back"));
  }

  private static IslandAggregateSnapshot findExistingOperation(
      Connection connection, IslandCreationRequest request) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT island_id, kind, slot_id FROM operations WHERE operation_id = ?")) {
      statement.setString(1, request.operationId().toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return null;
        }
        if (!request.islandId().toString().equals(result.getString("island_id"))
            || !CREATION_KIND.equals(result.getString("kind"))
            || result.getString("slot_id") == null) {
          throw new IslandCreationOperationConflictException(
              "Operation ID already belongs to a different creation intent");
        }
      }
    }

    IslandAggregateSnapshot snapshot =
        findById(connection, request.islandId())
            .orElseThrow(
                () ->
                    new IslandCreationOperationConflictException(
                        "Creation operation has no persisted island outcome"));
    AllocatedSlot slot =
        snapshot
            .primarySlot()
            .orElseThrow(
                () ->
                    new IslandCreationOperationConflictException(
                        "Creation operation outcome no longer owns a slot"));
    if (!snapshot.ownerId().equals(request.ownerId())
        || !slot.shardGroupId().equals(request.shardGroupId())
        || snapshot.currentBorderSize() != request.initialBorderSize()
        || snapshot.maximumBorderSize() != request.maximumBorderSize()) {
      throw new IslandCreationOperationConflictException(
          "Operation ID already has a different creation outcome");
    }
    return snapshot;
  }

  private static void rejectExistingMembership(Connection connection, PlayerId playerId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT island_id
            FROM island_memberships
            WHERE player_id = ? AND active = 1
            """)) {
      statement.setString(1, playerId.toString());
      try (ResultSet result = statement.executeQuery()) {
        if (result.next()) {
          throw new ActiveMembershipConflictException(
              playerId, IslandId.parse(result.getString("island_id")));
        }
      }
    }
  }

  private static void insertOperation(
      Connection connection, IslandCreationRequest request, AllocatedSlot slot)
      throws SQLException {
    String timestamp = request.requestedAt().toString();
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO operations (
                operation_id, island_id, kind, state, slot_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
      statement.setString(1, request.operationId().toString());
      statement.setString(2, request.islandId().toString());
      statement.setString(3, CREATION_KIND);
      statement.setString(4, ALLOCATED_STATE);
      statement.setString(5, slot.slotId().toString());
      statement.setString(6, timestamp);
      statement.setString(7, timestamp);
      statement.executeUpdate();
    }
  }

  private static void insertIsland(
      Connection connection, IslandCreationRequest request, AllocatedSlot slot)
      throws SQLException {
    String timestamp = request.requestedAt().toString();
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO islands (
                island_id, owner_player_id, lifecycle_state, primary_slot_id,
                current_border_size, maximum_border_size, version,
                pending_operation_id, created_at, updated_at
            ) VALUES (?, ?, 'ALLOCATING', ?, ?, ?, 0, ?, ?, ?)
            """)) {
      statement.setString(1, request.islandId().toString());
      statement.setString(2, request.ownerId().toString());
      statement.setString(3, slot.slotId().toString());
      statement.setInt(4, request.initialBorderSize());
      statement.setInt(5, request.maximumBorderSize());
      statement.setString(6, request.operationId().toString());
      statement.setString(7, timestamp);
      statement.setString(8, timestamp);
      statement.executeUpdate();
    }
  }

  private static void insertOwnerMembership(Connection connection, IslandCreationRequest request)
      throws SQLException {
    String timestamp = request.requestedAt().toString();
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO island_memberships (
                island_id, player_id, role_id, active, owner, created_at, updated_at
            ) VALUES (?, ?, ?, 1, 1, ?, ?)
            """)) {
      statement.setString(1, request.islandId().toString());
      statement.setString(2, request.ownerId().toString());
      statement.setString(3, OWNER_ROLE);
      statement.setString(4, timestamp);
      statement.setString(5, timestamp);
      statement.executeUpdate();
    }
  }

  private static Optional<IslandAggregateSnapshot> findById(
      Connection connection, IslandId islandId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT %s
            FROM islands i
            LEFT JOIN slots s ON s.slot_id = i.primary_slot_id
            WHERE i.island_id = ?
            """
                .formatted(SNAPSHOT_COLUMNS))) {
      statement.setString(1, islandId.toString());
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? Optional.of(readSnapshot(result)) : Optional.empty();
      }
    }
  }

  private static IslandAggregateSnapshot readSnapshot(ResultSet result) throws SQLException {
    IslandId islandId = IslandId.parse(result.getString("island_id"));
    String slotId = result.getString("slot_id");
    Optional<AllocatedSlot> primarySlot =
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
    String pendingOperation = result.getString("pending_operation_id");
    return new IslandAggregateSnapshot(
        islandId,
        PlayerId.parse(result.getString("owner_player_id")),
        IslandLifecycleState.valueOf(result.getString("lifecycle_state")),
        primarySlot,
        result.getInt("current_border_size"),
        result.getInt("maximum_border_size"),
        result.getLong("island_version"),
        pendingOperation == null
            ? Optional.empty()
            : Optional.of(OperationId.parse(pendingOperation)),
        java.time.Instant.parse(result.getString("island_created_at")),
        java.time.Instant.parse(result.getString("island_updated_at")));
  }

  private static SlotLocatorEntry toLocatorEntry(AllocatedSlot slot) {
    return new SlotLocatorEntry(
        slot.shardGroupId(),
        slot.gridPosition(),
        slot.slotId(),
        slot.ownerIslandId(),
        slot.state(),
        slot.version());
  }

  private <T> CompletionStage<T> supplyAsync(java.util.function.Supplier<T> supplier) {
    try {
      return CompletableFuture.supplyAsync(supplier, databaseExecutor);
    } catch (RuntimeException exception) {
      return CompletableFuture.failedFuture(exception);
    }
  }

  private record CreationOperation(String state, SlotId slotId) {}

  private record TransitionSpec(
      IslandLifecycleState sourceIslandState,
      IslandLifecycleState targetIslandState,
      SlotState sourceSlotState,
      SlotState targetSlotState,
      String sourceOperationState,
      String targetOperationState,
      boolean clearPendingOperation) {
    private static TransitionSpec forStage(IslandCreationStage stage) {
      return switch (stage) {
        case BEGIN_PREPARATION ->
            new TransitionSpec(
                IslandLifecycleState.ALLOCATING,
                IslandLifecycleState.CREATING,
                SlotState.RESERVED,
                SlotState.PREPARING,
                ALLOCATED_STATE,
                "PREPARING_WORLD",
                false);
        case ACTIVATE ->
            new TransitionSpec(
                IslandLifecycleState.CREATING,
                IslandLifecycleState.ACTIVE,
                SlotState.PREPARING,
                SlotState.ACTIVE,
                "PREPARING_WORLD",
                "COMPLETED",
                true);
      };
    }
  }
}
