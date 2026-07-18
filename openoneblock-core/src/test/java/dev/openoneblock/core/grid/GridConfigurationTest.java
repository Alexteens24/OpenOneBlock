package dev.openoneblock.core.grid;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GridConfigurationTest {
  @Test
  void acceptsDocumentedDefaults() {
    assertEquals(new GridConfiguration(512, 64, 384, 128), GridConfiguration.DEFAULT);
  }

  @Test
  void acceptsZeroSafetyGap() {
    assertDoesNotThrow(() -> new GridConfiguration(16, 16, 16, 0));
  }

  @ParameterizedTest
  @MethodSource("invalidConfigurations")
  void rejectsInvalidGeometry(int cellSize, int initial, int maximum, int gap) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new GridConfiguration(cellSize, initial, maximum, gap));
  }

  private static Stream<Arguments> invalidConfigurations() {
    return Stream.of(
        Arguments.of(0, 1, 1, 0),
        Arguments.of(17, 1, 1, 0),
        Arguments.of(512, 0, 384, 128),
        Arguments.of(512, 385, 384, 128),
        Arguments.of(512, 64, 0, 128),
        Arguments.of(512, 64, 384, -1),
        Arguments.of(512, 64, 385, 128));
  }
}
