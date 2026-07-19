package dev.openoneblock.core.island;

/** Raised when reset authority, version, identity, or durable stage no longer matches. */
public final class IslandResetConflictException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates a reset conflict.
   *
   * @param message stable conflict diagnostic
   */
  public IslandResetConflictException(String message) {
    super(message);
  }
}
