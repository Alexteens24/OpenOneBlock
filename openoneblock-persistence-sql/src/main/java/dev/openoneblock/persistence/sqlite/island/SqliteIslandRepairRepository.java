package dev.openoneblock.persistence.sqlite.island;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.grid.HorizontalBounds;
import dev.openoneblock.core.island.IslandAggregateSnapshot;
import dev.openoneblock.core.island.IslandRepairCompletion;
import dev.openoneblock.core.island.IslandRepairConflictException;
import dev.openoneblock.core.island.IslandRepairEvidence;
import dev.openoneblock.core.island.IslandRepairProgress;
import dev.openoneblock.core.island.IslandRepairRepository;
import dev.openoneblock.core.island.IslandRepairRequest;
import dev.openoneblock.core.lifecycle.IslandLifecyclePolicy;
import dev.openoneblock.core.lifecycle.TransitionDecision;
import dev.openoneblock.core.locator.CommittedSlotPublisher;
import dev.openoneblock.core.locator.LocatorPublishDecision;
import dev.openoneblock.core.locator.SlotLocatorEntry;
import dev.openoneblock.core.slot.AllocatedSlot;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotLifecyclePolicy;
import dev.openoneblock.core.slot.SlotState;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/** SQLite repair transaction that reconciles broken ownership into maintenance lock only. */
public final class SqliteIslandRepairRepository implements IslandRepairRepository {
  private static final String REPAIR_KIND = "ISLAND_REPAIR";
  private static final String SNAPSHOT_COLUMNS =
      """
      i.island_id, i.owner_player_id, i.lifecycle_state, i.current_border_size,
      i.maximum_border_size, i.version AS island_version, i.pending_operation_id,
      i.created_at AS island_created_at, i.updated_at AS island_updated_at,
      s.slot_id, s.shard_group_id, s.ordinal, s.grid_x, s.grid_z,
      s.state AS slot_state, s.owner_island_id, s.version AS slot_version
      """;

  private final SqliteImmediateTransactions transactions;
  private final Function<ShardGroupId, GridGeometry> geometryByShard;
  private final CommittedSlotPublisher locator;
  private final CommittedIslandProtectionPublisher protectionPublisher;
  private final Executor databaseExecutor;

  /**
   * Creates the repair repository without a gameplay projection publisher.
   *
   * @param connectionFactory SQLite connection source
   * @param geometryByShard validated grid geometry lookup
   * @param locator committed runtime slot projection
   * @param databaseExecutor executor reserved for SQL work
   */
  public SqliteIslandRepairRepository(
      SqliteConnectionFactory connectionFactory,
      Function<ShardGroupId, GridGeometry> geometryByShard,
      CommittedSlotPublisher locator,
      Executor databaseExecutor) {
    this(
        connectionFactory,
        geometryByShard,
        locator,
        CommittedIslandProtectionPublisher.NO_OP,
        databaseExecutor);
  }

  /**
   * Creates the authoritative repair repository.
   *
   * @param connectionFactory SQLite connection source
   * @param geometryByShard validated grid geometry lookup
   * @param locator committed runtime slot projection
   * @param protectionPublisher post-commit gameplay projection
   * @param databaseExecutor executor reserved for SQL work
   */
  public SqliteIslandRepairRepository(
      SqliteConnectionFactory connectionFactory,
      Function<ShardGroupId, GridGeometry> geometryByShard,
      CommittedSlotPublisher locator,
      CommittedIslandProtectionPublisher protectionPublisher,
      Executor databaseExecutor) {
    this.transactions = new SqliteImmediateTransactions(connectionFactory, 12, 2, 30);
    this.geometryByShard = Objects.requireNonNull(geometryByShard, "geometryByShard");
    this.locator = Objects.requireNonNull(locator, "locator");
    this.protectionPublisher = Objects.requireNonNull(protectionPublisher, "protectionPublisher");
    this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<IslandRepairProgress> beginRepair(IslandRepairRequest request) {
    Objects.requireNonNull(request, "request");
    return supplyAsync(() -> beginAndPublish(request));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<IslandRepairProgress> completeRepair(IslandRepairCompletion completion) {
    Objects.requireNonNull(completion, "completion");
    return supplyAsync(() -> completeAndPublish(completion));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<List<IslandRepairRequest>> findPendingRepairs() {
    return supplyAsync(this::findPending);
  }

  private IslandRepairProgress beginAndPublish(IslandRepairRequest request) {
    try {
      BeginOutcome outcome = transactions.execute(connection -> begin(connection, request));
      if (outcome.publishSlot()) {
        publishSlot(outcome.progress().island().primarySlot().orElseThrow());
      }
      protectionPublisher.publishCommitted(request.islandId());
      return outcome.progress();
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to begin SQLite island repair", exception);
    }
  }

  private IslandRepairProgress completeAndPublish(IslandRepairCompletion completion) {
    try {
      CompleteOutcome outcome =
          transactions.execute(connection -> complete(connection, completion));
      if (outcome.publishSlot()) {
        publishSlot(outcome.progress().island().primarySlot().orElseThrow());
      }
      protectionPublisher.publishCommitted(completion.islandId());
      return outcome.progress();
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to complete SQLite island repair", exception);
    }
  }

  private static BeginOutcome begin(Connection connection, IslandRepairRequest request)
      throws SQLException {
    Optional<OperationRow> existing = findOperation(connection, request.operationId());
    if (existing.isPresent()) {
      OperationRow operation = existing.orElseThrow();
      validateOperation(operation, request);
      return new BeginOutcome(progressForOperation(connection, operation, true), false);
    }
    IslandAggregateSnapshot current =
        findSnapshot(connection, request.islandId())
            .orElseThrow(() -> new IslandRepairConflictException("Unknown island"));
    AllocatedSlot slot = current.primarySlot().orElseThrow();
    if (current.lifecycleState() != IslandLifecycleState.BROKEN
        || slot.state() != SlotState.QUARANTINED
        || current.pendingOperationId().isPresent()) {
      throw new IslandRepairConflictException(
          "Repair requires idle BROKEN island with a QUARANTINED primary slot");
    }
    if (current.version() != request.expectedIslandVersion()
        || slot.version() != request.expectedSlotVersion()) {
      throw new IslandRepairConflictException("Repair confirmation versions are stale");
    }
    requireExclusiveSlotOwnership(connection, current, slot);
    requireNoOtherPendingOperation(connection, request.islandId(), null);
    insertOperation(connection, request, slot.slotId());
    insertContext(connection, request);
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE islands
            SET pending_operation_id = ?, lifecycle_lock_reason = 'openoneblock:repair-verifying',
                version = version + 1, updated_at = ?
            WHERE island_id = ? AND lifecycle_state = 'BROKEN' AND version = ?
              AND pending_operation_id IS NULL
            """)) {
      statement.setString(1, request.operationId().toString());
      statement.setString(2, request.requestedAt().toString());
      statement.setString(3, request.islandId().toString());
      statement.setLong(4, request.expectedIslandVersion());
      requireOne(statement, "Island changed before repair admission");
    }
    IslandAggregateSnapshot verifying = findSnapshot(connection, request.islandId()).orElseThrow();
    return new BeginOutcome(
        new IslandRepairProgress(
            verifying, IslandRepairProgress.Status.VERIFYING, false, "awaiting verification"),
        true);
  }

  private CompleteOutcome complete(Connection connection, IslandRepairCompletion completion)
      throws SQLException {
    OperationRow operation =
        findOperation(connection, completion.operationId())
            .orElseThrow(() -> new IslandRepairConflictException("Unknown repair operation"));
    if (!operation.kind().equals(REPAIR_KIND)
        || !operation.islandId().equals(completion.islandId())) {
      throw new IslandRepairConflictException("Operation is not this island repair");
    }
    if (operation.state().equals("COMPLETED")) {
      return new CompleteOutcome(progressForOperation(connection, operation, true), false);
    }
    if (!operation.state().equals("VERIFYING") || operation.outcomeState() != null) {
      throw new IslandRepairConflictException("Repair operation is not awaiting verification");
    }
    IslandAggregateSnapshot current =
        findSnapshot(connection, completion.islandId())
            .orElseThrow(() -> new IslandRepairConflictException("Repair island disappeared"));
    AllocatedSlot slot = current.primarySlot().orElseThrow();
    if (current.lifecycleState() != IslandLifecycleState.BROKEN
        || slot.state() != SlotState.QUARANTINED
        || !current.pendingOperationId().equals(Optional.of(completion.operationId()))) {
      throw new IslandRepairConflictException("Island is not in exact repair verification state");
    }
    if (current.version() != completion.expectedIslandVersion()
        || slot.version() != completion.expectedSlotVersion()) {
      throw new IslandRepairConflictException("Repair evidence uses stale versions");
    }

    IslandRepairEvidence evidence = completion.evidence();
    Optional<String> sqlFailure = Optional.empty();
    boolean internalAmbiguous = false;
    if (evidence.status() == IslandRepairEvidence.Status.VERIFIED) {
      try {
        sqlFailure = validateSqlState(connection, current, slot, completion, evidence);
      } catch (RuntimeException failure) {
        internalAmbiguous = true;
        sqlFailure = Optional.of(internalDiagnostic(failure));
      }
    }
    if (evidence.status() == IslandRepairEvidence.Status.VERIFIED && sqlFailure.isEmpty()) {
      return commitLocked(connection, current, slot, completion);
    }
    IslandRepairProgress.Status status =
        evidence.status() == IslandRepairEvidence.Status.AMBIGUOUS || internalAmbiguous
            ? IslandRepairProgress.Status.AMBIGUOUS
            : IslandRepairProgress.Status.FAILED;
    String diagnostic = sqlFailure.orElse(evidence.diagnostic());
    return commitBroken(connection, current, completion, status, diagnostic);
  }

  private Optional<String> validateSqlState(
      Connection connection,
      IslandAggregateSnapshot island,
      AllocatedSlot slot,
      IslandRepairCompletion completion,
      IslandRepairEvidence evidence)
      throws SQLException {
    try {
      requireExclusiveSlotOwnership(connection, island, slot);
      requireNoOtherPendingOperation(connection, island.islandId(), completion.operationId());
    } catch (IslandRepairConflictException conflict) {
      return Optional.of(conflict.getMessage());
    }
    GridGeometry geometry = geometryByShard.apply(slot.shardGroupId());
    if (island.maximumBorderSize() > geometry.configuration().maximumBorder()) {
      return Optional.of("island maximum border exceeds shard reservation");
    }
    HorizontalBounds reserved = geometry.reservedRegion(slot.gridPosition());
    Set<String> worldIds = new HashSet<>();
    for (WorldId worldId : evidence.verifiedWorldIds()) {
      if (!worldIds.add(worldId.toString())) {
        return Optional.of("verified world projection list contains duplicates");
      }
    }
    Optional<String> spawnFailure =
        validateSpawns(connection, island.islandId(), reserved, worldIds, completion);
    if (spawnFailure.isPresent()) {
      return spawnFailure;
    }
    return validateMagicBlocks(connection, island.islandId(), reserved, worldIds, completion);
  }

  private static Optional<String> validateSpawns(
      Connection connection,
      IslandId islandId,
      HorizontalBounds reserved,
      Set<String> worldIds,
      IslandRepairCompletion completion)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT world_id, x, y, z, primary_spawn
            FROM island_spawn_points WHERE island_id = ?
            """)) {
      statement.setString(1, islandId.toString());
      int total = 0;
      int primary = 0;
      int minimumY = contextMinimumY(connection, completion.operationId());
      int maximumY = contextMaximumY(connection, completion.operationId());
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          total++;
          primary += result.getInt("primary_spawn");
          if (!validLocation(
              result.getString("world_id"),
              result.getDouble("x"),
              result.getDouble("y"),
              result.getDouble("z"),
              reserved,
              worldIds,
              minimumY,
              maximumY)) {
            return Optional.of("spawn lies outside verified reserved world region");
          }
        }
      }
      return total > 0 && primary == 1
          ? Optional.empty()
          : Optional.of("repair requires exactly one primary spawn");
    }
  }

  private static Optional<String> validateMagicBlocks(
      Connection connection,
      IslandId islandId,
      HorizontalBounds reserved,
      Set<String> worldIds,
      IslandRepairCompletion completion)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT magic_block_id, world_id, block_x, block_y, block_z, state
            FROM magic_blocks WHERE island_id = ?
            """)) {
      statement.setString(1, islandId.toString());
      boolean known = false;
      boolean main = false;
      int minimumY = contextMinimumY(connection, completion.operationId());
      int maximumY = contextMaximumY(connection, completion.operationId());
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          known = true;
          main |= result.getString("magic_block_id").equals("openoneblock:main");
          String state = result.getString("state");
          if ((!state.equals("READY") && !state.equals("LOCKED"))
              || !validLocation(
                  result.getString("world_id"),
                  result.getInt("block_x"),
                  result.getInt("block_y"),
                  result.getInt("block_z"),
                  reserved,
                  worldIds,
                  minimumY,
                  maximumY)) {
            return Optional.of("Magic Block is not in a known recoverable reserved-region state");
          }
        }
      }
      return known && main
          ? Optional.empty()
          : Optional.of("repair requires a recoverable openoneblock:main Magic Block");
    }
  }

  private static boolean validLocation(
      String worldId,
      double x,
      double y,
      double z,
      HorizontalBounds reserved,
      Set<String> worldIds,
      int minimumY,
      int maximumYExclusive) {
    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
      return false;
    }
    int blockX = (int) Math.floor(x);
    int blockZ = (int) Math.floor(z);
    return worldIds.contains(worldId)
        && reserved.contains(blockX, blockZ)
        && y >= minimumY
        && y < maximumYExclusive;
  }

  private static CompleteOutcome commitLocked(
      Connection connection,
      IslandAggregateSnapshot island,
      AllocatedSlot slot,
      IslandRepairCompletion completion)
      throws SQLException {
    requireTransition(
        IslandLifecyclePolicy.evaluate(island.lifecycleState(), IslandLifecycleState.LOCKED));
    requireTransition(SlotLifecyclePolicy.evaluate(slot.state(), SlotState.ACTIVE));
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE islands
            SET lifecycle_state = 'LOCKED', pending_operation_id = NULL,
                lifecycle_lock_reason = 'openoneblock:repair-completed',
                version = version + 1, updated_at = ?
            WHERE island_id = ? AND lifecycle_state = 'BROKEN' AND version = ?
              AND pending_operation_id = ?
            """)) {
      statement.setString(1, completion.evidence().observedAt().toString());
      statement.setString(2, completion.islandId().toString());
      statement.setLong(3, completion.expectedIslandVersion());
      statement.setString(4, completion.operationId().toString());
      requireOne(statement, "Island changed while committing repair lock");
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE slots SET state = 'ACTIVE', version = version + 1, updated_at = ?
            WHERE slot_id = ? AND state = 'QUARANTINED' AND owner_island_id = ? AND version = ?
              AND ownership_role = 'PRIMARY'
            """)) {
      statement.setString(1, completion.evidence().observedAt().toString());
      statement.setString(2, slot.slotId().toString());
      statement.setString(3, completion.islandId().toString());
      statement.setLong(4, completion.expectedSlotVersion());
      requireOne(statement, "Slot changed while committing repaired ownership");
    }
    completeOperation(connection, completion, "SUCCEEDED", completion.evidence().diagnostic());
    IslandAggregateSnapshot locked = findSnapshot(connection, completion.islandId()).orElseThrow();
    return new CompleteOutcome(
        new IslandRepairProgress(
            locked, IslandRepairProgress.Status.LOCKED, false, completion.evidence().diagnostic()),
        true);
  }

  private static CompleteOutcome commitBroken(
      Connection connection,
      IslandAggregateSnapshot island,
      IslandRepairCompletion completion,
      IslandRepairProgress.Status status,
      String diagnostic)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE islands
            SET pending_operation_id = NULL, lifecycle_lock_reason = 'openoneblock:repair-failed',
                version = version + 1, updated_at = ?
            WHERE island_id = ? AND lifecycle_state = 'BROKEN' AND version = ?
              AND pending_operation_id = ?
            """)) {
      statement.setString(1, completion.evidence().observedAt().toString());
      statement.setString(2, completion.islandId().toString());
      statement.setLong(3, island.version());
      statement.setString(4, completion.operationId().toString());
      requireOne(statement, "Island changed while rejecting repair");
    }
    String outcome = status == IslandRepairProgress.Status.AMBIGUOUS ? "AMBIGUOUS" : "FAILED";
    completeOperation(connection, completion, outcome, diagnostic);
    IslandAggregateSnapshot broken = findSnapshot(connection, completion.islandId()).orElseThrow();
    return new CompleteOutcome(new IslandRepairProgress(broken, status, false, diagnostic), true);
  }

  private List<IslandRepairRequest> findPending() {
    try {
      return transactions.execute(SqliteIslandRepairRepository::findPendingInTransaction);
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to query pending island repairs", exception);
    }
  }

  private static List<IslandRepairRequest> findPendingInTransaction(Connection connection)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT c.operation_id, o.island_id, c.requested_by_player_id,
                   c.expected_island_version, c.expected_slot_version,
                   c.minimum_y, c.maximum_y_exclusive, c.requested_at
            FROM island_repair_contexts c
            JOIN operations o ON o.operation_id = c.operation_id
            JOIN islands i ON i.island_id = o.island_id
            JOIN slots s ON s.slot_id = i.primary_slot_id
            WHERE o.kind = 'ISLAND_REPAIR' AND o.state = 'VERIFYING'
              AND o.outcome_state IS NULL AND i.lifecycle_state = 'BROKEN'
              AND i.pending_operation_id = o.operation_id AND s.state = 'QUARANTINED'
            ORDER BY c.requested_at, c.operation_id
            """)) {
      try (ResultSet result = statement.executeQuery()) {
        List<IslandRepairRequest> requests = new ArrayList<>();
        while (result.next()) {
          requests.add(
              new IslandRepairRequest(
                  IslandId.parse(result.getString("island_id")),
                  OperationId.parse(result.getString("operation_id")),
                  PlayerId.parse(result.getString("requested_by_player_id")),
                  result.getLong("expected_island_version"),
                  result.getLong("expected_slot_version"),
                  result.getInt("minimum_y"),
                  result.getInt("maximum_y_exclusive"),
                  Instant.parse(result.getString("requested_at"))));
        }
        return List.copyOf(requests);
      }
    } catch (IllegalArgumentException exception) {
      throw new SQLException("Invalid persisted repair context", exception);
    }
  }

  private static void insertOperation(
      Connection connection, IslandRepairRequest request, SlotId slotId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO operations (
                operation_id, island_id, kind, state, slot_id, request_fingerprint,
                outcome_state, outcome_payload, completed_at, created_at, updated_at
            ) VALUES (?, ?, 'ISLAND_REPAIR', 'VERIFYING', ?, ?, NULL, NULL, NULL, ?, ?)
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

  private static void insertContext(Connection connection, IslandRepairRequest request)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO island_repair_contexts (
                operation_id, requested_by_player_id, expected_island_version,
                expected_slot_version, minimum_y, maximum_y_exclusive, requested_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
      statement.setString(1, request.operationId().toString());
      statement.setString(2, request.requestedBy().toString());
      statement.setLong(3, request.expectedIslandVersion());
      statement.setLong(4, request.expectedSlotVersion());
      statement.setInt(5, request.minimumY());
      statement.setInt(6, request.maximumYExclusive());
      statement.setString(7, request.requestedAt().toString());
      statement.executeUpdate();
    }
  }

  private static int contextMinimumY(Connection connection, OperationId operationId)
      throws SQLException {
    return contextHeight(connection, operationId, "minimum_y");
  }

  private static int contextMaximumY(Connection connection, OperationId operationId)
      throws SQLException {
    return contextHeight(connection, operationId, "maximum_y_exclusive");
  }

  private static int contextHeight(Connection connection, OperationId operationId, String column)
      throws SQLException {
    String sql = "SELECT " + column + " FROM island_repair_contexts WHERE operation_id = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, operationId.toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new IslandRepairConflictException("Repair context disappeared");
        }
        return result.getInt(1);
      }
    }
  }

  private static void completeOperation(
      Connection connection, IslandRepairCompletion completion, String outcome, String diagnostic)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE operations
            SET state = 'COMPLETED', outcome_state = ?, outcome_payload = ?,
                completed_at = ?, updated_at = ?
            WHERE operation_id = ? AND island_id = ? AND kind = 'ISLAND_REPAIR'
              AND state = 'VERIFYING' AND outcome_state IS NULL
            """)) {
      statement.setString(1, outcome);
      statement.setString(2, diagnostic);
      statement.setString(3, completion.evidence().observedAt().toString());
      statement.setString(4, completion.evidence().observedAt().toString());
      statement.setString(5, completion.operationId().toString());
      statement.setString(6, completion.islandId().toString());
      requireOne(statement, "Repair operation changed before completion");
    }
  }

  private static void requireExclusiveSlotOwnership(
      Connection connection, IslandAggregateSnapshot island, AllocatedSlot slot)
      throws SQLException {
    if (!slot.ownerIslandId().equals(island.islandId())) {
      throw new IslandRepairConflictException("Slot owner does not match island");
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT
              (SELECT COUNT(*) FROM islands WHERE primary_slot_id = ?),
              (SELECT COUNT(*) FROM slots
               WHERE slot_id = ? AND owner_island_id = ? AND ownership_role = 'PRIMARY')
            """)) {
      statement.setString(1, slot.slotId().toString());
      statement.setString(2, slot.slotId().toString());
      statement.setString(3, island.islandId().toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || result.getInt(1) != 1 || result.getInt(2) != 1) {
          throw new IslandRepairConflictException("Slot ownership is not exclusive and primary");
        }
      }
    }
  }

  private static void requireNoOtherPendingOperation(
      Connection connection, IslandId islandId, OperationId allowed) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT COUNT(*) FROM operations
            WHERE island_id = ? AND outcome_state IS NULL AND state <> 'COMPLETED'
              AND (? IS NULL OR operation_id <> ?)
            """)) {
      statement.setString(1, islandId.toString());
      String allowedId = allowed == null ? null : allowed.toString();
      statement.setString(2, allowedId);
      statement.setString(3, allowedId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || result.getInt(1) != 0) {
          throw new IslandRepairConflictException("Another non-terminal island operation exists");
        }
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
      throw new SQLException("Invalid persisted repair operation", exception);
    }
  }

  private static void validateOperation(OperationRow operation, IslandRepairRequest request) {
    if (!operation.kind().equals(REPAIR_KIND)
        || !operation.islandId().equals(request.islandId())
        || !Objects.equals(operation.fingerprint(), request.fingerprint())) {
      throw new IslandRepairConflictException(
          "Operation ID already belongs to a different repair intent");
    }
  }

  private static IslandRepairProgress progressForOperation(
      Connection connection, OperationRow operation, boolean replay) throws SQLException {
    IslandAggregateSnapshot island =
        findSnapshot(connection, operation.islandId())
            .orElseThrow(() -> new SQLException("Repair operation island disappeared"));
    if (!operation.state().equals("COMPLETED")) {
      return new IslandRepairProgress(
          island, IslandRepairProgress.Status.VERIFYING, replay, "awaiting verification");
    }
    IslandRepairProgress.Status status =
        switch (Objects.requireNonNull(operation.outcomeState(), "terminal repair outcome")) {
          case "SUCCEEDED" -> IslandRepairProgress.Status.LOCKED;
          case "FAILED" -> IslandRepairProgress.Status.FAILED;
          case "AMBIGUOUS" -> IslandRepairProgress.Status.AMBIGUOUS;
          default -> throw new SQLException("Unsupported repair outcome state");
        };
    return new IslandRepairProgress(
        island,
        status,
        replay,
        Objects.requireNonNullElse(operation.outcomePayload(), "missing repair diagnostic"));
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
    } catch (IllegalArgumentException exception) {
      throw new SQLException("Invalid persisted repair snapshot", exception);
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
        throw new IllegalStateException("Committed repair slot conflicts with locator");
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

  private static void requireTransition(TransitionDecision decision) {
    if (decision != TransitionDecision.ALLOWED) {
      throw new IslandRepairConflictException("Illegal repair lifecycle transition: " + decision);
    }
  }

  private static String internalDiagnostic(RuntimeException failure) {
    String message = failure.getMessage();
    String diagnostic =
        "repair invariant verifier threw "
            + failure.getClass().getSimpleName()
            + (message == null ? "" : ": " + message);
    return diagnostic.length() <= 2_048 ? diagnostic : diagnostic.substring(0, 2_048);
  }

  private static void requireOne(PreparedStatement statement, String message) throws SQLException {
    if (statement.executeUpdate() != 1) {
      throw new IslandRepairConflictException(message);
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

  private record BeginOutcome(IslandRepairProgress progress, boolean publishSlot) {}

  private record CompleteOutcome(IslandRepairProgress progress, boolean publishSlot) {}
}
