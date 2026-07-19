package dev.openoneblock.core.world;

import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.platform.RegionTaskTarget;
import java.util.Objects;

/**
 * Immutable integer block position in one verified world projection.
 *
 * @param worldId verified world identity
 * @param x block X
 * @param y block Y
 * @param z block Z
 */
public record WorldBlockPosition(WorldId worldId, int x, int y, int z) {
  /** Validates the world identity. */
  public WorldBlockPosition {
    Objects.requireNonNull(worldId, "worldId");
  }

  /**
   * Returns the region scheduler target which owns this block.
   *
   * @return owning chunk target
   */
  public RegionTaskTarget regionTarget() {
    return RegionTaskTarget.fromBlock(worldId, x, z);
  }
}
