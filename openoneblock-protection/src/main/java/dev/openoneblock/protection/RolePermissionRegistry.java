package dev.openoneblock.protection;

import dev.openoneblock.api.id.NamespacedId;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable compiled mapping from configured roles to native protection actions. */
public final class RolePermissionRegistry {
  private final Map<NamespacedId, Set<ProtectionAction>> permissions;
  private final Set<NamespacedId> wildcardRoles;
  private final NamespacedId visitorRole;

  /**
   * Creates a validated role permission registry.
   *
   * @param permissions role-to-action mapping
   * @param wildcardRoles roles granting every action
   * @param visitorRole fallback role for non-members
   */
  public RolePermissionRegistry(
      Map<NamespacedId, Set<ProtectionAction>> permissions,
      Set<NamespacedId> wildcardRoles,
      NamespacedId visitorRole) {
    Objects.requireNonNull(permissions, "permissions");
    this.permissions =
        permissions.entrySet().stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    this.wildcardRoles = Set.copyOf(wildcardRoles);
    this.visitorRole = Objects.requireNonNull(visitorRole, "visitorRole");
  }

  /**
   * Returns the configured visitor role used for non-members.
   *
   * @return visitor role ID
   */
  public NamespacedId visitorRole() {
    return visitorRole;
  }

  /**
   * Tests whether a role grants an action.
   *
   * @param role role identity
   * @param action attempted action
   * @return whether the role grants the action
   */
  public boolean allows(NamespacedId role, ProtectionAction action) {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(action, "action");
    return wildcardRoles.contains(role)
        || permissions.getOrDefault(role, Set.of()).contains(action)
        || (action == ProtectionAction.MAGIC_BLOCK_BREAK
            && permissions.getOrDefault(role, Set.of()).contains(ProtectionAction.BLOCK_BREAK));
  }
}
