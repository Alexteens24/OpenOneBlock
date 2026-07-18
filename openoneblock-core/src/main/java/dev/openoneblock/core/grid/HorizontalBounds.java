package dev.openoneblock.core.grid;

/**
 * Half-open X/Z bounds for a cell or border.
 *
 * @param minX inclusive minimum X
 * @param minZ inclusive minimum Z
 * @param maxXExclusive exclusive maximum X
 * @param maxZExclusive exclusive maximum Z
 */
public record HorizontalBounds(int minX, int minZ, int maxXExclusive, int maxZExclusive) {
  /** Validates that both axes describe non-empty ranges. */
  public HorizontalBounds {
    if (minX >= maxXExclusive || minZ >= maxZExclusive) {
      throw new IllegalArgumentException("Horizontal bounds must not be empty");
    }
  }

  /**
   * Returns whether the supplied block coordinates belong to these bounds.
   *
   * @param blockX block coordinate on the X axis
   * @param blockZ block coordinate on the Z axis
   * @return {@code true} when both coordinates are inside the bounds
   */
  public boolean contains(int blockX, int blockZ) {
    return blockX >= minX && blockX < maxXExclusive && blockZ >= minZ && blockZ < maxZExclusive;
  }

  /**
   * Returns the exact number of X coordinates represented by these bounds.
   *
   * @return width in block coordinates
   */
  public long width() {
    return (long) maxXExclusive - minX;
  }

  /**
   * Returns the exact number of Z coordinates represented by these bounds.
   *
   * @return depth in block coordinates
   */
  public long depth() {
    return (long) maxZExclusive - minZ;
  }

  /**
   * Returns the visualization center on the X axis.
   *
   * @return integer or half-block aligned X center
   */
  public double centerX() {
    return ((long) minX + maxXExclusive) / 2.0;
  }

  /**
   * Returns the visualization center on the Z axis.
   *
   * @return integer or half-block aligned Z center
   */
  public double centerZ() {
    return ((long) minZ + maxZExclusive) / 2.0;
  }
}
