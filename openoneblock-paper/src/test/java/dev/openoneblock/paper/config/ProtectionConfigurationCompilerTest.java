package dev.openoneblock.paper.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.island.IslandPermission;
import dev.openoneblock.protection.ProtectionAction;
import dev.openoneblock.protection.RolePermissionRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProtectionConfigurationCompilerTest {
  @Test
  void compilesInheritanceWildcardAndPermissionAliases() {
    Map<String, FoundationConfigurationSnapshot.RoleSettings> roles =
        Map.of(
            "owner",
            new FoundationConfigurationSnapshot.RoleSettings(1000, List.of(), Set.of("*")),
            "member",
            role(List.of(), "BLOCK_BREAK", "USE_BUCKET"),
            "trusted",
            role(List.of("member"), "REDSTONE_USE"),
            "visitor",
            role(List.of()),
            "banned",
            role(List.of()),
            "manager",
            role(List.of(), "INVITE_MEMBER"));

    RolePermissionRegistry compiled = new ProtectionConfigurationCompiler().compile(roles);

    assertTrue(compiled.allows(id("owner"), ProtectionAction.FIRE_SPREAD));
    assertTrue(compiled.allows(id("member"), ProtectionAction.BUCKET_FILL));
    assertTrue(compiled.allows(id("member"), ProtectionAction.BUCKET_EMPTY));
    assertTrue(compiled.allows(id("trusted"), ProtectionAction.BLOCK_BREAK));
    assertTrue(compiled.allows(id("trusted"), ProtectionAction.REDSTONE_USE));
    assertFalse(compiled.allows(id("visitor"), ProtectionAction.BLOCK_BREAK));
    assertFalse(compiled.allows(id("manager"), ProtectionAction.BLOCK_BREAK));

    dev.openoneblock.core.team.IslandRoleRegistry islandRoles =
        new ProtectionConfigurationCompiler().compileIslandRoles(roles);
    assertTrue(islandRoles.allows(id("trusted"), IslandPermission.BLOCK_BREAK));
    assertTrue(islandRoles.allows(id("manager"), IslandPermission.INVITE_MEMBER));
    assertFalse(islandRoles.allows(id("visitor"), IslandPermission.BLOCK_BREAK));
  }

  @Test
  void requiresExplicitVisitorFallback() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProtectionConfigurationCompiler()
                .compile(
                    Map.of(
                        "owner",
                        new FoundationConfigurationSnapshot.RoleSettings(
                            1000, List.of(), Set.of("*")))));
  }

  private static FoundationConfigurationSnapshot.RoleSettings role(
      List<String> inherits, String... permissions) {
    return new FoundationConfigurationSnapshot.RoleSettings(100, inherits, Set.of(permissions));
  }

  private static NamespacedId id(String role) {
    return NamespacedId.of("openoneblock", role);
  }
}
