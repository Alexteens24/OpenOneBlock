package dev.openoneblock.core.island;

import java.io.Serial;
import java.util.Objects;

/** Island activation committed, but a non-replayed post-commit delivery step failed. */
public final class IslandPostActivationDeliveryException extends IllegalStateException {
  @Serial private static final long serialVersionUID = 1L;

  /** Committed activation result that must never be rolled back by delivery failure. */
  private final transient CreateIslandResult result;

  /**
   * Creates a post-commit delivery failure.
   *
   * @param result committed active island
   * @param cause teleport or event publication failure
   */
  public IslandPostActivationDeliveryException(CreateIslandResult result, Throwable cause) {
    super("island activated but post-activation delivery failed", cause);
    this.result = Objects.requireNonNull(result, "result");
  }

  /**
   * Returns the committed active outcome.
   *
   * @return active island result
   */
  public CreateIslandResult result() {
    return result;
  }
}
