package dev.openoneblock.core.team;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.core.execution.IslandExecutionLanes;
import dev.openoneblock.core.execution.LaneSubmission;
import dev.openoneblock.core.operation.IslandOperationClass;
import dev.openoneblock.core.operation.IslandOperationRequest;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Routes every island-team mutation through the island's sequential execution lane. */
public final class IslandTeamService {
  private final IslandTeamRepository repository;
  private final IslandExecutionLanes lanes;
  private final IslandMembershipEventPublisher events;
  private final IslandTeamPolicy policy;

  public IslandTeamService(
      IslandTeamRepository repository,
      IslandExecutionLanes lanes,
      IslandMembershipEventPublisher events,
      IslandTeamPolicy policy) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.lanes = Objects.requireNonNull(lanes, "lanes");
    this.events = Objects.requireNonNull(events, "events");
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  public CompletionStage<TeamMutationResult> invite(IslandInvitationCommand command) {
    Objects.requireNonNull(command, "command");
    if (command.maximumTeamSize() != policy.maximumSize()
        || !command.expiresAt().equals(command.requestedAt().plus(policy.invitationExpiry()))) {
      return CompletableFuture.failedFuture(
          new IslandTeamMutationRejectedException("team-command-policy-mismatch"));
    }
    return submit(
        command.islandId(),
        command.operationId(),
        command.expectedIslandVersion(),
        command.requestedAt(),
        () -> repository.invite(command));
  }

  public CompletionStage<TeamMutationResult> respond(IslandInvitationResponseCommand command) {
    Objects.requireNonNull(command, "command");
    if (command.maximumTeamSize() != policy.maximumSize()) {
      return CompletableFuture.failedFuture(
          new IslandTeamMutationRejectedException("team-command-policy-mismatch"));
    }
    return submit(
        command.islandId(),
        command.operationId(),
        command.expectedIslandVersion(),
        command.respondedAt(),
        () -> repository.respond(command));
  }

  public CompletionStage<TeamMutationResult> mutate(IslandMembershipCommand command) {
    Objects.requireNonNull(command, "command");
    return submit(
        command.islandId(),
        command.operationId(),
        command.expectedIslandVersion(),
        command.requestedAt(),
        () -> repository.mutate(command));
  }

  public CompletionStage<TeamMutationResult> transferOwnership(
      IslandOwnershipTransferCommand command) {
    Objects.requireNonNull(command, "command");
    return submit(
        command.islandId(),
        command.operationId(),
        command.expectedIslandVersion(),
        command.requestedAt(),
        () -> repository.transferOwnership(command));
  }

  private CompletionStage<TeamMutationResult> submit(
      IslandId islandId,
      OperationId operationId,
      long expectedVersion,
      Instant submittedAt,
      Supplier<CompletionStage<TeamMutationResult>> work) {
    LaneSubmission<TeamMutationResult> submission =
        lanes.submit(
            new IslandOperationRequest(
                islandId,
                operationId,
                expectedVersion,
                submittedAt,
                IslandOperationClass.MUTATION),
            work::get);
    return switch (submission) {
      case LaneSubmission.Accepted<TeamMutationResult> accepted ->
          accepted
              .completion()
              .thenCompose(
                  result -> {
                    if (result.replayed()) {
                      return CompletableFuture.completedFuture(result);
                    }
                    return events.publish(result.event()).thenApply(ignored -> result);
                  });
      case LaneSubmission.Rejected<TeamMutationResult> rejected ->
          CompletableFuture.failedFuture(
              new IslandTeamMutationRejectedException(rejected.reason().name()));
    };
  }
}
