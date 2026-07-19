package dev.openoneblock.paper.world;

import org.bukkit.generator.ChunkGenerator;

/** Empty thread-safe generator that disables every Vanilla terrain and population phase. */
public final class OpenOneBlockVoidChunkGenerator extends ChunkGenerator {
  /** Creates a stateless void generator. */
  public OpenOneBlockVoidChunkGenerator() {}

  /** {@inheritDoc} */
  @Override
  public boolean shouldGenerateNoise() {
    return false;
  }

  /** {@inheritDoc} */
  @Override
  public boolean shouldGenerateSurface() {
    return false;
  }

  /** {@inheritDoc} */
  @Override
  public boolean shouldGenerateCaves() {
    return false;
  }

  /** {@inheritDoc} */
  @Override
  public boolean shouldGenerateDecorations() {
    return false;
  }

  /** {@inheritDoc} */
  @Override
  public boolean shouldGenerateMobs() {
    return false;
  }

  /** {@inheritDoc} */
  @Override
  public boolean shouldGenerateStructures() {
    return false;
  }
}
