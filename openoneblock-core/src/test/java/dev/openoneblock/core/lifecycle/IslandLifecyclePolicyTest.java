package dev.openoneblock.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.openoneblock.api.island.IslandLifecycleState;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IslandLifecyclePolicyTest {
  private static final Set<Edge> ALLOWED_EDGES =
      Set.of(
          edge("ALLOCATING", "CREATING"),
          edge("ALLOCATING", "ARCHIVED"),
          edge("CREATING", "ACTIVE"),
          edge("CREATING", "BROKEN"),
          edge("ACTIVE", "LOCKED"),
          edge("ACTIVE", "RESETTING"),
          edge("ACTIVE", "MIGRATING"),
          edge("ACTIVE", "DELETING"),
          edge("ACTIVE", "BROKEN"),
          edge("LOCKED", "ACTIVE"),
          edge("LOCKED", "RESETTING"),
          edge("LOCKED", "MIGRATING"),
          edge("LOCKED", "DELETING"),
          edge("LOCKED", "BROKEN"),
          edge("RESETTING", "ACTIVE"),
          edge("RESETTING", "BROKEN"),
          edge("MIGRATING", "ACTIVE"),
          edge("MIGRATING", "BROKEN"),
          edge("DELETING", "ARCHIVED"),
          edge("DELETING", "BROKEN"),
          edge("BROKEN", "LOCKED"),
          edge("BROKEN", "RESETTING"),
          edge("BROKEN", "DELETING"),
          edge("BROKEN", "ARCHIVED"),
          edge("ARCHIVED", "ALLOCATING"));

  @Test
  void evaluatesEveryStatePairAgainstTheAcceptedLifecycleGraph() {
    for (IslandLifecycleState current : IslandLifecycleState.values()) {
      for (IslandLifecycleState target : IslandLifecycleState.values()) {
        TransitionDecision expected =
            current == target
                ? TransitionDecision.SAME_STATE
                : ALLOWED_EDGES.contains(new Edge(current, target))
                    ? TransitionDecision.ALLOWED
                    : TransitionDecision.ILLEGAL_EDGE;

        assertEquals(expected, IslandLifecyclePolicy.evaluate(current, target));
      }
    }
  }

  @Test
  void onlyActiveAllowsGameplayMutation() {
    for (IslandLifecycleState state : IslandLifecycleState.values()) {
      assertEquals(
          state == IslandLifecycleState.ACTIVE,
          IslandLifecyclePolicy.allowsGameplayMutation(state));
    }
  }

  private static Edge edge(String current, String target) {
    return new Edge(IslandLifecycleState.valueOf(current), IslandLifecycleState.valueOf(target));
  }

  private record Edge(IslandLifecycleState current, IslandLifecycleState target) {}
}
