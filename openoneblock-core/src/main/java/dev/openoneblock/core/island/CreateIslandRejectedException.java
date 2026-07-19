package dev.openoneblock.core.island;

import dev.openoneblock.core.execution.LaneRejectionReason;
import java.io.Serial;
import java.util.Objects;

/** Raised when the bounded island lane refuses a creation before side effects begin. */
public final class CreateIslandRejectedException extends IllegalStateException {
  @Serial private static final long serialVersionUID = 1L;

  /** Stable machine-readable lane admission result. */
  private final LaneRejectionReason reason;

  /**
   * Creates a lane-admission failure.
   *
   * @param reason stable rejection reason
   */
  public CreateIslandRejectedException(LaneRejectionReason reason) {
    super("island creation lane rejected work: " + reason);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  /**
   * Returns the stable rejection reason.
   *
   * @return rejection reason
   */
  public LaneRejectionReason reason() {
    return reason;
  }
}
