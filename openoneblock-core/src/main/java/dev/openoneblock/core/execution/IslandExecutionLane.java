package dev.openoneblock.core.execution;

import dev.openoneblock.core.operation.IslandOperationClass;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

final class IslandExecutionLane {
  private final Executor executor;
  private final int maximumInFlight;
  private final Consumer<IslandExecutionLane> idleCallback;
  private final ArrayDeque<QueuedWork<?>> pending = new ArrayDeque<>();

  private QueuedWork<?> current;
  private boolean gameplayGated;
  private boolean shutdown;
  private boolean retired;

  IslandExecutionLane(
      Executor executor, int maximumInFlight, Consumer<IslandExecutionLane> idleCallback) {
    this.executor = Objects.requireNonNull(executor, "executor");
    this.maximumInFlight = maximumInFlight;
    this.idleCallback = Objects.requireNonNull(idleCallback, "idleCallback");
  }

  synchronized boolean isRetired() {
    return retired;
  }

  <T> LaneSubmission<T> submit(IslandOperationClass operationClass, IslandLaneWork<T> work) {
    Objects.requireNonNull(operationClass, "operationClass");
    Objects.requireNonNull(work, "work");
    QueuedWork<T> queuedWork;
    boolean dispatch;
    synchronized (this) {
      if (shutdown || retired) {
        return new LaneSubmission.Rejected<>(LaneRejectionReason.SHUTTING_DOWN);
      }
      if (operationClass == IslandOperationClass.GAMEPLAY && gameplayGated) {
        return new LaneSubmission.Rejected<>(LaneRejectionReason.GAMEPLAY_GATED);
      }
      int inFlight = pending.size() + (current == null ? 0 : 1);
      if (inFlight >= maximumInFlight) {
        return new LaneSubmission.Rejected<>(LaneRejectionReason.QUEUE_FULL);
      }

      queuedWork = new QueuedWork<>(work);
      if (operationClass == IslandOperationClass.LOCKING) {
        gameplayGated = true;
      }
      dispatch = current == null;
      if (dispatch) {
        current = queuedWork;
      } else {
        pending.addLast(queuedWork);
      }
    }

    if (dispatch) {
      dispatch(queuedWork);
    }
    return new LaneSubmission.Accepted<>(queuedWork.result.minimalCompletionStage());
  }

  void beginShutdown() {
    boolean notifyIdle = false;
    synchronized (this) {
      shutdown = true;
      if (current == null && pending.isEmpty() && !retired) {
        retired = true;
        notifyIdle = true;
      }
    }
    if (notifyIdle) {
      idleCallback.accept(this);
    }
  }

  private void dispatch(QueuedWork<?> work) {
    try {
      executor.execute(() -> start(work));
    } catch (RuntimeException exception) {
      failLane(exception);
    }
  }

  private <T> void start(QueuedWork<T> queuedWork) {
    CompletionStage<T> completion;
    try {
      completion = Objects.requireNonNull(queuedWork.work.execute(), "work completion stage");
    } catch (Throwable throwable) {
      finish(queuedWork, null, throwable);
      return;
    }
    completion.whenComplete((value, throwable) -> finish(queuedWork, value, throwable));
  }

  private <T> void finish(QueuedWork<T> completedWork, T value, Throwable throwable) {
    if (throwable == null) {
      completedWork.result.complete(value);
    } else {
      completedWork.result.completeExceptionally(throwable);
    }

    QueuedWork<?> next;
    boolean notifyIdle = false;
    synchronized (this) {
      if (current != completedWork) {
        throw new IllegalStateException("Completed work was not current lane work");
      }
      next = pending.pollFirst();
      current = next;
      if (next == null) {
        retired = true;
        notifyIdle = true;
      }
    }

    if (next != null) {
      dispatch(next);
    } else if (notifyIdle) {
      idleCallback.accept(this);
    }
  }

  private void failLane(RuntimeException exception) {
    List<QueuedWork<?>> failed = new ArrayList<>();
    synchronized (this) {
      if (retired) {
        return;
      }
      if (current != null) {
        failed.add(current);
        current = null;
      }
      while (!pending.isEmpty()) {
        failed.add(pending.removeFirst());
      }
      shutdown = true;
      retired = true;
    }
    failed.forEach(work -> work.result.completeExceptionally(exception));
    idleCallback.accept(this);
  }

  private static final class QueuedWork<T> {
    private final IslandLaneWork<T> work;
    private final CompletableFuture<T> result = new CompletableFuture<>();

    private QueuedWork(IslandLaneWork<T> work) {
      this.work = work;
    }
  }
}
