package dev.openoneblock.core.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.grid.CoordinateRange;
import dev.openoneblock.core.grid.GridConfiguration;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.island.IslandAggregateSnapshot;
import dev.openoneblock.core.slot.AllocatedSlot;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotState;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MinimalStarterPreparationPlanFactoryTest {
  @Test
  void buildsDeterministicCenteredMagicBlockAndSafeSpawn() {
    IslandAggregateSnapshot island = snapshot();
    WorldId world = WorldId.parse("00000000-0000-0000-0000-000000000055");
    GridGeometry geometry =
        new GridGeometry(GridConfiguration.DEFAULT, new CoordinateRange(-30_000_000, 30_000_001));

    IslandWorldPreparationPlan plan =
        new MinimalStarterPreparationPlanFactory(NamespacedId.parse("minecraft:grass_block"), 64)
            .create(island, world, geometry, -64, 320);

    WorldEffectPlan.SetVanillaBlock block = (WorldEffectPlan.SetVanillaBlock) plan.effects().get(1);
    WorldEffectPlan.VerifySafeSpawn spawn = (WorldEffectPlan.VerifySafeSpawn) plan.effects().get(2);
    assertEquals(new WorldBlockPosition(world, 512, 64, -512), block.position());
    assertEquals(new WorldSpawnPosition(world, 512.5, 65, -511.5, 0, 0), spawn.spawn());
    assertEquals(
        plan.reservedRegion(),
        ((WorldEffectPlan.VerifyCleanRegion) plan.effects().getFirst()).bounds());
  }

  @Test
  void rejectsNonVanillaStarterAndVerticalPositionOutsideBuildHeight() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MinimalStarterPreparationPlanFactory(
                NamespacedId.parse("itemsadder:custom_block"), 64));
    MinimalStarterPreparationPlanFactory outside =
        new MinimalStarterPreparationPlanFactory(NamespacedId.parse("minecraft:grass_block"), 320);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            outside.create(
                snapshot(),
                WorldId.parse("00000000-0000-0000-0000-000000000055"),
                new GridGeometry(
                    GridConfiguration.DEFAULT, new CoordinateRange(-30_000_000, 30_000_001)),
                -64,
                320));
  }

  private static IslandAggregateSnapshot snapshot() {
    IslandId island = IslandId.parse("00000000-0000-0000-0000-000000000051");
    Instant now = Instant.parse("2026-07-19T00:00:00Z");
    return new IslandAggregateSnapshot(
        island,
        PlayerId.parse("00000000-0000-0000-0000-000000000052"),
        IslandLifecycleState.CREATING,
        Optional.of(
            new AllocatedSlot(
                SlotId.parse("00000000-0000-0000-0000-000000000053"),
                ShardGroupId.parse("openoneblock:primary"),
                8,
                new GridPosition(1, -1),
                SlotState.PREPARING,
                island,
                2)),
        64,
        384,
        2,
        Optional.of(OperationId.parse("00000000-0000-0000-0000-000000000054")),
        now,
        now);
  }
}
