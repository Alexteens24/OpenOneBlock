package dev.openoneblock.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.DimensionId;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.grid.CoordinateRange;
import dev.openoneblock.core.grid.GridConfiguration;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.locator.InMemorySlotLocatorIndex;
import dev.openoneblock.core.locator.IslandLocationResolver;
import dev.openoneblock.core.locator.SlotLocatorEntry;
import dev.openoneblock.core.locator.WorldProjection;
import dev.openoneblock.core.locator.WorldProjectionRegistry;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotState;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProtectionEngineTest {
  private static final ShardGroupId SHARD = ShardGroupId.parse("openoneblock:primary");
  private static final WorldId WORLD =
      WorldId.of(UUID.fromString("9134f6bb-904d-49a4-8143-2025ff488bd3"));
  private static final WorldId UNMANAGED =
      WorldId.of(UUID.fromString("f3dad2a0-f014-4667-a8b7-944b290ba78a"));
  private static final GridGeometry GEOMETRY =
      new GridGeometry(GridConfiguration.DEFAULT, new CoordinateRange(-30_000_000, 30_000_001));
  private static final NamespacedId OWNER = NamespacedId.of("openoneblock", "owner");
  private static final NamespacedId MEMBER = NamespacedId.of("openoneblock", "member");
  private static final NamespacedId VISITOR = NamespacedId.of("openoneblock", "visitor");
  private static final NamespacedId CAUSE = NamespacedId.of("minecraft", "player");
  private static final PlayerId OWNER_PLAYER =
      PlayerId.of(UUID.fromString("43c84340-47d0-4a36-8ac0-1103be0b574e"));
  private static final PlayerId MEMBER_PLAYER =
      PlayerId.of(UUID.fromString("a63f45f1-99a6-47b9-af8e-bc8728a48620"));
  private static final PlayerId VISITOR_PLAYER =
      PlayerId.of(UUID.fromString("658092f2-dd55-4e61-a8ab-72d1ea17dd34"));

  @Test
  void unmanagedWorldPassesWithoutClaimingTheEvent() {
    Fixture fixture = fixture(IslandLifecycleState.ACTIVE, SlotState.ACTIVE, List.of());

    assertDecision(
        ProtectionOutcome.PASS,
        "unmanaged-world",
        fixture
            .engine()
            .evaluate(queryAt(VISITOR_PLAYER, ProtectionAction.BLOCK_BREAK, UNMANAGED, 0, 0, 0)));
  }

  @Test
  void activeOwnerIsAllowedInsideCurrentBorder() {
    Fixture fixture = fixture(IslandLifecycleState.ACTIVE, SlotState.ACTIVE, List.of());

    assertDecision(
        ProtectionOutcome.ALLOW,
        "managed-allow",
        fixture
            .engine()
            .evaluate(queryAt(OWNER_PLAYER, ProtectionAction.BLOCK_PLACE, WORLD, 31, 64, 31)));
  }

  @Test
  void borderIsHalfOpenAndFailsClosedOutsideCurrentSize() {
    Fixture fixture = fixture(IslandLifecycleState.ACTIVE, SlotState.ACTIVE, List.of());

    assertEquals(
        ProtectionOutcome.ALLOW,
        fixture
            .engine()
            .evaluate(queryAt(OWNER_PLAYER, ProtectionAction.BLOCK_BREAK, WORLD, -32, 0, -32))
            .outcome());
    assertDecision(
        ProtectionOutcome.DENY,
        "outside-current-border",
        fixture
            .engine()
            .evaluate(queryAt(OWNER_PLAYER, ProtectionAction.BLOCK_BREAK, WORLD, 32, 0, 0)));
  }

  @Test
  void nonActiveIslandOrSlotCannotReceiveGameplayMutation() {
    Fixture preparing = fixture(IslandLifecycleState.ACTIVE, SlotState.PREPARING, List.of());

    for (IslandLifecycleState state : IslandLifecycleState.values()) {
      if (state != IslandLifecycleState.ACTIVE && state != IslandLifecycleState.ARCHIVED) {
        Fixture unavailable = fixture(state, SlotState.ACTIVE, List.of());
        assertDecision(
            ProtectionOutcome.DENY,
            "island-locked",
            unavailable
                .engine()
                .evaluate(queryAt(OWNER_PLAYER, ProtectionAction.BLOCK_BREAK, WORLD, 0, 64, 0)));
      }
    }
    assertDecision(
        ProtectionOutcome.DENY,
        "island-locked",
        preparing
            .engine()
            .evaluate(queryAt(OWNER_PLAYER, ProtectionAction.BLOCK_BREAK, WORLD, 0, 64, 0)));
  }

  @Test
  void rolePermissionsApplyWithoutDatabaseLookup() {
    Fixture fixture = fixture(IslandLifecycleState.ACTIVE, SlotState.ACTIVE, List.of());

    assertEquals(
        ProtectionOutcome.ALLOW,
        fixture
            .engine()
            .evaluate(queryAt(MEMBER_PLAYER, ProtectionAction.BLOCK_BREAK, WORLD, 1, 64, 1))
            .outcome());
    assertDecision(
        ProtectionOutcome.DENY,
        "role-permission-denied",
        fixture
            .engine()
            .evaluate(queryAt(MEMBER_PLAYER, ProtectionAction.REDSTONE_USE, WORLD, 1, 64, 1)));
    assertDecision(
        ProtectionOutcome.DENY,
        "role-permission-denied",
        fixture
            .engine()
            .evaluate(queryAt(VISITOR_PLAYER, ProtectionAction.BLOCK_BREAK, WORLD, 1, 64, 1)));
  }

  @Test
  void sourceAndDestinationMustStayInsideTheSameIsland() {
    Fixture fixture = twoIslandFixture();
    ProtectionQuery query =
        new ProtectionQuery(
            ProtectionActor.environment(),
            ProtectionAction.PISTON_MOVE,
            Optional.of(position(WORLD, 31, 64, 0)),
            Optional.of(position(WORLD, 512, 64, 0)),
            NamespacedId.of("minecraft", "piston"),
            Map.of());

    assertDecision(
        ProtectionOutcome.DENY, "cross-island-operation", fixture.engine().evaluate(query));
  }

  @Test
  void managedToUnmanagedAndUnmanagedToManagedBothDeny() {
    Fixture fixture = fixture(IslandLifecycleState.ACTIVE, SlotState.ACTIVE, List.of());
    ProtectionQuery leaving = pair(WORLD, UNMANAGED);
    ProtectionQuery entering = pair(UNMANAGED, WORLD);

    assertDecision(
        ProtectionOutcome.DENY, "cross-island-operation", fixture.engine().evaluate(leaving));
    assertDecision(
        ProtectionOutcome.DENY, "cross-island-operation", fixture.engine().evaluate(entering));
  }

  @Test
  void magicBlockRequiresDedicatedActionAndAdministratorBypassIsExplicit() {
    Fixture fixture = fixture(IslandLifecycleState.ACTIVE, SlotState.ACTIVE, List.of());
    ProtectionPosition magic = position(WORLD, 0, 64, 0);

    assertDecision(
        ProtectionOutcome.DENY,
        "magic-block-protected",
        fixture
            .engine()
            .evaluate(queryAt(OWNER_PLAYER, ProtectionAction.BLOCK_BREAK, WORLD, 0, 64, 0)));
    assertEquals(
        ProtectionOutcome.ALLOW,
        fixture
            .engine()
            .evaluate(queryAt(OWNER_PLAYER, ProtectionAction.MAGIC_BLOCK_BREAK, WORLD, 0, 64, 0))
            .outcome());
    ProtectionQuery bypass =
        new ProtectionQuery(
            new ProtectionActor(Optional.of(VISITOR_PLAYER), true),
            ProtectionAction.BLOCK_BREAK,
            Optional.of(magic),
            Optional.empty(),
            CAUSE,
            Map.of());
    assertEquals(ProtectionOutcome.ALLOW, fixture.engine().evaluate(bypass).outcome());
  }

  @Test
  void temporaryPolicyCanDenyButAdministratorMayBypassIt() {
    ProtectionPolicy deny = (query, island) -> ProtectionDecision.deny("script-denied");
    Fixture fixture = fixture(IslandLifecycleState.ACTIVE, SlotState.ACTIVE, List.of(deny));

    assertDecision(
        ProtectionOutcome.DENY,
        "script-denied",
        fixture
            .engine()
            .evaluate(queryAt(OWNER_PLAYER, ProtectionAction.BLOCK_PLACE, WORLD, 2, 64, 2)));
    ProtectionQuery bypass =
        new ProtectionQuery(
            new ProtectionActor(Optional.of(OWNER_PLAYER), true),
            ProtectionAction.BLOCK_PLACE,
            Optional.of(position(WORLD, 2, 64, 2)),
            Optional.empty(),
            CAUSE,
            Map.of());
    assertEquals(ProtectionOutcome.ALLOW, fixture.engine().evaluate(bypass).outcome());
  }

  @Test
  void protectionIndexRejectsStalePublicationAndRemoval() {
    Fixture fixture = fixture(IslandLifecycleState.ACTIVE, SlotState.ACTIVE, List.of());
    IslandProtectionSnapshot current = fixture.index().find(fixture.islandId()).orElseThrow();
    IslandProtectionSnapshot newer =
        snapshot(fixture.islandId(), IslandLifecycleState.LOCKED, 4, new GridPosition(0, 0));
    IslandProtectionSnapshot stale =
        snapshot(fixture.islandId(), IslandLifecycleState.ACTIVE, 3, new GridPosition(0, 0));

    assertTrue(fixture.index().publish(newer));
    assertFalse(fixture.index().publish(stale));
    assertFalse(fixture.index().remove(fixture.islandId(), current.islandVersion()));
    assertTrue(fixture.index().remove(fixture.islandId(), newer.islandVersion()));
  }

  @Test
  void lookupWorkDoesNotGrowWithOneHundredThousandIslands() {
    int islandCount = 100_000;
    List<SlotLocatorEntry> slots = new java.util.ArrayList<>(islandCount);
    List<IslandProtectionSnapshot> snapshots = new java.util.ArrayList<>(islandCount);
    IslandId target = null;
    GridPosition targetGrid = null;
    for (int index = 0; index < islandCount; index++) {
      IslandId islandId = new IslandId(new UUID(0, index + 1L));
      GridPosition grid = new GridPosition(index % 400 - 200, index / 400 - 125);
      slots.add(
          new SlotLocatorEntry(
              SHARD, grid, new SlotId(new UUID(1, index + 1L)), islandId, SlotState.ACTIVE, 1));
      snapshots.add(
          new IslandProtectionSnapshot(
              islandId, IslandLifecycleState.ACTIVE, SHARD, grid, 64, 1, Map.of(), Set.of()));
      if (index == islandCount - 1) {
        target = islandId;
        targetGrid = grid;
      }
    }
    InMemorySlotLocatorIndex slotIndex = InMemorySlotLocatorIndex.rebuild(slots);
    InMemoryIslandProtectionIndex protectionIndex =
        InMemoryIslandProtectionIndex.rebuild(snapshots);
    WorldProjectionRegistry worlds =
        new WorldProjectionRegistry(
            List.of(
                new WorldProjection(WORLD, SHARD, DimensionId.of("openoneblock", "overworld"))));
    AtomicInteger geometryLookups = new AtomicInteger();
    java.util.function.Function<ShardGroupId, GridGeometry> geometry =
        ignored -> {
          geometryLookups.incrementAndGet();
          return GEOMETRY;
        };
    ProtectionEngine engine =
        new ProtectionEngine(
            new IslandLocationResolver(worlds, geometry, slotIndex),
            geometry,
            protectionIndex,
            new RolePermissionRegistry(Map.of(VISITOR, Set.of()), Set.of(), VISITOR),
            List.of());
    int x = targetGrid.gridX() * GridConfiguration.DEFAULT.cellSize();
    int z = targetGrid.gridZ() * GridConfiguration.DEFAULT.cellSize();
    ProtectionQuery query =
        ProtectionQuery.at(
            ProtectionActor.environment(),
            ProtectionAction.FLUID_FLOW,
            position(WORLD, x, 64, z),
            NamespacedId.of("minecraft", "water"));

    assertEquals(ProtectionOutcome.ALLOW, engine.evaluate(query).outcome());
    assertEquals(target, protectionIndex.find(target).orElseThrow().islandId());
    assertEquals(2, geometryLookups.get());
  }

  private static Fixture fixture(
      IslandLifecycleState lifecycle, SlotState slotState, List<ProtectionPolicy> policies) {
    IslandId islandId = IslandId.generate();
    GridPosition grid = new GridPosition(0, 0);
    SlotLocatorEntry entry = entry(islandId, grid, slotState);
    InMemorySlotLocatorIndex slots = InMemorySlotLocatorIndex.rebuild(List.of(entry));
    InMemoryIslandProtectionIndex index =
        InMemoryIslandProtectionIndex.rebuild(List.of(snapshot(islandId, lifecycle, 2, grid)));
    return new Fixture(islandId, index, engine(slots, index, policies));
  }

  private static Fixture twoIslandFixture() {
    IslandId first = IslandId.generate();
    IslandId second = IslandId.generate();
    GridPosition firstGrid = new GridPosition(0, 0);
    GridPosition secondGrid = new GridPosition(1, 0);
    InMemorySlotLocatorIndex slots =
        InMemorySlotLocatorIndex.rebuild(
            List.of(
                entry(first, firstGrid, SlotState.ACTIVE),
                entry(second, secondGrid, SlotState.ACTIVE)));
    InMemoryIslandProtectionIndex index =
        InMemoryIslandProtectionIndex.rebuild(
            List.of(
                snapshot(first, IslandLifecycleState.ACTIVE, 2, firstGrid),
                snapshot(second, IslandLifecycleState.ACTIVE, 2, secondGrid)));
    return new Fixture(first, index, engine(slots, index, List.of()));
  }

  private static ProtectionEngine engine(
      InMemorySlotLocatorIndex slots,
      InMemoryIslandProtectionIndex index,
      List<ProtectionPolicy> policies) {
    WorldProjectionRegistry worlds =
        new WorldProjectionRegistry(
            List.of(
                new WorldProjection(WORLD, SHARD, DimensionId.of("openoneblock", "overworld"))));
    IslandLocationResolver resolver =
        new IslandLocationResolver(worlds, ignored -> GEOMETRY, slots);
    RolePermissionRegistry roles =
        new RolePermissionRegistry(
            Map.of(
                OWNER, EnumSet.allOf(ProtectionAction.class),
                MEMBER, Set.of(ProtectionAction.BLOCK_BREAK, ProtectionAction.BLOCK_PLACE),
                VISITOR, Set.of()),
            Set.of(OWNER),
            VISITOR);
    return new ProtectionEngine(resolver, ignored -> GEOMETRY, index, roles, policies);
  }

  private static IslandProtectionSnapshot snapshot(
      IslandId islandId, IslandLifecycleState lifecycle, long version, GridPosition grid) {
    return new IslandProtectionSnapshot(
        islandId,
        lifecycle,
        SHARD,
        grid,
        64,
        version,
        Map.of(OWNER_PLAYER, OWNER, MEMBER_PLAYER, MEMBER),
        Set.of(position(WORLD, grid.gridX() * 512, 64, grid.gridZ() * 512)));
  }

  private static SlotLocatorEntry entry(IslandId islandId, GridPosition grid, SlotState slotState) {
    return new SlotLocatorEntry(SHARD, grid, SlotId.generate(), islandId, slotState, 1);
  }

  private static ProtectionQuery queryAt(
      PlayerId player, ProtectionAction action, WorldId world, int x, int y, int z) {
    return ProtectionQuery.at(
        ProtectionActor.player(player), action, position(world, x, y, z), CAUSE);
  }

  private static ProtectionQuery pair(WorldId source, WorldId destination) {
    return new ProtectionQuery(
        ProtectionActor.environment(),
        ProtectionAction.PISTON_MOVE,
        Optional.of(position(source, 0, 64, 0)),
        Optional.of(position(destination, 0, 64, 0)),
        NamespacedId.of("minecraft", "piston"),
        Map.of());
  }

  private static ProtectionPosition position(WorldId world, int x, int y, int z) {
    return new ProtectionPosition(world, x, y, z);
  }

  private static void assertDecision(
      ProtectionOutcome outcome, String reason, ProtectionDecision decision) {
    assertEquals(outcome, decision.outcome());
    assertEquals(NamespacedId.of("openoneblock", reason), decision.reason());
  }

  private record Fixture(
      IslandId islandId, InMemoryIslandProtectionIndex index, ProtectionEngine engine) {}
}
