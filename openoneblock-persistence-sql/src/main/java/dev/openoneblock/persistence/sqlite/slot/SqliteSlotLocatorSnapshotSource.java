package dev.openoneblock.persistence.sqlite.slot;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.core.locator.SlotLocatorEntry;
import dev.openoneblock.core.locator.SlotLocatorSnapshotSource;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotState;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.SqlitePersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Reads the minimal authoritative non-free slot projection used to rebuild the startup index. */
public final class SqliteSlotLocatorSnapshotSource implements SlotLocatorSnapshotSource {
  private final SqliteConnectionFactory connectionFactory;
  private final Executor databaseExecutor;

  /**
   * Creates a snapshot source.
   *
   * @param connectionFactory SQLite connection source
   * @param databaseExecutor shared executor reserved for database work
   */
  public SqliteSlotLocatorSnapshotSource(
      SqliteConnectionFactory connectionFactory, Executor databaseExecutor) {
    this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<List<SlotLocatorEntry>> loadCommittedEntries() {
    try {
      return CompletableFuture.supplyAsync(this::loadSnapshot, databaseExecutor);
    } catch (RuntimeException exception) {
      return CompletableFuture.failedFuture(exception);
    }
  }

  private List<SlotLocatorEntry> loadSnapshot() {
    try (Connection connection = connectionFactory.open();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT slot_id, shard_group_id, grid_x, grid_z, state,
                       owner_island_id, version
                FROM slots
                WHERE state <> 'FREE'
                ORDER BY shard_group_id, ordinal
                """);
        ResultSet result = statement.executeQuery()) {
      List<SlotLocatorEntry> entries = new ArrayList<>();
      while (result.next()) {
        entries.add(
            new SlotLocatorEntry(
                ShardGroupId.parse(result.getString("shard_group_id")),
                new GridPosition(result.getInt("grid_x"), result.getInt("grid_z")),
                SlotId.parse(result.getString("slot_id")),
                IslandId.parse(result.getString("owner_island_id")),
                SlotState.valueOf(result.getString("state")),
                result.getLong("version")));
      }
      return List.copyOf(entries);
    } catch (SQLException | IllegalArgumentException exception) {
      throw new SqlitePersistenceException(
          "Failed to load SQLite slot locator startup snapshot", exception);
    }
  }
}
