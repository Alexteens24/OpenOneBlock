package dev.openoneblock.core.island;

import dev.openoneblock.core.execution.IslandExecutionLanes;
import dev.openoneblock.core.execution.LaneSubmission;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.locator.WorldProjection;
import dev.openoneblock.core.locator.WorldProjectionRegistry;
import dev.openoneblock.core.operation.IslandOperationClass;
import dev.openoneblock.core.operation.IslandOperationRequest;
import dev.openoneblock.core.runtime.IslandActivityLease;
import dev.openoneblock.core.runtime.IslandActivityReason;
import dev.openoneblock.core.runtime.IslandRuntimeHeader;
import dev.openoneblock.core.runtime.IslandRuntimeManager;
import dev.openoneblock.core.world.IslandCleanup;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Crash-safe verified island cleanup and archival orchestration. */
public final class DeleteIslandService {
  private final IslandDeletionRepository repository;
  private final IslandExecutionLanes lanes;
  private final IslandRuntimeManager runtimes;
  private final WorldProjectionRegistry worlds;
  private final Function<dev.openoneblock.api.id.ShardGroupId, GridGeometry> geometryByShard;
  private final IslandCleanup cleanup;
  private final Clock clock;

  /**
   * Creates the deletion application service.
   *
   * @param repository durable deletion transactions
   * @param lanes sequential island mutation lanes
   * @param runtimes bounded chunk ticket lifecycle
   * @param worlds verified dimension registry
   * @param geometryByShard grid lookup
   * @param cleanup native verified cleanup provider
   * @param clock application clock
   */
  public DeleteIslandService(
      IslandDeletionRepository repository,
      IslandExecutionLanes lanes,
      IslandRuntimeManager runtimes,
      WorldProjectionRegistry worlds,
      Function<dev.openoneblock.api.id.ShardGroupId, GridGeometry> geometryByShard,
      IslandCleanup cleanup,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.lanes = Objects.requireNonNull(lanes, "lanes");
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.worlds = Objects.requireNonNull(worlds, "worlds");
    this.geometryByShard = Objects.requireNonNull(geometryByShard, "geometryByShard");
    this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Submits an exact-version idempotent deletion intent.
   *
   * @param request durable owner intent
   * @return verified archived result
   */
  public CompletionStage<IslandDeletionResult> delete(IslandDeletionRequest request) {
    Objects.requireNonNull(request, "request");
    IslandOperationRequest laneRequest =
        new IslandOperationRequest(
            request.islandId(),
            request.operationId(),
            request.expectedIslandVersion(),
            request.requestedAt(),
            IslandOperationClass.LOCKING);
    LaneSubmission<IslandDeletionResult> submission =
        lanes.submit(laneRequest, () -> deleteInLane(request));
    return switch (submission) {
      case LaneSubmission.Accepted<IslandDeletionResult> accepted -> accepted.completion();
      case LaneSubmission.Rejected<IslandDeletionResult> rejected ->
          CompletableFuture.failedFuture(new CreateIslandRejectedException(rejected.reason()));
    };
  }

  /**
   * Submits an exact-version retry for a quarantined failed deletion cleanup.
   *
   * @param request durable administrative retry intent
   * @return verified archived result
   */
  public CompletionStage<IslandDeletionResult> retryCleanup(IslandCleanupRetryRequest request) {
    Objects.requireNonNull(request, "request");
    IslandOperationRequest laneRequest =
        new IslandOperationRequest(
            request.islandId(),
            request.operationId(),
            request.expectedIslandVersion(),
            request.requestedAt(),
            IslandOperationClass.LOCKING);
    LaneSubmission<IslandDeletionResult> submission =
        lanes.submit(laneRequest, () -> retryInLane(request));
    return switch (submission) {
      case LaneSubmission.Accepted<IslandDeletionResult> accepted -> accepted.completion();
      case LaneSubmission.Rejected<IslandDeletionResult> rejected ->
          CompletableFuture.failedFuture(new CreateIslandRejectedException(rejected.reason()));
    };
  }

  /**
   * Resumes one durable cleanup retry during startup recovery.
   *
   * @param request persisted retry intent
   * @return completion after release or quarantine
   */
  public CompletionStage<Void> recoverPendingCleanupRetry(IslandCleanupRetryRequest request) {
    return retryCleanup(request)
        .handle(
            (ignored, failure) -> {
              Throwable cause = unwrap(failure);
              if (cause == null || cause instanceof IslandDeletionFailedException) {
                return null;
              }
              throw new CompletionException(cause);
            });
  }

  /**
   * Resumes one durable pending deletion and treats a committed safe failure as recovered.
   *
   * @param request persisted deletion intent
   * @return completion after release or quarantine
   */
  public CompletionStage<Void> recoverPending(IslandDeletionRequest request) {
    return delete(request)
        .handle(
            (ignored, failure) -> {
              Throwable cause = unwrap(failure);
              if (cause == null || cause instanceof IslandDeletionFailedException) {
                return null;
              }
              throw new CompletionException(cause);
            });
  }

  private CompletionStage<IslandDeletionResult> deleteInLane(IslandDeletionRequest request) {
    return repository
        .beginDeletion(request)
        .thenCompose(
            progress -> {
              if (progress.status() == IslandDeletionProgress.Status.SUCCEEDED) {
                return CompletableFuture.completedFuture(
                    new IslandDeletionResult(request.islandId(), request.operationId(), true));
              }
              if (progress.status() != IslandDeletionProgress.Status.CLEANING) {
                return CompletableFuture.failedFuture(new IslandDeletionFailedException(progress));
              }
              return cleanupAllDimensions(CleanupIntent.from(request), progress);
            });
  }

  private CompletionStage<IslandDeletionResult> retryInLane(IslandCleanupRetryRequest request) {
    return repository
        .beginCleanupRetry(request)
        .thenCompose(
            progress -> {
              if (progress.status() == IslandDeletionProgress.Status.SUCCEEDED) {
                return CompletableFuture.completedFuture(
                    new IslandDeletionResult(request.islandId(), request.operationId(), true));
              }
              if (progress.status() != IslandDeletionProgress.Status.CLEANING) {
                return CompletableFuture.failedFuture(new IslandDeletionFailedException(progress));
              }
              return cleanupAllDimensions(CleanupIntent.from(request), progress);
            });
  }

  private CompletionStage<IslandDeletionResult> cleanupAllDimensions(
      CleanupIntent request, IslandDeletionProgress progress) {
    var slot = progress.island().primarySlot().orElseThrow();
    GridGeometry geometry = geometryByShard.apply(slot.shardGroupId());
    var projections = worlds.projectionsForShard(slot.shardGroupId());
    if (projections.isEmpty()) {
      return complete(
          request,
          progress,
          new CleanupSummary(
              IslandCleanup.Status.AMBIGUOUS, "no verified world projections for island shard"));
    }
    CompletionStage<CleanupSummary> sequence =
        CompletableFuture.completedFuture(CleanupSummary.clean());
    for (WorldProjection projection : projections) {
      sequence =
          sequence.thenCompose(
              current ->
                  cleanupProjection(request, progress, geometry, projection)
                      .thenApply(current::combine));
    }
    return sequence.thenCompose(summary -> complete(request, progress, summary));
  }

  private CompletionStage<CleanupSummary> cleanupProjection(
      CleanupIntent request,
      IslandDeletionProgress progress,
      GridGeometry geometry,
      WorldProjection projection) {
    var slot = progress.island().primarySlot().orElseThrow();
    var bounds = geometry.fullCell(slot.gridPosition());
    IslandRuntimeHeader header =
        new IslandRuntimeHeader(
            request.islandId(), projection.worldId(), slot.gridPosition(), bounds);
    return runtimes
        .retain(header, IslandActivityReason.CLEANUP, request.operationId())
        .handle(
            (lease, ticketFailure) -> {
              if (ticketFailure != null) {
                return CompletableFuture.completedFuture(
                    new CleanupSummary(
                        IslandCleanup.Status.AMBIGUOUS,
                        projection.dimensionId()
                            + ": ticket acquisition failed: "
                            + diagnostic(ticketFailure)));
              }
              IslandCleanup.Plan plan =
                  new IslandCleanup.Plan(
                      request.operationId(),
                      request.islandId(),
                      projection.worldId(),
                      bounds,
                      request.minimumY(),
                      request.maximumYExclusive());
              return cleanupWithRelease(projection, lease, plan);
            })
        .thenCompose(stage -> stage);
  }

  private CompletionStage<CleanupSummary> cleanupWithRelease(
      WorldProjection projection, IslandActivityLease lease, IslandCleanup.Plan plan) {
    CompletionStage<IslandCleanup.Result> cleanupResult;
    try {
      cleanupResult = cleanup.cleanup(plan);
    } catch (Throwable failure) {
      cleanupResult = CompletableFuture.failedFuture(failure);
    }
    return cleanupResult
        .handle(
            (result, failure) ->
                failure == null
                    ? new CleanupSummary(
                        result.status(), projection.dimensionId() + ": " + result.diagnostic())
                    : new CleanupSummary(
                        IslandCleanup.Status.AMBIGUOUS,
                        projection.dimensionId() + ": cleanup threw: " + diagnostic(failure)))
        .thenCompose(
            outcome ->
                lease
                    .release()
                    .handle(
                        (ignored, releaseFailure) ->
                            releaseFailure == null
                                ? outcome
                                : outcome.combine(
                                    new CleanupSummary(
                                        IslandCleanup.Status.AMBIGUOUS,
                                        projection.dimensionId()
                                            + ": ticket release failed: "
                                            + diagnostic(releaseFailure)))));
  }

  private CompletionStage<IslandDeletionResult> complete(
      CleanupIntent request, IslandDeletionProgress progress, CleanupSummary summary) {
    var slot = progress.island().primarySlot().orElseThrow();
    IslandDeletionCompletion completion =
        new IslandDeletionCompletion(
            request.islandId(),
            request.operationId(),
            progress.island().version(),
            slot.version(),
            summary.status(),
            summary.diagnostic(),
            clock.instant());
    return repository
        .completeDeletion(completion)
        .thenCompose(
            terminal -> {
              if (terminal.status() == IslandDeletionProgress.Status.SUCCEEDED) {
                return CompletableFuture.completedFuture(
                    new IslandDeletionResult(
                        request.islandId(), request.operationId(), terminal.replay()));
              }
              return CompletableFuture.failedFuture(new IslandDeletionFailedException(terminal));
            });
  }

  private static String diagnostic(Throwable failure) {
    Throwable cause = unwrap(failure);
    String message = cause.getMessage();
    return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable current = failure;
    while (current instanceof CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private record CleanupSummary(IslandCleanup.Status status, String diagnostic) {
    private CleanupSummary {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(diagnostic, "diagnostic");
    }

    private static CleanupSummary clean() {
      return new CleanupSummary(IslandCleanup.Status.VERIFIED_CLEAN, "all dimensions clean");
    }

    private CleanupSummary combine(CleanupSummary other) {
      IslandCleanup.Status combined = worst(status, other.status);
      String evidence =
          diagnostic.equals("all dimensions clean")
              ? other.diagnostic
              : diagnostic + "; " + other.diagnostic;
      return new CleanupSummary(combined, evidence);
    }

    private static IslandCleanup.Status worst(
        IslandCleanup.Status first, IslandCleanup.Status second) {
      if (first == IslandCleanup.Status.AMBIGUOUS || second == IslandCleanup.Status.AMBIGUOUS) {
        return IslandCleanup.Status.AMBIGUOUS;
      }
      if (first == IslandCleanup.Status.VERIFIED_FAILURE
          || second == IslandCleanup.Status.VERIFIED_FAILURE) {
        return IslandCleanup.Status.VERIFIED_FAILURE;
      }
      return IslandCleanup.Status.VERIFIED_CLEAN;
    }
  }

  private record CleanupIntent(
      dev.openoneblock.api.id.IslandId islandId,
      dev.openoneblock.api.id.OperationId operationId,
      int minimumY,
      int maximumYExclusive) {
    private static CleanupIntent from(IslandDeletionRequest request) {
      return new CleanupIntent(
          request.islandId(),
          request.operationId(),
          request.minimumY(),
          request.maximumYExclusive());
    }

    private static CleanupIntent from(IslandCleanupRetryRequest request) {
      return new CleanupIntent(
          request.islandId(),
          request.operationId(),
          request.minimumY(),
          request.maximumYExclusive());
    }
  }
}
