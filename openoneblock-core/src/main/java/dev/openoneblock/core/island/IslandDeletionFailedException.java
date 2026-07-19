package dev.openoneblock.core.island;

import java.io.Serial;
import java.util.Objects;

/** Deletion terminated safely without slot reuse because cleanup failed or was ambiguous. */
public final class IslandDeletionFailedException extends IllegalStateException {
  @Serial private static final long serialVersionUID = 1L;

  private final transient IslandDeletionProgress progress;

  /**
   * Creates the terminal safe failure.
   *
   * @param progress durable broken/quarantined result
   */
  public IslandDeletionFailedException(IslandDeletionProgress progress) {
    super(Objects.requireNonNull(progress, "progress").diagnostic());
    this.progress = progress;
    if (progress.status() != IslandDeletionProgress.Status.FAILED
        && progress.status() != IslandDeletionProgress.Status.AMBIGUOUS) {
      throw new IllegalArgumentException("deletion failure requires a failed or ambiguous result");
    }
  }

  /**
   * Returns the durable terminal result.
   *
   * @return failed or ambiguous deletion progress
   */
  public IslandDeletionProgress progress() {
    return progress;
  }
}
