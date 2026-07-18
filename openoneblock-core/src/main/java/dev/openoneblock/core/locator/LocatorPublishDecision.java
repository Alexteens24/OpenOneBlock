package dev.openoneblock.core.locator;

/** Outcome of publishing one committed locator projection. */
public enum LocatorPublishDecision {
  /** The entry was inserted or advanced to a newer committed version. */
  APPLIED,
  /** The entry was already known or older than the current projection. */
  STALE_IGNORED,
  /** Contradictory ownership or same-version state was detected and failed closed. */
  CONFLICTED
}
