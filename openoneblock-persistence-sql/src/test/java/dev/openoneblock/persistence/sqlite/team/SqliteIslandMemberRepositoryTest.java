package dev.openoneblock.persistence.sqlite.team;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.island.MemberView;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.migration.SqliteSchemaMigrator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteIslandMemberRepositoryTest {
  private static final Instant NOW = Instant.parse("2026-07-19T08:00:00Z");
  private static final PlayerId OWNER =
      PlayerId.of(UUID.fromString("d81895f9-a2e1-4859-a1c0-c096e137cce1"));
  private static final PlayerId MEMBER =
      PlayerId.of(UUID.fromString("37e7d862-b663-4886-b606-2a7361e325e2"));

  @TempDir Path temporaryDirectory;
  private ExecutorService executor;

  @AfterEach
  void stopExecutor() {
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  @Test
  void returnsOnlyActiveMembersInDeterministicOwnerFirstOrder() throws Exception {
    SqliteConnectionFactory factory =
        new SqliteConnectionFactory(temporaryDirectory.resolve("members.db"), 50);
    new SqliteSchemaMigrator(factory).migrate();
    IslandId islandId = IslandId.generate();
    insertIsland(factory, islandId);
    insertMembership(factory, islandId, MEMBER, "openoneblock:member", true, false);
    insertMembership(factory, islandId, OWNER, "openoneblock:owner", true, true);
    insertMembership(
        factory,
        islandId,
        PlayerId.of(UUID.fromString("8a240591-27b1-4302-a2b9-b107b656aa8c")),
        "openoneblock:member",
        false,
        false);
    executor = Executors.newSingleThreadExecutor();

    List<MemberView> members =
        new SqliteIslandMemberRepository(factory, executor)
            .findActiveMembers(islandId)
            .toCompletableFuture()
            .get(10, SECONDS);

    assertEquals(List.of(OWNER, MEMBER), members.stream().map(MemberView::playerId).toList());
    assertEquals("openoneblock:owner", members.getFirst().roleId().toString());
    assertEquals("openoneblock:member", members.get(1).roleId().toString());
  }

  private static void insertIsland(SqliteConnectionFactory factory, IslandId islandId)
      throws Exception {
    SlotId slotId = SlotId.generate();
    try (Connection connection = factory.open()) {
      try (PreparedStatement slot =
          connection.prepareStatement(
              """
          INSERT INTO slots (
              slot_id, shard_group_id, ordinal, grid_x, grid_z, state,
              owner_island_id, ownership_role, version, created_at, updated_at
          ) VALUES (?, 'openoneblock:primary', 0, 0, 0, 'ACTIVE', ?, 'PRIMARY', 1, ?, ?)
          """)) {
        slot.setString(1, slotId.toString());
        slot.setString(2, islandId.toString());
        slot.setString(3, NOW.toString());
        slot.setString(4, NOW.toString());
        assertEquals(1, slot.executeUpdate());
      }
      try (PreparedStatement island =
          connection.prepareStatement(
              """
          INSERT INTO islands (
              island_id, owner_player_id, lifecycle_state, primary_slot_id,
              current_border_size, maximum_border_size, version,
              pending_operation_id, created_at, updated_at
          ) VALUES (?, ?, 'ACTIVE', ?, 64, 384, 1, NULL, ?, ?)
          """)) {
        island.setString(1, islandId.toString());
        island.setString(2, OWNER.toString());
        island.setString(3, slotId.toString());
        island.setString(4, NOW.toString());
        island.setString(5, NOW.toString());
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
}
