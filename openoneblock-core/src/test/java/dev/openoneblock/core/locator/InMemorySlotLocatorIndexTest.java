package dev.openoneblock.core.locator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotState;
import org.junit.jupiter.api.Test;

class InMemorySlotLocatorIndexTest {
  private static final ShardGroupId SHARD = ShardGroupId.parse("openoneblock:primary");
  private static final GridPosition POSITION = new GridPosition(0, 0);

  @Test
  void publishesAndAdvancesOneCommittedOwnershipProjection() {
    InMemorySlotLocatorIndex index = new InMemorySlotLocatorIndex();
    SlotId slotId = SlotId.generate();
    IslandId islandId = IslandId.generate();
    SlotLocatorEntry reserved = entry(slotId, islandId, SlotState.RESERVED, 0);
    SlotLocatorEntry active = entry(slotId, islandId, SlotState.ACTIVE, 1);

    assertEquals(LocatorPublishDecision.APPLIED, index.publishCommitted(reserved));
    assertEquals(LocatorPublishDecision.STALE_IGNORED, index.publishCommitted(reserved));
    assertEquals(LocatorPublishDecision.APPLIED, index.publishCommitted(active));
    assertEquals(LocatorPublishDecision.STALE_IGNORED, index.publishCommitted(reserved));
    assertEquals(
        active,
        assertInstanceOf(SlotLocatorLookup.Resolved.class, index.lookup(SHARD, POSITION)).entry());
  }

  @Test
  void contradictoryOwnershipFailsClosedAndRemainsConflicted() {
    InMemorySlotLocatorIndex index = new InMemorySlotLocatorIndex();
    SlotLocatorEntry first = entry(SlotId.generate(), IslandId.generate(), SlotState.RESERVED, 0);
    SlotLocatorEntry conflicting =
        entry(SlotId.generate(), IslandId.generate(), SlotState.RESERVED, 0);

    assertEquals(LocatorPublishDecision.APPLIED, index.publishCommitted(first));
    assertEquals(LocatorPublishDecision.CONFLICTED, index.publishCommitted(conflicting));
    SlotLocatorLookup.Conflicted lookup =
        assertInstanceOf(SlotLocatorLookup.Conflicted.class, index.lookup(SHARD, POSITION));
    assertEquals(first, lookup.existing());
    assertEquals(conflicting, lookup.conflicting());
    assertEquals(LocatorPublishDecision.CONFLICTED, index.publishCommitted(first));
  }

  @Test
  void sameVersionWithDifferentStateFailsClosed() {
    InMemorySlotLocatorIndex index = new InMemorySlotLocatorIndex();
    SlotId slotId = SlotId.generate();
    IslandId islandId = IslandId.generate();

    index.publishCommitted(entry(slotId, islandId, SlotState.RESERVED, 3));

    assertEquals(
        LocatorPublishDecision.CONFLICTED,
        index.publishCommitted(entry(slotId, islandId, SlotState.PREPARING, 3)));
    assertInstanceOf(SlotLocatorLookup.Conflicted.class, index.lookup(SHARD, POSITION));
  }

  @Test
  void unknownCellsReturnEmptyWithoutGrowingTheIndex() {
    InMemorySlotLocatorIndex index = new InMemorySlotLocatorIndex();

    assertInstanceOf(SlotLocatorLookup.Empty.class, index.lookup(SHARD, POSITION));
    assertEquals(0, index.size());
  }

  private static SlotLocatorEntry entry(
      SlotId slotId, IslandId islandId, SlotState state, long version) {
    return new SlotLocatorEntry(SHARD, POSITION, slotId, islandId, state, version);
  }
}
