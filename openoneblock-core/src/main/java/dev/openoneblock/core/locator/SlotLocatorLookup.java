package dev.openoneblock.core.locator;

import java.util.Objects;

/** Fail-closed result of an O(1) slot locator query. */
public sealed interface SlotLocatorLookup
    permits SlotLocatorLookup.Empty, SlotLocatorLookup.Resolved, SlotLocatorLookup.Conflicted {
  /** No committed non-free slot is projected for the cell. */
  record Empty() implements SlotLocatorLookup {}

  /**
   * One unambiguous committed slot owns the cell.
   *
   * @param entry resolved locator entry
   */
  record Resolved(SlotLocatorEntry entry) implements SlotLocatorLookup {
    /** Validates the resolved entry. */
    public Resolved {
      Objects.requireNonNull(entry, "entry");
    }
  }

  /**
   * Contradictory committed projections were observed; gameplay must fail closed.
   *
   * @param existing first observed projection
   * @param conflicting contradictory projection
   */
  record Conflicted(SlotLocatorEntry existing, SlotLocatorEntry conflicting)
      implements SlotLocatorLookup {
    /** Validates both diagnostic projections. */
    public Conflicted {
      Objects.requireNonNull(existing, "existing");
      Objects.requireNonNull(conflicting, "conflicting");
    }
  }
}
