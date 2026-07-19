package dev.openoneblock.core.platform;

import dev.openoneblock.api.id.WorldId;
import java.util.Objects;

/**
 * Platform-independent region ownership target expressed as world and chunk coordinates.
 *
 * @param worldId target world UUID
 * @param chunkX target chunk X
 * @param chunkZ target chunk Z
 */
public record RegionTaskTarget(WorldId worldId, int chunkX, int chunkZ) {
  /** Validates region target metadata. */
  public RegionTaskTarget {
    Objects.requireNonNull(worldId, "worldId");
  }

  /**
   * Derives a region target from signed block coordinates using floor division.
   *
   * @param worldId target world UUID
   * @param blockX target block X
   * @param blockZ target block Z
   * @return owning chunk target
   */
  public static RegionTaskTarget fromBlock(WorldId worldId, int blockX, int blockZ) {
    return new RegionTaskTarget(worldId, Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
  }
}
