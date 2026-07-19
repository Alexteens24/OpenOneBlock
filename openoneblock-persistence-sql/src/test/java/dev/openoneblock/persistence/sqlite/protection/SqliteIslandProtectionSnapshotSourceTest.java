package dev.openoneblock.persistence.sqlite.protection;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.migration.SqliteSchemaMigrator;
import dev.openoneblock.protection.IslandProtectionSnapshot;
import dev.openoneblock.protection.ProtectionPosition;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteIslandProtectionSnapshotSourceTest {
  private static final Instant NOW = Instant.parse("2026-07-19T05:00:00Z");
  private static final PlayerId OWNER =
      PlayerId.of(UUID.fromString("dc8dc150-60bf-45f6-9587-5202f7dc23d7"));
  private static final PlayerId INACTIVE_MEMBER =
      PlayerId.of(UUID.fromString("1cd20dae-292d-4eaf-a47b-b6579376b0e9"));
  private static final WorldId WORLD =
      WorldId.of(UUID.fromString("749755fb-5364-494f-8a28-7c5c86e01371"));

  @TempDir Path temporaryDirectory;

  private ExecutorService executor;

  @AfterEach
  void stopExecutor() {
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  @Test
  void loadsNormalizedMembershipAndMagicBlockStateWithoutArchivedIslands() throws Exception {
    SqliteConnectionFactory factory =
        new SqliteConnectionFactory(temporaryDirectory.resolve("protection.db"), 50);
    new SqliteSchemaMigrator(factory).migrate();
    IslandId active = IslandId.generate();
    insertIsland(factory, active, "ACTIVE", true);
    insertMembership(factory, active, OWNER, "openoneblock:owner", true, true);
    insertMembership(factory, active, INACTIVE_MEMBER, "openoneblock:member", false, false);
    insertMagicBlock(factory, active);
    insertIsland(factory, IslandId.generate(), "ARCHIVED", false);
    executor = Executors.newSingleThreadExecutor();

    List<IslandProtectionSnapshot> snapshots =
        new SqliteIslandProtectionSnapshotSource(factory, executor)
            .loadCommittedSnapshots()
            .toCompletableFuture()
            .get(10, SECONDS);

    assertEquals(1, snapshots.size());
    IslandProtectionSnapshot snapshot = snapshots.getFirst();
    assertEquals(active, snapshot.islandId());
    assertEquals(96, snapshot.currentBorderSize());
    assertEquals(7, snapshot.islandVersion());
    assertEquals(
        Map.of(OWNER, NamespacedId.parse("openoneblock:owner")), snapshot.activeMemberships());
    assertEquals(Set.of(new ProtectionPosition(WORLD, 0, 64, 0)), snapshot.magicBlocks());
  }

  private static void insertIsland(
      SqliteConnectionFactory factory, IslandId islandId, String lifecycle, boolean withSlot)
      throws Exception {
    SlotId slotId = withSlot ? SlotId.generate() : null;
    try (Connection connection = factory.open()) {
      if (withSlot) {
        try (PreparedStatement slot =
            connection.prepareStatement(
                """
            INSERT INTO slots (
                slot_id, shard_group_id, ordinal, grid_x, grid_z, state,
                owner_island_id, ownership_role, version, created_at, updated_at
            ) VALUES (?, 'openoneblock:primary', 0, 0, 0, 'ACTIVE', ?, 'PRIMARY', 3, ?, ?)
            """)) {
          slot.setString(1, slotId.toString());
          slot.setString(2, islandId.toString());
          slot.setString(3, NOW.toString());
          slot.setString(4, NOW.toString());
          assertEquals(1, slot.executeUpdate());
        }
      }
      try (PreparedStatement island =
          connection.prepareStatement(
              """
          INSERT INTO islands (
              island_id, owner_player_id, lifecycle_state, primary_slot_id,
              current_border_size, maximum_border_size, version,
              pending_operation_id, created_at, updated_at
          ) VALUES (?, ?, ?, ?, 96, 384, 7, NULL, ?, ?)
          """)) {
        island.setString(1, islandId.toString());
        island.setString(2, OWNER.toString());
        island.setString(3, lifecycle);
        island.setString(4, slotId == null ? null : slotId.toString());
        island.setString(5, NOW.toString());
        island.setString(6, NOW.toString());
        assertEquals(1, island.executeUpdate());
      }
    }
  }

  private static void insertMembership(
      SqliteConnectionFactory factory,
      IslandId islandId,
      PlayerId playerId,
      String role,
      boolean active,
      boolean owner)
      throws Exception {
    try (Connection connection = factory.open();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            INSERT INTO island_memberships (
                island_id, player_id, role_id, active, owner, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
      statement.setString(1, islandId.toString());
      statement.setString(2, playerId.toString());
      statement.setString(3, role);
      statement.setInt(4, active ? 1 : 0);
      statement.setInt(5, owner ? 1 : 0);
      statement.setString(6, NOW.toString());
      statement.setString(7, NOW.toString());
      assertEquals(1, statement.executeUpdate());
    }
  }

  private static void insertMagicBlock(SqliteConnectionFactory factory, IslandId islandId)
      throws Exception {
    try (Connection connection = factory.open();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            INSERT INTO magic_blocks (
                island_id, magic_block_id, world_id, block_x, block_y, block_z,
                profile_id, current_content_id, state, sequence,
                last_persisted_sequence, cooldown_until, version, created_at, updated_at
            ) VALUES (?, 'openoneblock:main', ?, 0, 64, 0, 'openoneblock:default',
                      'minecraft:stone', 'READY', 0, 0, NULL, 0, ?, ?)
            """)) {
      statement.setString(1, islandId.toString());
      statement.setString(2, WORLD.toString());
      statement.setString(3, NOW.toString());
      statement.setString(4, NOW.toString());
      assertEquals(1, statement.executeUpdate());
    }
  }
}
