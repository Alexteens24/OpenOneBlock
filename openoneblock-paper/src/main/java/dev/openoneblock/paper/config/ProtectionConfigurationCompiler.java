package dev.openoneblock.paper.config;

import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.island.IslandPermission;
import dev.openoneblock.core.team.IslandRoleDefinition;
import dev.openoneblock.core.team.IslandRoleRegistry;
import dev.openoneblock.protection.ProtectionAction;
import dev.openoneblock.protection.RolePermissionRegistry;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Compiles validated role inheritance into constant-time native action permissions. */
public final class ProtectionConfigurationCompiler {
  /** Creates a stateless compiler. */
  public ProtectionConfigurationCompiler() {}

  /**
   * Compiles role settings and requires an explicit visitor role.
   *
   * @param configuredRoles validated role inheritance graph
   * @return constant-time compiled permission registry
   */
  public RolePermissionRegistry compile(
      Map<String, FoundationConfigurationSnapshot.RoleSettings> configuredRoles) {
    Objects.requireNonNull(configuredRoles, "configuredRoles");
    if (!configuredRoles.containsKey("visitor")) {
      throw new IllegalArgumentException("roles.yml must define the visitor role");
    }
    Map<NamespacedId, Set<ProtectionAction>> compiled = new HashMap<>();
    Set<NamespacedId> wildcards = new HashSet<>();
    for (String role : configuredRoles.keySet()) {
      AccumulatedRole accumulated = accumulate(role, configuredRoles, new HashMap<>());
      NamespacedId roleId = NamespacedId.of("openoneblock", role);
      compiled.put(roleId, accumulated.actions());
      if (accumulated.wildcard()) {
        wildcards.add(roleId);
      }
    }
    return new RolePermissionRegistry(
        compiled, wildcards, NamespacedId.of("openoneblock", "visitor"));
  }

  /**
   * Compiles the same validated inheritance graph for application-service authorization.
   *
   * @param configuredRoles validated role inheritance graph
   * @return immutable domain role registry
   */
  public IslandRoleRegistry compileIslandRoles(
      Map<String, FoundationConfigurationSnapshot.RoleSettings> configuredRoles) {
    Objects.requireNonNull(configuredRoles, "configuredRoles");
    java.util.List<IslandRoleDefinition> definitions = new ArrayList<>();
    Map<String, AccumulatedRole> memo = new HashMap<>();
    for (String role : configuredRoles.keySet()) {
      AccumulatedRole accumulated = accumulate(role, configuredRoles, memo);
      definitions.add(
          new IslandRoleDefinition(
              NamespacedId.of("openoneblock", role),
              accumulated.permissions(),
              accumulated.wildcard(),
              configuredRoles.get(role).authority()));
    }
    return new IslandRoleRegistry(definitions);
  }

  private static AccumulatedRole accumulate(
      String role,
      Map<String, FoundationConfigurationSnapshot.RoleSettings> configured,
      Map<String, AccumulatedRole> memo) {
    AccumulatedRole existing = memo.get(role);
    if (existing != null) {
      return existing;
    }
    FoundationConfigurationSnapshot.RoleSettings settings =
        Objects.requireNonNull(configured.get(role), "unknown inherited role " + role);
    EnumSet<ProtectionAction> actions = EnumSet.noneOf(ProtectionAction.class);
    EnumSet<IslandPermission> permissions = EnumSet.noneOf(IslandPermission.class);
    boolean wildcard = false;
    for (String inherited : settings.inherits()) {
      AccumulatedRole parent = accumulate(inherited, configured, memo);
      actions.addAll(parent.actions());
      permissions.addAll(parent.permissions());
      wildcard |= parent.wildcard();
    }
    for (String permission : settings.permissions()) {
      if (permission.equals("*")) {
        wildcard = true;
        actions.addAll(EnumSet.allOf(ProtectionAction.class));
        permissions.addAll(EnumSet.allOf(IslandPermission.class));
      } else if (permission.equals("USE_BUCKET")) {
        permissions.add(IslandPermission.USE_BUCKET);
        actions.add(ProtectionAction.BUCKET_FILL);
        actions.add(ProtectionAction.BUCKET_EMPTY);
      } else if (permission.equals("DAMAGE_ANIMALS") || permission.equals("DAMAGE_MONSTERS")) {
        permissions.add(IslandPermission.valueOf(permission));
        actions.add(ProtectionAction.ENTITY_DAMAGE);
      } else {
        try {
          permissions.add(IslandPermission.valueOf(permission));
        } catch (IllegalArgumentException ignored) {
          // Protection-only actions remain outside application-service authorization.
        }
        try {
          actions.add(ProtectionAction.valueOf(permission));
        } catch (IllegalArgumentException ignored) {
          // Domain permissions such as member management are enforced by their application service.
        }
      }
    }
    AccumulatedRole result =
        new AccumulatedRole(Set.copyOf(actions), Set.copyOf(permissions), wildcard);
    memo.put(role, result);
    return result;
  }

  private record AccumulatedRole(
      Set<ProtectionAction> actions, Set<IslandPermission> permissions, boolean wildcard) {}
}
