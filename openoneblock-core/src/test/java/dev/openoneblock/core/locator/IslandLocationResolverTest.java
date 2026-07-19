package dev.openoneblock.core.locator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.DimensionId;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.grid.CoordinateRange;
import dev.openoneblock.core.grid.GridConfiguration;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotState;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IslandLocationResolverTest {
  private static final ShardGroupId SHARD = ShardGroupId.parse("openoneblock:primary");
  private static final WorldId OVERWORLD =
      WorldId.of(UUID.fromString("bcb72d49-b26f-42c7-8e7e-d4cd8301b3a1"));
  private static final WorldId NETHER =
      WorldId.of(UUID.fromString("3f8b7714-b49a-4a9a-acd4-f8c534415270"));
  private static final GridGeometry GEOMETRY =
      new GridGeometry(GridConfiguration.DEFAULT, new CoordinateRange(-30_000_000, 30_000_001));

  @Test
  void sameLogicalCellResolvesSameIslandAcrossDimensionWorldsAndBoundaries() {
    IslandId islandId = IslandId.generate();
    SlotLocatorEntry entry = entry(new GridPosition(0, 0), islandId, 0);
    InMemorySlotLocatorIndex slots = InMemorySlotLocatorIndex.rebuild(List.of(entry));
    IslandLocationResolver resolver = resolver(slots);

    assertEquals(entry, resolved(resolver.lookup(OVERWORLD, -256, 0)).entry());
    assertEquals(entry, resolved(resolver.lookup(OVERWORLD, 255, 0)).entry());
    assertEquals(entry, resolved(resolver.lookup(NETHER, -256, 0)).entry());
    assertEquals(entry, resolved(resolver.lookup(NETHER, 255, 0)).entry());
    IslandLocationLookup.EmptyCell west =
        assertInstanceOf(IslandLocationLookup.EmptyCell.class, resolver.lookup(OVERWORLD, -257, 0));
    IslandLocationLookup.EmptyCell east =
        assertInstanceOf(IslandLocationLookup.EmptyCell.class, resolver.lookup(OVERWORLD, 256, 0));
    assertEquals(new GridPosition(-1, 0), west.gridPosition());
    assertEquals(new GridPosition(1, 0), east.gridPosition());
  }

  @Test
  void unmanagedAndOutOfConfiguredRangeAreExplicitAndDoNotGrowIndex() {
    InMemorySlotLocatorIndex slots = new InMemorySlotLocatorIndex();
    IslandLocationResolver resolver = resolver(slots);
    WorldId unknown = WorldId.of(UUID.fromString("9374407e-e8b2-40bc-8cff-32fba6ef964e"));

    assertInstanceOf(IslandLocationLookup.UnmanagedWorld.class, resolver.lookup(unknown, 0, 0));
    assertInstanceOf(
        IslandLocationLookup.OutsideManagedRange.class,
        resolver.lookup(OVERWORLD, Integer.MAX_VALUE, 0));
    assertEquals(0, slots.size());
  }

  @Test
  void contradictoryOwnershipPropagatesFailClosedLocationConflict() {
    GridPosition position = new GridPosition(0, 0);
    InMemorySlotLocatorIndex slots =
        InMemorySlotLocatorIndex.rebuild(
            List.of(
                entry(position, IslandId.generate(), 0), entry(position, IslandId.generate(), 0)));
    IslandLocationResolver resolver = resolver(slots);

    IslandLocationLookup.Conflicted conflict =
        assertInstanceOf(IslandLocationLookup.Conflicted.class, resolver.lookup(OVERWORLD, 0, 0));
    assertEquals(SHARD, conflict.projection().shardGroupId());
  }

  @Test
  void registryRejectsDuplicateWorldAndDuplicateShardDimensionMappings() {
    WorldProjection overworld = projection(OVERWORLD, "overworld");

    assertThrows(
        IllegalArgumentException.class,
        () -> new WorldProjectionRegistry(List.of(overworld, projection(OVERWORLD, "nether"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorldProjectionRegistry(
                List.of(
                    overworld,
                    projection(
                        WorldId.of(UUID.fromString("d4040367-8185-42ef-8fb7-b6a410fa3d97")),
                        "overworld"))));
  }

  private static IslandLocationResolver resolver(InMemorySlotLocatorIndex slots) {
    WorldProjectionRegistry worlds =
        new WorldProjectionRegistry(
            List.of(projection(OVERWORLD, "overworld"), projection(NETHER, "nether")));
    return new IslandLocationResolver(worlds, ignored -> GEOMETRY, slots);
  }

  private static WorldProjection projection(WorldId worldId, String dimension) {
    return new WorldProjection(worldId, SHARD, DimensionId.of("openoneblock", dimension));
  }

  private static SlotLocatorEntry entry(GridPosition position, IslandId islandId, long version) {
    return new SlotLocatorEntry(
        SHARD, position, SlotId.generate(), islandId, SlotState.ACTIVE, version);
  }

  private static IslandLocationLookup.Resolved resolved(IslandLocationLookup lookup) {
    return assertInstanceOf(IslandLocationLookup.Resolved.class, lookup);
  }
}
