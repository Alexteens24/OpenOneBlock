package dev.openoneblock.core.execution;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.core.operation.IslandOperationRequest;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Registry of transient, bounded sequential lanes keyed by island identity. */
public final class IslandExecutionLanes {
  private final Executor executor;
  private final int maximumInFlightPerIsland;
  private final ConcurrentMap<IslandId, IslandExecutionLane> lanes = new ConcurrentHashMap<>();
  private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
  private final CompletableFuture<Void> termination = new CompletableFuture<>();

  private volatile boolean accepting = true;

  /**
   * Creates an execution-lane registry.
   *
   * @param executor shared executor used to start logical lane stages
   * @param maximumInFlightPerIsland maximum running-plus-queued operations per island
   */
  public IslandExecutionLanes(Executor executor, int maximumInFlightPerIsland) {
    this.executor = Objects.requireNonNull(executor, "executor");
    if (maximumInFlightPerIsland <= 0) {
      throw new IllegalArgumentException("maximumInFlightPerIsland must be positive");
    }
    this.maximumInFlightPerIsland = maximumInFlightPerIsland;
  }

  /**
   * Attempts to submit one operation without blocking the caller.
   *
   * @param request immutable operation metadata
   * @param work non-blocking logical work
   * @param <T> completion value type
   * @return accepted completion or synchronous rejection reason
   */
  public <T> LaneSubmission<T> submit(IslandOperationRequest request, IslandLaneWork<T> work) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(work, "work");
    while (true) {
      IslandExecutionLane lane;
      lifecycleLock.readLock().lock();
      try {
        if (!accepting) {
          return new LaneSubmission.Rejected<>(LaneRejectionReason.SHUTTING_DOWN);
        }
        lane =
            lanes.compute(
                request.islandId(),
                (islandId, existing) ->
                    existing == null || existing.isRetired()
                        ? new IslandExecutionLane(
                            executor,
                            maximumInFlightPerIsland,
                            idleLane -> onLaneIdle(islandId, idleLane))
                        : existing);
      } finally {
        lifecycleLock.readLock().unlock();
      }

      LaneSubmission<T> submission = lane.submit(request.operationClass(), work);
      if (submission instanceof LaneSubmission.Rejected<?> rejected
          && rejected.reason() == LaneRejectionReason.SHUTTING_DOWN
          && lane.isRetired()) {
        onLaneIdle(request.islandId(), lane);
        continue;
      }
      return submission;
    }
  }

  /**
   * Stops accepting new operations while allowing accepted work to drain.
   *
   * @return completion stage fulfilled after every active lane becomes idle
   */
  public CompletionStage<Void> shutdownGracefully() {
    lifecycleLock.writeLock().lock();
    try {
      if (accepting) {
        accepting = false;
        lanes.values().forEach(IslandExecutionLane::beginShutdown);
        completeTerminationIfIdle();
      }
      return termination.minimalCompletionStage();
    } finally {
      lifecycleLock.writeLock().unlock();
    }
  }

  /**
   * Returns whether the registry still accepts new operations.
   *
   * @return {@code true} before graceful shutdown begins
   */
  public boolean isAccepting() {
    return accepting;
  }

  /**
   * Returns the number of non-idle lane objects currently retained.
   *
   * @return active lane count
   */
  public int activeLaneCount() {
    return lanes.size();
  }

  private void onLaneIdle(IslandId islandId, IslandExecutionLane lane) {
    lanes.remove(islandId, lane);
    completeTerminationIfIdle();
  }

  private void completeTerminationIfIdle() {
    if (!accepting && lanes.isEmpty()) {
      termination.complete(null);
    }
  }
}
