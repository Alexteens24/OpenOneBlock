package dev.openoneblock.core.island;

import java.io.Serial;
import java.util.Objects;

/** Reports a durably quarantined reset without losing its terminal projection. */
public final class IslandResetFailedException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;
  private final transient IslandResetProgress progress;

  /**
   * Creates a terminal reset failure.
   *
   * @param progress committed broken/quarantined progress
   */
  public IslandResetFailedException(IslandResetProgress progress) {
    super(
        "island reset "
            + Objects.requireNonNull(progress, "progress").stage()
            + ": "
            + progress.diagnostic());
    this.progress = progress;
  }

  /**
   * Returns the committed terminal projection.
   *
   * @return broken/quarantined reset progress
   */
  public IslandResetProgress progress() {
    return progress;
  }
}
