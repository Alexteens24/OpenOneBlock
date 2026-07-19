package dev.openoneblock.core.team;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.island.IslandPermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IslandRoleRegistryTest {
  @Test
  void grantsCompiledPermissionAndFailsClosedForUnknownRole() {
    IslandRoleRegistry registry =
        new IslandRoleRegistry(
            List.of(
                role("owner", Set.of(), true),
                role("visitor", Set.of(), false),
                role("banned", Set.of(), false),
                role("member", Set.of(IslandPermission.BLOCK_BREAK), false)));

    assertTrue(registry.allows(id("owner"), IslandPermission.RESET_ISLAND));
    assertTrue(registry.allows(id("member"), IslandPermission.BLOCK_BREAK));
    assertFalse(registry.allows(id("member"), IslandPermission.KICK_MEMBER));
    assertFalse(registry.allows(id("missing"), IslandPermission.BLOCK_BREAK));
    assertTrue(registry.canManage(id("owner"), id("member")));
    assertFalse(registry.canManage(id("member"), id("owner")));
  }

  @Test
  void requiresOwnerVisitorAndBannedSafetyRoles() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IslandRoleRegistry(
                List.of(role("owner", Set.of(), true), role("visitor", Set.of(), false))));
  }

  private static IslandRoleDefinition role(
      String id, Set<IslandPermission> permissions, boolean wildcard) {
    int authority = id.equals("owner") ? 1000 : id.equals("member") ? 400 : 0;
    return new IslandRoleDefinition(id(id), permissions, wildcard, authority);
  }

  private static NamespacedId id(String value) {
    return NamespacedId.of("openoneblock", value);
  }
}
