package dev.openoneblock.paper.bootstrap;

import dev.openoneblock.core.execution.IslandExecutionLanes;
import dev.openoneblock.core.island.CreateIslandService;
import dev.openoneblock.core.island.DeleteIslandService;
import dev.openoneblock.core.island.IslandCreationRepository;
import dev.openoneblock.core.island.IslandDeletionRepository;
import dev.openoneblock.core.island.IslandHomeService;
import dev.openoneblock.core.island.IslandQueryRepository;
import dev.openoneblock.core.island.IslandResetRepository;
import dev.openoneblock.core.island.ResetIslandService;
import dev.openoneblock.core.locator.InMemorySlotLocatorIndex;
import dev.openoneblock.core.locator.WorldProjectionRegistry;
import dev.openoneblock.core.runtime.IslandRuntimeManager;
import dev.openoneblock.core.world.WorldEffectJournal;
import dev.openoneblock.core.world.WorldPreparationCoordinator;
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
 * @param islandRuntimes reference-counted island chunk lifecycle manager
 * @param worldEffects authoritative durable world-effect evidence
 * @param worldPreparation durable-before-dispatch preparation coordinator
 * @param islandCreation complete idempotent island creation application service
 * @param islandQueries asynchronous immutable player command projections
 * @param islandHome verified safe-home application service
 * @param islandDeletionRepository authoritative deletion/recovery persistence
 * @param islandDeletion crash-safe verified deletion service
 * @param islandResetRepository authoritative reset/recovery persistence
 * @param islandReset crash-safe verified reset service
 */
public record FoundationRuntime(
    FoundationConfigurationSnapshot configuration,
    WorldProjectionRegistry worldProjections,
    InMemorySlotLocatorIndex slotLocator,
    IslandCreationRepository islandRepository,
    IslandExecutionLanes islandLanes,
    IslandRuntimeManager islandRuntimes,
    WorldEffectJournal worldEffects,
    WorldPreparationCoordinator worldPreparation,
    CreateIslandService islandCreation,
    IslandQueryRepository islandQueries,
    IslandHomeService islandHome,
    IslandDeletionRepository islandDeletionRepository,
    DeleteIslandService islandDeletion,
    IslandResetRepository islandResetRepository,
    ResetIslandService islandReset) {
  /** Validates the complete recovered service graph. */
  public FoundationRuntime {
    Objects.requireNonNull(configuration, "configuration");
    Objects.requireNonNull(worldProjections, "worldProjections");
    Objects.requireNonNull(slotLocator, "slotLocator");
    Objects.requireNonNull(islandRepository, "islandRepository");
    Objects.requireNonNull(islandLanes, "islandLanes");
    Objects.requireNonNull(islandRuntimes, "islandRuntimes");
    Objects.requireNonNull(worldEffects, "worldEffects");
    Objects.requireNonNull(worldPreparation, "worldPreparation");
    Objects.requireNonNull(islandCreation, "islandCreation");
    Objects.requireNonNull(islandQueries, "islandQueries");
    Objects.requireNonNull(islandHome, "islandHome");
    Objects.requireNonNull(islandDeletionRepository, "islandDeletionRepository");
    Objects.requireNonNull(islandDeletion, "islandDeletion");
    Objects.requireNonNull(islandResetRepository, "islandResetRepository");
    Objects.requireNonNull(islandReset, "islandReset");
  }
}
