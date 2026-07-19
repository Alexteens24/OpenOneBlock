package dev.openoneblock.paper.world;

import dev.openoneblock.core.locator.WorldProjection;
import java.util.Objects;
import org.bukkit.World;

/**
 * Loaded Paper world paired with its immutable core projection metadata.
 *
 * @param world loaded platform world
 * @param projection world UUID to shard/dimension projection
 */
public record ProvisionedSharedWorld(World world, WorldProjection projection) {
  /** Validates provisioned world metadata. */
  public ProvisionedSharedWorld {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(projection, "projection");
    if (!world.getUID().equals(projection.worldId().value())) {
      throw new IllegalArgumentException("Paper world UUID does not match core projection");
    }
  }
}
