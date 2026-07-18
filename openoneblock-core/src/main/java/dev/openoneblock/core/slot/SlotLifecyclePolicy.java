package dev.openoneblock.core.slot;

import dev.openoneblock.core.lifecycle.TransitionDecision;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Central transition policy for safe slot reuse. */
public final class SlotLifecyclePolicy {
  private static final Map<SlotState, Set<SlotState>> TRANSITIONS = transitions();

  private SlotLifecyclePolicy() {}

  /**
   * Returns the decision for one proposed slot lifecycle transition.
   *
   * @param current current persisted state
   * @param target requested target state
   * @return transition decision
   */
  public static TransitionDecision evaluate(SlotState current, SlotState target) {
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(target, "target");
    if (current == target) {
      return TransitionDecision.SAME_STATE;
    }
    return TRANSITIONS.get(current).contains(target)
        ? TransitionDecision.ALLOWED
        : TransitionDecision.ILLEGAL_EDGE;
  }

  private static Map<SlotState, Set<SlotState>> transitions() {
    EnumMap<SlotState, Set<SlotState>> transitions = new EnumMap<>(SlotState.class);
    transitions.put(SlotState.FREE, EnumSet.of(SlotState.RESERVED));
    transitions.put(SlotState.RESERVED, EnumSet.of(SlotState.PREPARING, SlotState.FREE));
    transitions.put(
        SlotState.PREPARING,
        EnumSet.of(SlotState.ACTIVE, SlotState.CLEANING, SlotState.QUARANTINED));
    transitions.put(SlotState.ACTIVE, EnumSet.of(SlotState.PREPARING, SlotState.CLEANING));
    transitions.put(SlotState.CLEANING, EnumSet.of(SlotState.FREE, SlotState.QUARANTINED));
    transitions.put(SlotState.QUARANTINED, EnumSet.of(SlotState.CLEANING, SlotState.ACTIVE));
    return Map.copyOf(transitions);
  }
}
