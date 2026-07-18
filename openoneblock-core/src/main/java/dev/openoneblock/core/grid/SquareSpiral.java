package dev.openoneblock.core.grid;

import dev.openoneblock.api.grid.GridPosition;
import java.util.Objects;

/** Counter-clockwise square spiral beginning east of the origin. */
public final class SquareSpiral {
  private SquareSpiral() {}

  /**
   * Returns the grid position associated with a non-negative slot ordinal.
   *
   * @param ordinal non-negative slot ordinal
   * @return corresponding logical grid position
   */
  public static GridPosition positionAt(long ordinal) {
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must be non-negative");
    }
    if (ordinal == 0) {
      return new GridPosition(0, 0);
    }

    long ring = Math.addExact(floorSquareRoot(ordinal), 1L) / 2L;
    long innerSide = Math.subtractExact(Math.multiplyExact(2L, ring), 1L);
    long ringStart = Math.multiplyExact(innerSide, innerSide);
    long offset = Math.subtractExact(ordinal, ringStart);
    long sideLength = Math.multiplyExact(2L, ring);

    long gridX;
    long gridZ;
    if (offset < sideLength) {
      gridX = ring;
      gridZ = Math.addExact(Math.subtractExact(-ring, -1L), offset);
    } else if (offset < Math.multiplyExact(2L, sideLength)) {
      long sideOffset = offset - sideLength;
      gridX = Math.subtractExact(ring - 1L, sideOffset);
      gridZ = ring;
    } else if (offset < Math.multiplyExact(3L, sideLength)) {
      long sideOffset = offset - Math.multiplyExact(2L, sideLength);
      gridX = -ring;
      gridZ = Math.subtractExact(ring - 1L, sideOffset);
    } else {
      long sideOffset = offset - Math.multiplyExact(3L, sideLength);
      gridX = Math.addExact(-ring + 1L, sideOffset);
      gridZ = -ring;
    }
    return new GridPosition(Math.toIntExact(gridX), Math.toIntExact(gridZ));
  }

  /**
   * Returns the slot ordinal associated with a representable grid position.
   *
   * @param position logical grid position
   * @return corresponding non-negative slot ordinal
   */
  public static long ordinalOf(GridPosition position) {
    Objects.requireNonNull(position, "position");
    long gridX = position.gridX();
    long gridZ = position.gridZ();
    long ring = Math.max(Math.abs(gridX), Math.abs(gridZ));
    if (ring == 0) {
      return 0;
    }

    long innerSide = Math.subtractExact(Math.multiplyExact(2L, ring), 1L);
    long ringStart = Math.multiplyExact(innerSide, innerSide);
    long sideLength = Math.multiplyExact(2L, ring);
    long offset;
    if (gridX == ring && gridZ > -ring) {
      offset = Math.addExact(gridZ, ring - 1L);
    } else if (gridZ == ring) {
      offset = Math.addExact(sideLength, Math.subtractExact(ring - 1L, gridX));
    } else if (gridX == -ring) {
      offset =
          Math.addExact(Math.multiplyExact(2L, sideLength), Math.subtractExact(ring - 1L, gridZ));
    } else {
      offset = Math.addExact(Math.multiplyExact(3L, sideLength), Math.addExact(gridX, ring - 1L));
    }
    return Math.addExact(ringStart, offset);
  }

  private static long floorSquareRoot(long value) {
    long low = 0;
    long high = Math.min(value, 3_037_000_499L);
    while (low <= high) {
      long middle = low + ((high - low) >>> 1);
      if (middle == 0 || middle <= value / middle) {
        low = middle + 1;
      } else {
        high = middle - 1;
      }
    }
    return high;
  }
}
