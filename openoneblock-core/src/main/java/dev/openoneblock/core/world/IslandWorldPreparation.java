package dev.openoneblock.core.world;

import java.util.concurrent.CompletionStage;

/** Platform port for executing and inspecting one bounded preparation effect. */
public interface IslandWorldPreparation {
  /**
   * Executes a durably dispatched effect and returns only after explicit verification.
   *
   * @param effect immutable effect plan
   * @return explicit execution outcome
   */
  CompletionStage<WorldEffectOutcome> execute(WorldEffectPlan effect);

  /**
   * Inspects a previously dispatched recoverable effect without blindly reapplying it.
   *
   * @param effect immutable effect plan
   * @return explicit recovery evidence
   */
  CompletionStage<WorldEffectOutcome> verify(WorldEffectPlan effect);
}
