package dev.openoneblock.paper.bootstrap;

import dev.openoneblock.core.execution.IslandExecutionLanes;
import dev.openoneblock.core.island.CreateIslandService;
import dev.openoneblock.core.island.DeleteIslandService;
import dev.openoneblock.core.island.IslandCreationRepository;
import dev.openoneblock.core.island.IslandDeletionRepository;
import dev.openoneblock.core.island.IslandHomeService;
import dev.openoneblock.core.island.IslandInspectionService;
import dev.openoneblock.core.island.IslandQueryRepository;
import dev.openoneblock.core.island.IslandRepairRepository;
import dev.openoneblock.core.island.IslandResetRepository;
import dev.openoneblock.core.island.RepairIslandService;
import dev.openoneblock.core.island.ResetIslandService;
import dev.openoneblock.core.locator.InMemorySlotLocatorIndex;
import dev.openoneblock.core.locator.WorldProjectionRegistry;
import dev.openoneblock.core.operation.IslandOperationQueryRepository;
import dev.openoneblock.core.runtime.IslandRuntimeManager;
import dev.openoneblock.core.team.IslandInvitationRepository;
import dev.openoneblock.core.team.IslandMemberRepository;
import dev.openoneblock.core.team.IslandRoleRegistry;
import dev.openoneblock.core.team.IslandTeamService;
import dev.openoneblock.core.world.WorldEffectJournal;
import dev.openoneblock.core.world.WorldPreparationCoordinator;
import dev.openoneblock.paper.config.FoundationConfigurationSnapshot;
import dev.openoneblock.protection.InMemoryIslandProtectionIndex;
import dev.openoneblock.protection.ProtectionEngine;
import java.util.Objects;

/**
 * Fully recovered immutable service graph published only when startup reaches {@code READY}.
 *
 * @param configuration active validated configuration
 * @param worldProjections verified world UUID registry
 * @param slotLocator rebuilt committed slot projection
 * @param protectionIndex immutable hot-path island protection projections
 * @param protection native event-independent protection engine
 * @param islandRoles compiled application-service role registry
 * @param islandMembers authoritative immutable membership queries
 * @param islandInvitations authoritative immutable pending-invitation queries
 * @param islandTeam serialized transactional membership mutations
 * @param islandRepository authoritative island persistence service
 * @param islandLanes sequential mutation lanes
 * @param islandRuntimes reference-counted island chunk lifecycle manager
 * @param worldEffects authoritative durable world-effect evidence
 * @param worldPreparation durable-before-dispatch preparation coordinator
 * @param islandCreation complete idempotent island creation application service
 * @param islandQueries asynchronous immutable player command projections
 * @param islandHome verified safe-home application service
 * @param islandInspection non-loading admin inspection service
 * @param islandOperations non-loading durable operation diagnostics
 * @param islandDeletionRepository authoritative deletion/recovery persistence
 * @param islandDeletion crash-safe verified deletion service
 * @param islandRepairRepository authoritative repair/recovery persistence
 * @param islandRepair verified broken-to-locked repair service
 * @param islandResetRepository authoritative reset/recovery persistence
 * @param islandReset crash-safe verified reset service
 */
public record FoundationRuntime(
    FoundationConfigurationSnapshot configuration,
    WorldProjectionRegistry worldProjections,
    InMemorySlotLocatorIndex slotLocator,
    InMemoryIslandProtectionIndex protectionIndex,
    ProtectionEngine protection,
    IslandRoleRegistry islandRoles,
    IslandMemberRepository islandMembers,
    IslandInvitationRepository islandInvitations,
    IslandTeamService islandTeam,
    IslandCreationRepository islandRepository,
    IslandExecutionLanes islandLanes,
    IslandRuntimeManager islandRuntimes,
    WorldEffectJournal worldEffects,
    WorldPreparationCoordinator worldPreparation,
    CreateIslandService islandCreation,
    IslandQueryRepository islandQueries,
    IslandHomeService islandHome,
    IslandInspectionService islandInspection,
    IslandOperationQueryRepository islandOperations,
    IslandDeletionRepository islandDeletionRepository,
    DeleteIslandService islandDeletion,
    IslandRepairRepository islandRepairRepository,
    RepairIslandService islandRepair,
    IslandResetRepository islandResetRepository,
    ResetIslandService islandReset) {
  /** Validates the complete recovered service graph. */
  public FoundationRuntime {
    Objects.requireNonNull(configuration, "configuration");
    Objects.requireNonNull(worldProjections, "worldProjections");
    Objects.requireNonNull(slotLocator, "slotLocator");
    Objects.requireNonNull(protectionIndex, "protectionIndex");
    Objects.requireNonNull(protection, "protection");
    Objects.requireNonNull(islandRoles, "islandRoles");
    Objects.requireNonNull(islandMembers, "islandMembers");
    Objects.requireNonNull(islandInvitations, "islandInvitations");
    Objects.requireNonNull(islandTeam, "islandTeam");
    Objects.requireNonNull(islandRepository, "islandRepository");
    Objects.requireNonNull(islandLanes, "islandLanes");
    Objects.requireNonNull(islandRuntimes, "islandRuntimes");
    Objects.requireNonNull(worldEffects, "worldEffects");
    Objects.requireNonNull(worldPreparation, "worldPreparation");
    Objects.requireNonNull(islandCreation, "islandCreation");
    Objects.requireNonNull(islandQueries, "islandQueries");
    Objects.requireNonNull(islandHome, "islandHome");
    Objects.requireNonNull(islandInspection, "islandInspection");
    Objects.requireNonNull(islandOperations, "islandOperations");
    Objects.requireNonNull(islandDeletionRepository, "islandDeletionRepository");
    Objects.requireNonNull(islandDeletion, "islandDeletion");
    Objects.requireNonNull(islandRepairRepository, "islandRepairRepository");
    Objects.requireNonNull(islandRepair, "islandRepair");
    Objects.requireNonNull(islandResetRepository, "islandResetRepository");
    Objects.requireNonNull(islandReset, "islandReset");
  }
}
