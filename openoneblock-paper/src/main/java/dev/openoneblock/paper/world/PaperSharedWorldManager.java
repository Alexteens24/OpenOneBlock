package dev.openoneblock.paper.world;

import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.locator.WorldProjection;
import dev.openoneblock.core.platform.PlatformTaskScheduler;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.bukkit.World;

/** Provisions shared dimension worlds exclusively through global-region scheduler ownership. */
public final class PaperSharedWorldManager {
  private final PlatformTaskScheduler scheduler;
  private final PaperVoidWorldFactory worldFactory;

  /**
   * Creates the world manager.
   *
   * @param scheduler ownership-aware platform scheduler
   * @param worldFactory global-region world creation adapter
   */
  public PaperSharedWorldManager(
      PlatformTaskScheduler scheduler, PaperVoidWorldFactory worldFactory) {
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.worldFactory = Objects.requireNonNull(worldFactory, "worldFactory");
  }

  /**
   * Creates or verifies one shared world and returns its immutable locator projection.
   *
   * @param specification validated world configuration
   * @return full global-region provisioning completion
   */
  public CompletionStage<ProvisionedSharedWorld> provision(SharedWorldSpec specification) {
    Objects.requireNonNull(specification, "specification");
    return scheduler.global(
        () -> {
          World world = worldFactory.createOrLoad(specification);
          WorldProjection projection =
              new WorldProjection(
                  WorldId.of(world.getUID()),
                  specification.shardGroupId(),
                  specification.dimensionId());
          return new ProvisionedSharedWorld(world, projection);
        });
  }
}
