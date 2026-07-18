package dev.openoneblock.core.execution;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.core.operation.IslandOperationClass;
import dev.openoneblock.core.operation.IslandOperationRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IslandExecutionLanesTest {
  private static final Instant SUBMITTED_AT = Instant.parse("2026-07-19T00:00:00Z");

  private final List<ExecutorService> executors = new ArrayList<>();

  @AfterEach
  void stopExecutors() {
    executors.forEach(ExecutorService::shutdownNow);
  }

  @Test
  void serializesFullAsyncCompletionForOneIsland() throws Exception {
    ExecutorService executor = executor(4);
    IslandExecutionLanes lanes = new IslandExecutionLanes(executor, 8);
    IslandId islandId = IslandId.generate();
    List<Integer> starts = Collections.synchronizedList(new ArrayList<>());
    List<CompletableFuture<Integer>> gates =
        List.of(new CompletableFuture<>(), new CompletableFuture<>(), new CompletableFuture<>());
    List<CountDownLatch> started =
        List.of(new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));
    List<CompletionStage<Integer>> completions = new ArrayList<>();

    for (int index = 0; index < gates.size(); index++) {
      int workIndex = index;
      completions.add(
          accepted(
              lanes.submit(
                  request(islandId, IslandOperationClass.MUTATION),
                  () -> {
                    starts.add(workIndex);
                    started.get(workIndex).countDown();
                    return gates.get(workIndex);
                  })));
    }

    assertTrue(started.get(0).await(5, SECONDS));
    assertEquals(List.of(0), starts);
    gates.get(0).complete(10);
    assertTrue(started.get(1).await(5, SECONDS));
    assertEquals(List.of(0, 1), starts);
    gates.get(1).complete(11);
    assertTrue(started.get(2).await(5, SECONDS));
    assertEquals(List.of(0, 1, 2), starts);
    gates.get(2).complete(12);

    assertEquals(List.of(10, 11, 12), awaitAll(completions));
    awaitLaneCount(lanes, 0);
  }

  @Test
  void differentIslandsMayRunConcurrently() throws Exception {
    ExecutorService executor = executor(2);
    IslandExecutionLanes lanes = new IslandExecutionLanes(executor, 2);
    CountDownLatch bothStarted = new CountDownLatch(2);
    CompletableFuture<Void> firstGate = new CompletableFuture<>();
    CompletableFuture<Void> secondGate = new CompletableFuture<>();

    CompletionStage<Void> first =
        accepted(
            lanes.submit(
                request(IslandId.generate(), IslandOperationClass.MUTATION),
                () -> {
                  bothStarted.countDown();
                  return firstGate;
                }));
    CompletionStage<Void> second =
        accepted(
            lanes.submit(
                request(IslandId.generate(), IslandOperationClass.MUTATION),
                () -> {
                  bothStarted.countDown();
                  return secondGate;
                }));

    assertTrue(bothStarted.await(5, SECONDS));
    firstGate.complete(null);
    secondGate.complete(null);
    first.toCompletableFuture().get(5, SECONDS);
    second.toCompletableFuture().get(5, SECONDS);
  }

  @Test
  void boundsRunningAndQueuedWorkPerIsland() throws Exception {
    ExecutorService executor = executor(1);
    IslandExecutionLanes lanes = new IslandExecutionLanes(executor, 2);
    IslandId islandId = IslandId.generate();
    CompletableFuture<Void> gate = new CompletableFuture<>();

    CompletionStage<Void> first =
        accepted(lanes.submit(request(islandId, IslandOperationClass.MUTATION), () -> gate));
    CompletionStage<Void> second =
        accepted(
            lanes.submit(
                request(islandId, IslandOperationClass.MUTATION),
                () -> CompletableFuture.completedFuture(null)));
    LaneSubmission.Rejected<Void> rejected =
        rejected(
            lanes.submit(
                request(islandId, IslandOperationClass.MUTATION),
                () -> CompletableFuture.completedFuture(null)));

    assertEquals(LaneRejectionReason.QUEUE_FULL, rejected.reason());
    gate.complete(null);
    first.toCompletableFuture().get(5, SECONDS);
    second.toCompletableFuture().get(5, SECONDS);
  }

  @Test
  void acceptingLockingWorkImmediatelyGatesLaterGameplay() throws Exception {
    ExecutorService executor = executor(2);
    IslandExecutionLanes lanes = new IslandExecutionLanes(executor, 4);
    IslandId islandId = IslandId.generate();
    CompletableFuture<Void> gameplayGate = new CompletableFuture<>();
    CompletableFuture<Void> lockingGate = new CompletableFuture<>();
    CountDownLatch lockingStarted = new CountDownLatch(1);

    CompletionStage<Void> gameplay =
        accepted(
            lanes.submit(request(islandId, IslandOperationClass.GAMEPLAY), () -> gameplayGate));
    CompletionStage<Void> locking =
        accepted(
            lanes.submit(
                request(islandId, IslandOperationClass.LOCKING),
                () -> {
                  lockingStarted.countDown();
                  return lockingGate;
                }));
    LaneSubmission.Rejected<Void> rejected =
        rejected(
            lanes.submit(
                request(islandId, IslandOperationClass.GAMEPLAY),
                () -> CompletableFuture.completedFuture(null)));

    assertEquals(LaneRejectionReason.GAMEPLAY_GATED, rejected.reason());
    gameplayGate.complete(null);
    assertTrue(lockingStarted.await(5, SECONDS));
    lockingGate.complete(null);
    gameplay.toCompletableFuture().get(5, SECONDS);
    locking.toCompletableFuture().get(5, SECONDS);
  }

  @Test
  void failedWorkDoesNotStallFollowingWork() throws Exception {
    ExecutorService executor = executor(2);
    IslandExecutionLanes lanes = new IslandExecutionLanes(executor, 3);
    IslandId islandId = IslandId.generate();
    CompletableFuture<String> failingGate = new CompletableFuture<>();

    CompletionStage<String> failing =
        accepted(lanes.submit(request(islandId, IslandOperationClass.MUTATION), () -> failingGate));
    CompletionStage<String> following =
        accepted(
            lanes.submit(
                request(islandId, IslandOperationClass.MUTATION),
                () -> CompletableFuture.completedFuture("continued")));

    failingGate.completeExceptionally(new IllegalStateException("expected"));
    ExecutionException failure =
        assertThrows(ExecutionException.class, () -> failing.toCompletableFuture().get(5, SECONDS));
    assertInstanceOf(IllegalStateException.class, failure.getCause());
    assertEquals("continued", following.toCompletableFuture().get(5, SECONDS));
  }

  @Test
  void gracefulShutdownRejectsNewWorkAndDrainsAcceptedWork() throws Exception {
    ExecutorService executor = executor(1);
    IslandExecutionLanes lanes = new IslandExecutionLanes(executor, 2);
    IslandId islandId = IslandId.generate();
    CompletableFuture<Void> gate = new CompletableFuture<>();
    CompletionStage<Void> accepted =
        accepted(lanes.submit(request(islandId, IslandOperationClass.MUTATION), () -> gate));

    CompletionStage<Void> termination = lanes.shutdownGracefully();
    LaneSubmission.Rejected<Void> rejected =
        rejected(
            lanes.submit(
                request(islandId, IslandOperationClass.MUTATION),
                () -> CompletableFuture.completedFuture(null)));

    assertFalse(lanes.isAccepting());
    assertFalse(termination.toCompletableFuture().isDone());
    assertEquals(LaneRejectionReason.SHUTTING_DOWN, rejected.reason());
    gate.complete(null);
    accepted.toCompletableFuture().get(5, SECONDS);
    termination.toCompletableFuture().get(5, SECONDS);
    assertEquals(0, lanes.activeLaneCount());
  }

  @Test
  void synchronousExecutorDoesNotRetainACompletedLane() throws Exception {
    IslandExecutionLanes lanes = new IslandExecutionLanes(Runnable::run, 2);

    CompletionStage<String> completion =
        accepted(
            lanes.submit(
                request(IslandId.generate(), IslandOperationClass.MUTATION),
                () -> CompletableFuture.completedFuture("done")));

    assertEquals("done", completion.toCompletableFuture().get(5, SECONDS));
    assertEquals(0, lanes.activeLaneCount());
  }

  @Test
  void executorRejectionFailsAcceptedWorkAndReleasesLane() {
    IslandExecutionLanes lanes =
        new IslandExecutionLanes(
            ignored -> {
              throw new RejectedExecutionException("expected");
            },
            2);

    CompletionStage<Void> completion =
        accepted(
            lanes.submit(
                request(IslandId.generate(), IslandOperationClass.MUTATION),
                () -> CompletableFuture.completedFuture(null)));

    ExecutionException failure =
        assertThrows(
            ExecutionException.class, () -> completion.toCompletableFuture().get(5, SECONDS));
    assertInstanceOf(RejectedExecutionException.class, failure.getCause());
    assertEquals(0, lanes.activeLaneCount());
  }

  @Test
  void concurrentSubmissionsNeverOverlapForOneIsland() throws Exception {
    ExecutorService executor = executor(8);
    IslandExecutionLanes lanes = new IslandExecutionLanes(executor, 256);
    IslandId islandId = IslandId.generate();
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximumActive = new AtomicInteger();

    List<CompletionStage<Integer>> completions =
        IntStream.range(0, 200)
            .parallel()
            .mapToObj(
                index ->
                    accepted(
                        lanes.submit(
                            request(islandId, IslandOperationClass.MUTATION),
                            () -> {
                              int current = active.incrementAndGet();
                              maximumActive.accumulateAndGet(current, Math::max);
                              return CompletableFuture.supplyAsync(
                                  () -> {
                                    Thread.yield();
                                    active.decrementAndGet();
                                    return index;
                                  },
                                  executor);
                            })))
            .toList();

    awaitAll(completions);
    assertEquals(1, maximumActive.get());
    assertEquals(0, active.get());
    awaitLaneCount(lanes, 0);
  }

  private ExecutorService executor(int threads) {
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    executors.add(executor);
    return executor;
  }

  private static IslandOperationRequest request(
      IslandId islandId, IslandOperationClass operationClass) {
    return new IslandOperationRequest(
        islandId, OperationId.generate(), 0, SUBMITTED_AT, operationClass);
  }

  private static <T> CompletionStage<T> accepted(LaneSubmission<T> submission) {
    LaneSubmission.Accepted<?> accepted =
        assertInstanceOf(LaneSubmission.Accepted.class, submission);
    return castCompletion(accepted.completion());
  }

  @SuppressWarnings("unchecked")
  private static <T> CompletionStage<T> castCompletion(CompletionStage<?> completion) {
    return (CompletionStage<T>) completion;
  }

  @SuppressWarnings("unchecked")
  private static <T> LaneSubmission.Rejected<T> rejected(LaneSubmission<T> submission) {
    return (LaneSubmission.Rejected<T>) assertInstanceOf(LaneSubmission.Rejected.class, submission);
  }

  private static <T> List<T> awaitAll(List<CompletionStage<T>> completions) throws Exception {
    List<T> values = new ArrayList<>();
    for (CompletionStage<T> completion : completions) {
      values.add(completion.toCompletableFuture().get(5, SECONDS));
    }
    return values;
  }

  private static void awaitLaneCount(IslandExecutionLanes lanes, int expected) throws Exception {
    long deadline = System.nanoTime() + SECONDS.toNanos(5);
    while (lanes.activeLaneCount() != expected && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(expected, lanes.activeLaneCount());
  }
}
