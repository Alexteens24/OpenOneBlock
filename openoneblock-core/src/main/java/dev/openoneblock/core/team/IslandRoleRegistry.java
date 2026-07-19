package dev.openoneblock.core.team;

import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.island.IslandPermission;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable role registry used by application services instead of hard-coded role checks. */
public final class IslandRoleRegistry {
  private static final NamespacedId OWNER = NamespacedId.of("openoneblock", "owner");
  private static final NamespacedId VISITOR = NamespacedId.of("openoneblock", "visitor");
  private static final NamespacedId BANNED = NamespacedId.of("openoneblock", "banned");

  private final Map<NamespacedId, IslandRoleDefinition> roles;

  /**
   * Creates a complete registry and requires safety-critical built-in role identities.
   *
   * @param definitions compiled role definitions
   */
  public IslandRoleRegistry(Collection<IslandRoleDefinition> definitions) {
    Objects.requireNonNull(definitions, "definitions");
    Map<NamespacedId, IslandRoleDefinition> indexed = new HashMap<>();
    for (IslandRoleDefinition definition : definitions) {
      Objects.requireNonNull(definition, "definition");
      if (indexed.putIfAbsent(definition.roleId(), definition) != null) {
        throw new IllegalArgumentException("duplicate island role: " + definition.roleId());
      }
    }
    requireRole(indexed, OWNER);
    requireRole(indexed, VISITOR);
    requireRole(indexed, BANNED);
    this.roles = Map.copyOf(indexed);
  }

  /**
   * Resolves a compiled role.
   *
   * @param roleId stable role identity
   * @return role definition when configured
   */
  public Optional<IslandRoleDefinition> find(NamespacedId roleId) {
    Objects.requireNonNull(roleId, "roleId");
    return Optional.ofNullable(roles.get(roleId));
  }

  /**
   * Tests whether a configured role grants a permission.
   *
   * @param roleId role identity
   * @param permission requested permission
   * @return false for unknown roles, otherwise the compiled decision
   */
  public boolean allows(NamespacedId roleId, IslandPermission permission) {
    return find(roleId).map(role -> role.allows(permission)).orElse(false);
  }

  /**
   * Returns the immutable role count.
   *
   * @return configured roles
   */
  public int size() {
    return roles.size();
  }

  private static void requireRole(
      Map<NamespacedId, IslandRoleDefinition> roles, NamespacedId roleId) {
    if (!roles.containsKey(roleId)) {
      throw new IllegalArgumentException("required island role is missing: " + roleId);
    }
  }
}
