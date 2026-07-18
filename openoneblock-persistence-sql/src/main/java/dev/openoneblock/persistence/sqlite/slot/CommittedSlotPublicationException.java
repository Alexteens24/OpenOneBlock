package dev.openoneblock.persistence.sqlite.slot;

import dev.openoneblock.core.slot.AllocatedSlot;
import java.io.Serial;
import java.util.Objects;

/** Database commit succeeded but locator projection publication requires reconciliation. */
public final class CommittedSlotPublicationException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  private final transient AllocatedSlot committedSlot;

  /**
   * Creates a post-commit publication failure.
   *
   * @param committedSlot authoritative committed allocation
   * @param cause publication failure
   */
  public CommittedSlotPublicationException(AllocatedSlot committedSlot, Throwable cause) {
    super("Slot allocation committed but locator publication failed", cause);
    this.committedSlot = Objects.requireNonNull(committedSlot, "committedSlot");
  }

  /**
   * Returns the authoritative committed allocation requiring reconciliation.
   *
   * @return committed slot
   */
  public AllocatedSlot committedSlot() {
    return committedSlot;
  }
}
