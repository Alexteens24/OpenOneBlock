package dev.openoneblock.paper.world;

import org.bukkit.World;

/** Global-region-only adapter for creating or verifying one shared void world. */
@FunctionalInterface
public interface PaperVoidWorldFactory {
  /**
   * Creates, loads, verifies, and configures one world.
   *
   * @param specification validated shared-world configuration
   * @return loaded verified world
   */
  World createOrLoad(SharedWorldSpec specification);
}
