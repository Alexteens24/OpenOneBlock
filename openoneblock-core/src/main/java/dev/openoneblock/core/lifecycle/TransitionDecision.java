package dev.openoneblock.core.lifecycle;

/** Result of evaluating a persisted lifecycle transition. */
public enum TransitionDecision {
  /** The transition is a permitted graph edge. */
  ALLOWED,
  /** Current and target state are identical. */
  SAME_STATE,
  /** No permitted graph edge connects current and target state. */
  ILLEGAL_EDGE
}
