package dev.openoneblock.core.grid;

import dev.openoneblock.api.grid.GridPosition;
import java.util.Objects;

/** Pure grid lookup and bounds calculations, independent of Bukkit world objects. */
public final class GridGeometry {
  private final GridConfiguration configuration;
  private final CoordinateRange coordinateRange;

  /**
   * Creates a geometry calculator constrained to the supplied coordinate range.
   *
   * @param configuration validated shard-group grid configuration
   * @param coordinateRange allowed block-coordinate range
   */
  public GridGeometry(GridConfiguration configuration, CoordinateRange coordinateRange) {
    this.configuration = Objects.requireNonNull(configuration, "configuration");
    this.coordinateRange = Objects.requireNonNull(coordinateRange, "coordinateRange");
  }

  /**
   * Returns the validated grid configuration.
   *
   * @return grid configuration used by this calculator
   */
  public GridConfiguration configuration() {
    return configuration;
  }

  /**
   * Returns the configured world-coordinate range.
   *
   * @return allowed coordinate range
   */
  public CoordinateRange coordinateRange() {
    return coordinateRange;
  }

  /**
   * Returns the logical cell owning the supplied block coordinates.
   *
   * @param blockX block coordinate on the X axis
   * @param blockZ block coordinate on the Z axis
   * @return owning logical grid position
   */
  public GridPosition gridAt(int blockX, int blockZ) {
    requireCoordinate(blockX);
    requireCoordinate(blockZ);
    long halfCell = configuration.cellSize() / 2L;
    long gridX = Math.floorDiv(Math.addExact((long) blockX, halfCell), configuration.cellSize());
    long gridZ = Math.floorDiv(Math.addExact((long) blockZ, halfCell), configuration.cellSize());
    return new GridPosition(Math.toIntExact(gridX), Math.toIntExact(gridZ));
  }

  /**
   * Returns the full-cell bounds for a logical grid position.
   *
   * @param position logical grid position
   * @return validated full-cell bounds
   */
  public HorizontalBounds fullCell(GridPosition position) {
    Objects.requireNonNull(position, "position");
    long centerX = Math.multiplyExact((long) position.gridX(), configuration.cellSize());
    long centerZ = Math.multiplyExact((long) position.gridZ(), configuration.cellSize());
    long halfCell = configuration.cellSize() / 2L;
    long minX = Math.subtractExact(centerX, halfCell);
    long minZ = Math.subtractExact(centerZ, halfCell);
    return checkedBounds(
        minX,
        minZ,
        Math.addExact(minX, configuration.cellSize()),
        Math.addExact(minZ, configuration.cellSize()));
  }

  /**
   * Returns initial playable-border bounds for a logical grid position.
   *
   * @param position logical grid position
   * @return validated initial-border bounds
   */
  public HorizontalBounds initialBorder(GridPosition position) {
    return border(position, configuration.initialBorder());
  }

  /**
   * Returns maximum reserved-region bounds for a logical grid position.
   *
   * @param position logical grid position
   * @return validated reserved-region bounds
   */
  public HorizontalBounds reservedRegion(GridPosition position) {
    return border(position, configuration.maximumBorder());
  }

  /**
   * Returns centered bounds for a validated current border size.
   *
   * @param position logical grid position
   * @param borderSize requested border width
   * @return validated border bounds
   */
  public HorizontalBounds border(GridPosition position, int borderSize) {
    Objects.requireNonNull(position, "position");
    if (borderSize <= 0 || borderSize > configuration.maximumBorder()) {
      throw new IllegalArgumentException(
          "borderSize must be positive and not exceed maximumBorder");
    }
    long centerX = Math.multiplyExact((long) position.gridX(), configuration.cellSize());
    long centerZ = Math.multiplyExact((long) position.gridZ(), configuration.cellSize());
    long minX = Math.subtractExact(centerX, Math.floorDiv(borderSize, 2));
    long minZ = Math.subtractExact(centerZ, Math.floorDiv(borderSize, 2));
    return checkedBounds(
        minX, minZ, Math.addExact(minX, borderSize), Math.addExact(minZ, borderSize));
  }

  private HorizontalBounds checkedBounds(
      long minX, long minZ, long maxXExclusive, long maxZExclusive) {
    if (!coordinateRange.contains(minX, maxXExclusive)
        || !coordinateRange.contains(minZ, maxZExclusive)) {
      throw new IllegalArgumentException("Calculated bounds exceed configured world limits");
    }
    return new HorizontalBounds(
        Math.toIntExact(minX),
        Math.toIntExact(minZ),
        Math.toIntExact(maxXExclusive),
        Math.toIntExact(maxZExclusive));
  }

  private void requireCoordinate(int coordinate) {
    if (!coordinateRange.contains(coordinate)) {
      throw new IllegalArgumentException("Block coordinate exceeds configured world limits");
    }
  }
}
