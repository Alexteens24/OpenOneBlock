package dev.openoneblock.core.grid;

/**
 * Allowed half-open range for block coordinates in a shard projection.
 *
 * @param minInclusive smallest allowed block coordinate
 * @param maxExclusive exclusive upper block-coordinate limit
 */
public record CoordinateRange(int minInclusive, int maxExclusive) {
  /** Validates that the range is non-empty. */
  public CoordinateRange {
    if (minInclusive >= maxExclusive) {
      throw new IllegalArgumentException("Coordinate range must not be empty");
    }
  }

  /**
   * Returns whether one block coordinate belongs to this range.
   *
   * @param coordinate block coordinate to test
   * @return {@code true} when the coordinate is inside the range
   */
  public boolean contains(int coordinate) {
    return coordinate >= minInclusive && coordinate < maxExclusive;
  }

  boolean contains(long minimum, long maximumExclusive) {
    return minimum >= minInclusive && maximumExclusive <= maxExclusive;
  }
}
