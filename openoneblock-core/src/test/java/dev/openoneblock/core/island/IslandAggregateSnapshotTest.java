package dev.openoneblock.core.island;

import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.slot.AllocatedSlot;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotState;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IslandAggregateSnapshotTest {
  private static final Instant NOW = Instant.parse("2026-07-19T02:00:00Z");

  @Test
  void rejectsSlotOwnedByAnotherIsland() {
    IslandId islandId = IslandId.generate();
    AllocatedSlot foreignSlot = slot(IslandId.generate());

    assertThrows(
        IllegalArgumentException.class,
        () -> snapshot(islandId, IslandLifecycleState.ALLOCATING, Optional.of(foreignSlot)));
  }

  @Test
  void enforcesArchivedSlotRelationship() {
    IslandId islandId = IslandId.generate();

    assertThrows(
        IllegalArgumentException.class,
        () -> snapshot(islandId, IslandLifecycleState.ARCHIVED, Optional.of(slot(islandId))));
    assertThrows(
        IllegalArgumentException.class,
        () -> snapshot(islandId, IslandLifecycleState.ACTIVE, Optional.empty()));
  }

  private static IslandAggregateSnapshot snapshot(
      IslandId islandId, IslandLifecycleState state, Optional<AllocatedSlot> primarySlot) {
    return new IslandAggregateSnapshot(
        islandId,
        PlayerId.of(UUID.fromString("d44f82c8-60d2-4a92-855c-ec5f35582efd")),
        state,
        primarySlot,
        64,
        384,
        0,
        Optional.of(OperationId.generate()),
        NOW,
        NOW);
  }

  private static AllocatedSlot slot(IslandId owner) {
    return new AllocatedSlot(
        SlotId.generate(),
        ShardGroupId.parse("openoneblock:primary"),
        0,
        new GridPosition(0, 0),
        SlotState.RESERVED,
        owner,
        0);
  }
}
