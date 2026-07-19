package dev.openoneblock.protection;

import dev.openoneblock.api.id.IslandId;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Atomically published immutable island protection projections. */
public final class InMemoryIslandProtectionIndex {
  private final AtomicReference<Map<IslandId, IslandProtectionSnapshot>> snapshots;

  /** Creates an empty index. */
  public InMemoryIslandProtectionIndex() {
    this.snapshots = new AtomicReference<>(Map.of());
  }

  /**
   * Rebuilds an index and rejects duplicate island identities.
   *
   * @param snapshots complete committed projection set
   * @return rebuilt index
   */
  public static InMemoryIslandProtectionIndex rebuild(
      Collection<IslandProtectionSnapshot> snapshots) {
    Objects.requireNonNull(snapshots, "snapshots");
    InMemoryIslandProtectionIndex index = new InMemoryIslandProtectionIndex();
    Map<IslandId, IslandProtectionSnapshot> rebuilt = new HashMap<>();
    for (IslandProtectionSnapshot snapshot : snapshots) {
      Objects.requireNonNull(snapshot, "snapshot");
      if (rebuilt.putIfAbsent(snapshot.islandId(), snapshot) != null) {
        throw new IllegalArgumentException("duplicate protection snapshot: " + snapshot.islandId());
      }
    }
    index.snapshots.set(Map.copyOf(rebuilt));
    return index;
  }

  /**
   * Atomically replaces the complete projection registry after startup recovery or reload.
   *
   * @param replacement complete replacement set
   */
  public void replaceAll(Collection<IslandProtectionSnapshot> replacement) {
    InMemoryIslandProtectionIndex rebuilt = rebuild(replacement);
    snapshots.set(rebuilt.snapshots.get());
  }

  /**
   * Returns one immutable snapshot in constant expected time.
   *
   * @param islandId island identity
   * @return current snapshot if published
   */
  public Optional<IslandProtectionSnapshot> find(IslandId islandId) {
    Objects.requireNonNull(islandId, "islandId");
    return Optional.ofNullable(snapshots.get().get(islandId));
  }

  /**
   * Atomically publishes a newer or equal-version snapshot.
   *
   * @param snapshot committed replacement
   * @return whether publication was accepted
   */
  public boolean publish(IslandProtectionSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    while (true) {
      Map<IslandId, IslandProtectionSnapshot> current = snapshots.get();
      IslandProtectionSnapshot existing = current.get(snapshot.islandId());
      if (existing != null && existing.islandVersion() > snapshot.islandVersion()) {
        return false;
      }
      Map<IslandId, IslandProtectionSnapshot> replacement = new HashMap<>(current);
      replacement.put(snapshot.islandId(), snapshot);
      if (snapshots.compareAndSet(current, Map.copyOf(replacement))) {
        return true;
      }
    }
  }

  /**
   * Removes a projection only when the caller's version is not stale.
   *
   * @param islandId island identity
   * @param expectedMinimumVersion committed removal version
   * @return whether removal was accepted or already absent
   */
  public boolean remove(IslandId islandId, long expectedMinimumVersion) {
    Objects.requireNonNull(islandId, "islandId");
    while (true) {
      Map<IslandId, IslandProtectionSnapshot> current = snapshots.get();
      IslandProtectionSnapshot existing = current.get(islandId);
      if (existing == null) {
        return true;
      }
      if (existing.islandVersion() > expectedMinimumVersion) {
        return false;
      }
      Map<IslandId, IslandProtectionSnapshot> replacement = new HashMap<>(current);
      replacement.remove(islandId);
      if (snapshots.compareAndSet(current, Map.copyOf(replacement))) {
        return true;
      }
    }
  }

  /**
   * Returns the current projection count.
   *
   * @return projection count
   */
  public int size() {
    return snapshots.get().size();
  }
}
