package dev.openoneblock.paper.command;

import java.io.Serial;
import java.util.Objects;

/**
 * Raised when a destructive confirmation token is absent, expired, reused, or authority-mismatched.
 */
public final class ConfirmationRejectedException extends IllegalArgumentException {
  @Serial private static final long serialVersionUID = 1L;

  /** Stable rejection category. */
  private final String reason;

  /**
   * Creates a rejected confirmation.
   *
   * @param reason stable diagnostic
   */
  public ConfirmationRejectedException(String reason) {
    super("Destructive command confirmation rejected: " + reason);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  /**
   * Returns the stable rejection category.
   *
   * @return reason identity
   */
  public String reason() {
    return reason;
  }
}
