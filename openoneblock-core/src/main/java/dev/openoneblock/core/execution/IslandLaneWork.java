package dev.openoneblock.core.execution;

import java.util.concurrent.CompletionStage;

/**
 * A non-blocking unit of island work submitted to a sequential lane.
 *
 * @param <T> completion value type
 */
@FunctionalInterface
public interface IslandLaneWork<T> {
  /**
   * Starts the work and returns a stage representing its full logical completion.
   *
   * @return non-null completion stage
   */
  CompletionStage<T> execute();
}
