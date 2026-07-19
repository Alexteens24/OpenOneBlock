package dev.openoneblock.core.team;

import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.island.IslandPermission;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable compiled island role.
 *
 * @param roleId stable namespaced identity
 * @param effectivePermissions complete inherited permission set
 * @param wildcard whether this role grants every registered permission
 */
public record IslandRoleDefinition(
    NamespacedId roleId, Set<IslandPermission> effectivePermissions, boolean wildcard) {
  /** Validates and defensively copies role data. */
  public IslandRoleDefinition {
    Objects.requireNonNull(roleId, "roleId");
    effectivePermissions = Set.copyOf(effectivePermissions);
  }

  /**
   * Tests one permission.
   *
   * @param permission requested permission
   * @return whether this compiled role grants it
   */
  public boolean allows(IslandPermission permission) {
    Objects.requireNonNull(permission, "permission");
    return wildcard || effectivePermissions.contains(permission);
  }
}
