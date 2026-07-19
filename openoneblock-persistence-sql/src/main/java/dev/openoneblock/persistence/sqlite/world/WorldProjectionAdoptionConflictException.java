package dev.openoneblock.persistence.sqlite.world;

/** Raised when an adoption operation ID or optimistic projection version conflicts. */
public final class WorldProjectionAdoptionConflictException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates an adoption conflict.
   *
   * @param message stable conflict reason
   */
  public WorldProjectionAdoptionConflictException(String message) {
    super(message);
  }
}
