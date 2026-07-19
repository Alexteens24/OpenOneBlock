package dev.openoneblock.core.team;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.openoneblock.api.event.IslandMembershipChangedEvent;
import dev.openoneblock.api.event.MembershipMutationKind;
import dev.openoneblock.api.id.InvitationId;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.core.execution.IslandExecutionLanes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IslandTeamServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-19T08:00:00Z");
  private final ExecutorService executor = Executors.newFixedThreadPool(2);

  @AfterEach
  void stopExecutor() {
    executor.shutdownNow();
  }

  @Test
  void serializesRepositoryCompletionAndPublishesOnlyNewCommittedEvents() throws Exception {
    IslandId islandId = IslandId.generate();
    PlayerId owner = PlayerId.of(UUID.randomUUID());
    PlayerId subject = PlayerId.of(UUID.randomUUID());
    CompletableFuture<TeamMutationResult> firstGate = new CompletableFuture<>();
    CompletableFuture<TeamMutationResult> secondGate = new CompletableFuture<>();
    List<OperationId> repositoryStarts = java.util.Collections.synchronizedList(new ArrayList<>());
    List<IslandMembershipChangedEvent> published =
        java.util.Collections.synchronizedList(new ArrayList<>());
    IslandTeamRepository repository =
        new StubTeamRepository() {
          private int invocation;

          @Override
          public CompletionStage<TeamMutationResult> invite(IslandInvitationCommand command) {
            repositoryStarts.add(command.operationId());
            return invocation++ == 0 ? firstGate : secondGate;
          }
        };
    IslandTeamService service =
        new IslandTeamService(
            repository,
            new IslandExecutionLanes(executor, 4),
            event -> {
              published.add(event);
              return CompletableFuture.completedFuture(null);
            },
            new IslandTeamPolicy(8, java.time.Duration.ofMinutes(5)));
    IslandInvitationCommand first = command(islandId, owner, subject);
    IslandInvitationCommand second = command(islandId, owner, subject);

    CompletionStage<TeamMutationResult> firstCompletion = service.invite(first);
    CompletionStage<TeamMutationResult> secondCompletion = service.invite(second);
    awaitSize(repositoryStarts, 1);
    firstGate.complete(result(first, owner, subject, false));
    awaitSize(repositoryStarts, 2);
    secondGate.complete(result(second, owner, subject, true));

    firstCompletion.toCompletableFuture().get(5, SECONDS);
    secondCompletion.toCompletableFuture().get(5, SECONDS);
    assertEquals(List.of(first.operationId(), second.operationId()), repositoryStarts);
    assertEquals(List.of(first.operationId()), published.stream().map(IslandMembershipChangedEvent::operationId).toList());
  }

  private static IslandInvitationCommand command(
      IslandId islandId, PlayerId owner, PlayerId subject) {
    return new IslandInvitationCommand(
        islandId,
        OperationId.generate(),
        InvitationId.of(UUID.randomUUID()),
        owner,
        subject,
        NamespacedId.of("openoneblock", "member"),
        1,
        8,
        NOW,
        NOW.plusSeconds(300));
  }

  private static TeamMutationResult result(
      IslandInvitationCommand command, PlayerId owner, PlayerId subject, boolean replayed) {
    return new TeamMutationResult(
        new IslandMembershipChangedEvent(
            command.islandId(),
            command.operationId(),
            MembershipMutationKind.INVITED,
            owner,
            subject,
            Optional.of(command.proposedRoleId()),
            2,
            NOW),
        replayed);
  }

  private static void awaitSize(List<?> values, int expected) throws InterruptedException {
    long deadline = System.nanoTime() + SECONDS.toNanos(5);
    while (values.size() < expected && System.nanoTime() < deadline) {
      Thread.sleep(2);
    }
    assertEquals(expected, values.size());
  }

  private abstract static class StubTeamRepository implements IslandTeamRepository {
    @Override
    public CompletionStage<TeamMutationResult> respond(IslandInvitationResponseCommand command) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public CompletionStage<TeamMutationResult> mutate(IslandMembershipCommand command) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public CompletionStage<TeamMutationResult> transferOwnership(
        IslandOwnershipTransferCommand command) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }
  }
}
