package dev.openoneblock.core.recovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Starts deterministic recovery batches without blocking threads or flooding downstream systems.
 */
public final class BoundedRecoveryExecutor {
  private BoundedRecoveryExecutor() {}

  /**
   * Maps immutable inputs in ordered batches with at most {@code maximumConcurrency} active stages.
   *
   * <p>A failed batch waits for its already-started peers and prevents every later batch from
   * starting. Result order always matches input order.
   *
   * @param inputs deterministic recovery inputs
   * @param maximumConcurrency maximum stages started in one batch
   * @param recovery non-blocking recovery mapper
   * @param <T> input type
   * @param <R> result type
   * @return immutable ordered results, or the first failed batch
   */
  public static <T, R> CompletionStage<List<R>> map(
      List<T> inputs,
      int maximumConcurrency,
      Function<? super T, ? extends CompletionStage<? extends R>> recovery) {
    List<T> immutableInputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
    Objects.requireNonNull(recovery, "recovery");
    if (maximumConcurrency <= 0) {
      throw new IllegalArgumentException("maximumConcurrency must be positive");
    }
    CompletionStage<List<R>> sequence = CompletableFuture.completedFuture(List.of());
    for (int start = 0; start < immutableInputs.size(); start += maximumConcurrency) {
      int from = start;
      int to = Math.min(start + maximumConcurrency, immutableInputs.size());
      sequence =
          sequence.thenCompose(
              completed -> startBatch(immutableInputs.subList(from, to), recovery, completed));
    }
    return sequence;
  }

  private static <T, R> CompletionStage<List<R>> startBatch(
      List<T> batch,
      Function<? super T, ? extends CompletionStage<? extends R>> recovery,
      List<R> completed) {
    List<CompletionStage<? extends R>> active = new ArrayList<>(batch.size());
    try {
      for (T input : batch) {
        active.add(Objects.requireNonNull(recovery.apply(input), "recovery completion stage"));
      }
    } catch (Throwable failure) {
      return awaitStartedThenFail(active, failure);
    }
    CompletableFuture<?>[] futures =
        active.stream().map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new);
    return CompletableFuture.allOf(futures)
        .thenApply(
            ignored -> {
              List<R> combined = new ArrayList<>(completed.size() + active.size());
              combined.addAll(completed);
              active.forEach(stage -> combined.add(stage.toCompletableFuture().join()));
              return List.copyOf(combined);
            });
  }

  private static <R> CompletionStage<List<R>> awaitStartedThenFail(
      List<CompletionStage<? extends R>> active, Throwable mappingFailure) {
    CompletableFuture<?>[] futures =
        active.stream()
            .map(stage -> stage.handle((ignored, failure) -> null).toCompletableFuture())
            .toArray(CompletableFuture[]::new);
    return CompletableFuture.allOf(futures)
        .thenCompose(ignored -> CompletableFuture.failedFuture(mappingFailure));
  }
}
