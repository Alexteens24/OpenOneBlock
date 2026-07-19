package dev.openoneblock.core.island;

import dev.openoneblock.core.locator.InMemorySlotLocatorIndex;
import dev.openoneblock.core.locator.SlotLocatorLookup;
import dev.openoneblock.core.locator.WorldProjection;
import dev.openoneblock.core.locator.WorldProjectionRegistry;
import dev.openoneblock.core.slot.SlotState;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Verifies repair ownership against already-verified startup projections without loading chunks.
 */
public final class RuntimeIslandRepairVerifier implements IslandRepairVerifier {
  private final InMemorySlotLocatorIndex locator;
  private final WorldProjectionRegistry worlds;
  private final Clock clock;

  /**
   * Creates the runtime repair verifier.
   *
   * @param locator committed O(1) slot projection
   * @param worlds verified world UUID registry
   * @param clock application clock
   */
  public RuntimeIslandRepairVerifier(
      InMemorySlotLocatorIndex locator, WorldProjectionRegistry worlds, Clock clock) {
    this.locator = Objects.requireNonNull(locator, "locator");
    this.worlds = Objects.requireNonNull(worlds, "worlds");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<IslandRepairEvidence> verify(
      IslandRepairRequest request, IslandAggregateSnapshot island) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(island, "island");
    var slot = island.primarySlot().orElseThrow();
    boolean exactAdmission =
        island.islandId().equals(request.islandId())
            && request.expectedIslandVersion() < Long.MAX_VALUE
            && island.version() == request.expectedIslandVersion() + 1
            && island.pendingOperationId().equals(java.util.Optional.of(request.operationId()))
            && slot.version() == request.expectedSlotVersion()
            && slot.state() == SlotState.QUARANTINED;
    if (!exactAdmission) {
      return evidence(
          IslandRepairEvidence.Status.FAILED,
          List.of(),
          "durable repair admission does not match the requested versions");
    }
    List<WorldProjection> projections = worlds.projectionsForShard(slot.shardGroupId());
    if (projections.isEmpty()) {
      return evidence(IslandRepairEvidence.Status.AMBIGUOUS, List.of(), "no verified shard worlds");
    }
    SlotLocatorLookup lookup = locator.lookup(slot.shardGroupId(), slot.gridPosition());
    if (lookup instanceof SlotLocatorLookup.Conflicted) {
      return evidence(
          IslandRepairEvidence.Status.AMBIGUOUS, List.of(), "runtime locator cell is conflicted");
    }
    if (!(lookup instanceof SlotLocatorLookup.Resolved resolved)) {
      return evidence(
          IslandRepairEvidence.Status.FAILED, List.of(), "runtime locator ownership is absent");
    }
    var entry = resolved.entry();
    boolean exact =
        entry.slotId().equals(slot.slotId())
            && entry.islandId().equals(island.islandId())
            && entry.slotState() == SlotState.QUARANTINED
            && entry.slotVersion() == slot.version();
    if (!exact) {
      return evidence(
          IslandRepairEvidence.Status.FAILED,
          List.of(),
          "runtime locator does not match quarantined database ownership");
    }
    return evidence(
        IslandRepairEvidence.Status.VERIFIED,
        projections.stream().map(WorldProjection::worldId).toList(),
        "locator and configured shard projections verified");
  }

  private CompletionStage<IslandRepairEvidence> evidence(
      IslandRepairEvidence.Status status,
      List<dev.openoneblock.api.id.WorldId> worlds,
      String diagnostic) {
    return CompletableFuture.completedFuture(
        new IslandRepairEvidence(status, worlds, diagnostic, clock.instant()));
  }
}
