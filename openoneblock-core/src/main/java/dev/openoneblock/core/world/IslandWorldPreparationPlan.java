package dev.openoneblock.core.world;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.grid.HorizontalBounds;
import dev.openoneblock.core.slot.SlotId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Complete immutable preparation intent validated before any durable effect is dispatched.
 *
 * @param operationId durable operation identity
 * @param islandId owning island
 * @param expectedIslandVersion optimistic island version
 * @param slotId authoritative slot
 * @param expectedSlotVersion optimistic slot version
 * @param worldId verified world projection
 * @param reservedRegion maximum permitted horizontal bounds
 * @param minimumY inclusive build minimum
 * @param maximumYExclusive exclusive build maximum
 * @param effects ordered complete effect list
 */
public record IslandWorldPreparationPlan(
    OperationId operationId,
    IslandId islandId,
    long expectedIslandVersion,
    SlotId slotId,
    long expectedSlotVersion,
    WorldId worldId,
    HorizontalBounds reservedRegion,
    int minimumY,
    int maximumYExclusive,
    List<WorldEffectPlan> effects) {
  /** Validates target ownership, bounds, stable effect order, and operation identity. */
  public IslandWorldPreparationPlan {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(slotId, "slotId");
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(reservedRegion, "reservedRegion");
    effects = List.copyOf(effects);
    if (expectedIslandVersion < 0 || expectedSlotVersion < 0) {
      throw new IllegalArgumentException("expected versions must be non-negative");
    }
    if (minimumY >= maximumYExclusive) {
      throw new IllegalArgumentException("preparation height must not be empty");
    }
    if (effects.isEmpty()) {
      throw new IllegalArgumentException("preparation requires at least one effect");
    }
    Set<WorldEffectKey> keys = new HashSet<>();
    for (int index = 0; index < effects.size(); index++) {
      WorldEffectPlan effect = Objects.requireNonNull(effects.get(index), "effect");
      if (!effect.key().operationId().equals(operationId)
          || effect.key().effectIndex() != index
          || !effect.islandId().equals(islandId)
          || !effect.worldId().equals(worldId)
          || !keys.add(effect.key())) {
        throw new IllegalArgumentException(
            "effects must have matching contiguous stable identities");
      }
      validateBounds(effect, reservedRegion, minimumY, maximumYExclusive);
    }
  }

  private static void validateBounds(
      WorldEffectPlan effect, HorizontalBounds reserved, int minimumY, int maximumYExclusive) {
    if (effect instanceof WorldEffectPlan.VerifyCleanRegion clean) {
      requireContains(reserved, clean.bounds(), "clean verification");
      requireHeight(minimumY, maximumYExclusive, clean.minimumY(), clean.maximumYExclusive());
    } else if (effect instanceof WorldEffectPlan.SetVanillaBlock block) {
      requirePosition(reserved, minimumY, maximumYExclusive, block.position(), "block effect");
    } else if (effect instanceof WorldEffectPlan.VerifySafeSpawn spawn) {
      WorldBlockPosition feet = spawn.spawn().feetBlock();
      requirePosition(reserved, minimumY + 1, maximumYExclusive - 1, feet, "spawn");
    } else if (effect instanceof WorldEffectPlan.PlaceStructure structure) {
      requireContains(reserved, structure.footprint(), "structure");
      requirePosition(
          reserved, minimumY, maximumYExclusive, structure.anchor(), "structure anchor");
      requireHeight(
          minimumY, maximumYExclusive, structure.minimumY(), structure.maximumYExclusive());
    }
  }

  private static void requirePosition(
      HorizontalBounds bounds,
      int minimumY,
      int maximumYExclusive,
      WorldBlockPosition position,
      String description) {
    if (!bounds.contains(position.x(), position.z())
        || position.y() < minimumY
        || position.y() >= maximumYExclusive) {
      throw new IllegalArgumentException(description + " lies outside the reserved region");
    }
  }

  private static void requireContains(
      HorizontalBounds outer, HorizontalBounds inner, String description) {
    if (inner.minX() < outer.minX()
        || inner.minZ() < outer.minZ()
        || inner.maxXExclusive() > outer.maxXExclusive()
        || inner.maxZExclusive() > outer.maxZExclusive()) {
      throw new IllegalArgumentException(description + " exceeds the reserved region");
    }
  }

  private static void requireHeight(
      int allowedMinimum, int allowedMaximum, int minimum, int maximum) {
    if (minimum < allowedMinimum || maximum > allowedMaximum) {
      throw new IllegalArgumentException("effect exceeds the configured build height");
    }
  }
}
