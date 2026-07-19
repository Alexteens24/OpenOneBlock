package dev.openoneblock.persistence.sqlite.protection;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.protection.CommittedIslandProtectionPublisher;
import dev.openoneblock.protection.InMemoryIslandProtectionIndex;
import dev.openoneblock.protection.IslandProtectionSnapshot;
import java.util.Objects;

/** Synchronously refreshes one in-memory projection after its SQL transaction commits. */
public final class SqliteCommittedIslandProtectionPublisher
    implements CommittedIslandProtectionPublisher {
  private final SqliteIslandProtectionSnapshotSource source;
  private final InMemoryIslandProtectionIndex index;

  /**
   * Creates a post-commit publisher.
   *
   * @param source authoritative single-island snapshot source
   * @param index target hot-path index
   */
  public SqliteCommittedIslandProtectionPublisher(
      SqliteIslandProtectionSnapshotSource source, InMemoryIslandProtectionIndex index) {
    this.source = Objects.requireNonNull(source, "source");
    this.index = Objects.requireNonNull(index, "index");
  }

  /** {@inheritDoc} */
  @Override
  public void publishCommitted(IslandId islandId) {
    IslandProtectionSnapshot snapshot =
        source
            .loadCommittedSnapshot(islandId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Committed island projection is missing: " + islandId));
    if (!index.publish(snapshot)) {
      throw new IllegalStateException(
          "Committed island projection publication was stale: " + islandId);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void removeCommitted(IslandId islandId, long committedVersion) {
    if (!index.remove(islandId, committedVersion)) {
      throw new IllegalStateException("Committed island projection removal was stale: " + islandId);
    }
  }
}
