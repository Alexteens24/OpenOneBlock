package dev.openoneblock.core.island;

import dev.openoneblock.core.execution.IslandExecutionLanes;
import dev.openoneblock.core.execution.LaneSubmission;
import dev.openoneblock.core.operation.IslandOperationClass;
import dev.openoneblock.core.operation.IslandOperationRequest;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Serializes exact-version repair verification without ever activating gameplay. */
public final class RepairIslandService {
  private final IslandRepairRepository repository;
  private final IslandRepairVerifier verifier;
  private final IslandExecutionLanes lanes;
  private final Clock clock;

  /**
   * Creates the repair application service.
   *
   * @param repository durable repair transaction
   * @param verifier read-only runtime/world projection verifier
   * @param lanes sequential island mutation lanes
   * @param clock application clock
   */
  public RepairIslandService(
      IslandRepairRepository repository,
      IslandRepairVerifier verifier,
      IslandExecutionLanes lanes,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.verifier = Objects.requireNonNull(verifier, "verifier");
    this.lanes = Objects.requireNonNull(lanes, "lanes");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Repairs one broken island into maintenance lock after fresh verification.
   *
   * @param request durable exact-version intent
   * @return terminal locked progress
   */
  public CompletionStage<IslandRepairProgress> repair(IslandRepairRequest request) {
    Objects.requireNonNull(request, "request");
    IslandOperationRequest laneRequest =
        new IslandOperationRequest(
            request.islandId(),
            request.operationId(),
            request.expectedIslandVersion(),
            request.requestedAt(),
            IslandOperationClass.LOCKING);
    LaneSubmission<IslandRepairProgress> submission =
        lanes.submit(laneRequest, () -> repairInLane(request));
    return switch (submission) {
      case LaneSubmission.Accepted<IslandRepairProgress> accepted -> accepted.completion();
      case LaneSubmission.Rejected<IslandRepairProgress> rejected ->
          CompletableFuture.failedFuture(new CreateIslandRejectedException(rejected.reason()));
    };
  }

  /**
   * Replays one durable repair during startup recovery.
   *
   * @param request persisted repair intent
   * @return completion after lock or safe rejection
   */
  public CompletionStage<Void> recoverPending(IslandRepairRequest request) {
    return repair(request)
        .handle(
            (ignored, failure) -> {
              Throwable cause = unwrap(failure);
              if (cause == null || cause instanceof IslandRepairFailedException) {
                return null;
              }
              throw new CompletionException(cause);
            });
  }

  private CompletionStage<IslandRepairProgress> repairInLane(IslandRepairRequest request) {
    return repository
        .beginRepair(request)
        .thenCompose(
            progress -> {
              if (progress.status() == IslandRepairProgress.Status.LOCKED) {
                return CompletableFuture.completedFuture(progress);
              }
              if (progress.status() != IslandRepairProgress.Status.VERIFYING) {
                return CompletableFuture.failedFuture(new IslandRepairFailedException(progress));
              }
              return verifySafely(request, progress.island())
                  .handle(
                      (evidence, failure) ->
                          failure == null
                              ? evidence
                              : new IslandRepairEvidence(
                                  IslandRepairEvidence.Status.AMBIGUOUS,
                                  java.util.List.of(),
                                  diagnostic(failure),
                                  clock.instant()))
                  .thenCompose(
                      evidence -> {
                        var slot = progress.island().primarySlot().orElseThrow();
                        return repository.completeRepair(
                            new IslandRepairCompletion(
                                request.islandId(),
                                request.operationId(),
                                progress.island().version(),
                                slot.version(),
                                evidence));
                      })
                  .thenCompose(
                      terminal ->
                          terminal.status() == IslandRepairProgress.Status.LOCKED
                              ? CompletableFuture.completedFuture(terminal)
                              : CompletableFuture.failedFuture(
                                  new IslandRepairFailedException(terminal)));
            });
  }

  private CompletionStage<IslandRepairEvidence> verifySafely(
      IslandRepairRequest request, IslandAggregateSnapshot island) {
    try {
      return Objects.requireNonNull(
          verifier.verify(request, island), "repair verifier completion stage");
    } catch (Throwable failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  private static String diagnostic(Throwable failure) {
    Throwable cause = unwrap(failure);
    String message = cause.getMessage();
    String diagnostic =
        "repair verifier threw "
            + cause.getClass().getSimpleName()
            + (message == null ? "" : ": " + message);
    return diagnostic.length() <= 2_048 ? diagnostic : diagnostic.substring(0, 2_048);
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable current = failure;
    while (current instanceof CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }
}
