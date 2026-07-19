package dev.openoneblock.core.island;

import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.execution.IslandExecutionLanes;
import dev.openoneblock.core.execution.LaneSubmission;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.locator.WorldProjection;
import dev.openoneblock.core.locator.WorldProjectionRegistry;
import dev.openoneblock.core.magic.InitialMagicBlock;
import dev.openoneblock.core.operation.IslandOperationClass;
import dev.openoneblock.core.operation.IslandOperationRequest;
import dev.openoneblock.core.runtime.IslandActivityReason;
import dev.openoneblock.core.runtime.IslandRuntimeHeader;
import dev.openoneblock.core.runtime.IslandRuntimeManager;
import dev.openoneblock.core.world.IslandCellCleanupCoordinator;
import dev.openoneblock.core.world.IslandWorldPreparationPlan;
import dev.openoneblock.core.world.MinimalStarterPreparationPlanFactory;
import dev.openoneblock.core.world.WorldEffectPlan;
import dev.openoneblock.core.world.WorldPreparationCoordinator;
import dev.openoneblock.core.world.WorldPreparationReport;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

/** Crash-safe cleanup, starter reconstruction, and atomic reset activation orchestration. */
public final class ResetIslandService {
  private static final NamespacedId PRIMARY_SPAWN = NamespacedId.parse("openoneblock:home");
  private static final NamespacedId PRIMARY_MAGIC_BLOCK = NamespacedId.parse("openoneblock:main");

  private final IslandResetRepository repository;
  private final IslandExecutionLanes lanes;
  private final IslandRuntimeManager runtimes;
  private final WorldProjectionRegistry worlds;
  private final Function<dev.openoneblock.api.id.ShardGroupId, GridGeometry> geometryByShard;
  private final IslandCellCleanupCoordinator cleanup;
  private final WorldPreparationCoordinator preparation;
  private final Clock clock;

  /**
   * Creates the reset application service.
   *
   * @param repository durable reset transactions
   * @param lanes sequential island mutation lanes
   * @param runtimes bounded chunk ticket lifecycle
   * @param worlds verified dimension registry
   * @param geometryByShard authoritative grid lookup
   * @param cleanup verified multi-dimension cleanup coordinator
   * @param preparation durable world preparation coordinator
   * @param clock application clock
   */
  public ResetIslandService(
      IslandResetRepository repository,
      IslandExecutionLanes lanes,
      IslandRuntimeManager runtimes,
      WorldProjectionRegistry worlds,
      Function<dev.openoneblock.api.id.ShardGroupId, GridGeometry> geometryByShard,
      IslandCellCleanupCoordinator cleanup,
      WorldPreparationCoordinator preparation,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.lanes = Objects.requireNonNull(lanes, "lanes");
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.worlds = Objects.requireNonNull(worlds, "worlds");
    this.geometryByShard = Objects.requireNonNull(geometryByShard, "geometryByShard");
    this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    this.preparation = Objects.requireNonNull(preparation, "preparation");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Submits one exact-version reset intent to the island lane.
   *
   * @param request durable owner intent
   * @return active reset outcome
   */
  public CompletionStage<IslandResetResult> reset(IslandResetRequest request) {
    Objects.requireNonNull(request, "request");
    IslandOperationRequest laneRequest =
        new IslandOperationRequest(
            request.islandId(),
            request.operationId(),
            request.expectedIslandVersion(),
            request.requestedAt(),
            IslandOperationClass.LOCKING);
    LaneSubmission<IslandResetResult> submission =
        lanes.submit(laneRequest, () -> resetInLane(request));
    return switch (submission) {
      case LaneSubmission.Accepted<IslandResetResult> accepted -> accepted.completion();
      case LaneSubmission.Rejected<IslandResetResult> rejected ->
          CompletableFuture.failedFuture(new CreateIslandRejectedException(rejected.reason()));
    };
  }

  /**
   * Resumes one pending reset and accepts a committed quarantine as recovered.
   *
   * @param request persisted reset intent
   * @return completion after activation or safe terminal quarantine
   */
  public CompletionStage<Void> recoverPending(IslandResetRequest request) {
    return reset(request)
        .handle(
            (ignored, failure) -> {
              Throwable cause = unwrap(failure);
              if (cause == null || cause instanceof IslandResetFailedException) {
                return null;
              }
              throw new CompletionException(cause);
            });
  }

  private CompletionStage<IslandResetResult> resetInLane(IslandResetRequest request) {
    return repository.beginReset(request).thenCompose(progress -> process(request, progress));
  }

  private CompletionStage<IslandResetResult> process(
      IslandResetRequest request, IslandResetProgress progress) {
    return switch (progress.stage()) {
      case CLEANING_INITIAL, CLEANING_FAILURE -> cleanup(request, progress);
      case PREPARING -> prepare(request, progress);
      case SUCCEEDED ->
          CompletableFuture.completedFuture(
              new IslandResetResult(progress.island(), progress.replay()));
      case FAILED, AMBIGUOUS ->
          CompletableFuture.failedFuture(new IslandResetFailedException(progress));
    };
  }

  private CompletionStage<IslandResetResult> cleanup(
      IslandResetRequest request, IslandResetProgress progress) {
    var island = progress.island();
    var slot = island.primarySlot().orElseThrow();
    return cleanup
        .cleanup(island, request.operationId(), request.minimumY(), request.maximumYExclusive())
        .thenCompose(
            result ->
                repository.completeCleanup(
                    new IslandResetCleanupCompletion(
                        island.islandId(),
                        request.operationId(),
                        progress.stage(),
                        island.version(),
                        slot.version(),
                        result.status(),
                        cleanupDiagnostic(progress, result.diagnostic()),
                        clock.instant())))
        .thenCompose(next -> process(request, next));
  }

  private static String cleanupDiagnostic(IslandResetProgress progress, String diagnostic) {
    if (progress.stage() != IslandResetProgress.Stage.CLEANING_FAILURE
        || progress.diagnostic().isBlank()) {
      return diagnostic;
    }
    return "preparation failed: " + progress.diagnostic() + "; cleanup: " + diagnostic;
  }

  private CompletionStage<IslandResetResult> prepare(
      IslandResetRequest request, IslandResetProgress progress) {
    IslandAggregateSnapshot island = progress.island();
    WorldProjection world;
    GridGeometry geometry;
    try {
      world =
          worlds
              .resolve(request.primaryWorldId())
              .orElseThrow(() -> new IllegalArgumentException("reset world is not registered"));
      var slot = island.primarySlot().orElseThrow();
      if (!world.shardGroupId().equals(slot.shardGroupId())) {
        throw new IllegalArgumentException("reset world belongs to another shard");
      }
      geometry = Objects.requireNonNull(geometryByShard.apply(slot.shardGroupId()), "geometry");
    } catch (RuntimeException failure) {
      return beginPreparationFailure(request, island, diagnostic(failure));
    }
    var slot = island.primarySlot().orElseThrow();
    var bounds = geometry.reservedRegion(slot.gridPosition());
    IslandRuntimeHeader header =
        new IslandRuntimeHeader(island.islandId(), world.worldId(), slot.gridPosition(), bounds);
    return runtimes
        .retain(header, IslandActivityReason.WORLD_PREPARATION, request.operationId())
        .handle(
            (lease, retainFailure) -> {
              if (retainFailure != null) {
                return CompletableFuture.<IslandResetResult>failedFuture(unwrap(retainFailure));
              }
              return withLease(lease, () -> prepareWithLease(request, island, world, geometry));
            })
        .thenCompose(stage -> stage)
        .exceptionallyCompose(
            failure -> {
              Throwable cause = unwrap(failure);
              if (cause instanceof IslandResetFailedException) {
                return CompletableFuture.failedFuture(cause);
              }
              return beginPreparationFailure(request, island, diagnostic(cause));
            });
  }

  private CompletionStage<IslandResetResult> prepareWithLease(
      IslandResetRequest request,
      IslandAggregateSnapshot island,
      WorldProjection world,
      GridGeometry geometry) {
    IslandWorldPreparationPlan plan =
        new MinimalStarterPreparationPlanFactory(request.starterBlockId(), request.magicBlockY())
            .create(
                island, world.worldId(), geometry, request.minimumY(), request.maximumYExclusive());
    CompletionStage<WorldPreparationReport> report;
    try {
      report = Objects.requireNonNull(preparation.prepare(island, plan), "preparation completion");
    } catch (Throwable failure) {
      report = CompletableFuture.failedFuture(failure);
    }
    return report
        .handle((value, failure) -> new PreparationOutcome(value, unwrap(failure)))
        .thenCompose(
            outcome -> {
              if (outcome.failure() != null) {
                return CompletableFuture.failedFuture(outcome.failure());
              }
              WorldPreparationReport verified = outcome.report();
              if (verified.status() != WorldPreparationReport.Status.VERIFIED_SUCCESS) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException(verified.diagnostic()));
              }
              WorldEffectPlan.SetVanillaBlock block =
                  plan.effects().stream()
                      .filter(WorldEffectPlan.SetVanillaBlock.class::isInstance)
                      .map(WorldEffectPlan.SetVanillaBlock.class::cast)
                      .findFirst()
                      .orElseThrow();
              WorldEffectPlan.VerifySafeSpawn spawn =
                  plan.effects().stream()
                      .filter(WorldEffectPlan.VerifySafeSpawn.class::isInstance)
                      .map(WorldEffectPlan.VerifySafeSpawn.class::cast)
                      .findFirst()
                      .orElseThrow();
              var slot = island.primarySlot().orElseThrow();
              return repository
                  .activateReset(
                      new IslandResetActivation(
                          island.islandId(),
                          request.operationId(),
                          island.version(),
                          slot.version(),
                          new IslandSpawnPoint(PRIMARY_SPAWN, spawn.spawn(), true),
                          new InitialMagicBlock(
                              PRIMARY_MAGIC_BLOCK,
                              block.position(),
                              request.profileId(),
                              block.blockType()),
                          request.phaseId(),
                          verified.receipts().stream().map(receipt -> receipt.key()).toList(),
                          clock.instant()))
                  .thenCompose(next -> process(request, next));
            });
  }

  private CompletionStage<IslandResetResult> beginPreparationFailure(
      IslandResetRequest request, IslandAggregateSnapshot island, String diagnostic) {
    if (island.lifecycleState() != IslandLifecycleState.RESETTING) {
      return CompletableFuture.failedFuture(new IllegalStateException(diagnostic));
    }
    var slot = island.primarySlot().orElseThrow();
    return repository
        .beginPreparationFailure(
            new IslandResetPreparationFailure(
                island.islandId(),
                request.operationId(),
                island.version(),
                slot.version(),
                diagnostic,
                clock.instant()))
        .thenCompose(next -> process(request, next));
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable current = failure;
    while ((current instanceof CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static String diagnostic(Throwable failure) {
    Throwable cause = unwrap(failure);
    String message = cause.getMessage();
    return cause.getClass().getSimpleName()
        + (message == null || message.isBlank() ? "" : ": " + message);
  }

  private static <T> CompletionStage<T> withLease(
      dev.openoneblock.core.runtime.IslandActivityLease lease,
      Supplier<CompletionStage<T>> workSupplier) {
    CompletableFuture<T> result = new CompletableFuture<>();
    CompletionStage<T> work;
    try {
      work = Objects.requireNonNull(workSupplier.get(), "lease work");
    } catch (Throwable failure) {
      work = CompletableFuture.failedFuture(failure);
    }
    work.whenComplete(
        (value, workFailure) ->
            lease
                .release()
                .whenComplete(
                    (ignored, releaseFailure) -> {
                      Throwable failure = unwrap(workFailure);
                      Throwable releaseCause = unwrap(releaseFailure);
                      if (failure != null) {
                        if (releaseCause != null && releaseCause != failure) {
                          failure.addSuppressed(releaseCause);
                        }
                        result.completeExceptionally(failure);
                      } else if (releaseCause != null) {
                        result.completeExceptionally(releaseCause);
                      } else {
                        result.complete(value);
                      }
                    }));
    return result;
  }

  private record PreparationOutcome(WorldPreparationReport report, Throwable failure) {}
}
