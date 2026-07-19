package dev.openoneblock.paper.bootstrap;

import dev.openoneblock.core.locator.WorldProjectionDrift;
import java.util.List;

/** Startup failure retaining complete authoritative world identity mismatch diagnostics. */
public final class WorldProjectionDriftException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Immutable projection drift details. */
  private final transient List<WorldProjectionDrift> drifts;

  /**
   * Creates a fail-closed startup error.
   *
   * @param drifts non-empty projection mismatch list
   */
  public WorldProjectionDriftException(List<WorldProjectionDrift> drifts) {
    super("World projection drift blocks startup: " + drifts);
    this.drifts = List.copyOf(drifts);
    if (drifts.isEmpty()) {
      throw new IllegalArgumentException("drifts must not be empty");
    }
  }

  /**
   * Returns complete operator diagnostics.
   *
   * @return immutable drift list
   */
  public List<WorldProjectionDrift> drifts() {
    return drifts;
  }
}
