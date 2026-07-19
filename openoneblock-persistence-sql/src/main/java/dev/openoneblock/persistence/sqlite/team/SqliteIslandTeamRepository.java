package dev.openoneblock.persistence.sqlite.team;

import dev.openoneblock.api.event.IslandMembershipChangedEvent;
import dev.openoneblock.api.event.MembershipMutationKind;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.api.island.IslandPermission;
import dev.openoneblock.core.team.IslandInvitationCommand;
import dev.openoneblock.core.team.IslandInvitationResponseCommand;
import dev.openoneblock.core.team.IslandMembershipCommand;
import dev.openoneblock.core.team.IslandOwnershipTransferCommand;
import dev.openoneblock.core.team.IslandRoleRegistry;
import dev.openoneblock.core.team.IslandTeamMutationRejectedException;
import dev.openoneblock.core.team.IslandTeamRepository;
import dev.openoneblock.core.team.MembershipCommandKind;
import dev.openoneblock.core.team.TeamMutationResult;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.SqliteImmediateTransactions;
import dev.openoneblock.persistence.sqlite.SqlitePersistenceException;
import dev.openoneblock.protection.CommittedIslandProtectionPublisher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** SQLite atomic and idempotent island-team mutation repository. */
public final class SqliteIslandTeamRepository implements IslandTeamRepository {
  private static final NamespacedId OWNER_ROLE = NamespacedId.of("openoneblock", "owner");
  private static final NamespacedId BANNED_ROLE = NamespacedId.of("openoneblock", "banned");
  private static final NamespacedId TRUSTED_ROLE = NamespacedId.of("openoneblock", "trusted");
  private static final NamespacedId VISITOR_ROLE = NamespacedId.of("openoneblock", "visitor");

  private final SqliteImmediateTransactions transactions;
  private final IslandRoleRegistry roles;
  private final CommittedIslandProtectionPublisher protectionPublisher;
  private final Executor databaseExecutor;

  /** Creates a transactional team repository. */
  public SqliteIslandTeamRepository(
      SqliteConnectionFactory connectionFactory,
      IslandRoleRegistry roles,
      CommittedIslandProtectionPublisher protectionPublisher,
      Executor databaseExecutor) {
    this.transactions =
        new SqliteImmediateTransactions(
            Objects.requireNonNull(connectionFactory, "connectionFactory"), 12, 2, 30);
    this.roles = Objects.requireNonNull(roles, "roles");
    this.protectionPublisher = Objects.requireNonNull(protectionPublisher, "protectionPublisher");
    this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
  }

  @Override
  public CompletionStage<TeamMutationResult> invite(IslandInvitationCommand command) {
    Objects.requireNonNull(command, "command");
    return execute(
        command.islandId(),
        () ->
            transact(
                command.operationId(),
                fingerprint(command),
                connection -> invite(connection, command)));
  }

  @Override
  public CompletionStage<TeamMutationResult> respond(IslandInvitationResponseCommand command) {
    Objects.requireNonNull(command, "command");
    return execute(
        command.islandId(),
        () ->
            transact(
                command.operationId(),
                fingerprint(command),
                connection -> respond(connection, command)));
  }

  @Override
  public CompletionStage<TeamMutationResult> mutate(IslandMembershipCommand command) {
    Objects.requireNonNull(command, "command");
    return execute(
        command.islandId(),
        () ->
            transact(
                command.operationId(),
                fingerprint(command),
                connection -> mutate(connection, command)));
  }

  @Override
  public CompletionStage<TeamMutationResult> transferOwnership(
      IslandOwnershipTransferCommand command) {
    Objects.requireNonNull(command, "command");
    return execute(
        command.islandId(),
        () ->
            transact(
                command.operationId(),
                fingerprint(command),
                connection -> transfer(connection, command)));
  }

  private CompletionStage<TeamMutationResult> execute(
      IslandId islandId, Supplier<TeamMutationResult> work) {
    try {
      return CompletableFuture.supplyAsync(
          () -> {
            TeamMutationResult result = work.get();
            if (changesProtection(result.event().kind())) {
              protectionPublisher.publishCommitted(islandId);
            }
            return result;
          },
          databaseExecutor);
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  private TeamMutationResult transact(OperationId operationId, String fingerprint, SqlWork work) {
    try {
      return transactions.execute(
          connection -> {
            Optional<Receipt> existing = findReceipt(connection, operationId);
            if (existing.isPresent()) {
              Receipt receipt = existing.orElseThrow();
              if (!receipt.fingerprint().equals(fingerprint)) {
                throw reject("operation-id-reused-with-different-team-command");
              }
              return new TeamMutationResult(receipt.event(), true);
            }
            return work.execute(connection);
          });
    } catch (SQLException failure) {
      throw new SqlitePersistenceException("Failed SQLite island-team transaction", failure);
    }
  }

  private TeamMutationResult invite(Connection connection, IslandInvitationCommand command)
      throws SQLException {
    IslandRow island =
        requireActiveVersion(connection, command.islandId(), command.expectedIslandVersion());
    MembershipRow actor = requireMember(connection, command.islandId(), command.actorPlayerId());
    requirePermission(actor, IslandPermission.INVITE_MEMBER);
    requireAssignableRole(command.proposedRoleId());
    requireCanManage(actor, command.proposedRoleId());
    if (command.actorPlayerId().equals(command.invitedPlayerId())) {
      throw reject("cannot-invite-self");
    }
    if (findActiveMembership(connection, command.invitedPlayerId()).isPresent()) {
      throw reject("player-already-has-active-island");
    }
    if (isBanned(connection, command.islandId(), command.invitedPlayerId())) {
      throw reject("player-is-banned");
    }
    if (activeMemberCount(connection, command.islandId()) >= command.maximumTeamSize()) {
      throw reject("team-size-limit-reached");
    }
    expirePendingInvitation(
        connection, command.islandId(), command.invitedPlayerId(), command.requestedAt());
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO island_invitations (
                invitation_id, island_id, invited_player_id, invited_by_player_id,
                proposed_role_id, state, expires_at, version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, 0, ?, ?)
            """)) {
      statement.setString(1, command.invitationId().toString());
      statement.setString(2, command.islandId().toString());
      statement.setString(3, command.invitedPlayerId().toString());
      statement.setString(4, command.actorPlayerId().toString());
      statement.setString(5, command.proposedRoleId().toString());
      statement.setString(6, command.expiresAt().toString());
      statement.setString(7, command.requestedAt().toString());
      statement.setString(8, command.requestedAt().toString());
      statement.executeUpdate();
    }
    long committedVersion = incrementIslandVersion(connection, island, command.requestedAt());
    return commitReceipt(
        connection,
        command.operationId(),
        fingerprint(command),
        command.islandId(),
        MembershipMutationKind.INVITED,
        command.actorPlayerId(),
        command.invitedPlayerId(),
        Optional.of(command.proposedRoleId()),
        committedVersion,
        command.requestedAt());
  }

  private TeamMutationResult respond(Connection connection, IslandInvitationResponseCommand command)
      throws SQLException {
    IslandRow island =
        requireActiveVersion(connection, command.islandId(), command.expectedIslandVersion());
    InvitationRow invitation = requirePendingInvitation(connection, command);
    MembershipMutationKind kind;
    if (command.accept()) {
      if (!command.respondedAt().isBefore(invitation.expiresAt())) {
        throw reject("invitation-expired");
      }
      if (findActiveMembership(connection, command.invitedPlayerId()).isPresent()) {
        throw reject("player-already-has-active-island");
      }
      if (isBanned(connection, command.islandId(), command.invitedPlayerId())) {
        throw reject("player-is-banned");
      }
      if (activeMemberCount(connection, command.islandId()) >= command.maximumTeamSize()) {
        throw reject("team-size-limit-reached");
      }
      insertOrReactivateMembership(connection, command, invitation.roleId());
      terminalInvitation(connection, command, "ACCEPTED");
      kind = MembershipMutationKind.INVITATION_ACCEPTED;
    } else {
      terminalInvitation(connection, command, "DECLINED");
      kind = MembershipMutationKind.INVITATION_DECLINED;
    }
    long committedVersion = incrementIslandVersion(connection, island, command.respondedAt());
    return commitReceipt(
        connection,
        command.operationId(),
        fingerprint(command),
        command.islandId(),
        kind,
        command.invitedPlayerId(),
        command.invitedPlayerId(),
        Optional.of(invitation.roleId()),
        committedVersion,
        command.respondedAt());
  }

  private TeamMutationResult mutate(Connection connection, IslandMembershipCommand command)
      throws SQLException {
    IslandRow island =
        requireActiveVersion(connection, command.islandId(), command.expectedIslandVersion());
    MembershipRow actor = requireMember(connection, command.islandId(), command.actorPlayerId());
    MembershipMutationKind eventKind;
    Optional<NamespacedId> role = command.targetRoleId();
    switch (command.kind()) {
      case LEAVE -> {
        if (actor.owner()) {
          throw reject("owner-must-transfer-or-delete");
        }
        deactivateMembership(
            connection, command.islandId(), command.subjectPlayerId(), command.requestedAt());
        eventKind = MembershipMutationKind.LEFT;
        role = Optional.of(actor.roleId());
      }
      case KICK -> {
        requirePermission(actor, IslandPermission.KICK_MEMBER);
        MembershipRow subject =
            requireMember(connection, command.islandId(), command.subjectPlayerId());
        if (subject.owner()) {
          throw reject("owner-cannot-be-kicked");
        }
        requireCanManage(actor, subject.roleId());
        deactivateMembership(
            connection, command.islandId(), command.subjectPlayerId(), command.requestedAt());
        eventKind = MembershipMutationKind.KICKED;
        role = Optional.of(subject.roleId());
      }
      case BAN -> {
        requirePermission(actor, IslandPermission.KICK_MEMBER);
        Optional<MembershipRow> subject =
            membership(connection, command.islandId(), command.subjectPlayerId());
        if (subject.filter(MembershipRow::owner).isPresent()) {
          throw reject("owner-cannot-be-banned");
        }
        if (subject.filter(MembershipRow::active).isPresent()) {
          requireCanManage(actor, subject.orElseThrow().roleId());
          deactivateMembership(
              connection, command.islandId(), command.subjectPlayerId(), command.requestedAt());
        } else {
          requireCanManage(actor, VISITOR_ROLE);
        }
        upsertAccess(
            connection,
            command.islandId(),
            command.subjectPlayerId(),
            "BANNED",
            null,
            command.requestedAt());
        revokePendingInvitation(
            connection, command.islandId(), command.subjectPlayerId(), command.requestedAt());
        eventKind = MembershipMutationKind.BANNED;
        role = Optional.of(BANNED_ROLE);
      }
      case UNBAN -> {
        requirePermission(actor, IslandPermission.KICK_MEMBER);
        requireCanManage(actor, BANNED_ROLE);
        if (!deleteBannedAccess(connection, command.islandId(), command.subjectPlayerId())) {
          throw reject("player-is-not-banned");
        }
        eventKind = MembershipMutationKind.UNBANNED;
        role = Optional.of(BANNED_ROLE);
      }
      case TRUST -> {
        requirePermission(actor, IslandPermission.CHANGE_SETTINGS);
        requireCanManage(actor, TRUSTED_ROLE);
        if (findActiveMembership(connection, command.subjectPlayerId()).isPresent()) {
          throw reject("active-member-does-not-need-trust-access");
        }
        upsertAccess(
            connection,
            command.islandId(),
            command.subjectPlayerId(),
            "TRUSTED",
            TRUSTED_ROLE,
            command.requestedAt());
        eventKind = MembershipMutationKind.TRUSTED;
        role = Optional.of(TRUSTED_ROLE);
      }
      case UNTRUST -> {
        requirePermission(actor, IslandPermission.CHANGE_SETTINGS);
        requireCanManage(actor, TRUSTED_ROLE);
        if (!deleteTrustedAccess(connection, command.islandId(), command.subjectPlayerId())) {
          throw reject("player-is-not-trusted");
        }
        eventKind = MembershipMutationKind.UNTRUSTED;
        role = Optional.of(TRUSTED_ROLE);
      }
      case PROMOTE, DEMOTE -> {
        requirePermission(actor, IslandPermission.CHANGE_SETTINGS);
        MembershipRow subject =
            requireMember(connection, command.islandId(), command.subjectPlayerId());
        if (subject.owner()) {
          throw reject("owner-role-requires-transfer");
        }
        NamespacedId target = command.targetRoleId().orElseThrow();
        requireAssignableRole(target);
        requireCanManage(actor, subject.roleId());
        requireCanManage(actor, target);
        updateMembershipRole(
            connection,
            command.islandId(),
            command.subjectPlayerId(),
            target,
            command.requestedAt());
        eventKind =
            command.kind() == MembershipCommandKind.PROMOTE
                ? MembershipMutationKind.PROMOTED
                : MembershipMutationKind.DEMOTED;
      }
      default -> throw new IllegalStateException("unhandled membership command " + command.kind());
    }
    long committedVersion = incrementIslandVersion(connection, island, command.requestedAt());
    return commitReceipt(
        connection,
        command.operationId(),
        fingerprint(command),
        command.islandId(),
        eventKind,
        command.actorPlayerId(),
        command.subjectPlayerId(),
        role,
        committedVersion,
        command.requestedAt());
  }

  private TeamMutationResult transfer(Connection connection, IslandOwnershipTransferCommand command)
      throws SQLException {
    IslandRow island =
        requireActiveVersion(connection, command.islandId(), command.expectedIslandVersion());
    if (!island.ownerId().equals(command.currentOwnerPlayerId())) {
      throw reject("ownership-transfer-requires-current-owner");
    }
    MembershipRow oldOwner =
        requireMember(connection, command.islandId(), command.currentOwnerPlayerId());
    MembershipRow newOwner =
        requireMember(connection, command.islandId(), command.newOwnerPlayerId());
    if (!oldOwner.owner() || newOwner.owner()) {
      throw reject("invalid-owner-membership-projection");
    }
    requireAssignableRole(command.previousOwnerRoleId());
    try (PreparedStatement demote =
        connection.prepareStatement(
            """
                UPDATE island_memberships
                SET role_id = ?, owner = 0, updated_at = ?
                WHERE island_id = ? AND player_id = ? AND active = 1 AND owner = 1
                """)) {
      demote.setString(1, command.previousOwnerRoleId().toString());
      demote.setString(2, command.requestedAt().toString());
      demote.setString(3, command.islandId().toString());
      demote.setString(4, command.currentOwnerPlayerId().toString());
      requireOne(demote.executeUpdate(), "current owner membership changed concurrently");
    }
    try (PreparedStatement promote =
        connection.prepareStatement(
            """
                UPDATE island_memberships
                SET role_id = ?, owner = 1, updated_at = ?
                WHERE island_id = ? AND player_id = ? AND active = 1 AND owner = 0
                """)) {
      promote.setString(1, OWNER_ROLE.toString());
      promote.setString(2, command.requestedAt().toString());
      promote.setString(3, command.islandId().toString());
      promote.setString(4, command.newOwnerPlayerId().toString());
      requireOne(promote.executeUpdate(), "new owner membership changed concurrently");
    }
    try (PreparedStatement update =
        connection.prepareStatement(
            """
            UPDATE islands SET owner_player_id = ?, version = version + 1, updated_at = ?
            WHERE island_id = ? AND version = ? AND lifecycle_state = 'ACTIVE'
            """)) {
      update.setString(1, command.newOwnerPlayerId().toString());
      update.setString(2, command.requestedAt().toString());
      update.setString(3, command.islandId().toString());
      update.setLong(4, island.version());
      requireOne(update.executeUpdate(), "island changed during ownership transfer");
    }
    long committedVersion = island.version() + 1;
    return commitReceipt(
        connection,
        command.operationId(),
        fingerprint(command),
        command.islandId(),
        MembershipMutationKind.OWNERSHIP_TRANSFERRED,
        command.currentOwnerPlayerId(),
        command.newOwnerPlayerId(),
        Optional.of(OWNER_ROLE),
        committedVersion,
        command.requestedAt());
  }

  private IslandRow requireActiveVersion(Connection connection, IslandId islandId, long version)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT owner_player_id, lifecycle_state, version FROM islands WHERE island_id = ?")) {
      statement.setString(1, islandId.toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw reject("unknown-island");
        }
        IslandLifecycleState state =
            IslandLifecycleState.valueOf(result.getString("lifecycle_state"));
        long actualVersion = result.getLong("version");
        if (state != IslandLifecycleState.ACTIVE) {
          throw reject("island-not-active");
        }
        if (actualVersion != version) {
          throw reject("stale-island-version");
        }
        return new IslandRow(
            islandId, PlayerId.parse(result.getString("owner_player_id")), actualVersion);
      }
    }
  }

  private MembershipRow requireMember(Connection connection, IslandId islandId, PlayerId playerId)
      throws SQLException {
    return membership(connection, islandId, playerId)
        .filter(MembershipRow::active)
        .orElseThrow(() -> reject("active-island-membership-required"));
  }

  private Optional<MembershipRow> membership(
      Connection connection, IslandId islandId, PlayerId playerId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT role_id, active, owner FROM island_memberships
            WHERE island_id = ? AND player_id = ?
            """)) {
      statement.setString(1, islandId.toString());
      statement.setString(2, playerId.toString());
      try (ResultSet result = statement.executeQuery()) {
        return result.next()
            ? Optional.of(
                new MembershipRow(
                    NamespacedId.parse(result.getString("role_id")),
                    result.getInt("active") == 1,
                    result.getInt("owner") == 1))
            : Optional.empty();
      }
    }
  }

  private static Optional<IslandId> findActiveMembership(Connection connection, PlayerId playerId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT island_id FROM island_memberships WHERE player_id = ? AND active = 1")) {
      statement.setString(1, playerId.toString());
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? Optional.of(IslandId.parse(result.getString(1))) : Optional.empty();
      }
    }
  }

  private void requirePermission(MembershipRow member, IslandPermission permission) {
    if (!roles.allows(member.roleId(), permission)) {
      throw reject("missing-permission-" + permission.name().toLowerCase());
    }
  }

  private void requireAssignableRole(NamespacedId roleId) {
    if (roleId.equals(OWNER_ROLE)
        || roleId.equals(BANNED_ROLE)
        || roleId.equals(TRUSTED_ROLE)
        || roleId.equals(VISITOR_ROLE)
        || roles.find(roleId).isEmpty()) {
      throw reject("role-is-not-assignable");
    }
  }

  private void requireCanManage(MembershipRow actor, NamespacedId targetRole) {
    if (!roles.canManage(actor.roleId(), targetRole)) {
      throw reject("target-role-has-equal-or-greater-authority");
    }
  }

  private static int activeMemberCount(Connection connection, IslandId islandId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT COUNT(*) FROM island_memberships WHERE island_id = ? AND active = 1")) {
      statement.setString(1, islandId.toString());
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    }
  }

  private static boolean isBanned(Connection connection, IslandId islandId, PlayerId playerId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT 1 FROM island_access_records
            WHERE island_id = ? AND player_id = ? AND access_state = 'BANNED'
            """)) {
      statement.setString(1, islandId.toString());
      statement.setString(2, playerId.toString());
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private static void expirePendingInvitation(
      Connection connection, IslandId islandId, PlayerId playerId, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE island_invitations
            SET state = 'EXPIRED', version = version + 1, updated_at = ?, responded_at = ?
            WHERE island_id = ? AND invited_player_id = ? AND state = 'PENDING' AND expires_at <= ?
            """)) {
      statement.setString(1, now.toString());
      statement.setString(2, now.toString());
      statement.setString(3, islandId.toString());
      statement.setString(4, playerId.toString());
      statement.setString(5, now.toString());
      statement.executeUpdate();
    }
  }

  private static InvitationRow requirePendingInvitation(
      Connection connection, IslandInvitationResponseCommand command) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT invited_player_id, proposed_role_id, state, expires_at
            FROM island_invitations WHERE invitation_id = ? AND island_id = ?
            """)) {
      statement.setString(1, command.invitationId().toString());
      statement.setString(2, command.islandId().toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()
            || !result
                .getString("invited_player_id")
                .equals(command.invitedPlayerId().toString())) {
          throw reject("unknown-invitation");
        }
        if (!result.getString("state").equals("PENDING")) {
          throw reject("invitation-is-not-pending");
        }
        return new InvitationRow(
            NamespacedId.parse(result.getString("proposed_role_id")),
            Instant.parse(result.getString("expires_at")));
      }
    }
  }

  private static void terminalInvitation(
      Connection connection, IslandInvitationResponseCommand command, String state)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE island_invitations
            SET state = ?, version = version + 1, updated_at = ?, responded_at = ?
            WHERE invitation_id = ? AND state = 'PENDING'
            """)) {
      statement.setString(1, state);
      statement.setString(2, command.respondedAt().toString());
      statement.setString(3, command.respondedAt().toString());
      statement.setString(4, command.invitationId().toString());
      requireOne(statement.executeUpdate(), "invitation changed concurrently");
    }
  }

  private static void insertOrReactivateMembership(
      Connection connection, IslandInvitationResponseCommand command, NamespacedId roleId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO island_memberships (
                island_id, player_id, role_id, active, owner, created_at, updated_at
            ) VALUES (?, ?, ?, 1, 0, ?, ?)
            ON CONFLICT (island_id, player_id) DO UPDATE SET
                role_id = excluded.role_id, active = 1, owner = 0, updated_at = excluded.updated_at
            WHERE island_memberships.active = 0
            """)) {
      statement.setString(1, command.islandId().toString());
      statement.setString(2, command.invitedPlayerId().toString());
      statement.setString(3, roleId.toString());
      statement.setString(4, command.respondedAt().toString());
      statement.setString(5, command.respondedAt().toString());
      requireOne(statement.executeUpdate(), "membership is already active");
    }
  }

  private static void deactivateMembership(
      Connection connection, IslandId islandId, PlayerId playerId, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE island_memberships SET active = 0, owner = 0, updated_at = ?
            WHERE island_id = ? AND player_id = ? AND active = 1 AND owner = 0
            """)) {
      statement.setString(1, now.toString());
      statement.setString(2, islandId.toString());
      statement.setString(3, playerId.toString());
      requireOne(statement.executeUpdate(), "non-owner active membership required");
    }
  }

  private static void updateMembershipRole(
      Connection connection, IslandId islandId, PlayerId playerId, NamespacedId role, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE island_memberships SET role_id = ?, updated_at = ?
            WHERE island_id = ? AND player_id = ? AND active = 1 AND owner = 0
            """)) {
      statement.setString(1, role.toString());
      statement.setString(2, now.toString());
      statement.setString(3, islandId.toString());
      statement.setString(4, playerId.toString());
      requireOne(statement.executeUpdate(), "non-owner active membership required");
    }
  }

  private static void upsertAccess(
      Connection connection,
      IslandId islandId,
      PlayerId playerId,
      String state,
      NamespacedId role,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO island_access_records (
                island_id, player_id, access_state, role_id, version, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 0, ?, ?)
            ON CONFLICT (island_id, player_id) DO UPDATE SET
                access_state = excluded.access_state, role_id = excluded.role_id,
                version = island_access_records.version + 1, updated_at = excluded.updated_at
            """)) {
      statement.setString(1, islandId.toString());
      statement.setString(2, playerId.toString());
      statement.setString(3, state);
      statement.setString(4, role == null ? null : role.toString());
      statement.setString(5, now.toString());
      statement.setString(6, now.toString());
      statement.executeUpdate();
    }
  }

  private static boolean deleteBannedAccess(
      Connection connection, IslandId islandId, PlayerId playerId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            DELETE FROM island_access_records
            WHERE island_id = ? AND player_id = ? AND access_state = 'BANNED'
            """)) {
      statement.setString(1, islandId.toString());
      statement.setString(2, playerId.toString());
      return statement.executeUpdate() == 1;
    }
  }

  private static boolean deleteTrustedAccess(
      Connection connection, IslandId islandId, PlayerId playerId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            DELETE FROM island_access_records
            WHERE island_id = ? AND player_id = ? AND access_state = 'TRUSTED'
            """)) {
      statement.setString(1, islandId.toString());
      statement.setString(2, playerId.toString());
      return statement.executeUpdate() == 1;
    }
  }

  private static void revokePendingInvitation(
      Connection connection, IslandId islandId, PlayerId playerId, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE island_invitations
            SET state = 'REVOKED', version = version + 1, updated_at = ?, responded_at = ?
            WHERE island_id = ? AND invited_player_id = ? AND state = 'PENDING'
            """)) {
      statement.setString(1, now.toString());
      statement.setString(2, now.toString());
      statement.setString(3, islandId.toString());
      statement.setString(4, playerId.toString());
      statement.executeUpdate();
    }
  }

  private static long incrementIslandVersion(Connection connection, IslandRow island, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE islands SET version = version + 1, updated_at = ?
            WHERE island_id = ? AND owner_player_id = ? AND version = ?
              AND lifecycle_state = 'ACTIVE'
            """)) {
      statement.setString(1, now.toString());
      statement.setString(2, island.islandId().toString());
      statement.setString(3, island.ownerId().toString());
      statement.setLong(4, island.version());
      requireOne(statement.executeUpdate(), "island changed during team mutation");
      return island.version() + 1;
    }
  }

  private static TeamMutationResult commitReceipt(
      Connection connection,
      OperationId operationId,
      String fingerprint,
      IslandId islandId,
      MembershipMutationKind kind,
      PlayerId actor,
      PlayerId subject,
      Optional<NamespacedId> role,
      long committedVersion,
      Instant committedAt)
      throws SQLException {
    try (PreparedStatement operation =
        connection.prepareStatement(
            """
            INSERT INTO operations (
                operation_id, island_id, kind, state, request_fingerprint,
                outcome_state, completed_at, created_at, updated_at
            ) VALUES (?, ?, 'ISLAND_TEAM_MUTATION', 'COMPLETED', ?, 'SUCCEEDED', ?, ?, ?)
            """)) {
      operation.setString(1, operationId.toString());
      operation.setString(2, islandId.toString());
      operation.setString(3, fingerprint);
      operation.setString(4, committedAt.toString());
      operation.setString(5, committedAt.toString());
      operation.setString(6, committedAt.toString());
      operation.executeUpdate();
    }
    IslandMembershipChangedEvent event =
        new IslandMembershipChangedEvent(
            islandId, operationId, kind, actor, subject, role, committedVersion, committedAt);
    try (PreparedStatement receipt =
        connection.prepareStatement(
            """
            INSERT INTO team_mutation_receipts (
                operation_id, island_id, mutation_kind, actor_player_id, subject_player_id,
                role_id, committed_island_version, committed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
      receipt.setString(1, operationId.toString());
      receipt.setString(2, islandId.toString());
      receipt.setString(3, kind.name());
      receipt.setString(4, actor.toString());
      receipt.setString(5, subject.toString());
      receipt.setString(6, role.map(NamespacedId::toString).orElse(null));
      receipt.setLong(7, committedVersion);
      receipt.setString(8, committedAt.toString());
      receipt.executeUpdate();
    }
    return new TeamMutationResult(event, false);
  }

  private static Optional<Receipt> findReceipt(Connection connection, OperationId operationId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT r.island_id, r.mutation_kind, r.actor_player_id, r.subject_player_id,
                   r.role_id, r.committed_island_version, r.committed_at, o.request_fingerprint
            FROM team_mutation_receipts r
            JOIN operations o ON o.operation_id = r.operation_id
            WHERE r.operation_id = ?
            """)) {
      statement.setString(1, operationId.toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return Optional.empty();
        }
        String role = result.getString("role_id");
        return Optional.of(
            new Receipt(
                result.getString("request_fingerprint"),
                new IslandMembershipChangedEvent(
                    IslandId.parse(result.getString("island_id")),
                    operationId,
                    MembershipMutationKind.valueOf(result.getString("mutation_kind")),
                    PlayerId.parse(result.getString("actor_player_id")),
                    PlayerId.parse(result.getString("subject_player_id")),
                    role == null ? Optional.empty() : Optional.of(NamespacedId.parse(role)),
                    result.getLong("committed_island_version"),
                    Instant.parse(result.getString("committed_at")))));
      }
    }
  }

  private static boolean changesProtection(MembershipMutationKind kind) {
    return kind != MembershipMutationKind.INVITED
        && kind != MembershipMutationKind.INVITATION_DECLINED;
  }

  private static String fingerprint(IslandInvitationCommand command) {
    return sha256(
        String.join(
            "|",
            "invite",
            command.islandId().toString(),
            command.invitationId().toString(),
            command.actorPlayerId().toString(),
            command.invitedPlayerId().toString(),
            command.proposedRoleId().toString(),
            Long.toString(command.expectedIslandVersion()),
            Integer.toString(command.maximumTeamSize()),
            command.requestedAt().toString(),
            command.expiresAt().toString()));
  }

  private static String fingerprint(IslandInvitationResponseCommand command) {
    return sha256(
        String.join(
            "|",
            "respond",
            command.islandId().toString(),
            command.invitationId().toString(),
            command.invitedPlayerId().toString(),
            Long.toString(command.expectedIslandVersion()),
            Integer.toString(command.maximumTeamSize()),
            Boolean.toString(command.accept()),
            command.respondedAt().toString()));
  }

  private static String fingerprint(IslandMembershipCommand command) {
    return sha256(
        String.join(
            "|",
            "membership",
            command.islandId().toString(),
            command.kind().name(),
            command.actorPlayerId().toString(),
            command.subjectPlayerId().toString(),
            command.targetRoleId().map(NamespacedId::toString).orElse("-"),
            Long.toString(command.expectedIslandVersion()),
            command.requestedAt().toString()));
  }

  private static String fingerprint(IslandOwnershipTransferCommand command) {
    return sha256(
        String.join(
            "|",
            "transfer",
            command.islandId().toString(),
            command.currentOwnerPlayerId().toString(),
            command.newOwnerPlayerId().toString(),
            command.previousOwnerRoleId().toString(),
            Long.toString(command.expectedIslandVersion()),
            command.requestedAt().toString()));
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void requireOne(int changed, String reason) {
    if (changed != 1) {
      throw reject(reason);
    }
  }

  private static IslandTeamMutationRejectedException reject(String reason) {
    return new IslandTeamMutationRejectedException(reason);
  }

  private record IslandRow(IslandId islandId, PlayerId ownerId, long version) {}

  private record MembershipRow(NamespacedId roleId, boolean active, boolean owner) {}

  private record InvitationRow(NamespacedId roleId, Instant expiresAt) {}

  private record Receipt(String fingerprint, IslandMembershipChangedEvent event) {}

  @FunctionalInterface
  private interface SqlWork {
    TeamMutationResult execute(Connection connection) throws SQLException;
  }
}
