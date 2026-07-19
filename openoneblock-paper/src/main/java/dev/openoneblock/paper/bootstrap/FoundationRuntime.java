package dev.openoneblock.paper.bootstrap;

import dev.openoneblock.core.execution.IslandExecutionLanes;
import dev.openoneblock.core.island.IslandCreationRepository;
import dev.openoneblock.core.locator.InMemorySlotLocatorIndex;
import dev.openoneblock.core.locator.WorldProjectionRegistry;
import dev.openoneblock.paper.config.FoundationConfigurationSnapshot;
import java.util.Objects;

/**
 * Fully recovered immutable service graph published only when startup reaches {@code READY}.
 *
 * @param configuration active validated configuration
 * @param worldProjections verified world UUID registry
 * @param slotLocator rebuilt committed slot projection
 * @param islandRepository authoritative island persistence service
 * @param islandLanes sequential mutation lanes
 */
public record FoundationRuntime(
    FoundationConfigurationSnapshot configuration,
    WorldProjectionRegistry worldProjections,
    InMemorySlotLocatorIndex slotLocator,
    IslandCreationRepository islandRepository,
    IslandExecutionLanes islandLanes) {
  /** Validates the complete recovered service graph. */
  public FoundationRuntime {
    Objects.requireNonNull(configuration, "configuration");
    Objects.requireNonNull(worldProjections, "worldProjections");
    Objects.requireNonNull(slotLocator, "slotLocator");
    Objects.requireNonNull(islandRepository, "islandRepository");
    Objects.requireNonNull(islandLanes, "islandLanes");
  }
}
