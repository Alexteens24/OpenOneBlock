package dev.openoneblock.persistence.sqlite.slot;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.core.locator.InMemorySlotLocatorIndex;
import dev.openoneblock.core.locator.SlotLocatorEntry;
import dev.openoneblock.core.locator.SlotLocatorLookup;
import dev.openoneblock.core.locator.SlotLocatorSnapshotSource;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotState;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.migration.SqliteSchemaMigrator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteSlotLocatorSnapshotSourceTest {
  private static final ShardGroupId SHARD = ShardGroupId.parse("openoneblock:primary");
  private static final Instant NOW = Instant.parse("2026-07-19T04:00:00Z");

  @TempDir Path temporaryDirectory;

  private ExecutorService executor;

  @AfterEach
  void stopExecutor() {
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  @Test
  void restartSnapshotContainsEveryNonFreeSlotAndExcludesReusableCells() throws Exception {
    SqliteConnectionFactory initial = initializedFactory("locator-restart.db");
    SlotLocatorEntry active =
        insertSlot(initial, 0, new GridPosition(0, 0), SlotState.ACTIVE, IslandId.generate(), 2);
    SlotLocatorEntry preparing =
        insertSlot(initial, 1, new GridPosition(1, 0), SlotState.PREPARING, IslandId.generate(), 1);
    insertFreeSlot(initial, 2, new GridPosition(1, 1));

    SqliteConnectionFactory restarted = new SqliteConnectionFactory(initial.databaseFile(), 50);
    new SqliteSchemaMigrator(restarted).migrate();
    executor = Executors.newSingleThreadExecutor();
    SlotLocatorSnapshotSource source = new SqliteSlotLocatorSnapshotSource(restarted, executor);

    List<SlotLocatorEntry> snapshot =
        source.loadCommittedEntries().toCompletableFuture().get(10, SECONDS);
    InMemorySlotLocatorIndex rebuilt = InMemorySlotLocatorIndex.rebuild(snapshot);

    assertEquals(List.of(active, preparing), snapshot);
    assertEquals(2, rebuilt.size());
    assertEquals(
        active,
        assertInstanceOf(
                SlotLocatorLookup.Resolved.class, rebuilt.lookup(SHARD, active.gridPosition()))
            .entry());
    assertInstanceOf(SlotLocatorLookup.Empty.class, rebuilt.lookup(SHARD, new GridPosition(1, 1)));
  }

  private SqliteConnectionFactory initializedFactory(String fileName) {
    SqliteConnectionFactory factory =
        new SqliteConnectionFactory(temporaryDirectory.resolve(fileName), 50);
    new SqliteSchemaMigrator(factory).migrate();
    return factory;
  }

  private static SlotLocatorEntry insertSlot(
      SqliteConnectionFactory factory,
      long ordinal,
      GridPosition position,
      SlotState state,
      IslandId islandId,
      long version)
      throws Exception {
    SlotId slotId = SlotId.generate();
    try (Connection connection = factory.open();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                INSERT INTO slots (
                    slot_id, shard_group_id, ordinal, grid_x, grid_z, state,
                    owner_island_id, ownership_role, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PRIMARY', ?, ?, ?)
                """)) {
      statement.setString(1, slotId.toString());
      statement.setString(2, SHARD.toString());
      statement.setLong(3, ordinal);
      statement.setInt(4, position.gridX());
      statement.setInt(5, position.gridZ());
      statement.setString(6, state.name());
      statement.setString(7, islandId.toString());
      statement.setLong(8, version);
      statement.setString(9, NOW.toString());
      statement.setString(10, NOW.toString());
      assertEquals(1, statement.executeUpdate());
    }
    return new SlotLocatorEntry(SHARD, position, slotId, islandId, state, version);
  }

  private static void insertFreeSlot(
      SqliteConnectionFactory factory, long ordinal, GridPosition position) throws Exception {
    try (Connection connection = factory.open();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                INSERT INTO slots (
                    slot_id, shard_group_id, ordinal, grid_x, grid_z, state,
                    owner_island_id, ownership_role, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'FREE', NULL, NULL, 4, ?, ?)
                """)) {
      statement.setString(1, SlotId.generate().toString());
      statement.setString(2, SHARD.toString());
      statement.setLong(3, ordinal);
      statement.setInt(4, position.gridX());
      statement.setInt(5, position.gridZ());
      statement.setString(6, NOW.toString());
      statement.setString(7, NOW.toString());
      assertEquals(1, statement.executeUpdate());
    }
  }
}
