package dev.openoneblock.core.locator;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.ShardGroupId;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe O(1) runtime projection containing no island aggregates or chunk state. */
public final class InMemorySlotLocatorIndex implements CommittedSlotPublisher {
  private final ConcurrentMap<Key, SlotLocatorLookup> entries = new ConcurrentHashMap<>();

  /** Creates an empty runtime locator projection. */
  public InMemorySlotLocatorIndex() {}

  /** {@inheritDoc} */
  @Override
  public LocatorPublishDecision publishCommitted(SlotLocatorEntry entry) {
    Objects.requireNonNull(entry, "entry");
    Key key = new Key(entry.shardGroupId(), entry.gridPosition());
    AtomicReference<LocatorPublishDecision> decision = new AtomicReference<>();
    entries.compute(
        key,
        (ignored, current) -> {
          if (current == null || current instanceof SlotLocatorLookup.Empty) {
            decision.set(LocatorPublishDecision.APPLIED);
            return new SlotLocatorLookup.Resolved(entry);
          }
          if (current instanceof SlotLocatorLookup.Conflicted) {
            decision.set(LocatorPublishDecision.CONFLICTED);
            return current;
          }
          SlotLocatorEntry existing = ((SlotLocatorLookup.Resolved) current).entry();
          if (!sameOwnership(existing, entry)) {
            decision.set(LocatorPublishDecision.CONFLICTED);
            return new SlotLocatorLookup.Conflicted(existing, entry);
          }
          if (entry.slotVersion() < existing.slotVersion()
              || (entry.slotVersion() == existing.slotVersion()
                  && entry.slotState() == existing.slotState())) {
            decision.set(LocatorPublishDecision.STALE_IGNORED);
            return current;
          }
          if (entry.slotVersion() == existing.slotVersion()) {
            decision.set(LocatorPublishDecision.CONFLICTED);
            return new SlotLocatorLookup.Conflicted(existing, entry);
          }
          decision.set(LocatorPublishDecision.APPLIED);
          return new SlotLocatorLookup.Resolved(entry);
        });
    return Objects.requireNonNull(decision.get(), "locator publication decision");
  }

  /**
   * Resolves one logical cell without database access or island scans.
   *
   * @param shardGroupId target shard group
   * @param gridPosition logical cell position
   * @return empty, resolved, or conflicted fail-closed lookup
   */
  public SlotLocatorLookup lookup(ShardGroupId shardGroupId, GridPosition gridPosition) {
    Objects.requireNonNull(shardGroupId, "shardGroupId");
    Objects.requireNonNull(gridPosition, "gridPosition");
    return entries.getOrDefault(new Key(shardGroupId, gridPosition), new SlotLocatorLookup.Empty());
  }

  /**
   * Returns the number of projected logical cells, including conflicted cells.
   *
   * @return projected cell count
   */
  public int size() {
    return entries.size();
  }

  private static boolean sameOwnership(SlotLocatorEntry first, SlotLocatorEntry second) {
    return first.slotId().equals(second.slotId()) && first.islandId().equals(second.islandId());
  }

  private record Key(ShardGroupId shardGroupId, GridPosition gridPosition) {}
}
