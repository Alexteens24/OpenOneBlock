package dev.openoneblock.core.platform;

import java.io.Serial;

/** The target entity retired before ownership-safe scheduled work could execute. */
public final class EntityTaskUnavailableException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  /** Creates an entity retirement failure. */
  public EntityTaskUnavailableException() {
    super("Entity retired before scheduled work could execute");
  }
}
