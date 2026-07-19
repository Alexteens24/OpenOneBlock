package dev.openoneblock.core.locator;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.ShardGroupId;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe O(1) runtime projection containing no island aggregates or chunk state. */
public final class InMemorySlotLocatorIndex implements CommittedSlotPublisher {
  private final ConcurrentMap<Key, SlotLocatorLookup> entries = new ConcurrentHashMap<>();

  /** Creates an empty runtime locator projection. */
  public InMemorySlotLocatorIndex() {}

  /**
   * Rebuilds a new isolated index from one authoritative startup snapshot.
   *
   * <p>Contradictory entries remain represented as fail-closed conflicted cells.
   *
   * @param entries database-committed non-free entries
   * @return fully built index suitable for publication to event adapters
   */
  public static InMemorySlotLocatorIndex rebuild(Collection<SlotLocatorEntry> entries) {
    Objects.requireNonNull(entries, "entries");
    InMemorySlotLocatorIndex rebuilt = new InMemorySlotLocatorIndex();
    entries.forEach(rebuilt::publishCommitted);
    return rebuilt;
  }

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

  /** {@inheritDoc} */
  @Override
  public LocatorRemovalDecision removeCommitted(SlotLocatorEntry releasedEntry) {
    Objects.requireNonNull(releasedEntry, "releasedEntry");
    Key key = new Key(releasedEntry.shardGroupId(), releasedEntry.gridPosition());
    AtomicReference<LocatorRemovalDecision> decision = new AtomicReference<>();
    entries.compute(
        key,
        (ignored, current) -> {
          if (current == null || current instanceof SlotLocatorLookup.Empty) {
            decision.set(LocatorRemovalDecision.ALREADY_ABSENT);
            return null;
          }
          if (current instanceof SlotLocatorLookup.Conflicted) {
            decision.set(LocatorRemovalDecision.CONFLICTED);
            return current;
          }
          SlotLocatorEntry existing = ((SlotLocatorLookup.Resolved) current).entry();
          if (!sameOwnership(existing, releasedEntry)
              || existing.slotVersion() > releasedEntry.slotVersion()) {
            decision.set(LocatorRemovalDecision.CONFLICTED);
            return current;
          }
          decision.set(LocatorRemovalDecision.APPLIED);
          return null;
        });
    return Objects.requireNonNull(decision.get(), "locator removal decision");
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
