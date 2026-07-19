package dev.openoneblock.core.world;

import dev.openoneblock.api.id.WorldId;
import java.util.Objects;

/**
 * Immutable precise island spawn position independent of Bukkit location objects.
 *
 * @param worldId verified world identity
 * @param x precise X
 * @param y precise Y
 * @param z precise Z
 * @param yaw horizontal rotation
 * @param pitch vertical rotation
 */
public record WorldSpawnPosition(
    WorldId worldId, double x, double y, double z, float yaw, float pitch) {
  /** Rejects non-finite coordinates and rotations. */
  public WorldSpawnPosition {
    Objects.requireNonNull(worldId, "worldId");
    if (!Double.isFinite(x)
        || !Double.isFinite(y)
        || !Double.isFinite(z)
        || !Float.isFinite(yaw)
        || !Float.isFinite(pitch)) {
      throw new IllegalArgumentException("spawn coordinates and rotations must be finite");
    }
  }

  /**
   * Returns the block containing the player's feet.
   *
   * @return feet block
   */
  public WorldBlockPosition feetBlock() {
    return new WorldBlockPosition(worldId, floorToInt(x), floorToInt(y), floorToInt(z));
  }

  private static int floorToInt(double value) {
    double floor = Math.floor(value);
    if (floor < Integer.MIN_VALUE || floor > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("spawn coordinate exceeds integer block range");
    }
    return (int) floor;
  }
}
