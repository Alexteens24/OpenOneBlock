package dev.openoneblock.paper.world;

import dev.openoneblock.core.world.IslandStructurePlacement;
import dev.openoneblock.core.world.WorldEffectOutcome;
import dev.openoneblock.core.world.WorldEffectPlan;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Fail-closed structure port used until a validated WorldEdit bridge is registered. */
public final class UnavailableIslandStructurePlacement implements IslandStructurePlacement {
  /** Creates a stateless fail-closed provider. */
  public UnavailableIslandStructurePlacement() {}

  /** {@inheritDoc} */
  @Override
  public CompletionStage<WorldEffectOutcome> execute(WorldEffectPlan.PlaceStructure effect) {
    return CompletableFuture.completedFuture(
        new WorldEffectOutcome(
            WorldEffectOutcome.Status.VERIFIED_FAILURE,
            false,
            "no validated structure provider is registered"));
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<WorldEffectOutcome> verify(WorldEffectPlan.PlaceStructure effect) {
    return CompletableFuture.completedFuture(
        new WorldEffectOutcome(
            WorldEffectOutcome.Status.AMBIGUOUS,
            true,
            "structure provider is unavailable and prior placement cannot be verified"));
  }
}
