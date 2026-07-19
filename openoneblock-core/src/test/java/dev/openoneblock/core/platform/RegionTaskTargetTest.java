package dev.openoneblock.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.openoneblock.api.id.WorldId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegionTaskTargetTest {
  private static final WorldId WORLD =
      WorldId.of(UUID.fromString("444b5848-a9f2-43a0-bc8a-2e1c20171067"));

  @Test
  void derivesChunksWithFloorDivisionAtSignedBoundaries() {
    assertEquals(new RegionTaskTarget(WORLD, 0, 0), RegionTaskTarget.fromBlock(WORLD, 0, 15));
    assertEquals(new RegionTaskTarget(WORLD, 1, 1), RegionTaskTarget.fromBlock(WORLD, 16, 31));
    assertEquals(new RegionTaskTarget(WORLD, -1, -1), RegionTaskTarget.fromBlock(WORLD, -1, -16));
    assertEquals(new RegionTaskTarget(WORLD, -2, -2), RegionTaskTarget.fromBlock(WORLD, -17, -32));
  }
}
