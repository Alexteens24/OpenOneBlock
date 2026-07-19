package dev.openoneblock.persistence.sqlite.team;

import dev.openoneblock.api.id.InvitationId;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.island.InvitationState;
import dev.openoneblock.api.island.IslandInvitationView;
import dev.openoneblock.core.team.IslandInvitationRepository;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** SQLite immutable pending-invitation query implementation. */
public final class SqliteIslandInvitationRepository implements IslandInvitationRepository {
  private final SqliteConnectionFactory connectionFactory;
  private final Executor databaseExecutor;

  /** Creates the query repository. */
  public SqliteIslandInvitationRepository(
      SqliteConnectionFactory connectionFactory, Executor databaseExecutor) {
    this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
  }

  @Override
  public CompletionStage<List<IslandInvitationView>> findPendingInvitations(
      PlayerId playerId, Instant observedAt) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(observedAt, "observedAt");
    try {
      return CompletableFuture.supplyAsync(() -> find(playerId, observedAt), databaseExecutor);
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  private List<IslandInvitationView> find(PlayerId playerId, Instant observedAt) {
    try (Connection connection = connectionFactory.open();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT invitation_id, island_id, invited_player_id, invited_by_player_id,
                       proposed_role_id, state, expires_at, version,
                       created_at, updated_at, responded_at
                FROM island_invitations
                WHERE invited_player_id = ? AND state = 'PENDING' AND expires_at > ?
                ORDER BY expires_at, created_at, invitation_id
                """)) {
      statement.setString(1, playerId.toString());
      statement.setString(2, observedAt.toString());
      try (ResultSet result = statement.executeQuery()) {
        List<IslandInvitationView> invitations = new ArrayList<>();
        while (result.next()) {
          String respondedAt = result.getString("responded_at");
          invitations.add(
              new IslandInvitationView(
                  InvitationId.parse(result.getString("invitation_id")),
                  IslandId.parse(result.getString("island_id")),
                  PlayerId.parse(result.getString("invited_player_id")),
                  PlayerId.parse(result.getString("invited_by_player_id")),
                  NamespacedId.parse(result.getString("proposed_role_id")),
                  InvitationState.valueOf(result.getString("state")),
                  Instant.parse(result.getString("expires_at")),
                  result.getLong("version"),
                  Instant.parse(result.getString("created_at")),
                  Instant.parse(result.getString("updated_at")),
                  respondedAt == null
                      ? Optional.empty()
                      : Optional.of(Instant.parse(respondedAt))));
        }
        return List.copyOf(invitations);
      }
    } catch (SQLException | IllegalArgumentException failure) {
      throw new SqlitePersistenceException(
          "Failed to query pending island invitations for " + playerId, failure);
    }
  }
}
