package dev.openoneblock.persistence.sqlite.team;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.event.MembershipMutationKind;
import dev.openoneblock.api.id.InvitationId;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.island.IslandPermission;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.team.IslandInvitationCommand;
import dev.openoneblock.core.team.IslandInvitationResponseCommand;
import dev.openoneblock.core.team.IslandMembershipCommand;
import dev.openoneblock.core.team.IslandOwnershipTransferCommand;
import dev.openoneblock.core.team.IslandRoleDefinition;
import dev.openoneblock.core.team.IslandRoleRegistry;
import dev.openoneblock.core.team.IslandTeamMutationRejectedException;
import dev.openoneblock.core.team.MembershipCommandKind;
import dev.openoneblock.core.team.TeamMutationResult;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.migration.SqliteSchemaMigrator;
import dev.openoneblock.protection.CommittedIslandProtectionPublisher;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteIslandTeamRepositoryTest {
  private static final Instant NOW = Instant.parse("2026-07-19T08:00:00Z");
  private static final NamespacedId MEMBER_ROLE = NamespacedId.of("openoneblock", "member");
  private static final NamespacedId CO_OWNER_ROLE = NamespacedId.of("openoneblock", "co_owner");
  private static final NamespacedId MODERATOR_ROLE = NamespacedId.of("openoneblock", "moderator");
  private static final PlayerId PLAYER = PlayerId.of(UUID.fromString("37e7d862-b663-4886-b606-2a7361e325e2"));

  @TempDir Path temporaryDirectory;
  private ExecutorService executor;
  private SqliteConnectionFactory factory;
  private SqliteIslandTeamRepository repository;

  @BeforeEach
  void setUp() {
    factory = new SqliteConnectionFactory(temporaryDirectory.resolve("team.db"), 1_000);
    new SqliteSchemaMigrator(factory).migrate();
    executor = Executors.newFixedThreadPool(4);
    repository =
        new SqliteIslandTeamRepository(
            factory, roles(), CommittedIslandProtectionPublisher.NO_OP, executor);
  }

  @AfterEach
  void stopExecutor() {
    executor.shutdownNow();
  }

  @Test
  void inviteAcceptAndReplayAreAtomicAndVersioned() throws Exception {
    IslandId island = IslandId.generate();
    PlayerId owner = PlayerId.of(UUID.randomUUID());
    seedIsland(island, owner);
    IslandInvitationCommand invite = invite(island, owner, PLAYER, 1);

    TeamMutationResult invited = await(repository.invite(invite));
    assertEquals(
        List.of(invite.invitationId()),
        new SqliteIslandInvitationRepository(factory, executor)
            .findPendingInvitations(PLAYER, NOW)
            .toCompletableFuture()
            .get(10, SECONDS)
            .stream()
            .map(dev.openoneblock.api.island.IslandInvitationView::invitationId)
            .toList());
    TeamMutationResult replayed = await(repository.invite(invite));
    TeamMutationResult accepted =
        await(
            repository.respond(
                new IslandInvitationResponseCommand(
                    island,
                    OperationId.generate(),
                    invite.invitationId(),
                    PLAYER,
                    2,
                    4,
                    true,
                    NOW.plusSeconds(60))));

    assertEquals(MembershipMutationKind.INVITED, invited.event().kind());
    assertEquals(2, invited.event().committedIslandVersion());
    assertTrue(replayed.replayed());
    assertEquals(MembershipMutationKind.INVITATION_ACCEPTED, accepted.event().kind());
    assertEquals(3, accepted.event().committedIslandVersion());
    assertEquals(2, activeMemberCount(island));
    assertEquals(3, islandVersion(island));
  }

  @Test
  void ownerCannotLeaveAndTransferMovesBothOwnerProjectionsAtomically() throws Exception {
    IslandId island = IslandId.generate();
    PlayerId owner = PlayerId.of(UUID.randomUUID());
    seedIsland(island, owner);
    seedMembership(island, PLAYER, MEMBER_ROLE, false);

    ExecutionException leaveFailure =
        assertThrows(
            ExecutionException.class,
            () ->
                await(
                    repository.mutate(
                        new IslandMembershipCommand(
                            island,
                            OperationId.generate(),
                            MembershipCommandKind.LEAVE,
                            owner,
                            owner,
                            Optional.empty(),
                            1,
                            NOW.plusSeconds(10)))));
    assertTrue(leaveFailure.getCause() instanceof IslandTeamMutationRejectedException);

    TeamMutationResult transferred =
        await(
            repository.transferOwnership(
                new IslandOwnershipTransferCommand(
                    island,
                    OperationId.generate(),
                    owner,
                    PLAYER,
                    CO_OWNER_ROLE,
                    1,
                    NOW.plusSeconds(20))));

    assertEquals(MembershipMutationKind.OWNERSHIP_TRANSFERRED, transferred.event().kind());
    assertEquals(PLAYER, islandOwner(island));
    assertFalse(isOwnerMembership(island, owner));
    assertTrue(isOwnerMembership(island, PLAYER));
    assertEquals(CO_OWNER_ROLE.toString(), membershipRole(island, owner));
    assertEquals(2, islandVersion(island));
  }

  @Test
  void concurrentAcceptsAcrossIslandsCannotCreateTwoActiveMemberships() throws Exception {
    IslandId firstIsland = IslandId.generate();
    IslandId secondIsland = IslandId.generate();
    PlayerId firstOwner = PlayerId.of(UUID.randomUUID());
    PlayerId secondOwner = PlayerId.of(UUID.randomUUID());
    seedIsland(firstIsland, firstOwner);
    seedIsland(secondIsland, secondOwner);
    IslandInvitationCommand firstInvite = invite(firstIsland, firstOwner, PLAYER, 1);
    IslandInvitationCommand secondInvite = invite(secondIsland, secondOwner, PLAYER, 1);
    await(repository.invite(firstInvite));
    await(repository.invite(secondInvite));

    var first =
        repository
            .respond(
                response(firstInvite, PLAYER, NOW.plusSeconds(30)))
            .toCompletableFuture();
    var second =
        repository
            .respond(
                response(secondInvite, PLAYER, NOW.plusSeconds(30)))
            .toCompletableFuture();
    CompletableFuture.allOf(first.handle((value, failure) -> null), second.handle((value, failure) -> null))
        .get(10, SECONDS);

    assertEquals(1, (first.isCompletedExceptionally() ? 0 : 1) + (second.isCompletedExceptionally() ? 0 : 1));
    assertEquals(1, activeMembershipCount(PLAYER));
  }

  @Test
  void directMutationsCoverDeclineRolesKickBanAndUnban() throws Exception {
    IslandId island = IslandId.generate();
    PlayerId owner = PlayerId.of(UUID.randomUUID());
    PlayerId visitor = PlayerId.of(UUID.randomUUID());
    seedIsland(island, owner);
    IslandInvitationCommand declinedInvite = invite(island, owner, visitor, 1);
    await(repository.invite(declinedInvite));
    TeamMutationResult declined =
        await(
            repository.respond(
                new IslandInvitationResponseCommand(
                    island,
                    OperationId.generate(),
                    declinedInvite.invitationId(),
                    visitor,
                    2,
                    4,
                    false,
                    NOW.plusSeconds(5))));
    assertEquals(MembershipMutationKind.INVITATION_DECLINED, declined.event().kind());

    seedMembership(island, PLAYER, MEMBER_ROLE, false);
    assertEquals(
        MembershipMutationKind.PROMOTED,
        await(repository.mutate(mutation(island, owner, PLAYER, MembershipCommandKind.PROMOTE, 3, CO_OWNER_ROLE)))
            .event()
            .kind());
    assertEquals(
        MembershipMutationKind.DEMOTED,
        await(repository.mutate(mutation(island, owner, PLAYER, MembershipCommandKind.DEMOTE, 4, MEMBER_ROLE)))
            .event()
            .kind());
    assertEquals(
        MembershipMutationKind.KICKED,
        await(repository.mutate(mutation(island, owner, PLAYER, MembershipCommandKind.KICK, 5, null)))
            .event()
            .kind());
    assertEquals(
        MembershipMutationKind.BANNED,
        await(repository.mutate(mutation(island, owner, PLAYER, MembershipCommandKind.BAN, 6, null)))
            .event()
            .kind());
    assertEquals(
        MembershipMutationKind.UNBANNED,
        await(repository.mutate(mutation(island, owner, PLAYER, MembershipCommandKind.UNBAN, 7, null)))
            .event()
            .kind());
    assertEquals(
        MembershipMutationKind.TRUSTED,
        await(repository.mutate(mutation(island, owner, PLAYER, MembershipCommandKind.TRUST, 8, null)))
            .event()
            .kind());
    assertEquals(
        MembershipMutationKind.UNTRUSTED,
        await(repository.mutate(mutation(island, owner, PLAYER, MembershipCommandKind.UNTRUST, 9, null)))
            .event()
            .kind());
    assertEquals(10, islandVersion(island));
  }

  @Test
  void configurableAuthorityPreventsPeerEscalationAndHigherRoleRemoval() throws Exception {
    IslandId island = IslandId.generate();
    PlayerId owner = PlayerId.of(UUID.randomUUID());
    PlayerId moderator = PlayerId.of(UUID.randomUUID());
    seedIsland(island, owner);
    seedMembership(island, PLAYER, CO_OWNER_ROLE, false);
    seedMembership(island, moderator, MODERATOR_ROLE, false);

    ExecutionException kickFailure =
        assertThrows(
            ExecutionException.class,
            () ->
                await(
                    repository.mutate(
                        mutation(
                            island,
                            moderator,
                            PLAYER,
                            MembershipCommandKind.KICK,
                            1,
                            null))));
    ExecutionException promoteFailure =
        assertThrows(
            ExecutionException.class,
            () ->
                await(
                    repository.mutate(
                        mutation(
                            island,
                            moderator,
                            moderator,
                            MembershipCommandKind.PROMOTE,
                            1,
                            CO_OWNER_ROLE))));

    assertTrue(kickFailure.getCause() instanceof IslandTeamMutationRejectedException);
    assertTrue(promoteFailure.getCause() instanceof IslandTeamMutationRejectedException);
    assertEquals(1, islandVersion(island));
  }

  private static IslandMembershipCommand mutation(
      IslandId island,
      PlayerId actor,
      PlayerId subject,
      MembershipCommandKind kind,
      long version,
      NamespacedId role) {
    return new IslandMembershipCommand(
        island,
        OperationId.generate(),
        kind,
        actor,
        subject,
        Optional.ofNullable(role),
        version,
        NOW.plusSeconds(version));
  }

  private IslandInvitationCommand invite(
      IslandId island, PlayerId owner, PlayerId player, long version) {
    return new IslandInvitationCommand(
        island,
        OperationId.generate(),
        InvitationId.of(UUID.randomUUID()),
        owner,
        player,
        MEMBER_ROLE,
        version,
        4,
        NOW,
        NOW.plus(5, ChronoUnit.MINUTES));
  }

  private static IslandInvitationResponseCommand response(
      IslandInvitationCommand invitation, PlayerId player, Instant now) {
    return new IslandInvitationResponseCommand(
        invitation.islandId(),
        OperationId.generate(),
        invitation.invitationId(),
        player,
        2,
        4,
        true,
        now);
  }

  private static TeamMutationResult await(java.util.concurrent.CompletionStage<TeamMutationResult> stage)
      throws Exception {
    return stage.toCompletableFuture().get(10, SECONDS);
  }

  private void seedIsland(IslandId islandId, PlayerId owner) throws Exception {
    SlotId slotId = SlotId.generate();
    try (Connection connection = factory.open()) {
      try (PreparedStatement slot =
          connection.prepareStatement(
              """
              INSERT INTO slots (
                  slot_id, shard_group_id, ordinal, grid_x, grid_z, state,
                  owner_island_id, ownership_role, version, created_at, updated_at
              ) VALUES (?, ?, ?, ?, 0, 'ACTIVE', ?, 'PRIMARY', 1, ?, ?)
              """)) {
        int ordinal = islandId.hashCode() & Integer.MAX_VALUE;
        slot.setString(1, slotId.toString());
        slot.setString(2, "openoneblock:primary");
        slot.setInt(3, ordinal);
        slot.setInt(4, ordinal);
        slot.setString(5, islandId.toString());
        slot.setString(6, NOW.toString());
        slot.setString(7, NOW.toString());
        slot.executeUpdate();
      }
      try (PreparedStatement island =
          connection.prepareStatement(
              """
              INSERT INTO islands (
                  island_id, owner_player_id, lifecycle_state, primary_slot_id,
                  current_border_size, maximum_border_size, version, created_at, updated_at
              ) VALUES (?, ?, 'ACTIVE', ?, 64, 384, 1, ?, ?)
              """)) {
        island.setString(1, islandId.toString());
        island.setString(2, owner.toString());
        island.setString(3, slotId.toString());
        island.setString(4, NOW.toString());
        island.setString(5, NOW.toString());
        island.executeUpdate();
      }
    }
    seedMembership(islandId, owner, NamespacedId.of("openoneblock", "owner"), true);
  }

  private void seedMembership(
      IslandId islandId, PlayerId player, NamespacedId role, boolean owner) throws Exception {
    try (Connection connection = factory.open();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                INSERT INTO island_memberships (
                    island_id, player_id, role_id, active, owner, created_at, updated_at
                ) VALUES (?, ?, ?, 1, ?, ?, ?)
                """)) {
      statement.setString(1, islandId.toString());
      statement.setString(2, player.toString());
      statement.setString(3, role.toString());
      statement.setInt(4, owner ? 1 : 0);
      statement.setString(5, NOW.toString());
      statement.setString(6, NOW.toString());
      statement.executeUpdate();
    }
  }

  private int activeMemberCount(IslandId islandId) throws Exception {
    return scalarInt(
        "SELECT COUNT(*) FROM island_memberships WHERE island_id = ? AND active = 1",
        islandId.toString());
  }

  private int activeMembershipCount(PlayerId player) throws Exception {
    return scalarInt(
        "SELECT COUNT(*) FROM island_memberships WHERE player_id = ? AND active = 1",
        player.toString());
  }

  private long islandVersion(IslandId islandId) throws Exception {
    return scalarLong("SELECT version FROM islands WHERE island_id = ?", islandId.toString());
  }

  private PlayerId islandOwner(IslandId islandId) throws Exception {
    return PlayerId.parse(scalarString("SELECT owner_player_id FROM islands WHERE island_id = ?", islandId.toString()));
  }

  private boolean isOwnerMembership(IslandId islandId, PlayerId player) throws Exception {
    return scalarInt(
            "SELECT owner FROM island_memberships WHERE island_id = ? AND player_id = ?",
            islandId.toString(),
            player.toString())
        == 1;
  }

  private String membershipRole(IslandId islandId, PlayerId player) throws Exception {
    return scalarString(
        "SELECT role_id FROM island_memberships WHERE island_id = ? AND player_id = ?",
        islandId.toString(),
        player.toString());
  }

  private int scalarInt(String sql, String... values) throws Exception {
    return (int) scalarLong(sql, values);
  }

  private long scalarLong(String sql, String... values) throws Exception {
    try (Connection connection = factory.open();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < values.length; index++) {
        statement.setString(index + 1, values[index]);
      }
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private String scalarString(String sql, String... values) throws Exception {
    try (Connection connection = factory.open();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < values.length; index++) {
        statement.setString(index + 1, values[index]);
      }
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getString(1);
      }
    }
  }

  private static IslandRoleRegistry roles() {
    Set<IslandPermission> administration =
        Set.of(
            IslandPermission.INVITE_MEMBER,
            IslandPermission.KICK_MEMBER,
            IslandPermission.CHANGE_SETTINGS);
    return new IslandRoleRegistry(
        List.of(
            new IslandRoleDefinition(
                NamespacedId.of("openoneblock", "owner"), Set.of(), true, 1000),
            new IslandRoleDefinition(CO_OWNER_ROLE, administration, false, 800),
            new IslandRoleDefinition(MODERATOR_ROLE, administration, false, 600),
            new IslandRoleDefinition(MEMBER_ROLE, Set.of(), false, 400),
            new IslandRoleDefinition(
                NamespacedId.of("openoneblock", "trusted"), Set.of(), false, 200),
            new IslandRoleDefinition(
                NamespacedId.of("openoneblock", "visitor"), Set.of(), false, 100),
            new IslandRoleDefinition(
                NamespacedId.of("openoneblock", "banned"), Set.of(), false, 0)));
  }
}
