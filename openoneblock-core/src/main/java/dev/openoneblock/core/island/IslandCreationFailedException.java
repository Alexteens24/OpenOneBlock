package dev.openoneblock.core.island;

import java.io.Serial;
import java.util.Objects;

/** Creation failed and its release or quarantine disposition was committed durably. */
public final class IslandCreationFailedException extends IllegalStateException {
  @Serial private static final long serialVersionUID = 1L;

  /** Durable terminal failed-creation snapshot. */
  private final transient IslandAggregateSnapshot island;

  /**
   * Creates a durable creation failure.
   *
   * @param diagnostic stable failure evidence
   * @param island committed archived or broken island
   */
  public IslandCreationFailedException(String diagnostic, IslandAggregateSnapshot island) {
    super(Objects.requireNonNull(diagnostic, "diagnostic"));
    this.island = Objects.requireNonNull(island, "island");
    if (island.lifecycleState() != dev.openoneblock.api.island.IslandLifecycleState.ARCHIVED
        && island.lifecycleState() != dev.openoneblock.api.island.IslandLifecycleState.BROKEN) {
      throw new IllegalArgumentException("failed creation must be archived or broken");
    }
  }

  /**
   * Returns the committed failure disposition.
   *
   * @return archived or broken island snapshot
   */
  public IslandAggregateSnapshot island() {
    return island;
  }
}
