package dev.openoneblock.paper.bootstrap;

import dev.openoneblock.core.island.IslandAggregateSnapshot;
import java.util.List;

/** Prevents startup from activating islands whose durable creation operations need repair. */
public final class PendingIslandRecoveryException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Immutable pending island snapshots. */
  private final transient List<IslandAggregateSnapshot> pending;

  /**
   * Creates a fail-closed recovery result.
   *
   * @param pending non-empty unfinished creation snapshots
   */
  public PendingIslandRecoveryException(List<IslandAggregateSnapshot> pending) {
    super(
        "Unfinished island creations require recovery before gameplay: "
            + pending.stream().map(snapshot -> snapshot.islandId().toString()).toList());
    this.pending = List.copyOf(pending);
    if (pending.isEmpty()) {
      throw new IllegalArgumentException("pending must not be empty");
    }
  }

  /**
   * Returns unfinished durable snapshots for diagnostics and the future repair engine.
   *
   * @return immutable pending snapshots
   */
  public List<IslandAggregateSnapshot> pending() {
    return pending;
  }
}
