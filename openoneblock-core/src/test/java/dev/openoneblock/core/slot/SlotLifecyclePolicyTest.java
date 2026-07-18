package dev.openoneblock.core.slot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.openoneblock.core.lifecycle.TransitionDecision;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SlotLifecyclePolicyTest {
  private static final Set<Edge> ALLOWED_EDGES =
      Set.of(
          edge("FREE", "RESERVED"),
          edge("RESERVED", "PREPARING"),
          edge("RESERVED", "FREE"),
          edge("PREPARING", "ACTIVE"),
          edge("PREPARING", "CLEANING"),
          edge("PREPARING", "QUARANTINED"),
          edge("ACTIVE", "PREPARING"),
          edge("ACTIVE", "CLEANING"),
          edge("CLEANING", "FREE"),
          edge("CLEANING", "QUARANTINED"),
          edge("QUARANTINED", "CLEANING"),
          edge("QUARANTINED", "ACTIVE"));

  @Test
  void evaluatesEveryStatePairAgainstTheAcceptedLifecycleGraph() {
    for (SlotState current : SlotState.values()) {
      for (SlotState target : SlotState.values()) {
        TransitionDecision expected =
            current == target
                ? TransitionDecision.SAME_STATE
                : ALLOWED_EDGES.contains(new Edge(current, target))
                    ? TransitionDecision.ALLOWED
                    : TransitionDecision.ILLEGAL_EDGE;

        assertEquals(expected, SlotLifecyclePolicy.evaluate(current, target));
      }
    }
  }

  private static Edge edge(String current, String target) {
    return new Edge(SlotState.valueOf(current), SlotState.valueOf(target));
  }

  private record Edge(SlotState current, SlotState target) {}
}
