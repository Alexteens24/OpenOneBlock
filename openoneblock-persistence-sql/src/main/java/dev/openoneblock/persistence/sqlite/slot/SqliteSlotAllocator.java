package dev.openoneblock.persistence.sqlite.slot;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.locator.CommittedSlotPublisher;
import dev.openoneblock.core.locator.LocatorPublishDecision;
import dev.openoneblock.core.locator.SlotLocatorEntry;
import dev.openoneblock.core.slot.AllocatedSlot;
import dev.openoneblock.core.slot.SlotAllocationRequest;
import dev.openoneblock.core.slot.SlotAllocator;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotState;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.SqliteImmediateTransactions;
import dev.openoneblock.persistence.sqlite.SqlitePersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** SQLite slot allocator using immediate transactions and post-commit locator publication. */
public final class SqliteSlotAllocator implements SlotAllocator {
  private static final String ALLOCATION_KIND = "SLOT_ALLOCATION";
  private static final String COMPLETED_STATE = "COMPLETED";

  private final SqliteImmediateTransactions transactions;
  private final SqliteSlotReservations reservations;
  private final CommittedSlotPublisher locatorPublisher;
  private final Executor databaseExecutor;

  /**
   * Creates an asynchronous SQLite slot allocator.
   *
   * @param connectionFactory SQLite connection source
   * @param geometryByShard validated grid geometry lookup
   * @param locatorPublisher post-commit locator projection
   * @param databaseExecutor shared executor reserved for database work
   */
  public SqliteSlotAllocator(
      SqliteConnectionFactory connectionFactory,
      Function<ShardGroupId, GridGeometry> geometryByShard,
      CommittedSlotPublisher locatorPublisher,
      Executor databaseExecutor) {
    this.transactions =
        new SqliteImmediateTransactions(
            Objects.requireNonNull(connectionFactory, "connectionFactory"), 12, 2, 30);
    this.reservations = new SqliteSlotReservations(geometryByShard);
    this.locatorPublisher = Objects.requireNonNull(locatorPublisher, "locatorPublisher");
    this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<AllocatedSlot> allocate(SlotAllocationRequest request) {
    Objects.requireNonNull(request, "request");
    try {
      return CompletableFuture.supplyAsync(() -> allocateAndPublish(request), databaseExecutor);
    } catch (RuntimeException exception) {
      return CompletableFuture.failedFuture(exception);
    }
  }

  private AllocatedSlot allocateAndPublish(SlotAllocationRequest request) {
    AllocatedSlot committed;
    try {
      committed = transactions.execute(connection -> allocateInTransaction(connection, request));
    } catch (SQLException exception) {
      throw new SqlitePersistenceException("Failed to allocate SQLite slot", exception);
    }

    SlotLocatorEntry entry = toLocatorEntry(committed);
    try {
      LocatorPublishDecision decision = locatorPublisher.publishCommitted(entry);
      if (decision == LocatorPublishDecision.CONFLICTED) {
        throw new IllegalStateException("Committed slot conflicts with locator projection");
      }
    } catch (RuntimeException exception) {
      throw new CommittedSlotPublicationException(committed, exception);
    }
    return committed;
  }

  private AllocatedSlot allocateInTransaction(Connection connection, SlotAllocationRequest request)
      throws SQLException {
    AllocatedSlot existing = findExistingOperation(connection, request);
    if (existing != null) {
      return existing;
    }

    AllocatedSlot allocated = reservations.reserve(connection, request);
    insertCompletedOperation(connection, request, allocated.slotId());
    return allocated;
  }

  private static AllocatedSlot findExistingOperation(
      Connection connection, SlotAllocationRequest request) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT o.island_id, o.kind, o.state, o.slot_id,
                   s.shard_group_id, s.ordinal, s.grid_x, s.grid_z,
                   s.state AS slot_state, s.owner_island_id, s.version
            FROM operations o
            LEFT JOIN slots s ON s.slot_id = o.slot_id
            WHERE o.operation_id = ?
            """)) {
      statement.setString(1, request.operationId().toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return null;
        }
        if (!request.islandId().toString().equals(result.getString("island_id"))
            || !ALLOCATION_KIND.equals(result.getString("kind"))
            || !COMPLETED_STATE.equals(result.getString("state"))
            || result.getString("slot_id") == null
            || !request.shardGroupId().toString().equals(result.getString("shard_group_id"))
            || !request.islandId().toString().equals(result.getString("owner_island_id"))) {
          throw new SlotAllocationOperationConflictException(
              "Operation ID already belongs to a different allocation outcome");
        }
        return readAllocatedSlot(result);
      }
    }
  }

  private static void insertCompletedOperation(
      Connection connection, SlotAllocationRequest request, SlotId slotId) throws SQLException {
    String timestamp = request.requestedAt().toString();
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
            INSERT INTO operations (
                operation_id, island_id, kind, state, slot_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
      insert.setString(1, request.operationId().toString());
      insert.setString(2, request.islandId().toString());
      insert.setString(3, ALLOCATION_KIND);
      insert.setString(4, COMPLETED_STATE);
      insert.setString(5, slotId.toString());
      insert.setString(6, timestamp);
      insert.setString(7, timestamp);
      insert.executeUpdate();
    }
  }

  private static AllocatedSlot readAllocatedSlot(ResultSet result) throws SQLException {
    SlotState state = SlotState.valueOf(result.getString("slot_state"));
    if (state == SlotState.FREE) {
      throw new SlotAllocationOperationConflictException(
          "Existing allocation outcome points to a slot that has since been released");
    }
    return new AllocatedSlot(
        SlotId.parse(result.getString("slot_id")),
        ShardGroupId.parse(result.getString("shard_group_id")),
        result.getLong("ordinal"),
        new GridPosition(result.getInt("grid_x"), result.getInt("grid_z")),
        state,
        IslandId.parse(result.getString("owner_island_id")),
        result.getLong("version"));
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
}
