package dev.openoneblock.persistence.sqlite.slot;

import java.io.Serial;

/** Existing operation identity conflicts with a new slot allocation request. */
public final class SlotAllocationOperationConflictException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates an operation conflict.
   *
   * @param message diagnostic conflict description
   */
  public SlotAllocationOperationConflictException(String message) {
    super(message);
  }
}
