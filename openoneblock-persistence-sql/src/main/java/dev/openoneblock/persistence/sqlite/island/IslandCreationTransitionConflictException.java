package dev.openoneblock.persistence.sqlite.island;

import java.io.Serial;

/** Raised when persisted creation state cannot legally execute the requested pipeline stage. */
public final class IslandCreationTransitionConflictException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates a durable creation state conflict.
   *
   * @param message diagnostic conflict description
   */
  public IslandCreationTransitionConflictException(String message) {
    super(message);
  }
}
