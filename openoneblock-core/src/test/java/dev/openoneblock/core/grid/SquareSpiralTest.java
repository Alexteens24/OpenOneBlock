package dev.openoneblock.core.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openoneblock.api.grid.GridPosition;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SquareSpiralTest {
  @Test
  void mapsDocumentedInitialSequence() {
    List<GridPosition> expected =
        List.of(
            new GridPosition(0, 0),
            new GridPosition(1, 0),
            new GridPosition(1, 1),
            new GridPosition(0, 1),
            new GridPosition(-1, 1),
            new GridPosition(-1, 0),
            new GridPosition(-1, -1),
            new GridPosition(0, -1),
            new GridPosition(1, -1),
            new GridPosition(2, -1));

    for (int ordinal = 0; ordinal < expected.size(); ordinal++) {
      assertEquals(expected.get(ordinal), SquareSpiral.positionAt(ordinal));
    }
  }

  @Test
  void completeRingsAreUniqueAndInvertible() {
    int ring = 100;
    long exclusiveEnd = (long) (2 * ring + 1) * (2 * ring + 1);
    Set<GridPosition> positions = new HashSet<>();

    for (long ordinal = 0; ordinal < exclusiveEnd; ordinal++) {
      GridPosition position = SquareSpiral.positionAt(ordinal);
      positions.add(position);
      assertEquals(ordinal, SquareSpiral.ordinalOf(position));
    }

    assertEquals(exclusiveEnd, positions.size());
  }

  @Test
  void randomOrdinalsRoundTripWithFixedSeed() {
    Random random = new Random(0x5A17C0DEL);
    for (int iteration = 0; iteration < 20_000; iteration++) {
      long ordinal = random.nextLong(0, Long.MAX_VALUE);
      assertEquals(ordinal, SquareSpiral.ordinalOf(SquareSpiral.positionAt(ordinal)));
    }
  }

  @Test
  void supportsLargestSignedOrdinal() {
    assertEquals(Long.MAX_VALUE, SquareSpiral.ordinalOf(SquareSpiral.positionAt(Long.MAX_VALUE)));
  }

  @Test
  void rejectsNegativeOrUnrepresentableInputs() {
    assertThrows(IllegalArgumentException.class, () -> SquareSpiral.positionAt(-1));
    assertThrows(
        ArithmeticException.class,
        () -> SquareSpiral.ordinalOf(new GridPosition(Integer.MIN_VALUE, 0)));
  }
}
