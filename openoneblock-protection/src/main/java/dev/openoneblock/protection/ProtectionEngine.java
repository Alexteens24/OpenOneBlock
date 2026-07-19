package dev.openoneblock.protection;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.grid.HorizontalBounds;
import dev.openoneblock.core.locator.IslandLocationLookup;
import dev.openoneblock.core.locator.IslandLocationResolver;
import dev.openoneblock.core.locator.SlotLocatorEntry;
import dev.openoneblock.core.slot.SlotState;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Fail-closed native island protection pipeline with no database or chunk access. */
public final class ProtectionEngine {
  private final IslandLocationResolver locations;
  private final java.util.function.Function<dev.openoneblock.api.id.ShardGroupId, GridGeometry>
      geometryByShard;
  private final InMemoryIslandProtectionIndex islands;
  private final RolePermissionRegistry roles;
  private final ProtectionPolicyRegistry policies;
  private final Clock clock;

  /**
   * Creates the immutable protection pipeline.
   *
   * @param locations constant-time location resolver
   * @param geometryByShard immutable grid geometry lookup
   * @param islands hot-path island projection index
   * @param roles compiled role permissions
   * @param policies deterministic additional policy chain
   */
  public ProtectionEngine(
      IslandLocationResolver locations,
      java.util.function.Function<dev.openoneblock.api.id.ShardGroupId, GridGeometry>
          geometryByShard,
      InMemoryIslandProtectionIndex islands,
      RolePermissionRegistry roles,
      List<ProtectionPolicy> policies) {
    this(locations, geometryByShard, islands, roles, legacyRegistry(policies), Clock.systemUTC());
  }

  /**
   * Creates a pipeline backed by a bounded dynamically registered policy set.
   *
   * @param locations constant-time location resolver
   * @param geometryByShard immutable grid geometry lookup
   * @param islands hot-path island projection index
   * @param roles compiled role permissions
   * @param policies bounded deterministic policy registry
   * @param clock expiry clock
   */
  public ProtectionEngine(
      IslandLocationResolver locations,
      java.util.function.Function<dev.openoneblock.api.id.ShardGroupId, GridGeometry>
          geometryByShard,
      InMemoryIslandProtectionIndex islands,
      RolePermissionRegistry roles,
      ProtectionPolicyRegistry policies,
      Clock clock) {
    this.locations = Objects.requireNonNull(locations, "locations");
    this.geometryByShard = Objects.requireNonNull(geometryByShard, "geometryByShard");
    this.islands = Objects.requireNonNull(islands, "islands");
    this.roles = Objects.requireNonNull(roles, "roles");
    this.policies = Objects.requireNonNull(policies, "policies");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Evaluates one query using only immutable and in-memory projections.
   *
   * @param query immutable attempted interaction
   * @return tri-state decision with stable reason
   */
  public ProtectionDecision evaluate(ProtectionQuery query) {
    Objects.requireNonNull(query, "query");
    List<LocatedPosition> located = new ArrayList<>(2);
    query.source().ifPresent(position -> located.add(locate(position)));
    query.destination().ifPresent(position -> located.add(locate(position)));
    boolean anyManaged =
        located.stream()
            .anyMatch(
                position -> !(position.lookup() instanceof IslandLocationLookup.UnmanagedWorld));
    if (!anyManaged) {
      return ProtectionDecision.pass();
    }
    List<ResolvedPosition> resolved = new ArrayList<>(located.size());
    for (LocatedPosition position : located) {
      ProtectionDecision failure =
          switch (position.lookup()) {
            case IslandLocationLookup.UnmanagedWorld ignored ->
                ProtectionDecision.deny("cross-island-operation");
            case IslandLocationLookup.OutsideManagedRange ignored ->
                ProtectionDecision.deny("outside-managed-range");
            case IslandLocationLookup.EmptyCell ignored ->
                ProtectionDecision.deny("outside-island");
            case IslandLocationLookup.Conflicted ignored ->
                ProtectionDecision.deny("slot-ownership-conflict");
            case IslandLocationLookup.Resolved value -> {
              resolved.add(new ResolvedPosition(position.position(), value.entry()));
              yield null;
            }
          };
      if (failure != null) {
        return failure;
      }
    }

    ResolvedPosition first = resolved.getFirst();
    IslandId islandId = first.entry().islandId();
    if (resolved.stream().anyMatch(position -> !position.entry().islandId().equals(islandId))) {
      return ProtectionDecision.deny("cross-island-operation");
    }
    IslandProtectionSnapshot island = islands.find(islandId).orElse(null);
    if (island == null) {
      return ProtectionDecision.deny("island-projection-missing");
    }
    if (island.lifecycleState() != IslandLifecycleState.ACTIVE
        || resolved.stream()
            .anyMatch(position -> position.entry().slotState() != SlotState.ACTIVE)) {
      return ProtectionDecision.deny("island-locked");
    }
    if (!island.shardGroupId().equals(first.entry().shardGroupId())
        || !island.gridPosition().equals(first.entry().gridPosition())) {
      return ProtectionDecision.deny("island-projection-conflict");
    }
    GridGeometry geometry =
        Objects.requireNonNull(
            geometryByShard.apply(island.shardGroupId()), "missing island grid geometry");
    HorizontalBounds border;
    try {
      border = geometry.border(island.gridPosition(), island.currentBorderSize());
    } catch (IllegalArgumentException exception) {
      return ProtectionDecision.deny("invalid-current-border");
    }
    if (resolved.stream()
        .anyMatch(
            position ->
                !border.contains(position.position().blockX(), position.position().blockZ()))) {
      return ProtectionDecision.deny("outside-current-border");
    }

    boolean magicBlock =
        resolved.stream().map(ResolvedPosition::position).anyMatch(island.magicBlocks()::contains);
    if (magicBlock && query.action() != ProtectionAction.MAGIC_BLOCK_BREAK) {
      if (query.actor().administrator()) {
        return ProtectionDecision.allow();
      }
      return ProtectionDecision.deny("magic-block-protected");
    }

    if (query.actor().playerId().isPresent()) {
      PlayerId playerId = query.actor().playerId().orElseThrow();
      NamespacedId role = island.activeMemberships().getOrDefault(playerId, roles.visitorRole());
      if (!roles.allows(role, query.action()) && !query.actor().administrator()) {
        return ProtectionDecision.deny("role-permission-denied");
      }
    }

    if (policies.size() > 0) {
      for (RegisteredProtectionPolicy registration : policies.activeAt(clock.instant())) {
        ProtectionDecision policyDecision =
            Objects.requireNonNull(
                registration.policy().evaluate(query, island),
                "protection policy returned null: " + registration.policyId());
        if (policyDecision.outcome() == ProtectionOutcome.DENY && !query.actor().administrator()) {
          return policyDecision;
        }
      }
    }
    return ProtectionDecision.allow();
  }

  /**
   * Returns the bounded registry used for explicit native and temporary policy registration.
   *
   * @return live copy-on-write policy registry
   */
  public ProtectionPolicyRegistry policyRegistry() {
    return policies;
  }

  private LocatedPosition locate(ProtectionPosition position) {
    return new LocatedPosition(
        position, locations.lookup(position.worldId(), position.blockX(), position.blockZ()));
  }

  private static ProtectionPolicyRegistry legacyRegistry(List<ProtectionPolicy> policies) {
    Objects.requireNonNull(policies, "policies");
    ProtectionPolicyRegistry registry = new ProtectionPolicyRegistry(Math.max(1, policies.size()));
    for (int index = 0; index < policies.size(); index++) {
      registry.register(
          NamespacedId.of("openoneblock", "constructor-policy/" + index),
          -index,
          Objects.requireNonNull(policies.get(index), "policy"));
    }
    return registry;
  }

  private record LocatedPosition(ProtectionPosition position, IslandLocationLookup lookup) {}

  private record ResolvedPosition(ProtectionPosition position, SlotLocatorEntry entry) {}
}
