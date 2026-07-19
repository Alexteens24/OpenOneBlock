package dev.openoneblock.paper.world;

import java.util.Objects;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldCreator;

/**
 * Paper implementation that creates shared worlds without custom dimensions or per-island worlds.
 */
public final class BukkitVoidWorldFactory implements PaperVoidWorldFactory {
  private final Server server;

  /**
   * Creates a world factory.
   *
   * @param server active Paper or Folia server
   */
  public BukkitVoidWorldFactory(Server server) {
    this.server = Objects.requireNonNull(server, "server");
  }

  /** {@inheritDoc} */
  @Override
  public World createOrLoad(SharedWorldSpec specification) {
    Objects.requireNonNull(specification, "specification");
    World world = server.getWorld(specification.worldName());
    if (world == null) {
      WorldCreator creator =
          new WorldCreator(
                  specification.worldName(), NamespacedKey.minecraft(specification.worldName()))
              .environment(specification.environment())
              .seed(specification.seed())
              .generateStructures(false)
              .generator(new OpenOneBlockVoidChunkGenerator());
      world = server.createWorld(creator);
      if (world == null) {
        throw new IllegalStateException(
            "Paper refused to create shared world " + specification.worldName());
      }
    }
    verify(world, specification);
    configure(world);
    return world;
  }

  private static void verify(World world, SharedWorldSpec specification) {
    if (world.getEnvironment() != specification.environment()) {
      throw new IllegalStateException(
          "Shared world environment differs from configuration: " + specification.worldName());
    }
    if (!(world.getGenerator() instanceof OpenOneBlockVoidChunkGenerator)) {
      throw new IllegalStateException(
          "Shared world is not using the OpenOneBlock void generator: "
              + specification.worldName());
    }
    if (world.canGenerateStructures()) {
      throw new IllegalStateException(
          "Shared world still permits natural structures: " + specification.worldName());
    }
  }

  private static void configure(World world) {
    world.setSpawnFlags(false, false);
    world.setAutoSave(true);
  }
}
