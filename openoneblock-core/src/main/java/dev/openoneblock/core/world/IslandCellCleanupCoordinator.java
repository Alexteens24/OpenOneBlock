package dev.openoneblock.core.world;

import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.island.IslandAggregateSnapshot;
import dev.openoneblock.core.locator.WorldProjection;
import dev.openoneblock.core.locator.WorldProjectionRegistry;
import dev.openoneblock.core.runtime.IslandActivityLease;
import dev.openoneblock.core.runtime.IslandActivityReason;
import dev.openoneblock.core.runtime.IslandRuntimeHeader;
import dev.openoneblock.core.runtime.IslandRuntimeManager;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Clears and combines verified full-cell cleanup evidence across every shard dimension. */
public final class IslandCellCleanupCoordinator {
  private final IslandRuntimeManager runtimes;
  private final WorldProjectionRegistry worlds;
  private final Function<ShardGroupId, GridGeometry> geometryByShard;
  private final IslandCleanup cleanup;

  /**
   * Creates a multi-dimension cleanup coordinator.
   *
   * @param runtimes bounded chunk ticket lifecycle
   * @param worlds verified dimension registry
   * @param geometryByShard authoritative grid lookup
   * @param cleanup native verified cleanup provider
   */
  public IslandCellCleanupCoordinator(
      IslandRuntimeManager runtimes,
      WorldProjectionRegistry worlds,
      Function<ShardGroupId, GridGeometry> geometryByShard,
      IslandCleanup cleanup) {
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.worlds = Objects.requireNonNull(worlds, "worlds");
    this.geometryByShard = Objects.requireNonNull(geometryByShard, "geometryByShard");
    this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
  }

  /**
   * Clears the full logical cell in every verified dimension, one region lease at a time.
   *
   * @param island locked island owning the slot
   * @param operationId durable operation identity
   * @param minimumY inclusive cleanup height
   * @param maximumYExclusive exclusive cleanup height
   * @return combined worst-case evidence
   */
  public CompletionStage<Result> cleanup(
      IslandAggregateSnapshot island,
      OperationId operationId,
      int minimumY,
      int maximumYExclusive) {
    Objects.requireNonNull(island, "island");
    Objects.requireNonNull(operationId, "operationId");
    if (minimumY >= maximumYExclusive) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("cleanup height must not be empty"));
    }
    var slot = island.primarySlot().orElseThrow();
    GridGeometry geometry =
        Objects.requireNonNull(geometryByShard.apply(slot.shardGroupId()), "geometry");
    var projections = worlds.projectionsForShard(slot.shardGroupId());
    if (projections.isEmpty()) {
      return CompletableFuture.completedFuture(
          new Result(
              IslandCleanup.Status.AMBIGUOUS, "no verified world projections for island shard"));
    }
    CompletionStage<Result> sequence = CompletableFuture.completedFuture(Result.clean());
    for (WorldProjection projection : projections) {
      sequence =
          sequence.thenCompose(
              current ->
                  cleanupProjection(
                          island, operationId, geometry, projection, minimumY, maximumYExclusive)
                      .thenApply(current::combine));
    }
    return sequence;
  }

  private CompletionStage<Result> cleanupProjection(
      IslandAggregateSnapshot island,
      OperationId operationId,
      GridGeometry geometry,
      WorldProjection projection,
      int minimumY,
      int maximumYExclusive) {
    var slot = island.primarySlot().orElseThrow();
    var bounds = geometry.fullCell(slot.gridPosition());
    IslandRuntimeHeader header =
        new IslandRuntimeHeader(
            island.islandId(), projection.worldId(), slot.gridPosition(), bounds);
    return runtimes
        .retain(header, IslandActivityReason.CLEANUP, operationId)
        .handle(
            (lease, ticketFailure) -> {
              if (ticketFailure != null) {
                return CompletableFuture.completedFuture(
                    new Result(
                        IslandCleanup.Status.AMBIGUOUS,
                        projection.dimensionId()
                            + ": ticket acquisition failed: "
                            + diagnostic(ticketFailure)));
              }
              IslandCleanup.Plan plan =
                  new IslandCleanup.Plan(
                      operationId,
                      island.islandId(),
                      projection.worldId(),
                      bounds,
                      minimumY,
                      maximumYExclusive);
              return cleanupWithRelease(projection, lease, plan);
            })
        .thenCompose(stage -> stage);
  }

  private CompletionStage<Result> cleanupWithRelease(
      WorldProjection projection, IslandActivityLease lease, IslandCleanup.Plan plan) {
    CompletionStage<IslandCleanup.Result> work;
    try {
      work = Objects.requireNonNull(cleanup.cleanup(plan), "cleanup completion");
    } catch (Throwable failure) {
      work = CompletableFuture.failedFuture(failure);
    }
    return work.handle(
            (result, failure) ->
                failure == null
                    ? new Result(
                        result.status(), projection.dimensionId() + ": " + result.diagnostic())
                    : new Result(
                        IslandCleanup.Status.AMBIGUOUS,
                        projection.dimensionId() + ": cleanup failed: " + diagnostic(failure)))
        .thenCompose(result -> release(projection, lease, result));
  }

  private static CompletionStage<Result> release(
      WorldProjection projection, IslandActivityLease lease, Result result) {
    return lease
        .release()
        .handle(
            (ignored, releaseFailure) -> {
              if (releaseFailure == null) {
                return result;
              }
              return result.combine(
                  new Result(
                      IslandCleanup.Status.AMBIGUOUS,
                      projection.dimensionId()
                          + ": ticket release failed: "
                          + diagnostic(releaseFailure)));
            });
  }

  private static String diagnostic(Throwable failure) {
    Throwable current = failure;
    while ((current instanceof java.util.concurrent.CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    String message = current.getMessage();
    return current.getClass().getSimpleName()
        + (message == null || message.isBlank() ? "" : ": " + message);
  }

  /**
   * Combined cleanup evidence, using ambiguity as the worst status.
   *
   * @param status combined cleanup status
   * @param diagnostic combined dimension evidence
   */
  public record Result(IslandCleanup.Status status, String diagnostic) {
    /** Validates evidence. */
    public Result {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(diagnostic, "diagnostic");
      if (diagnostic.isBlank()) {
        throw new IllegalArgumentException("cleanup diagnostic must not be blank");
      }
    }

    private static Result clean() {
      return new Result(IslandCleanup.Status.VERIFIED_CLEAN, "all dimensions clean");
    }

    private Result combine(Result other) {
      IslandCleanup.Status combined = worst(status, other.status);
      return new Result(
          combined,
          status.name().toLowerCase(Locale.ROOT)
              + ": "
              + diagnostic
              + "; "
              + other.status.name().toLowerCase(Locale.ROOT)
              + ": "
              + other.diagnostic);
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
}
