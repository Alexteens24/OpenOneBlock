package dev.openoneblock.core.locator;

/** Outcome of removing one database-committed slot ownership from the runtime locator. */
public enum LocatorRemovalDecision {
  /** The matching ownership projection was removed. */
  APPLIED,
  /** No runtime projection existed, so authoritative absence was already represented. */
  ALREADY_ABSENT,
  /** A newer or contradictory projection prevented safe removal. */
  CONFLICTED,
  /** The publisher cannot represent committed removal and requires reconciliation. */
  UNSUPPORTED
}
