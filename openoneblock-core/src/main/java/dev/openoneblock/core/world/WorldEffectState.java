package dev.openoneblock.core.world;

/** Durable evidence state for one planned world effect. */
public enum WorldEffectState {
  /** Receipt exists, proving intent was recorded before platform dispatch. */
  NOT_STARTED,
  /** Dispatch began but no verified outcome has been durably recorded. */
  DISPATCHED,
  /** Target state was explicitly verified. */
  VERIFIED_SUCCESS,
  /** Non-application or a failed target was explicitly verified. */
  VERIFIED_FAILURE,
  /** Available evidence cannot prove whether the effect happened. */
  AMBIGUOUS;

  /**
   * Returns whether no automatic state transition may follow.
   *
   * @return whether this state is terminal
   */
  public boolean terminal() {
    return this == VERIFIED_SUCCESS || this == VERIFIED_FAILURE || this == AMBIGUOUS;
  }
}
