package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import java.io.Serial;
import java.util.Objects;

/** Raised when a persisted home no longer satisfies current world, border, or height invariants. */
public final class UnsafeIslandHomeException extends IllegalStateException {
  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates a fail-closed invalid-home result.
   *
   * @param islandId affected island
   * @param reason stable diagnostic
   */
  public UnsafeIslandHomeException(IslandId islandId, String reason) {
    super(
        "Island "
            + Objects.requireNonNull(islandId, "islandId")
            + " has an unsafe home: "
            + Objects.requireNonNull(reason, "reason"));
  }
}
