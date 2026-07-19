package dev.openoneblock.persistence.sqlite.world;

import java.io.Serial;

/** Raised when a stable effect key is replayed with different intent or terminal evidence. */
public final class WorldEffectJournalConflictException extends IllegalStateException {
  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates an effect journal invariant conflict.
   *
   * @param message stable conflict description
   */
  public WorldEffectJournalConflictException(String message) {
    super(message);
  }
}
