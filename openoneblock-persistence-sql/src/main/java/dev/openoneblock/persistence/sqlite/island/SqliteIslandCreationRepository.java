package dev.openoneblock.persistence.sqlite.island;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.island.IslandAggregateSnapshot;
import dev.openoneblock.core.island.IslandCreationActivationRequest;
import dev.openoneblock.core.island.IslandCreationCleanupCompletionRequest;
import dev.openoneblock.core.island.IslandCreationContext;
import dev.openoneblock.core.island.IslandCreationFailedException;
import dev.openoneblock.core.island.IslandCreationFailureRequest;
import dev.openoneblock.core.island.IslandCreationRepository;
import dev.openoneblock.core.island.IslandCreationRequest;
import dev.openoneblock.core.island.IslandCreationStage;
import dev.openoneblock.core.island.IslandCreationTransitionRequest;
import dev.openoneblock.core.island.IslandMembershipConflictException;
import dev.openoneblock.core.island.IslandOptimisticLockException;
import dev.openoneblock.core.lifecycle.IslandLifecyclePolicy;
import dev.openoneblock.core.lifecycle.TransitionDecision;
import dev.openoneblock.core.locator.CommittedSlotPublisher;
import dev.openoneblock.core.locator.LocatorPublishDecision;
import dev.openoneblock.core.locator.LocatorRemovalDecision;
import dev.openoneblock.core.locator.SlotLocatorEntry;
import dev.openoneblock.core.slot.AllocatedSlot;
import dev.openoneblock.core.slot.SlotAllocationRequest;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotLifecyclePolicy;
import dev.openoneblock.core.slot.SlotState;
import dev.openoneblock.core.world.IslandCleanup;
import dev.openoneblock.core.world.WorldEffectState;
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
    if (request.stage() == IslandCreationStage.ACTIVATE) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException(
              "activation requires verified spawn, progression, Magic Block, and effect evidence"));
    }
    return supplyAsync(() -> advanceAndPublish(request));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<IslandAggregateSnapshot> activateCreation(
      IslandCreationActivationRequest request) {
    Objects.requireNonNull(request, "request");
    return supplyAsync(() -> activateAndPublish(request));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<IslandAggregateSnapshot> abortCreationBeforeWorldWork(
      IslandCreationFailureRequest request) {
    Objects.requireNonNull(request, "request");
    return supplyAsync(() -> abortAndUnpublish(request));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<IslandAggregateSnapshot> beginCreationCleanup(
      IslandCreationFailureRequest request) {
    Objects.requireNonNull(request, "request");
    return supplyAsync(() -> beginCleanupAndPublish(request));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<IslandAggregateSnapshot> completeCreationCleanup(
      IslandCreationCleanupCompletionRequest request) {
    Objects.requireNonNull(request, "request");
    return supplyAsync(() -> completeCleanupAndPublish(request));
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
                        AND i.lifecycle_state IN ('ALLOCATING', 'CREATING', 'BROKEN')
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

  /** {@inheritDoc} */
  @Override
  public CompletionStage<List<IslandCreationRequest>> findPendingCreationRequests() {
    return supplyAsync(
        () -> {
          try (Connection connection = connectionFactory.open();
              PreparedStatement statement =
                  connection.prepareStatement(
                      """
                      SELECT i.island_id, i.owner_player_id, s.shard_group_id,
                             i.pending_operation_id, i.current_border_size,
                             i.maximum_border_size, o.created_at,
                             c.primary_world_id, c.profile_id, c.phase_id,
                             c.starter_block_id, c.magic_block_y,
                             c.minimum_y, c.maximum_y_exclusive
                      FROM islands i
                      JOIN slots s ON s.slot_id = i.primary_slot_id
                      JOIN operations o ON o.operation_id = i.pending_operation_id
                      JOIN island_creation_contexts c
                        ON c.operation_id = i.pending_operation_id
                      WHERE i.pending_operation_id IS NOT NULL
                        AND i.lifecycle_state IN ('ALLOCATING', 'CREATING', 'BROKEN')
                        AND o.kind = ?
                      ORDER BY i.created_at, i.island_id
                      """)) {
            statement.setString(1, CREATION_KIND);
            try (ResultSet result = statement.executeQuery()) {
              List<IslandCreationRequest> pending = new ArrayList<>();
              while (result.next()) {
                pending.add(readPendingRequest(result));
              }
              return List.copyOf(pending);
            }
          } catch (SQLException exception) {
            throw new SqlitePersistenceException(
                "Failed to read pending SQLite creation intents", exception);
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

  private IslandAggregateSnapshot activateAndPublish(IslandCreationActivationRequest request) {
    IslandAggregateSnapshot committed;
    try {
      committed = transactions.execute(connection -> activateInTransaction(connection, request));
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to activate SQLite island creation", exception);
    }
    AllocatedSlot slot = committed.primarySlot().orElseThrow();
    try {
      LocatorPublishDecision decision = locatorPublisher.publishCommitted(toLocatorEntry(slot));
      if (decision == LocatorPublishDecision.CONFLICTED) {
        throw new IllegalStateException("Committed active slot conflicts with locator projection");
      }
    } catch (RuntimeException exception) {
      throw new CommittedSlotPublicationException(slot, exception);
    }
    return committed;
  }

  private IslandAggregateSnapshot abortAndUnpublish(IslandCreationFailureRequest request) {
    ReleaseTransition committed;
    try {
      committed =
          transactions.execute(connection -> abortBeforeWorldInTransaction(connection, request));
    } catch (SQLException exception) {
      throw new SqlitePersistenceException(
          "Failed to abort SQLite island creation before world work", exception);
    }
    removeReleasedProjection(committed.releasedEntry());
    return committed.snapshot();
  }

  private IslandAggregateSnapshot beginCleanupAndPublish(IslandCreationFailureRequest request) {
    IslandAggregateSnapshot committed;
    try {
      committed =
          transactions.execute(connection -> beginCleanupInTransaction(connection, request));
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to begin SQLite creation cleanup", exception);
    }
    publishSlot(committed.primarySlot().orElseThrow());
    return committed;
  }

  private IslandAggregateSnapshot completeCleanupAndPublish(
      IslandCreationCleanupCompletionRequest request) {
    CleanupTransition committed;
    try {
      committed =
          transactions.execute(connection -> completeCleanupInTransaction(connection, request));
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to complete SQLite creation cleanup", exception);
    }
    if (committed.releasedEntry() != null) {
      removeReleasedProjection(committed.releasedEntry());
    } else {
      publishSlot(committed.snapshot().primarySlot().orElseThrow());
    }
    return committed.snapshot();
  }

  private void publishSlot(AllocatedSlot slot) {
    try {
      if (locatorPublisher.publishCommitted(toLocatorEntry(slot))
          == LocatorPublishDecision.CONFLICTED) {
        throw new IllegalStateException("Committed slot conflicts with locator projection");
      }
    } catch (RuntimeException exception) {
      throw new CommittedSlotPublicationException(slot, exception);
    }
  }

  private void removeReleasedProjection(SlotLocatorEntry releasedEntry) {
    LocatorRemovalDecision decision = locatorPublisher.removeCommitted(releasedEntry);
    if (decision == LocatorRemovalDecision.CONFLICTED
        || decision == LocatorRemovalDecision.UNSUPPORTED) {
      throw new IllegalStateException(
          "Committed slot release could not update locator projection: " + decision);
    }
  }

  private static IslandAggregateSnapshot activateInTransaction(
      Connection connection, IslandCreationActivationRequest request) throws SQLException {
    IslandAggregateSnapshot current =
        findById(connection, request.islandId())
            .orElseThrow(
                () ->
                    new IslandCreationTransitionConflictException(
                        "Activation refers to an unknown island"));
    AllocatedSlot slot =
        current
            .primarySlot()
            .orElseThrow(
                () ->
                    new IslandCreationTransitionConflictException(
                        "Activation island has no primary slot"));
    ActivationOperation operation = readActivationOperation(connection, request.operationId());
    long targetIslandVersion = Math.incrementExact(request.expectedIslandVersion());
    long targetSlotVersion = Math.incrementExact(request.expectedSlotVersion());
    if (current.lifecycleState() == IslandLifecycleState.ACTIVE
        && current.version() == targetIslandVersion
        && slot.state() == SlotState.ACTIVE
        && slot.version() == targetSlotVersion
        && operation.state().equals("COMPLETED")
        && Objects.equals(operation.outcomeState(), "SUCCEEDED")
        && Objects.equals(operation.outcomePayload(), request.islandId().toString())) {
      verifyActivationProjectionReplay(connection, request);
      return current;
    }
    if (current.version() != request.expectedIslandVersion()
        || slot.version() != request.expectedSlotVersion()) {
      throw new IslandOptimisticLockException(
          request.islandId(),
          request.expectedIslandVersion(),
          current.version(),
          request.expectedSlotVersion(),
          slot.version());
    }
    if (current.lifecycleState() != IslandLifecycleState.CREATING
        || slot.state() != SlotState.PREPARING
        || current.pendingOperationId().filter(request.operationId()::equals).isEmpty()
        || !operation.islandId().equals(request.islandId())
        || !operation.slotId().equals(slot.slotId())
        || !operation.state().equals("PREPARING_WORLD")) {
      throw new IslandCreationTransitionConflictException(
          "Persisted creation is not ready for verified activation");
    }
    verifyRequiredEffects(connection, request);
    insertSpawn(connection, request);
    insertProgression(connection, request);
    insertInitialCounters(connection, request);
    insertMagicBlock(connection, request);
    updateActivationIsland(connection, request, targetIslandVersion);
    updateActivationSlot(connection, request, slot, targetSlotVersion);
    completeActivationOperation(connection, request);
    return findById(connection, request.islandId())
        .orElseThrow(() -> new SQLException("Activated island could not be read back"));
  }

  private static ReleaseTransition abortBeforeWorldInTransaction(
      Connection connection, IslandCreationFailureRequest request) throws SQLException {
    IslandAggregateSnapshot current = requireIsland(connection, request.islandId());
    FailureOperation operation = readFailureOperation(connection, request.operationId());
    SlotRecord slot = readOperationSlot(connection, operation.slotId());
    long targetIslandVersion = Math.incrementExact(request.expectedIslandVersion());
    long targetSlotVersion = Math.incrementExact(request.expectedSlotVersion());
    SlotLocatorEntry releasedEntry =
        slot.releasedEntry(request.islandId(), request.expectedSlotVersion(), SlotState.PREPARING);
    if (current.lifecycleState() == IslandLifecycleState.ARCHIVED
        && current.version() == targetIslandVersion
        && slot.state() == SlotState.FREE
        && slot.version() == targetSlotVersion
        && operation.completedWith("FAILED")) {
      return new ReleaseTransition(current, releasedEntry);
    }
    AllocatedSlot currentSlot = requireOwnedPrimarySlot(current, request.operationId());
    if (current.version() != request.expectedIslandVersion()
        || currentSlot.version() != request.expectedSlotVersion()) {
      throw new IslandOptimisticLockException(
          request.islandId(),
          request.expectedIslandVersion(),
          current.version(),
          request.expectedSlotVersion(),
          currentSlot.version());
    }
    boolean allocated =
        current.lifecycleState() == IslandLifecycleState.ALLOCATING
            && currentSlot.state() == SlotState.RESERVED
            && operation.state().equals(ALLOCATED_STATE);
    boolean preparing =
        current.lifecycleState() == IslandLifecycleState.CREATING
            && currentSlot.state() == SlotState.PREPARING
            && operation.state().equals("PREPARING_WORLD");
    if ((!allocated && !preparing)
        || !operation.islandId().equals(request.islandId())
        || !operation.slotId().equals(currentSlot.slotId())) {
      throw new IslandCreationTransitionConflictException(
          "Creation is not eligible for pre-world abort");
    }
    requireNoDispatchedEffects(connection, request.operationId());
    archiveFailedCreation(connection, request, targetIslandVersion);
    releaseCreationSlot(connection, request, currentSlot, targetSlotVersion);
    deactivateCreationMemberships(connection, request);
    completeFailedCreationOperation(connection, request, "FAILED");
    IslandAggregateSnapshot archived = requireIsland(connection, request.islandId());
    return new ReleaseTransition(
        archived,
        new SlotLocatorEntry(
            currentSlot.shardGroupId(),
            currentSlot.gridPosition(),
            currentSlot.slotId(),
            request.islandId(),
            currentSlot.state(),
            currentSlot.version()));
  }

  private static IslandAggregateSnapshot beginCleanupInTransaction(
      Connection connection, IslandCreationFailureRequest request) throws SQLException {
    IslandAggregateSnapshot current = requireIsland(connection, request.islandId());
    AllocatedSlot slot = requireOwnedPrimarySlot(current, request.operationId());
    FailureOperation operation = readFailureOperation(connection, request.operationId());
    long targetIslandVersion = Math.incrementExact(request.expectedIslandVersion());
    long targetSlotVersion = Math.incrementExact(request.expectedSlotVersion());
    if (current.lifecycleState() == IslandLifecycleState.BROKEN
        && current.version() == targetIslandVersion
        && slot.state() == SlotState.CLEANING
        && slot.version() == targetSlotVersion
        && operation.state().equals("CLEANING_WORLD")) {
      return current;
    }
    requireExpectedVersions(current, slot, request);
    if (current.lifecycleState() != IslandLifecycleState.CREATING
        || slot.state() != SlotState.PREPARING
        || !operation.state().equals("PREPARING_WORLD")
        || !operation.islandId().equals(request.islandId())
        || !operation.slotId().equals(slot.slotId())) {
      throw new IslandCreationTransitionConflictException(
          "Creation is not eligible to begin cleanup");
    }
    requireCreationCleanupEvidence(connection, request.operationId());
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE islands
            SET lifecycle_state = 'BROKEN', version = ?,
                lifecycle_lock_reason = ?, updated_at = ?
            WHERE island_id = ? AND lifecycle_state = 'CREATING' AND version = ?
              AND pending_operation_id = ?
            """)) {
      statement.setLong(1, targetIslandVersion);
      statement.setString(2, "creation-cleanup: " + request.diagnostic());
      statement.setString(3, request.failedAt().toString());
      statement.setString(4, request.islandId().toString());
      statement.setLong(5, request.expectedIslandVersion());
      statement.setString(6, request.operationId().toString());
      requireSingleUpdate(statement, "Island changed while beginning creation cleanup");
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE slots
            SET state = 'CLEANING', version = ?, updated_at = ?
            WHERE slot_id = ? AND owner_island_id = ? AND state = 'PREPARING'
              AND version = ?
            """)) {
      statement.setLong(1, targetSlotVersion);
      statement.setString(2, request.failedAt().toString());
      statement.setString(3, slot.slotId().toString());
      statement.setString(4, request.islandId().toString());
      statement.setLong(5, request.expectedSlotVersion());
      requireSingleUpdate(statement, "Slot changed while beginning creation cleanup");
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE operations
            SET state = 'CLEANING_WORLD', outcome_payload = ?, updated_at = ?
            WHERE operation_id = ? AND island_id = ? AND kind = ?
              AND state = 'PREPARING_WORLD' AND outcome_state IS NULL
            """)) {
      statement.setString(1, request.diagnostic());
      statement.setString(2, request.failedAt().toString());
      statement.setString(3, request.operationId().toString());
      statement.setString(4, request.islandId().toString());
      statement.setString(5, CREATION_KIND);
      requireSingleUpdate(statement, "Operation changed while beginning creation cleanup");
    }
    return requireIsland(connection, request.islandId());
  }

  private static CleanupTransition completeCleanupInTransaction(
      Connection connection, IslandCreationCleanupCompletionRequest request) throws SQLException {
    IslandAggregateSnapshot current = requireIsland(connection, request.islandId());
    FailureOperation operation = readFailureOperation(connection, request.operationId());
    SlotRecord persistedSlot = readOperationSlot(connection, operation.slotId());
    long targetIslandVersion = Math.incrementExact(request.expectedIslandVersion());
    long targetSlotVersion = Math.incrementExact(request.expectedSlotVersion());
    boolean clean = request.status() == IslandCleanup.Status.VERIFIED_CLEAN;
    if (isCleanupReplay(
        current,
        persistedSlot,
        operation,
        targetIslandVersion,
        targetSlotVersion,
        clean,
        request.status())) {
      SlotLocatorEntry released =
          clean
              ? persistedSlot.releasedEntry(
                  request.islandId(), request.expectedSlotVersion(), SlotState.CLEANING)
              : null;
      return new CleanupTransition(current, released);
    }
    AllocatedSlot slot = requireOwnedPrimarySlot(current, request.operationId());
    requireExpectedVersions(current, slot, request);
    if (current.lifecycleState() != IslandLifecycleState.BROKEN
        || slot.state() != SlotState.CLEANING
        || !operation.state().equals("CLEANING_WORLD")
        || !operation.islandId().equals(request.islandId())
        || !operation.slotId().equals(slot.slotId())) {
      throw new IslandCreationTransitionConflictException(
          "Creation cleanup is not awaiting terminal evidence");
    }
    if (clean) {
      archiveAfterVerifiedCleanup(connection, request, targetIslandVersion);
      releaseAfterVerifiedCleanup(connection, request, slot, targetSlotVersion);
      deactivateCreationMemberships(connection, request);
    } else {
      quarantineAfterCleanupFailure(
          connection, request, targetIslandVersion, targetSlotVersion, slot);
    }
    String outcome = request.status() == IslandCleanup.Status.AMBIGUOUS ? "AMBIGUOUS" : "FAILED";
    completeCleanupOperation(connection, request, outcome);
    IslandAggregateSnapshot completed = requireIsland(connection, request.islandId());
    SlotLocatorEntry released =
        clean
            ? new SlotLocatorEntry(
                slot.shardGroupId(),
                slot.gridPosition(),
                slot.slotId(),
                request.islandId(),
                SlotState.CLEANING,
                slot.version())
            : null;
    return new CleanupTransition(completed, released);
  }

  private static IslandAggregateSnapshot requireIsland(Connection connection, IslandId islandId)
      throws SQLException {
    return findById(connection, islandId)
        .orElseThrow(
            () ->
                new IslandCreationTransitionConflictException(
                    "Creation operation refers to an unknown island"));
  }

  private static AllocatedSlot requireOwnedPrimarySlot(
      IslandAggregateSnapshot island, OperationId operationId) {
    AllocatedSlot slot =
        island
            .primarySlot()
            .orElseThrow(
                () ->
                    new IslandCreationTransitionConflictException(
                        "Creation operation no longer owns a primary slot"));
    if (island.pendingOperationId().filter(operationId::equals).isEmpty()) {
      throw new IslandCreationTransitionConflictException(
          "Creation operation is not the island's pending operation");
    }
    return slot;
  }

  private static FailureOperation readFailureOperation(
      Connection connection, OperationId operationId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT island_id, state, slot_id, outcome_state, outcome_payload
            FROM operations
            WHERE operation_id = ? AND kind = ?
            """)) {
      statement.setString(1, operationId.toString());
      statement.setString(2, CREATION_KIND);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || result.getString("slot_id") == null) {
          throw new IslandCreationTransitionConflictException(
              "Creation failure operation does not exist");
        }
        return new FailureOperation(
            IslandId.parse(result.getString("island_id")),
            result.getString("state"),
            SlotId.parse(result.getString("slot_id")),
            result.getString("outcome_state"),
            result.getString("outcome_payload"));
      }
    }
  }

  private static SlotRecord readOperationSlot(Connection connection, SlotId slotId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT shard_group_id, grid_x, grid_z, state, version
            FROM slots
            WHERE slot_id = ?
            """)) {
      statement.setString(1, slotId.toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new IslandCreationTransitionConflictException(
              "Creation operation slot does not exist");
        }
        return new SlotRecord(
            slotId,
            ShardGroupId.parse(result.getString("shard_group_id")),
            new GridPosition(result.getInt("grid_x"), result.getInt("grid_z")),
            SlotState.valueOf(result.getString("state")),
            result.getLong("version"));
      }
    }
  }

  private static void requireExpectedVersions(
      IslandAggregateSnapshot current, AllocatedSlot slot, IslandCreationFailureRequest request) {
    if (current.version() != request.expectedIslandVersion()
        || slot.version() != request.expectedSlotVersion()) {
      throw new IslandOptimisticLockException(
          request.islandId(),
          request.expectedIslandVersion(),
          current.version(),
          request.expectedSlotVersion(),
          slot.version());
    }
  }

  private static void requireExpectedVersions(
      IslandAggregateSnapshot current,
      AllocatedSlot slot,
      IslandCreationCleanupCompletionRequest request) {
    if (current.version() != request.expectedIslandVersion()
        || slot.version() != request.expectedSlotVersion()) {
      throw new IslandOptimisticLockException(
          request.islandId(),
          request.expectedIslandVersion(),
          current.version(),
          request.expectedSlotVersion(),
          slot.version());
    }
  }

  private static void requireNoDispatchedEffects(Connection connection, OperationId operationId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT COUNT(*)
            FROM world_effect_receipts
            WHERE operation_id = ?
              AND effect_kind IN ('SET_VANILLA_BLOCK', 'PLACE_STRUCTURE')
              AND state <> 'NOT_STARTED'
            """)) {
      statement.setString(1, operationId.toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || result.getInt(1) != 0) {
          throw new IslandCreationTransitionConflictException(
              "Pre-world abort is forbidden after effect dispatch");
        }
      }
    }
  }

  private static void requireCreationCleanupEvidence(Connection connection, OperationId operationId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT COUNT(*)
            FROM world_effect_receipts
            WHERE operation_id = ?
              AND (
                (effect_kind IN ('SET_VANILLA_BLOCK', 'PLACE_STRUCTURE')
                  AND state <> 'NOT_STARTED')
                OR (effect_kind = 'VERIFY_CLEAN_REGION' AND state = 'VERIFIED_FAILURE')
              )
            """)) {
      statement.setString(1, operationId.toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || result.getInt(1) == 0) {
          throw new IslandCreationTransitionConflictException(
              "Creation cleanup requires a dispatched world mutation");
        }
      }
    }
  }

  private static void archiveFailedCreation(
      Connection connection, IslandCreationFailureRequest request, long targetVersion)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE islands
            SET lifecycle_state = 'ARCHIVED', primary_slot_id = NULL, version = ?,
                pending_operation_id = NULL, lifecycle_lock_reason = NULL, updated_at = ?
            WHERE island_id = ? AND lifecycle_state IN ('ALLOCATING', 'CREATING')
              AND version = ? AND pending_operation_id = ?
            """)) {
      statement.setLong(1, targetVersion);
      statement.setString(2, request.failedAt().toString());
      statement.setString(3, request.islandId().toString());
      statement.setLong(4, request.expectedIslandVersion());
      statement.setString(5, request.operationId().toString());
      requireSingleUpdate(statement, "Island changed during pre-world creation abort");
    }
  }

  private static void releaseCreationSlot(
      Connection connection,
      IslandCreationFailureRequest request,
      AllocatedSlot slot,
      long targetVersion)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE slots
            SET state = 'FREE', owner_island_id = NULL, ownership_role = NULL,
                version = ?, updated_at = ?
            WHERE slot_id = ? AND owner_island_id = ? AND state IN ('RESERVED', 'PREPARING')
              AND version = ?
            """)) {
      statement.setLong(1, targetVersion);
      statement.setString(2, request.failedAt().toString());
      statement.setString(3, slot.slotId().toString());
      statement.setString(4, request.islandId().toString());
      statement.setLong(5, request.expectedSlotVersion());
      requireSingleUpdate(statement, "Slot changed during pre-world creation abort");
    }
  }

  private static void deactivateCreationMemberships(
      Connection connection, IslandCreationFailureRequest request) throws SQLException {
    deactivateCreationMemberships(connection, request.islandId(), request.failedAt().toString());
  }

  private static void deactivateCreationMemberships(
      Connection connection, IslandCreationCleanupCompletionRequest request) throws SQLException {
    deactivateCreationMemberships(connection, request.islandId(), request.completedAt().toString());
  }

  private static void deactivateCreationMemberships(
      Connection connection, IslandId islandId, String timestamp) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE island_memberships
            SET active = 0, owner = 0, updated_at = ?
            WHERE island_id = ? AND active = 1
            """)) {
      statement.setString(1, timestamp);
      statement.setString(2, islandId.toString());
      if (statement.executeUpdate() < 1) {
        throw new SQLException("Creation island has no active membership to deactivate");
      }
    }
  }

  private static void completeFailedCreationOperation(
      Connection connection, IslandCreationFailureRequest request, String outcome)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE operations
            SET state = 'COMPLETED', outcome_state = ?, outcome_payload = ?,
                completed_at = ?, updated_at = ?
            WHERE operation_id = ? AND island_id = ? AND kind = ?
              AND state IN ('ALLOCATED', 'PREPARING_WORLD') AND outcome_state IS NULL
            """)) {
      statement.setString(1, outcome);
      statement.setString(2, request.diagnostic());
      statement.setString(3, request.failedAt().toString());
      statement.setString(4, request.failedAt().toString());
      statement.setString(5, request.operationId().toString());
      statement.setString(6, request.islandId().toString());
      statement.setString(7, CREATION_KIND);
      requireSingleUpdate(statement, "Operation changed during pre-world creation abort");
    }
  }

  private static boolean isCleanupReplay(
      IslandAggregateSnapshot current,
      SlotRecord slot,
      FailureOperation operation,
      long targetIslandVersion,
      long targetSlotVersion,
      boolean clean,
      IslandCleanup.Status status) {
    if (current.version() != targetIslandVersion
        || slot.version() != targetSlotVersion
        || !operation.state().equals("COMPLETED")) {
      return false;
    }
    if (clean) {
      return current.lifecycleState() == IslandLifecycleState.ARCHIVED
          && slot.state() == SlotState.FREE
          && operation.completedWith("FAILED");
    }
    String expectedOutcome = status == IslandCleanup.Status.AMBIGUOUS ? "AMBIGUOUS" : "FAILED";
    return current.lifecycleState() == IslandLifecycleState.BROKEN
        && current.pendingOperationId().isEmpty()
        && slot.state() == SlotState.QUARANTINED
        && operation.completedWith(expectedOutcome);
  }

  private static void archiveAfterVerifiedCleanup(
      Connection connection, IslandCreationCleanupCompletionRequest request, long targetVersion)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE islands
            SET lifecycle_state = 'ARCHIVED', primary_slot_id = NULL, version = ?,
                pending_operation_id = NULL, lifecycle_lock_reason = NULL, updated_at = ?
            WHERE island_id = ? AND lifecycle_state = 'BROKEN' AND version = ?
              AND pending_operation_id = ?
            """)) {
      statement.setLong(1, targetVersion);
      statement.setString(2, request.completedAt().toString());
      statement.setString(3, request.islandId().toString());
      statement.setLong(4, request.expectedIslandVersion());
      statement.setString(5, request.operationId().toString());
      requireSingleUpdate(statement, "Island changed during verified creation cleanup");
    }
  }

  private static void releaseAfterVerifiedCleanup(
      Connection connection,
      IslandCreationCleanupCompletionRequest request,
      AllocatedSlot slot,
      long targetVersion)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE slots
            SET state = 'FREE', owner_island_id = NULL, ownership_role = NULL,
                version = ?, updated_at = ?
            WHERE slot_id = ? AND owner_island_id = ? AND state = 'CLEANING'
              AND version = ?
            """)) {
      statement.setLong(1, targetVersion);
      statement.setString(2, request.completedAt().toString());
      statement.setString(3, slot.slotId().toString());
      statement.setString(4, request.islandId().toString());
      statement.setLong(5, request.expectedSlotVersion());
      requireSingleUpdate(statement, "Slot changed during verified creation cleanup");
    }
  }

  private static void quarantineAfterCleanupFailure(
      Connection connection,
      IslandCreationCleanupCompletionRequest request,
      long targetIslandVersion,
      long targetSlotVersion,
      AllocatedSlot slot)
      throws SQLException {
    String lockPrefix =
        request.status() == IslandCleanup.Status.AMBIGUOUS
            ? "creation-cleanup-ambiguous: "
            : "creation-cleanup-failed: ";
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE islands
            SET version = ?, pending_operation_id = NULL,
                lifecycle_lock_reason = ?, updated_at = ?
            WHERE island_id = ? AND lifecycle_state = 'BROKEN' AND version = ?
              AND pending_operation_id = ?
            """)) {
      statement.setLong(1, targetIslandVersion);
      statement.setString(2, lockPrefix + request.diagnostic());
      statement.setString(3, request.completedAt().toString());
      statement.setString(4, request.islandId().toString());
      statement.setLong(5, request.expectedIslandVersion());
      statement.setString(6, request.operationId().toString());
      requireSingleUpdate(statement, "Island changed during creation quarantine");
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE slots
            SET state = 'QUARANTINED', version = ?, updated_at = ?
            WHERE slot_id = ? AND owner_island_id = ? AND state = 'CLEANING'
              AND version = ?
            """)) {
      statement.setLong(1, targetSlotVersion);
      statement.setString(2, request.completedAt().toString());
      statement.setString(3, slot.slotId().toString());
      statement.setString(4, request.islandId().toString());
      statement.setLong(5, request.expectedSlotVersion());
      requireSingleUpdate(statement, "Slot changed during creation quarantine");
    }
  }

  private static void completeCleanupOperation(
      Connection connection, IslandCreationCleanupCompletionRequest request, String outcome)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE operations
            SET state = 'COMPLETED', outcome_state = ?, outcome_payload = ?,
                completed_at = ?, updated_at = ?
            WHERE operation_id = ? AND island_id = ? AND kind = ?
              AND state = 'CLEANING_WORLD' AND outcome_state IS NULL
            """)) {
      statement.setString(1, outcome);
      statement.setString(2, request.diagnostic());
      statement.setString(3, request.completedAt().toString());
      statement.setString(4, request.completedAt().toString());
      statement.setString(5, request.operationId().toString());
      statement.setString(6, request.islandId().toString());
      statement.setString(7, CREATION_KIND);
      requireSingleUpdate(statement, "Operation changed during creation cleanup completion");
    }
  }

  private static void requireSingleUpdate(PreparedStatement statement, String diagnostic)
      throws SQLException {
    if (statement.executeUpdate() != 1) {
      throw new SQLException(diagnostic);
    }
  }

  private static ActivationOperation readActivationOperation(
      Connection connection, OperationId operationId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT island_id, state, slot_id, outcome_state, outcome_payload
            FROM operations
            WHERE operation_id = ? AND kind = ?
            """)) {
      statement.setString(1, operationId.toString());
      statement.setString(2, CREATION_KIND);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || result.getString("slot_id") == null) {
          throw new IslandCreationTransitionConflictException(
              "Activation operation does not exist");
        }
        return new ActivationOperation(
            IslandId.parse(result.getString("island_id")),
            result.getString("state"),
            SlotId.parse(result.getString("slot_id")),
            result.getString("outcome_state"),
            result.getString("outcome_payload"));
      }
    }
  }

  private static void verifyRequiredEffects(
      Connection connection, IslandCreationActivationRequest request) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT island_id, state
            FROM world_effect_receipts
            WHERE operation_id = ? AND effect_index = ?
            """)) {
      for (var key : request.requiredEffects()) {
        statement.setString(1, key.operationId().toString());
        statement.setInt(2, key.effectIndex());
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next()
              || !request.islandId().toString().equals(result.getString("island_id"))
              || !WorldEffectState.VERIFIED_SUCCESS.name().equals(result.getString("state"))) {
            throw new IslandCreationTransitionConflictException(
                "Activation requires every declared world effect to be verified");
          }
        }
      }
    }
  }

  private static void insertSpawn(Connection connection, IslandCreationActivationRequest request)
      throws SQLException {
    var spawn = request.primarySpawn();
    var position = spawn.position();
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO island_spawn_points (
                island_id, spawn_id, world_id, x, y, z, yaw, pitch,
                primary_spawn, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
            """)) {
      statement.setString(1, request.islandId().toString());
      statement.setString(2, spawn.spawnId().toString());
      statement.setString(3, position.worldId().toString());
      statement.setDouble(4, position.x());
      statement.setDouble(5, position.y());
      statement.setDouble(6, position.z());
      statement.setFloat(7, position.yaw());
      statement.setFloat(8, position.pitch());
      statement.setString(9, request.activatedAt().toString());
      statement.setString(10, request.activatedAt().toString());
      statement.executeUpdate();
    }
  }

  private static void insertProgression(
      Connection connection, IslandCreationActivationRequest request) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO island_progression (
                island_id, current_phase_id, version, created_at, updated_at
            ) VALUES (?, ?, 0, ?, ?)
            """)) {
      statement.setString(1, request.islandId().toString());
      statement.setString(2, request.initialPhaseId().toString());
      statement.setString(3, request.activatedAt().toString());
      statement.setString(4, request.activatedAt().toString());
      statement.executeUpdate();
    }
  }

  private static void insertMagicBlock(
      Connection connection, IslandCreationActivationRequest request) throws SQLException {
    var magic = request.magicBlock();
    var position = magic.position();
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO magic_blocks (
                island_id, magic_block_id, world_id, block_x, block_y, block_z,
                profile_id, current_content_id, state, sequence, last_persisted_sequence,
                cooldown_until, version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'READY', 0, 0, NULL, 0, ?, ?)
            """)) {
      statement.setString(1, request.islandId().toString());
      statement.setString(2, magic.magicBlockId().toString());
      statement.setString(3, position.worldId().toString());
      statement.setInt(4, position.x());
      statement.setInt(5, position.y());
      statement.setInt(6, position.z());
      statement.setString(7, magic.profileId().toString());
      statement.setString(8, magic.currentContentId().toString());
      statement.setString(9, request.activatedAt().toString());
      statement.setString(10, request.activatedAt().toString());
      statement.executeUpdate();
    }
  }

  private static void insertInitialCounters(
      Connection connection, IslandCreationActivationRequest request) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO counters (
                scope_type, scope_id, counter_id, value, version, created_at, updated_at
            ) VALUES ('ISLAND', ?, ?, 0, 0, ?, ?)
            """)) {
      for (String counterId : List.of("openoneblock:total_breaks", "openoneblock:phase_breaks")) {
        statement.setString(1, request.islandId().toString());
        statement.setString(2, counterId);
        statement.setString(3, request.activatedAt().toString());
        statement.setString(4, request.activatedAt().toString());
        statement.addBatch();
      }
      int[] counts = statement.executeBatch();
      if (counts.length != 2) {
        throw new SQLException("Initial creation counters were not inserted completely");
      }
    }
  }

  private static void updateActivationIsland(
      Connection connection, IslandCreationActivationRequest request, long targetVersion)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE islands
            SET lifecycle_state = 'ACTIVE', version = ?, pending_operation_id = NULL,
                lifecycle_lock_reason = NULL, updated_at = ?
            WHERE island_id = ? AND lifecycle_state = 'CREATING' AND version = ?
              AND pending_operation_id = ?
            """)) {
      statement.setLong(1, targetVersion);
      statement.setString(2, request.activatedAt().toString());
      statement.setString(3, request.islandId().toString());
      statement.setLong(4, request.expectedIslandVersion());
      statement.setString(5, request.operationId().toString());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("Island changed during activation");
      }
    }
  }

  private static void updateActivationSlot(
      Connection connection,
      IslandCreationActivationRequest request,
      AllocatedSlot slot,
      long targetVersion)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE slots
            SET state = 'ACTIVE', version = ?, updated_at = ?
            WHERE slot_id = ? AND owner_island_id = ? AND state = 'PREPARING' AND version = ?
            """)) {
      statement.setLong(1, targetVersion);
      statement.setString(2, request.activatedAt().toString());
      statement.setString(3, slot.slotId().toString());
      statement.setString(4, request.islandId().toString());
      statement.setLong(5, request.expectedSlotVersion());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("Slot changed during activation");
      }
    }
  }

  private static void completeActivationOperation(
      Connection connection, IslandCreationActivationRequest request) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE operations
            SET state = 'COMPLETED', outcome_state = 'SUCCEEDED', outcome_payload = ?,
                completed_at = ?, updated_at = ?
            WHERE operation_id = ? AND island_id = ? AND kind = ? AND state = 'PREPARING_WORLD'
              AND outcome_state IS NULL
            """)) {
      statement.setString(1, request.islandId().toString());
      statement.setString(2, request.activatedAt().toString());
      statement.setString(3, request.activatedAt().toString());
      statement.setString(4, request.operationId().toString());
      statement.setString(5, request.islandId().toString());
      statement.setString(6, CREATION_KIND);
      if (statement.executeUpdate() != 1) {
        throw new SQLException("Creation operation changed during activation");
      }
    }
  }

  private static void verifyActivationProjectionReplay(
      Connection connection, IslandCreationActivationRequest request) throws SQLException {
    verifyRequiredEffects(connection, request);
    var spawn = request.primarySpawn();
    var magic = request.magicBlock();
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT
                (SELECT COUNT(*) FROM island_spawn_points
                 WHERE island_id = ? AND spawn_id = ? AND world_id = ?
                   AND x = ? AND y = ? AND z = ? AND yaw = ? AND pitch = ?
                   AND primary_spawn = 1) AS spawn_count,
                (SELECT COUNT(*) FROM island_progression
                 WHERE island_id = ? AND current_phase_id = ?) AS progression_count,
                (SELECT COUNT(*) FROM counters
                 WHERE scope_type = 'ISLAND' AND scope_id = ?
                   AND counter_id IN (
                     'openoneblock:total_breaks', 'openoneblock:phase_breaks'
                   ) AND value = 0 AND version = 0) AS counter_count,
                (SELECT COUNT(*) FROM magic_blocks
                 WHERE island_id = ? AND magic_block_id = ? AND world_id = ?
                   AND block_x = ? AND block_y = ? AND block_z = ?
                   AND profile_id = ? AND current_content_id = ?
                   AND state = 'READY' AND sequence = 0) AS magic_count
            """)) {
      var spawnPosition = spawn.position();
      var magicPosition = magic.position();
      int parameter = 1;
      statement.setString(parameter++, request.islandId().toString());
      statement.setString(parameter++, spawn.spawnId().toString());
      statement.setString(parameter++, spawnPosition.worldId().toString());
      statement.setDouble(parameter++, spawnPosition.x());
      statement.setDouble(parameter++, spawnPosition.y());
      statement.setDouble(parameter++, spawnPosition.z());
      statement.setFloat(parameter++, spawnPosition.yaw());
      statement.setFloat(parameter++, spawnPosition.pitch());
      statement.setString(parameter++, request.islandId().toString());
      statement.setString(parameter++, request.initialPhaseId().toString());
      statement.setString(parameter++, request.islandId().toString());
      statement.setString(parameter++, request.islandId().toString());
      statement.setString(parameter++, magic.magicBlockId().toString());
      statement.setString(parameter++, magicPosition.worldId().toString());
      statement.setInt(parameter++, magicPosition.x());
      statement.setInt(parameter++, magicPosition.y());
      statement.setInt(parameter++, magicPosition.z());
      statement.setString(parameter++, magic.profileId().toString());
      statement.setString(parameter, magic.currentContentId().toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()
            || result.getInt("spawn_count") != 1
            || result.getInt("progression_count") != 1
            || result.getInt("counter_count") != 2
            || result.getInt("magic_count") != 1) {
          throw new IslandCreationOperationConflictException(
              "Completed creation replay does not match activation projections");
        }
      }
    }
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
    insertCreationContext(connection, request);
    insertIsland(connection, request, slot);
    insertOwnerMembership(connection, request);
    return findById(connection, request.islandId())
        .orElseThrow(() -> new SQLException("Inserted island could not be read back"));
  }

  private static IslandAggregateSnapshot findExistingOperation(
      Connection connection, IslandCreationRequest request) throws SQLException {
    ExistingCreationOperation existingOperation;
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT o.island_id, o.kind, o.slot_id, o.request_fingerprint,
                   o.state, o.outcome_state, o.outcome_payload, s.shard_group_id
            FROM operations o
            JOIN slots s ON s.slot_id = o.slot_id
            WHERE o.operation_id = ?
            """)) {
      statement.setString(1, request.operationId().toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return null;
        }
        if (!request.islandId().toString().equals(result.getString("island_id"))
            || !CREATION_KIND.equals(result.getString("kind"))
            || result.getString("slot_id") == null
            || !request.fingerprint().equals(result.getString("request_fingerprint"))) {
          throw new IslandCreationOperationConflictException(
              "Operation ID already belongs to a different creation intent");
        }
        existingOperation =
            new ExistingCreationOperation(
                result.getString("state"),
                result.getString("outcome_state"),
                result.getString("outcome_payload"),
                ShardGroupId.parse(result.getString("shard_group_id")));
      }
    }

    IslandAggregateSnapshot snapshot =
        findById(connection, request.islandId())
            .orElseThrow(
                () ->
                    new IslandCreationOperationConflictException(
                        "Creation operation has no persisted island outcome"));
    if (!snapshot.ownerId().equals(request.ownerId())
        || !existingOperation.shardGroupId().equals(request.shardGroupId())
        || snapshot.currentBorderSize() != request.initialBorderSize()
        || snapshot.maximumBorderSize() != request.maximumBorderSize()) {
      throw new IslandCreationOperationConflictException(
          "Operation ID already has a different creation outcome");
    }
    if (existingOperation.state().equals("COMPLETED")
        && (Objects.equals(existingOperation.outcomeState(), "FAILED")
            || Objects.equals(existingOperation.outcomeState(), "AMBIGUOUS"))) {
      throw new IslandCreationFailedException(
          Objects.requireNonNullElse(
              existingOperation.outcomePayload(), "stored island creation failure"),
          snapshot);
    }
    if (snapshot.primarySlot().isEmpty()) {
      throw new IslandCreationOperationConflictException(
          "Non-failed creation operation outcome no longer owns a slot");
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
          throw new IslandMembershipConflictException(
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
                operation_id, island_id, kind, state, slot_id, request_fingerprint,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
      statement.setString(1, request.operationId().toString());
      statement.setString(2, request.islandId().toString());
      statement.setString(3, CREATION_KIND);
      statement.setString(4, ALLOCATED_STATE);
      statement.setString(5, slot.slotId().toString());
      statement.setString(6, request.fingerprint());
      statement.setString(7, timestamp);
      statement.setString(8, timestamp);
      statement.executeUpdate();
    }
  }

  private static void insertCreationContext(Connection connection, IslandCreationRequest request)
      throws SQLException {
    IslandCreationContext context = request.context();
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO island_creation_contexts (
                operation_id, primary_world_id, profile_id, phase_id,
                starter_block_id, magic_block_y, minimum_y, maximum_y_exclusive
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
      statement.setString(1, request.operationId().toString());
      statement.setString(2, context.primaryWorldId().toString());
      statement.setString(3, context.profileId().toString());
      statement.setString(4, context.phaseId().toString());
      statement.setString(5, context.starterBlockId().toString());
      statement.setInt(6, context.magicBlockY());
      statement.setInt(7, context.minimumY());
      statement.setInt(8, context.maximumYExclusive());
      statement.executeUpdate();
    }
  }

  private static IslandCreationRequest readPendingRequest(ResultSet result) throws SQLException {
    IslandCreationContext context =
        new IslandCreationContext(
            WorldId.parse(result.getString("primary_world_id")),
            NamespacedId.parse(result.getString("profile_id")),
            NamespacedId.parse(result.getString("phase_id")),
            NamespacedId.parse(result.getString("starter_block_id")),
            result.getInt("magic_block_y"),
            result.getInt("minimum_y"),
            result.getInt("maximum_y_exclusive"));
    return new IslandCreationRequest(
        IslandId.parse(result.getString("island_id")),
        PlayerId.parse(result.getString("owner_player_id")),
        ShardGroupId.parse(result.getString("shard_group_id")),
        OperationId.parse(result.getString("pending_operation_id")),
        result.getInt("current_border_size"),
        result.getInt("maximum_border_size"),
        context,
        java.time.Instant.parse(result.getString("created_at")));
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

  private record ActivationOperation(
      IslandId islandId, String state, SlotId slotId, String outcomeState, String outcomePayload) {}

  private record FailureOperation(
      IslandId islandId, String state, SlotId slotId, String outcomeState, String outcomePayload) {
    private boolean completedWith(String expectedOutcome) {
      return state.equals("COMPLETED") && Objects.equals(outcomeState, expectedOutcome);
    }
  }

  private record ExistingCreationOperation(
      String state, String outcomeState, String outcomePayload, ShardGroupId shardGroupId) {}

  private record SlotRecord(
      SlotId slotId,
      ShardGroupId shardGroupId,
      GridPosition gridPosition,
      SlotState state,
      long version) {
    private SlotLocatorEntry releasedEntry(
        IslandId islandId, long releasedVersion, SlotState releasedState) {
      return new SlotLocatorEntry(
          shardGroupId, gridPosition, slotId, islandId, releasedState, releasedVersion);
    }
  }

  private record ReleaseTransition(
      IslandAggregateSnapshot snapshot, SlotLocatorEntry releasedEntry) {}

  private record CleanupTransition(
      IslandAggregateSnapshot snapshot, SlotLocatorEntry releasedEntry) {}

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
