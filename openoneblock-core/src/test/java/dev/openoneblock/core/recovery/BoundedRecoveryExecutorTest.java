package dev.openoneblock.core.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class BoundedRecoveryExecutorTest {
  @Test
  void startsOnlyOneBoundedBatchAndPreservesInputOrder() {
    List<Integer> started = new ArrayList<>();
    List<CompletableFuture<String>> stages =
        List.of(
            new CompletableFuture<>(),
            new CompletableFuture<>(),
            new CompletableFuture<>(),
            new CompletableFuture<>());
    var result =
        BoundedRecoveryExecutor.map(
            List.of(0, 1, 2, 3),
            2,
            index -> {
              started.add(index);
              return stages.get(index);
            });

    assertEquals(List.of(0, 1), started);
    stages.get(1).complete("one");
    assertEquals(List.of(0, 1), started);
    stages.get(0).complete("zero");
    assertEquals(List.of(0, 1, 2, 3), started);
    stages.get(3).complete("three");
    stages.get(2).complete("two");

    assertEquals(List.of("zero", "one", "two", "three"), result.toCompletableFuture().join());
  }

  @Test
  void failedBatchWaitsForStartedPeersAndNeverStartsLaterInputs() {
    List<Integer> started = new ArrayList<>();
    CompletableFuture<String> failed = new CompletableFuture<>();
    CompletableFuture<String> peer = new CompletableFuture<>();
    var result =
        BoundedRecoveryExecutor.map(
            List.of(0, 1, 2),
            2,
            index -> {
              started.add(index);
              return index == 0 ? failed : peer;
            });

    failed.completeExceptionally(new IllegalStateException("unsafe recovery"));
    assertFalse(result.toCompletableFuture().isDone());
    peer.complete("peer finished");

    assertThrows(CompletionException.class, () -> result.toCompletableFuture().join());
    assertEquals(List.of(0, 1), started);
  }

  @Test
  void synchronousMapperFailureWaitsForEarlierPeersAndRejectsInvalidLimit() {
    CompletableFuture<String> peer = new CompletableFuture<>();
    var result =
        BoundedRecoveryExecutor.map(
            List.of(0, 1),
            2,
            index -> {
              if (index == 1) {
                throw new IllegalArgumentException("bad input");
              }
              return peer;
            });

    assertFalse(result.toCompletableFuture().isDone());
    peer.complete("done");
    assertThrows(CompletionException.class, () -> result.toCompletableFuture().join());
    assertThrows(
        IllegalArgumentException.class,
        () -> BoundedRecoveryExecutor.map(List.of(1), 0, CompletableFuture::completedFuture));
  }
}
