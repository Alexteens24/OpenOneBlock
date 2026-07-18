package dev.openoneblock.core.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.grid.GridPosition;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GridGeometryTest {
  private static final CoordinateRange WORLD_LIMIT = new CoordinateRange(-30_000_000, 30_000_001);
  private static final GridGeometry GEOMETRY =
      new GridGeometry(GridConfiguration.DEFAULT, WORLD_LIMIT);

  @ParameterizedTest
  @MethodSource("lookupBoundaries")
  void usesFloorDivisionAtPositiveAndNegativeCellBoundaries(int coordinate, int expectedGrid) {
    assertEquals(new GridPosition(expectedGrid, 0), GEOMETRY.gridAt(coordinate, 0));
    assertEquals(new GridPosition(0, expectedGrid), GEOMETRY.gridAt(0, coordinate));
  }

  @Test
  void fullCellsAreHalfOpenAndDoNotOverlap() {
    HorizontalBounds origin = GEOMETRY.fullCell(new GridPosition(0, 0));
    HorizontalBounds east = GEOMETRY.fullCell(new GridPosition(1, 0));

    assertEquals(new HorizontalBounds(-256, -256, 256, 256), origin);
    assertEquals(origin.maxXExclusive(), east.minX());
    assertTrue(origin.contains(-256, -256));
    assertTrue(origin.contains(255, 255));
    assertFalse(origin.contains(256, 0));
  }

  @Test
  void oddAndEvenBordersContainExactlyTheirRequestedWidth() {
    HorizontalBounds even = GEOMETRY.border(new GridPosition(0, 0), 64);
    HorizontalBounds odd = GEOMETRY.border(new GridPosition(0, 0), 65);

    assertEquals(64, even.width());
    assertEquals(64, even.depth());
    assertEquals(0.0, even.centerX());
    assertEquals(65, odd.width());
    assertEquals(65, odd.depth());
    assertEquals(0.5, odd.centerX());
    assertEquals(new HorizontalBounds(-32, -32, 33, 33), odd);
  }

  @Test
  void adjacentReservedRegionsPreserveConfiguredSafetyGap() {
    HorizontalBounds origin = GEOMETRY.reservedRegion(new GridPosition(0, 0));
    HorizontalBounds east = GEOMETRY.reservedRegion(new GridPosition(1, 0));

    assertEquals(GridConfiguration.DEFAULT.safetyGap(), east.minX() - origin.maxXExclusive());
  }

  @Test
  void randomLocationsRoundTripThroughTheirCells() {
    Random random = new Random(0x0B10C0DEL);
    for (int iteration = 0; iteration < 10_000; iteration++) {
      GridPosition position =
          new GridPosition(random.nextInt(-50_000, 50_001), random.nextInt(-50_000, 50_001));
      HorizontalBounds cell = GEOMETRY.fullCell(position);
      int blockX = random.nextInt(cell.minX(), cell.maxXExclusive());
      int blockZ = random.nextInt(cell.minZ(), cell.maxZExclusive());

      assertEquals(position, GEOMETRY.gridAt(blockX, blockZ));
    }
  }

  @Test
  void rejectsCoordinatesAndBoundsOutsideConfiguredWorldLimit() {
    assertThrows(IllegalArgumentException.class, () -> GEOMETRY.gridAt(30_000_001, 0));
    assertThrows(
        IllegalArgumentException.class, () -> GEOMETRY.fullCell(new GridPosition(58_594, 0)));
  }

  private static Stream<Arguments> lookupBoundaries() {
    return Stream.of(
        Arguments.of(-257, -1), Arguments.of(-256, 0), Arguments.of(255, 0), Arguments.of(256, 1));
  }
}
