package dev.openoneblock.persistence.sqlite.team;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.island.MemberView;
import dev.openoneblock.core.team.IslandMemberRepository;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.SqlitePersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** SQLite immutable active-membership query implementation. */
public final class SqliteIslandMemberRepository implements IslandMemberRepository {
  private final SqliteConnectionFactory connectionFactory;
  private final Executor databaseExecutor;

  /**
   * Creates a membership query repository.
   *
   * @param connectionFactory SQLite connection source
   * @param databaseExecutor executor reserved for SQL work
   */
  public SqliteIslandMemberRepository(
      SqliteConnectionFactory connectionFactory, Executor databaseExecutor) {
    this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<List<MemberView>> findActiveMembers(IslandId islandId) {
    Objects.requireNonNull(islandId, "islandId");
    try {
      return CompletableFuture.supplyAsync(() -> find(islandId), databaseExecutor);
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  private List<MemberView> find(IslandId islandId) {
    try (Connection connection = connectionFactory.open();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT island_id, player_id, role_id, owner, created_at, updated_at
            FROM island_memberships
            WHERE island_id = ? AND active = 1
            ORDER BY owner DESC, created_at, player_id
            """)) {
      statement.setString(1, islandId.toString());
      try (ResultSet result = statement.executeQuery()) {
        List<MemberView> members = new ArrayList<>();
        while (result.next()) {
          members.add(
              new MemberView(
                  IslandId.parse(result.getString("island_id")),
                  PlayerId.parse(result.getString("player_id")),
                  NamespacedId.parse(result.getString("role_id")),
                  result.getInt("owner") == 1,
                  Instant.parse(result.getString("created_at")),
                  Instant.parse(result.getString("updated_at"))));
        }
        return List.copyOf(members);
      }
    } catch (SQLException | IllegalArgumentException failure) {
      throw new SqlitePersistenceException(
          "Failed to query active memberships for " + islandId, failure);
    }
  }
}
