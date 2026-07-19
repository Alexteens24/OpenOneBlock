package dev.openoneblock.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.id.NamespacedId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtectionPolicyRegistryTest {
  private static final Instant NOW = Instant.parse("2026-07-19T07:00:00Z");
  private static final ProtectionPolicy PASS = (query, island) -> ProtectionDecision.pass();

  @Test
  void ordersHigherPriorityFirstAndBreaksTiesByNamespacedIdentity() {
    ProtectionPolicyRegistry registry = new ProtectionPolicyRegistry(4);
    registry.register(id("z-last"), 10, PASS);
    registry.register(id("b-second"), 20, PASS);
    registry.register(id("a-first"), 20, PASS);

    assertEquals(
        List.of(id("a-first"), id("b-second"), id("z-last")),
        registry.activeAt(NOW).stream().map(RegisteredProtectionPolicy::policyId).toList());
  }

  @Test
  void temporaryPolicyExpiresLazilyWithoutSchedulerTask() {
    ProtectionPolicyRegistry registry = new ProtectionPolicyRegistry(2);
    registry.registerTemporary(id("temporary"), 0, NOW.plusSeconds(30), PASS);

    assertEquals(1, registry.activeAt(NOW).size());
    assertTrue(registry.activeAt(NOW.plusSeconds(30)).isEmpty());
    assertEquals(0, registry.size());
    assertFalse(registry.remove(id("temporary")));
  }

  @Test
  void capacityIsBoundedButExistingIdentityCanBeAtomicallyReplaced() {
    ProtectionPolicyRegistry registry = new ProtectionPolicyRegistry(1);
    registry.register(id("one"), 0, PASS);
    registry.register(id("one"), 100, PASS);

    assertEquals(100, registry.activeAt(NOW).getFirst().priority());
    assertThrows(IllegalStateException.class, () -> registry.register(id("two"), 0, PASS));
  }

  private static NamespacedId id(String value) {
    return NamespacedId.of("test", value);
  }
}
