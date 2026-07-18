package dev.openoneblock.core.execution;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Result of attempting to submit work to an island execution lane.
 *
 * @param <T> completion value type
 */
public sealed interface LaneSubmission<T> permits LaneSubmission.Accepted, LaneSubmission.Rejected {
  /**
   * Work accepted by the lane with a read-only completion view.
   *
   * @param completion full logical completion of the accepted work
   * @param <T> completion value type
   */
  record Accepted<T>(CompletionStage<T> completion) implements LaneSubmission<T> {
    /** Validates the accepted completion stage. */
    public Accepted {
      Objects.requireNonNull(completion, "completion");
    }
  }

  /**
   * Work rejected before execution and without side effects.
   *
   * @param reason stable rejection reason
   * @param <T> completion value type of the rejected work
   */
  record Rejected<T>(LaneRejectionReason reason) implements LaneSubmission<T> {
    /** Validates the rejection reason. */
    public Rejected {
      Objects.requireNonNull(reason, "reason");
    }
  }
}
