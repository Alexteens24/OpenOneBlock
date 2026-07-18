package dev.openoneblock.persistence.sqlite.island;

import java.io.Serial;

/** Raised when an operation ID has already been used for a different island creation intent. */
public final class IslandCreationOperationConflictException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates an operation identity conflict.
   *
   * @param message diagnostic conflict description
   */
  public IslandCreationOperationConflictException(String message) {
    super(message);
  }
}
