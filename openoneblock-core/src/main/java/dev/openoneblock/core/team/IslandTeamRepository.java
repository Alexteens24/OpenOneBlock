package dev.openoneblock.core.team;

import java.util.concurrent.CompletionStage;

/** Authoritative transactional island-team persistence boundary. */
public interface IslandTeamRepository {
  CompletionStage<TeamMutationResult> invite(IslandInvitationCommand command);

  CompletionStage<TeamMutationResult> respond(IslandInvitationResponseCommand command);

  CompletionStage<TeamMutationResult> mutate(IslandMembershipCommand command);

  CompletionStage<TeamMutationResult> transferOwnership(IslandOwnershipTransferCommand command);
}
