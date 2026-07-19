package dev.openoneblock.core.world;

import java.util.concurrent.CompletionStage;

/** Optional structure-provider port kept independent from WorldEdit and FAWE APIs. */
public interface IslandStructurePlacement {
  /**
   * Places and verifies one registered structure effect.
   *
   * @param effect validated structure plan
   * @return explicit placement outcome
   */
  CompletionStage<WorldEffectOutcome> execute(WorldEffectPlan.PlaceStructure effect);

  /**
   * Inspects a previously dispatched structure effect using provider-owned evidence.
   *
   * @param effect validated structure plan
   * @return explicit recovery outcome
   */
  CompletionStage<WorldEffectOutcome> verify(WorldEffectPlan.PlaceStructure effect);
}
