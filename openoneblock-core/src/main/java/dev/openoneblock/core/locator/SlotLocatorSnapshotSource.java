package dev.openoneblock.core.locator;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Startup port that reads the authoritative minimal projection of every non-free slot. */
@FunctionalInterface
public interface SlotLocatorSnapshotSource {
  /**
   * Loads one internally consistent committed snapshot without retaining database resources.
   *
   * @return immutable non-free slot entries
   */
  CompletionStage<List<SlotLocatorEntry>> loadCommittedEntries();
}
