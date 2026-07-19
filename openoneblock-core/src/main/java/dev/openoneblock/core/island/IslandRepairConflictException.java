package dev.openoneblock.core.island;

import java.io.Serial;

/** Rejects stale, conflicting, or structurally invalid repair intents. */
public final class IslandRepairConflictException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates a repair conflict.
   *
   * @param message deterministic rejection reason
   */
  public IslandRepairConflictException(String message) {
    super(message);
  }
}
