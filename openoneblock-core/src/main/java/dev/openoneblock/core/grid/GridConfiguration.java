package dev.openoneblock.core.grid;

/**
 * Validated geometry shared by every slot in one shard group.
 *
 * @param cellSize full-cell width in blocks
 * @param initialBorder initial playable border width
 * @param maximumBorder maximum reserved border width
 * @param safetyGap required gap between adjacent maximum borders
 */
public record GridConfiguration(int cellSize, int initialBorder, int maximumBorder, int safetyGap) {
  /** Documented baseline configuration. */
  public static final GridConfiguration DEFAULT = new GridConfiguration(512, 64, 384, 128);

  /** Validates the complete grid geometry. */
  public GridConfiguration {
    if (cellSize <= 0 || cellSize % 16 != 0) {
      throw new IllegalArgumentException("cellSize must be positive and divisible by 16");
    }
    if (initialBorder <= 0) {
      throw new IllegalArgumentException("initialBorder must be positive");
    }
    if (maximumBorder <= 0) {
      throw new IllegalArgumentException("maximumBorder must be positive");
    }
    if (initialBorder > maximumBorder) {
      throw new IllegalArgumentException("initialBorder must not exceed maximumBorder");
    }
    if (safetyGap < 0) {
      throw new IllegalArgumentException("safetyGap must be non-negative");
    }
    if ((long) cellSize - maximumBorder < safetyGap) {
      throw new IllegalArgumentException("cellSize minus maximumBorder must be at least safetyGap");
    }
  }
}
