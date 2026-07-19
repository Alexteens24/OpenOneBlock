package dev.openoneblock.paper.world;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class OpenOneBlockVoidChunkGeneratorTest {
  @Test
  void disablesEveryVanillaTerrainPopulationAndStructurePhase() {
    OpenOneBlockVoidChunkGenerator generator = new OpenOneBlockVoidChunkGenerator();

    assertFalse(generator.shouldGenerateNoise());
    assertFalse(generator.shouldGenerateSurface());
    assertFalse(generator.shouldGenerateCaves());
    assertFalse(generator.shouldGenerateDecorations());
    assertFalse(generator.shouldGenerateMobs());
    assertFalse(generator.shouldGenerateStructures());
  }
}
