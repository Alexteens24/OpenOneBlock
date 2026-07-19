package dev.openoneblock.core.island;

import java.io.Serial;

/**
 * Raised when a deletion intent conflicts with authoritative owner, version, or operation state.
 */
public final class IslandDeletionConflictException extends IllegalStateException {
  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates a conflict with a stable diagnostic.
   *
   * @param message conflict diagnostic
   */
  public IslandDeletionConflictException(String message) {
    super(message);
  }
}
