package dev.openoneblock.persistence.sqlite.island;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.island.IslandHomeSnapshot;
import dev.openoneblock.core.island.IslandInfoSnapshot;
import dev.openoneblock.core.island.IslandQueryRepository;
import dev.openoneblock.core.world.WorldSpawnPosition;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.SqlitePersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** SQLite read repository for immutable player command projections. */
public final class SqliteIslandQueryRepository implements IslandQueryRepository {
  private static final String ACTIVE_HOME_SQL =
      """
      SELECT i.island_id, s.shard_group_id, s.grid_x, s.grid_z,
             i.current_border_size, i.version AS island_version,
             sp.world_id, sp.x, sp.y, sp.z, sp.yaw, sp.pitch
      FROM island_memberships m
      JOIN islands i ON i.island_id = m.island_id
      JOIN slots s ON s.slot_id = i.primary_slot_id
      JOIN island_spawn_points sp ON sp.island_id = i.island_id AND sp.primary_spawn = 1
      WHERE m.player_id = ? AND m.active = 1
        AND i.lifecycle_state = 'ACTIVE'
        AND s.state = 'ACTIVE' AND s.ownership_role = 'PRIMARY'
      """;
  private static final String ACTIVE_INFO_SQL =
      """
      SELECT i.island_id, i.owner_player_id, m.role_id, s.shard_group_id,
             s.grid_x, s.grid_z, i.current_border_size, i.maximum_border_size,
             p.current_phase_id, COALESCE(c.value, 0) AS total_breaks,
             mb.sequence AS magic_block_sequence, i.version AS island_version,
             (SELECT COUNT(*) FROM island_memberships members
              WHERE members.island_id = i.island_id AND members.active = 1) AS member_count
      FROM island_memberships m
      JOIN islands i ON i.island_id = m.island_id
      JOIN slots s ON s.slot_id = i.primary_slot_id
      JOIN island_progression p ON p.island_id = i.island_id
      JOIN magic_blocks mb ON mb.island_id = i.island_id
                           AND mb.magic_block_id = 'openoneblock:main'
      LEFT JOIN counters c ON c.scope_type = 'ISLAND'
                          AND c.scope_id = i.island_id
                          AND c.counter_id = 'openoneblock:total_breaks'
      WHERE m.player_id = ? AND m.active = 1
        AND i.lifecycle_state = 'ACTIVE'
        AND s.state = 'ACTIVE' AND s.ownership_role = 'PRIMARY'
      """;

  private final SqliteConnectionFactory connectionFactory;
  private final Executor databaseExecutor;

  /**
   * Creates the asynchronous query repository.
   *
   * @param connectionFactory SQLite connection source
   * @param databaseExecutor executor reserved for SQL work
   */
  public SqliteIslandQueryRepository(
      SqliteConnectionFactory connectionFactory, Executor databaseExecutor) {
    this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<Optional<IslandHomeSnapshot>> findActiveHome(PlayerId playerId) {
    Objects.requireNonNull(playerId, "playerId");
    return supplyAsync(() -> findHome(playerId));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<Optional<IslandInfoSnapshot>> findActiveInfo(PlayerId playerId) {
    Objects.requireNonNull(playerId, "playerId");
    return supplyAsync(() -> findInfo(playerId));
  }

  private Optional<IslandHomeSnapshot> findHome(PlayerId playerId) {
    try (Connection connection = connectionFactory.open();
        PreparedStatement statement = connection.prepareStatement(ACTIVE_HOME_SQL)) {
      statement.setString(1, playerId.toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return Optional.empty();
        }
        IslandHomeSnapshot home =
            new IslandHomeSnapshot(
                IslandId.parse(result.getString("island_id")),
                ShardGroupId.parse(result.getString("shard_group_id")),
                new GridPosition(result.getInt("grid_x"), result.getInt("grid_z")),
                result.getInt("current_border_size"),
                result.getLong("island_version"),
                new WorldSpawnPosition(
                    WorldId.parse(result.getString("world_id")),
                    result.getDouble("x"),
                    result.getDouble("y"),
                    result.getDouble("z"),
                    result.getFloat("yaw"),
                    result.getFloat("pitch")));
        requireSingleRow(result, "active home", playerId);
        return Optional.of(home);
      }
    } catch (SQLException | IllegalArgumentException exception) {
      throw new SqlitePersistenceException("Failed to query active island home", exception);
    }
  }

  private Optional<IslandInfoSnapshot> findInfo(PlayerId playerId) {
    try (Connection connection = connectionFactory.open();
        PreparedStatement statement = connection.prepareStatement(ACTIVE_INFO_SQL)) {
      statement.setString(1, playerId.toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return Optional.empty();
        }
        IslandInfoSnapshot info =
            new IslandInfoSnapshot(
                IslandId.parse(result.getString("island_id")),
                PlayerId.parse(result.getString("owner_player_id")),
                NamespacedId.parse(result.getString("role_id")),
                ShardGroupId.parse(result.getString("shard_group_id")),
                new GridPosition(result.getInt("grid_x"), result.getInt("grid_z")),
                result.getInt("current_border_size"),
                result.getInt("maximum_border_size"),
                NamespacedId.parse(result.getString("current_phase_id")),
                result.getLong("total_breaks"),
                result.getLong("magic_block_sequence"),
                Math.toIntExact(result.getLong("member_count")),
                result.getLong("island_version"));
        requireSingleRow(result, "active info", playerId);
        return Optional.of(info);
      }
    } catch (SQLException | IllegalArgumentException | ArithmeticException exception) {
      throw new SqlitePersistenceException("Failed to query active island info", exception);
    }
  }

  private <T> CompletionStage<T> supplyAsync(Supplier<T> work) {
    try {
      return CompletableFuture.supplyAsync(work, databaseExecutor);
    } catch (RuntimeException exception) {
      return CompletableFuture.failedFuture(exception);
    }
  }

  private static void requireSingleRow(ResultSet result, String projection, PlayerId playerId)
      throws SQLException {
    if (result.next()) {
      throw new SQLException(
          "Database invariant violation: multiple " + projection + " rows for " + playerId);
    }
  }
}
