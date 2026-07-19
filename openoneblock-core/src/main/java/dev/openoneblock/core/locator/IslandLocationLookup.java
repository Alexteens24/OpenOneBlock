package dev.openoneblock.core.locator;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.WorldId;
import java.util.Objects;

/** Fail-closed result of resolving a block location through immutable runtime projections. */
public sealed interface IslandLocationLookup
    permits IslandLocationLookup.UnmanagedWorld,
        IslandLocationLookup.OutsideManagedRange,
        IslandLocationLookup.EmptyCell,
        IslandLocationLookup.Resolved,
        IslandLocationLookup.Conflicted {
  /**
   * The UUID does not identify a configured OpenOneBlock world.
   *
   * @param worldId queried world UUID
   */
  record UnmanagedWorld(WorldId worldId) implements IslandLocationLookup {
    /** Validates the diagnostic world identity. */
    public UnmanagedWorld {
      Objects.requireNonNull(worldId, "worldId");
    }
  }

  /**
   * Coordinates fall outside the configured safe world range and must fail closed.
   *
   * @param projection managed world projection
   * @param blockX queried block X
   * @param blockZ queried block Z
   */
  record OutsideManagedRange(WorldProjection projection, int blockX, int blockZ)
      implements IslandLocationLookup {
    /** Validates projection metadata. */
    public OutsideManagedRange {
      Objects.requireNonNull(projection, "projection");
    }
  }

  /**
   * A managed world cell currently has no non-free slot.
   *
   * @param projection managed world projection
   * @param gridPosition arithmetically resolved cell
   */
  record EmptyCell(WorldProjection projection, GridPosition gridPosition)
      implements IslandLocationLookup {
    /** Validates lookup metadata. */
    public EmptyCell {
      Objects.requireNonNull(projection, "projection");
      Objects.requireNonNull(gridPosition, "gridPosition");
    }
  }

  /**
   * One unambiguous committed slot owns the location.
   *
   * @param projection managed world projection
   * @param entry committed slot projection
   */
  record Resolved(WorldProjection projection, SlotLocatorEntry entry)
      implements IslandLocationLookup {
    /** Validates lookup metadata. */
    public Resolved {
      Objects.requireNonNull(projection, "projection");
      Objects.requireNonNull(entry, "entry");
    }
  }

  /**
   * Contradictory committed ownership was observed for the resolved cell.
   *
   * @param projection managed world projection
   * @param conflict fail-closed locator conflict
   */
  record Conflicted(WorldProjection projection, SlotLocatorLookup.Conflicted conflict)
      implements IslandLocationLookup {
    /** Validates diagnostic metadata. */
    public Conflicted {
      Objects.requireNonNull(projection, "projection");
      Objects.requireNonNull(conflict, "conflict");
    }
  }
}
