package dev.openoneblock.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.grid.HorizontalBounds;
import dev.openoneblock.core.platform.RegionTaskTarget;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChunkCoverageTest {
  private static final WorldId WORLD =
      WorldId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));

  @Test
  void coversHalfOpenBoundsWithoutTouchingAdjacentChunks() {
    assertEquals(
        List.of(new RegionTaskTarget(WORLD, 0, 0)),
        ChunkCoverage.covering(WORLD, new HorizontalBounds(0, 0, 16, 16)));
    assertEquals(
        List.of(
            new RegionTaskTarget(WORLD, 0, 0),
            new RegionTaskTarget(WORLD, 0, 1),
            new RegionTaskTarget(WORLD, 1, 0),
            new RegionTaskTarget(WORLD, 1, 1)),
        ChunkCoverage.covering(WORLD, new HorizontalBounds(15, 15, 17, 17)));
  }

  @Test
  void signedCoordinatesUseFloorDivisionAtChunkBoundaries() {
    assertEquals(
        List.of(new RegionTaskTarget(WORLD, -1, -1)),
        ChunkCoverage.covering(WORLD, new HorizontalBounds(-16, -16, 0, 0)));
    assertEquals(
        List.of(
            new RegionTaskTarget(WORLD, -2, -2),
            new RegionTaskTarget(WORLD, -2, -1),
            new RegionTaskTarget(WORLD, -1, -2),
            new RegionTaskTarget(WORLD, -1, -1)),
        ChunkCoverage.covering(WORLD, new HorizontalBounds(-17, -17, -15, -15)));
  }
}
