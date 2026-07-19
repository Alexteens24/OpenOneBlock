package dev.openoneblock.persistence.sqlite.protection;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.SqlitePersistenceException;
import dev.openoneblock.protection.IslandProtectionSnapshot;
import dev.openoneblock.protection.IslandProtectionSnapshotSource;
import dev.openoneblock.protection.ProtectionPosition;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Loads normalized SQL state into the bounded hot-path protection projection. */
public final class SqliteIslandProtectionSnapshotSource implements IslandProtectionSnapshotSource {
  private final SqliteConnectionFactory connectionFactory;
  private final Executor databaseExecutor;

  /**
   * Creates a SQLite protection snapshot source.
   *
   * @param connectionFactory SQLite connection source
   * @param databaseExecutor executor reserved for database work
   */
  public SqliteIslandProtectionSnapshotSource(
      SqliteConnectionFactory connectionFactory, Executor databaseExecutor) {
    this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<List<IslandProtectionSnapshot>> loadCommittedSnapshots() {
    try {
      return CompletableFuture.supplyAsync(this::load, databaseExecutor);
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  /**
   * Loads one projection synchronously for a caller already running on the database executor.
   *
   * @param islandId committed island identity
   * @return non-archived projection, if present
   */
  public Optional<IslandProtectionSnapshot> loadCommittedSnapshot(IslandId islandId) {
    Objects.requireNonNull(islandId, "islandId");
    try (Connection connection = connectionFactory.open()) {
      Builder builder = loadIsland(connection, islandId);
      if (builder == null) {
        return Optional.empty();
      }
      loadMemberships(connection, islandId, builder);
      loadMagicBlocks(connection, islandId, builder);
      return Optional.of(builder.build());
    } catch (SQLException | IllegalArgumentException failure) {
      throw new SqlitePersistenceException(
          "Failed to load SQLite island protection projection for " + islandId, failure);
    }
  }

  private List<IslandProtectionSnapshot> load() {
    try (Connection connection = connectionFactory.open()) {
      Map<IslandId, Builder> builders = loadIslands(connection);
      loadMemberships(connection, builders);
      loadMagicBlocks(connection, builders);
      return builders.values().stream().map(Builder::build).toList();
    } catch (SQLException | IllegalArgumentException failure) {
      throw new SqlitePersistenceException(
          "Failed to load SQLite island protection startup snapshot", failure);
    }
  }

  private static Map<IslandId, Builder> loadIslands(Connection connection) throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT i.island_id, i.lifecycle_state, i.current_border_size,
                   i.version AS island_version, s.shard_group_id, s.grid_x, s.grid_z
            FROM islands i
            JOIN slots s ON s.slot_id = i.primary_slot_id
            WHERE i.lifecycle_state <> 'ARCHIVED'
            ORDER BY i.island_id
            """);
        ResultSet result = statement.executeQuery()) {
      Map<IslandId, Builder> builders = new LinkedHashMap<>();
      while (result.next()) {
        IslandId islandId = IslandId.parse(result.getString("island_id"));
        Builder builder =
            new Builder(
                islandId,
                IslandLifecycleState.valueOf(result.getString("lifecycle_state")),
                ShardGroupId.parse(result.getString("shard_group_id")),
                new GridPosition(result.getInt("grid_x"), result.getInt("grid_z")),
                result.getInt("current_border_size"),
                result.getLong("island_version"));
        if (builders.putIfAbsent(islandId, builder) != null) {
          throw new SQLException("duplicate island protection projection: " + islandId);
        }
      }
      return builders;
    }
  }

  private static Builder loadIsland(Connection connection, IslandId islandId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
        SELECT i.island_id, i.lifecycle_state, i.current_border_size,
               i.version AS island_version, s.shard_group_id, s.grid_x, s.grid_z
        FROM islands i
        JOIN slots s ON s.slot_id = i.primary_slot_id
        WHERE i.island_id = ? AND i.lifecycle_state <> 'ARCHIVED'
        """)) {
      statement.setString(1, islandId.toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return null;
        }
        return new Builder(
            IslandId.parse(result.getString("island_id")),
            IslandLifecycleState.valueOf(result.getString("lifecycle_state")),
            ShardGroupId.parse(result.getString("shard_group_id")),
            new GridPosition(result.getInt("grid_x"), result.getInt("grid_z")),
            result.getInt("current_border_size"),
            result.getLong("island_version"));
      }
    }
  }

  private static void loadMemberships(Connection connection, Map<IslandId, Builder> builders)
      throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT island_id, player_id, role_id
            FROM island_memberships
            WHERE active = 1
            ORDER BY island_id, player_id
            """);
        ResultSet result = statement.executeQuery()) {
      while (result.next()) {
        Builder builder = builders.get(IslandId.parse(result.getString("island_id")));
        if (builder != null) {
          builder.addMembership(
              PlayerId.parse(result.getString("player_id")),
              NamespacedId.parse(result.getString("role_id")));
        }
      }
    }
  }

  private static void loadMemberships(Connection connection, IslandId islandId, Builder builder)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
        SELECT player_id, role_id
        FROM island_memberships
        WHERE island_id = ? AND active = 1
        ORDER BY player_id
        """)) {
      statement.setString(1, islandId.toString());
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          builder.addMembership(
              PlayerId.parse(result.getString("player_id")),
              NamespacedId.parse(result.getString("role_id")));
        }
      }
    }
  }

  private static void loadMagicBlocks(Connection connection, Map<IslandId, Builder> builders)
      throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT island_id, world_id, block_x, block_y, block_z
            FROM magic_blocks
            ORDER BY island_id, magic_block_id
            """);
        ResultSet result = statement.executeQuery()) {
      while (result.next()) {
        Builder builder = builders.get(IslandId.parse(result.getString("island_id")));
        if (builder != null) {
          builder.addMagicBlock(
              new ProtectionPosition(
                  WorldId.parse(result.getString("world_id")),
                  result.getInt("block_x"),
                  result.getInt("block_y"),
                  result.getInt("block_z")));
        }
      }
    }
  }

  private static void loadMagicBlocks(Connection connection, IslandId islandId, Builder builder)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
        SELECT world_id, block_x, block_y, block_z
        FROM magic_blocks
        WHERE island_id = ?
        ORDER BY magic_block_id
        """)) {
      statement.setString(1, islandId.toString());
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          builder.addMagicBlock(
              new ProtectionPosition(
                  WorldId.parse(result.getString("world_id")),
                  result.getInt("block_x"),
                  result.getInt("block_y"),
                  result.getInt("block_z")));
        }
      }
    }
  }

  private static final class Builder {
    private final IslandId islandId;
    private final IslandLifecycleState lifecycle;
    private final ShardGroupId shard;
    private final GridPosition grid;
    private final int border;
    private final long version;
    private final Map<PlayerId, NamespacedId> memberships = new HashMap<>();
    private final List<ProtectionPosition> magicBlocks = new ArrayList<>();

    private Builder(
        IslandId islandId,
        IslandLifecycleState lifecycle,
        ShardGroupId shard,
        GridPosition grid,
        int border,
        long version) {
      this.islandId = islandId;
      this.lifecycle = lifecycle;
      this.shard = shard;
      this.grid = grid;
      this.border = border;
      this.version = version;
    }

    private void addMembership(PlayerId playerId, NamespacedId roleId) throws SQLException {
      if (memberships.putIfAbsent(playerId, roleId) != null) {
        throw new SQLException("duplicate active membership for " + playerId);
      }
    }

    private void addMagicBlock(ProtectionPosition position) {
      magicBlocks.add(position);
    }

    private IslandProtectionSnapshot build() {
      return new IslandProtectionSnapshot(
          islandId,
          lifecycle,
          shard,
          grid,
          border,
          version,
          memberships,
          java.util.Set.copyOf(magicBlocks));
    }
  }
}
