package dev.openoneblock.core.runtime;

import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.grid.HorizontalBounds;
import dev.openoneblock.core.platform.RegionTaskTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure half-open block bounds to complete owning-chunk coverage calculation. */
public final class ChunkCoverage {
  private static final int CHUNK_SIZE = 16;

  private ChunkCoverage() {}

  /**
   * Returns every chunk intersecting the supplied half-open horizontal bounds.
   *
   * @param worldId verified world UUID
   * @param bounds required block bounds
   * @return deterministic X-major immutable owning-region targets
   */
  public static List<RegionTaskTarget> covering(WorldId worldId, HorizontalBounds bounds) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(bounds, "bounds");
    int minimumChunkX = Math.floorDiv(bounds.minX(), CHUNK_SIZE);
    int maximumChunkX = Math.floorDiv(bounds.maxXExclusive() - 1, CHUNK_SIZE);
    int minimumChunkZ = Math.floorDiv(bounds.minZ(), CHUNK_SIZE);
    int maximumChunkZ = Math.floorDiv(bounds.maxZExclusive() - 1, CHUNK_SIZE);
    long width = (long) maximumChunkX - minimumChunkX + 1L;
    long depth = (long) maximumChunkZ - minimumChunkZ + 1L;
    int capacity = Math.toIntExact(Math.multiplyExact(width, depth));
    List<RegionTaskTarget> chunks = new ArrayList<>(capacity);
    for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
      for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
        chunks.add(new RegionTaskTarget(worldId, chunkX, chunkZ));
      }
    }
    return List.copyOf(chunks);
  }
}
