package dev.openoneblock.paper.command;

import java.io.Serial;
import java.util.Objects;

/** Raised when a command cannot obtain a fully recovered READY runtime. */
public final class CommandRuntimeUnavailableException extends IllegalStateException {
  @Serial private static final long serialVersionUID = 1L;

  /** Stable diagnostic identity. */
  private final String reason;

  /**
   * Creates a stable runtime-unavailability result.
   *
   * @param reason machine-readable diagnostic
   */
  public CommandRuntimeUnavailableException(String reason) {
    super("OpenOneBlock command runtime is unavailable: " + reason);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  /**
   * Returns the stable diagnostic.
   *
   * @return diagnostic identity
   */
  public String reason() {
    return reason;
  }
}
