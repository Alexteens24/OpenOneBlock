package dev.openoneblock.core.lifecycle;

import dev.openoneblock.api.island.IslandLifecycleState;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Central transition and gameplay policy for island lifecycle states. */
public final class IslandLifecyclePolicy {
  private static final Map<IslandLifecycleState, Set<IslandLifecycleState>> TRANSITIONS =
      transitions();

  private IslandLifecyclePolicy() {}

  /**
   * Returns the decision for one proposed island lifecycle transition.
   *
   * @param current current persisted state
   * @param target requested target state
   * @return transition decision
   */
  public static TransitionDecision evaluate(
      IslandLifecycleState current, IslandLifecycleState target) {
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(target, "target");
    if (current == target) {
      return TransitionDecision.SAME_STATE;
    }
    return TRANSITIONS.get(current).contains(target)
        ? TransitionDecision.ALLOWED
        : TransitionDecision.ILLEGAL_EDGE;
  }

  /**
   * Returns whether normal gameplay is allowed to mutate an island in this state.
   *
   * @param state current island state
   * @return {@code true} only for {@code ACTIVE}
   */
  public static boolean allowsGameplayMutation(IslandLifecycleState state) {
    return Objects.requireNonNull(state, "state") == IslandLifecycleState.ACTIVE;
  }

  private static Map<IslandLifecycleState, Set<IslandLifecycleState>> transitions() {
    EnumMap<IslandLifecycleState, Set<IslandLifecycleState>> transitions =
        new EnumMap<>(IslandLifecycleState.class);
    transitions.put(
        IslandLifecycleState.ALLOCATING,
        EnumSet.of(IslandLifecycleState.CREATING, IslandLifecycleState.ARCHIVED));
    transitions.put(
        IslandLifecycleState.CREATING,
        EnumSet.of(IslandLifecycleState.ACTIVE, IslandLifecycleState.BROKEN));
    transitions.put(
        IslandLifecycleState.ACTIVE,
        EnumSet.of(
            IslandLifecycleState.LOCKED,
            IslandLifecycleState.RESETTING,
            IslandLifecycleState.MIGRATING,
            IslandLifecycleState.DELETING,
            IslandLifecycleState.BROKEN));
    transitions.put(
        IslandLifecycleState.LOCKED,
        EnumSet.of(
            IslandLifecycleState.ACTIVE,
            IslandLifecycleState.RESETTING,
            IslandLifecycleState.MIGRATING,
            IslandLifecycleState.DELETING,
            IslandLifecycleState.BROKEN));
    transitions.put(
        IslandLifecycleState.RESETTING,
        EnumSet.of(IslandLifecycleState.ACTIVE, IslandLifecycleState.BROKEN));
    transitions.put(
        IslandLifecycleState.MIGRATING,
        EnumSet.of(IslandLifecycleState.ACTIVE, IslandLifecycleState.BROKEN));
    transitions.put(
        IslandLifecycleState.DELETING,
        EnumSet.of(IslandLifecycleState.ARCHIVED, IslandLifecycleState.BROKEN));
    transitions.put(
        IslandLifecycleState.BROKEN,
        EnumSet.of(
            IslandLifecycleState.LOCKED,
            IslandLifecycleState.RESETTING,
            IslandLifecycleState.DELETING));
    transitions.put(IslandLifecycleState.ARCHIVED, EnumSet.of(IslandLifecycleState.ALLOCATING));
    return Map.copyOf(transitions);
  }
}
