package dev.openoneblock.persistence.sqlite.slot;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.grid.SquareSpiral;
import dev.openoneblock.core.slot.AllocatedSlot;
import dev.openoneblock.core.slot.SlotAllocationRequest;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotState;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Function;

/** Transaction-scoped SQLite primitive for reserving a slot without committing or publishing it. */
public final class SqliteSlotReservations {
  private final Function<ShardGroupId, GridGeometry> geometryByShard;

  /**
   * Creates the reservation primitive.
   *
   * @param geometryByShard validated grid geometry lookup
   */
  public SqliteSlotReservations(Function<ShardGroupId, GridGeometry> geometryByShard) {
    this.geometryByShard = Objects.requireNonNull(geometryByShard, "geometryByShard");
  }

  /**
   * Reserves the lowest reusable slot or creates the next spiral slot in the caller's transaction.
   *
   * <p>The caller owns transaction boundaries, operation idempotency, and post-commit locator
   * publication.
   *
   * @param connection active SQLite write transaction
   * @param request allocation metadata
   * @return reserved slot visible inside the transaction
   * @throws SQLException on a storage or contention failure
   */
  public AllocatedSlot reserve(Connection connection, SlotAllocationRequest request)
      throws SQLException {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(request, "request");
    AllocatedSlot allocated = reserveFreeSlot(connection, request);
    return allocated != null ? allocated : createSlot(connection, request);
  }

  private static AllocatedSlot reserveFreeSlot(Connection connection, SlotAllocationRequest request)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
            SELECT slot_id, ordinal, grid_x, grid_z, version
            FROM slots
            WHERE shard_group_id = ? AND state = 'FREE'
            ORDER BY ordinal
            LIMIT 1
            """)) {
      select.setString(1, request.shardGroupId().toString());
      try (ResultSet result = select.executeQuery()) {
        if (!result.next()) {
          return null;
        }
        SlotId slotId = SlotId.parse(result.getString("slot_id"));
        long ordinal = result.getLong("ordinal");
        GridPosition position = new GridPosition(result.getInt("grid_x"), result.getInt("grid_z"));
        long previousVersion = result.getLong("version");
        long nextVersion = Math.incrementExact(previousVersion);
        updateFreeSlot(connection, request, slotId, previousVersion, nextVersion);
        return new AllocatedSlot(
            slotId,
            request.shardGroupId(),
            ordinal,
            position,
            SlotState.RESERVED,
            request.islandId(),
            nextVersion);
      }
    }
  }

  private static void updateFreeSlot(
      Connection connection,
      SlotAllocationRequest request,
      SlotId slotId,
      long previousVersion,
      long nextVersion)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
            UPDATE slots
            SET state = 'RESERVED', owner_island_id = ?, ownership_role = 'PRIMARY',
                version = ?, updated_at = ?
            WHERE slot_id = ? AND state = 'FREE' AND version = ?
            """)) {
      update.setString(1, request.islandId().toString());
      update.setLong(2, nextVersion);
      update.setString(3, request.requestedAt().toString());
      update.setString(4, slotId.toString());
      update.setLong(5, previousVersion);
      if (update.executeUpdate() != 1) {
        throw new SQLException("Selected FREE slot changed before reservation");
      }
    }
  }

  private AllocatedSlot createSlot(Connection connection, SlotAllocationRequest request)
      throws SQLException {
    ensureAllocatorRow(connection, request.shardGroupId());
    AllocatorCursor cursor = readAllocatorCursor(connection, request.shardGroupId());
    long nextOrdinal = Math.incrementExact(cursor.nextOrdinal());
    GridPosition position = SquareSpiral.positionAt(cursor.nextOrdinal());
    GridGeometry geometry =
        Objects.requireNonNull(
            geometryByShard.apply(request.shardGroupId()),
            "No grid geometry configured for shard " + request.shardGroupId());
    geometry.fullCell(position);
    advanceAllocator(connection, request.shardGroupId(), cursor, nextOrdinal);

    SlotId slotId = SlotId.generate();
    insertSlot(connection, request, slotId, cursor.nextOrdinal(), position);
    return new AllocatedSlot(
        slotId,
        request.shardGroupId(),
        cursor.nextOrdinal(),
        position,
        SlotState.RESERVED,
        request.islandId(),
        0);
  }

  private static void ensureAllocatorRow(Connection connection, ShardGroupId shardGroupId)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
            INSERT INTO shard_allocators (shard_group_id, next_ordinal, version)
            VALUES (?, 0, 0)
            ON CONFLICT (shard_group_id) DO NOTHING
            """)) {
      insert.setString(1, shardGroupId.toString());
      insert.executeUpdate();
    }
  }

  private static AllocatorCursor readAllocatorCursor(
      Connection connection, ShardGroupId shardGroupId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT next_ordinal, version FROM shard_allocators WHERE shard_group_id = ?")) {
      select.setString(1, shardGroupId.toString());
      try (ResultSet result = select.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("Missing shard allocator row after initialization");
        }
        return new AllocatorCursor(result.getLong("next_ordinal"), result.getLong("version"));
      }
    }
  }

  private static void advanceAllocator(
      Connection connection, ShardGroupId shardGroupId, AllocatorCursor cursor, long nextOrdinal)
      throws SQLException {
    long nextVersion = Math.incrementExact(cursor.version());
    try (PreparedStatement update =
        connection.prepareStatement(
            """
            UPDATE shard_allocators
            SET next_ordinal = ?, version = ?
            WHERE shard_group_id = ? AND next_ordinal = ? AND version = ?
            """)) {
      update.setLong(1, nextOrdinal);
      update.setLong(2, nextVersion);
      update.setString(3, shardGroupId.toString());
      update.setLong(4, cursor.nextOrdinal());
      update.setLong(5, cursor.version());
      if (update.executeUpdate() != 1) {
        throw new SQLException("Shard allocator cursor changed unexpectedly");
      }
    }
  }

  private static void insertSlot(
      Connection connection,
      SlotAllocationRequest request,
      SlotId slotId,
      long ordinal,
      GridPosition position)
      throws SQLException {
    String timestamp = request.requestedAt().toString();
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
            INSERT INTO slots (
                slot_id, shard_group_id, ordinal, grid_x, grid_z, state,
                owner_island_id, ownership_role, version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, 'RESERVED', ?, 'PRIMARY', 0, ?, ?)
            """)) {
      insert.setString(1, slotId.toString());
      insert.setString(2, request.shardGroupId().toString());
      insert.setLong(3, ordinal);
      insert.setInt(4, position.gridX());
      insert.setInt(5, position.gridZ());
      insert.setString(6, request.islandId().toString());
      insert.setString(7, timestamp);
      insert.setString(8, timestamp);
      insert.executeUpdate();
    }
  }

  private record AllocatorCursor(long nextOrdinal, long version) {}
}
