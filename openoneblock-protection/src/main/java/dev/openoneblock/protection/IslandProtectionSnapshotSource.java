package dev.openoneblock.protection;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Asynchronous persistence port used to rebuild protection projections before publication. */
@FunctionalInterface
public interface IslandProtectionSnapshotSource {
  /**
   * Loads every non-archived island protection projection from authoritative persistence.
   *
   * @return asynchronous immutable projection list
   */
  CompletionStage<List<IslandProtectionSnapshot>> loadCommittedSnapshots();
}
