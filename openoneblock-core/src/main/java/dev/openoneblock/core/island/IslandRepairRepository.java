package dev.openoneblock.core.island;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Authoritative transactional repair and startup-recovery boundary. */
public interface IslandRepairRepository {
  /**
   * Admits or exactly replays a repair intent while keeping the island fail-closed.
   *
   * @param request durable repair intent
   * @return verifying or terminal replay progress
   */
  CompletionStage<IslandRepairProgress> beginRepair(IslandRepairRequest request);

  /**
   * Revalidates all SQL invariants and commits either {@code LOCKED} or {@code BROKEN}.
   *
   * @param completion exact external evidence
   * @return terminal durable progress
   */
  CompletionStage<IslandRepairProgress> completeRepair(IslandRepairCompletion completion);

  /**
   * Returns every admitted repair still awaiting verification.
   *
   * @return deterministic immutable repair requests
   */
  CompletionStage<List<IslandRepairRequest>> findPendingRepairs();
}
