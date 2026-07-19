package dev.openoneblock.core.island;

import java.io.Serial;
import java.util.Objects;

/** Signals a safely persisted repair rejection that remains fail-closed. */
public final class IslandRepairFailedException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  private final transient IslandRepairProgress progress;

  /**
   * Creates the failure from terminal progress.
   *
   * @param progress failed or ambiguous repair progress
   */
  public IslandRepairFailedException(IslandRepairProgress progress) {
    super(progress.diagnostic());
    this.progress = Objects.requireNonNull(progress, "progress");
    if (progress.status() != IslandRepairProgress.Status.FAILED
        && progress.status() != IslandRepairProgress.Status.AMBIGUOUS) {
      throw new IllegalArgumentException("repair failure requires terminal unsafe progress");
    }
  }

  /**
   * Returns terminal durable progress.
   *
   * @return repair progress
   */
  public IslandRepairProgress progress() {
    return progress;
  }
}
