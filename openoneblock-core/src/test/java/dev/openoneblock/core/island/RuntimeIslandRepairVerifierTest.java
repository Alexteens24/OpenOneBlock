package dev.openoneblock.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.DimensionId;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.locator.InMemorySlotLocatorIndex;
import dev.openoneblock.core.locator.SlotLocatorEntry;
import dev.openoneblock.core.locator.WorldProjection;
import dev.openoneblock.core.locator.WorldProjectionRegistry;
import dev.openoneblock.core.slot.AllocatedSlot;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeIslandRepairVerifierTest {
  private static final Instant NOW = Instant.parse("2026-07-19T15:00:00Z");
  private static final ShardGroupId SHARD = ShardGroupId.parse("openoneblock:primary");
  private static final WorldId WORLD = WorldId.parse("00000000-0000-0000-0000-0000000000d2");

  @Test
  void exactQuarantinedProjectionIsVerifiedWithoutWorldLoading() {
    InMemorySlotLocatorIndex locator = new InMemorySlotLocatorIndex();
    IslandAggregateSnapshot island = snapshot(IslandId.generate());
    locator.publishCommitted(entry(island));

    IslandRepairEvidence evidence = verify(locator, island);

    assertEquals(IslandRepairEvidence.Status.VERIFIED, evidence.status());
    assertEquals(List.of(WORLD), evidence.verifiedWorldIds());
  }

  @Test
  void missingProjectionFailsClosed() {
    IslandAggregateSnapshot island = snapshot(IslandId.generate());

    IslandRepairEvidence evidence = verify(new InMemorySlotLocatorIndex(), island);

    assertEquals(IslandRepairEvidence.Status.FAILED, evidence.status());
  }

  @Test
  void conflictedProjectionIsAmbiguous() {
    InMemorySlotLocatorIndex locator = new InMemorySlotLocatorIndex();
    IslandAggregateSnapshot island = snapshot(IslandId.generate());
    locator.publishCommitted(entry(island));
    locator.publishCommitted(
        new SlotLocatorEntry(
            SHARD,
            new GridPosition(0, 0),
            SlotId.generate(),
            IslandId.generate(),
            SlotState.QUARANTINED,
            1));

    IslandRepairEvidence evidence = verify(locator, island);

    assertEquals(IslandRepairEvidence.Status.AMBIGUOUS, evidence.status());
  }

  private static IslandRepairEvidence verify(
      InMemorySlotLocatorIndex locator, IslandAggregateSnapshot island) {
    var worlds =
        new WorldProjectionRegistry(
            List.of(
                new WorldProjection(WORLD, SHARD, DimensionId.parse("openoneblock:overworld"))));
    var verifier =
        new RuntimeIslandRepairVerifier(locator, worlds, Clock.fixed(NOW, ZoneOffset.UTC));
    return verifier.verify(request(island), island).toCompletableFuture().join();
  }

  private static IslandRepairRequest request(IslandAggregateSnapshot island) {
    return new IslandRepairRequest(
        island.islandId(),
        island.pendingOperationId().orElseThrow(),
        PlayerId.of(UUID.randomUUID()),
        island.version() - 1,
        island.primarySlot().orElseThrow().version(),
        -64,
        320,
        NOW);
  }

  private static IslandAggregateSnapshot snapshot(IslandId islandId) {
    AllocatedSlot slot =
        new AllocatedSlot(
            SlotId.generate(),
            SHARD,
            0,
            new GridPosition(0, 0),
            SlotState.QUARANTINED,
            islandId,
            4);
    return new IslandAggregateSnapshot(
        islandId,
        PlayerId.of(UUID.randomUUID()),
        IslandLifecycleState.BROKEN,
        Optional.of(slot),
        64,
        384,
        8,
        Optional.of(OperationId.generate()),
        NOW.minusSeconds(60),
        NOW);
  }

  private static SlotLocatorEntry entry(IslandAggregateSnapshot island) {
    AllocatedSlot slot = island.primarySlot().orElseThrow();
    return new SlotLocatorEntry(
        slot.shardGroupId(),
        slot.gridPosition(),
        slot.slotId(),
        island.islandId(),
        slot.state(),
        slot.version());
  }
}
