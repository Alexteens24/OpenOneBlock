package dev.openoneblock.core.world;

import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.grid.HorizontalBounds;
import dev.openoneblock.core.island.IslandAggregateSnapshot;
import java.util.List;
import java.util.Objects;

/** Builds the deterministic no-structure starter plan used by the first creation implementation. */
public final class MinimalStarterPreparationPlanFactory {
  private final NamespacedId starterBlock;
  private final int magicBlockY;

  /**
   * Creates a factory for one validated Vanilla starter block and vertical position.
   *
   * @param starterBlock canonical Vanilla block identity
   * @param magicBlockY Magic Block Y coordinate
   */
  public MinimalStarterPreparationPlanFactory(NamespacedId starterBlock, int magicBlockY) {
    this.starterBlock = Objects.requireNonNull(starterBlock, "starterBlock");
    if (!starterBlock.namespace().equals("minecraft")) {
      throw new IllegalArgumentException("minimal starter block must use minecraft namespace");
    }
    this.magicBlockY = magicBlockY;
  }

  /**
   * Creates clean-check, exact block placement, and safe-spawn verification effects.
   *
   * @param island creating island snapshot
   * @param worldId verified target world
   * @param geometry authoritative grid geometry
   * @param minimumY inclusive build minimum
   * @param maximumYExclusive exclusive build maximum
   * @return validated deterministic starter plan
   */
  public IslandWorldPreparationPlan create(
      IslandAggregateSnapshot island,
      WorldId worldId,
      GridGeometry geometry,
      int minimumY,
      int maximumYExclusive) {
    Objects.requireNonNull(island, "island");
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(geometry, "geometry");
    var operationId = island.pendingOperationId().orElseThrow();
    var slot = island.primarySlot().orElseThrow();
    HorizontalBounds reserved = geometry.reservedRegion(slot.gridPosition());
    int centerX =
        Math.toIntExact(
            Math.multiplyExact(
                (long) slot.gridPosition().gridX(), geometry.configuration().cellSize()));
    int centerZ =
        Math.toIntExact(
            Math.multiplyExact(
                (long) slot.gridPosition().gridZ(), geometry.configuration().cellSize()));
    WorldBlockPosition magicBlock = new WorldBlockPosition(worldId, centerX, magicBlockY, centerZ);
    WorldSpawnPosition spawn =
        new WorldSpawnPosition(worldId, centerX + 0.5, magicBlockY + 1.0, centerZ + 0.5, 0, 0);
    List<WorldEffectPlan> effects =
        List.of(
            new WorldEffectPlan.VerifyCleanRegion(
                new WorldEffectKey(operationId, 0),
                island.islandId(),
                worldId,
                reserved,
                minimumY,
                maximumYExclusive),
            new WorldEffectPlan.SetVanillaBlock(
                new WorldEffectKey(operationId, 1), island.islandId(), magicBlock, starterBlock),
            new WorldEffectPlan.VerifySafeSpawn(
                new WorldEffectKey(operationId, 2), island.islandId(), spawn));
    return new IslandWorldPreparationPlan(
        operationId,
        island.islandId(),
        island.version(),
        slot.slotId(),
        slot.version(),
        worldId,
        reserved,
        minimumY,
        maximumYExclusive,
        effects);
  }
}
