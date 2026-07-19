package dev.openoneblock.core.world;

import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.island.IslandAggregateSnapshot;
import dev.openoneblock.core.slot.SlotState;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Durable-before-dispatch coordinator for ordered crash-recoverable world preparation. */
public final class WorldPreparationCoordinator {
  private final WorldEffectJournal journal;
  private final IslandWorldPreparation preparation;
  private final Clock clock;

  /**
   * Creates a coordinator around authoritative journal and platform ports.
   *
   * @param journal authoritative effect evidence
   * @param preparation platform effect provider
   * @param clock durable timestamp source
   */
  public WorldPreparationCoordinator(
      WorldEffectJournal journal, IslandWorldPreparation preparation, Clock clock) {
    this.journal = Objects.requireNonNull(journal, "journal");
    this.preparation = Objects.requireNonNull(preparation, "preparation");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Validates durable lifecycle ownership, then executes effects in stable order.
   *
   * @param island authoritative island snapshot
   * @param plan immutable complete preparation plan
   * @return operation-level verified outcome
   */
  public CompletionStage<WorldPreparationReport> prepare(
      IslandAggregateSnapshot island, IslandWorldPreparationPlan plan) {
    try {
      validateLifecycle(island, plan);
    } catch (RuntimeException exception) {
      return CompletableFuture.failedFuture(exception);
    }
    return process(plan, 0, new ArrayList<>(), false);
  }

  private CompletionStage<WorldPreparationReport> process(
      IslandWorldPreparationPlan plan,
      int index,
      List<WorldEffectReceipt> receipts,
      boolean cleanupRequired) {
    if (index == plan.effects().size()) {
      return CompletableFuture.completedFuture(
          new WorldPreparationReport(
              WorldPreparationReport.Status.VERIFIED_SUCCESS,
              receipts,
              false,
              "all world effects verified"));
    }
    WorldEffectPlan effect = plan.effects().get(index);
    return journal
        .register(effect, clock.instant())
        .thenCompose(
            receipt ->
                resumeEffect(effect, receipt)
                    .thenCompose(
                        outcome -> {
                          List<WorldEffectReceipt> nextReceipts = new ArrayList<>(receipts);
                          nextReceipts.add(outcome.receipt());
                          boolean nextCleanup =
                              cleanupRequired
                                  || outcome.cleanupRequired()
                                  || (effect.kind().mutatesWorld()
                                      && outcome.receipt().state() != WorldEffectState.NOT_STARTED);
                          if (outcome.receipt().state() == WorldEffectState.VERIFIED_SUCCESS) {
                            return process(plan, index + 1, nextReceipts, nextCleanup);
                          }
                          WorldPreparationReport.Status status =
                              outcome.receipt().state() == WorldEffectState.AMBIGUOUS
                                  ? WorldPreparationReport.Status.RECONCILIATION_REQUIRED
                                  : WorldPreparationReport.Status.VERIFIED_FAILURE;
                          return CompletableFuture.completedFuture(
                              new WorldPreparationReport(
                                  status,
                                  nextReceipts,
                                  nextCleanup,
                                  outcome.receipt().diagnostic().orElse("world effect failed")));
                        }));
  }

  private CompletionStage<ResolvedEffect> resumeEffect(
      WorldEffectPlan effect, WorldEffectReceipt receipt) {
    return switch (receipt.state()) {
      case VERIFIED_SUCCESS, VERIFIED_FAILURE, AMBIGUOUS ->
          CompletableFuture.completedFuture(new ResolvedEffect(receipt, false));
      case NOT_STARTED ->
          journal
              .markDispatched(effect, clock.instant())
              .thenCompose(dispatched -> execute(effect));
      case DISPATCHED -> recover(effect);
    };
  }

  private CompletionStage<ResolvedEffect> recover(WorldEffectPlan effect) {
    if (!effect.safety().automaticallyRecoverable()) {
      return complete(
          effect,
          WorldEffectState.AMBIGUOUS,
          "non-idempotent effect was dispatched without a verified outcome",
          true);
    }
    return preparation
        .verify(effect)
        .handle((outcome, failure) -> failureOutcome(outcome, failure, "verification"))
        .thenCompose(
            outcome ->
                outcome.status() == WorldEffectOutcome.Status.NOT_APPLIED
                    ? execute(effect)
                    : record(effect, outcome));
  }

  private CompletionStage<ResolvedEffect> execute(WorldEffectPlan effect) {
    return preparation
        .execute(effect)
        .handle((outcome, failure) -> failureOutcome(outcome, failure, "execution"))
        .thenCompose(
            outcome -> {
              if (outcome.status() == WorldEffectOutcome.Status.NOT_APPLIED) {
                return complete(
                    effect,
                    WorldEffectState.AMBIGUOUS,
                    "executor returned verification-only NOT_APPLIED outcome",
                    true);
              }
              return record(effect, outcome);
            });
  }

  private CompletionStage<ResolvedEffect> record(
      WorldEffectPlan effect, WorldEffectOutcome outcome) {
    WorldEffectState state =
        switch (outcome.status()) {
          case VERIFIED_SUCCESS -> WorldEffectState.VERIFIED_SUCCESS;
          case VERIFIED_FAILURE, NOT_APPLIED -> WorldEffectState.VERIFIED_FAILURE;
          case AMBIGUOUS -> WorldEffectState.AMBIGUOUS;
        };
    return complete(effect, state, outcome.diagnostic(), outcome.cleanupRequired());
  }

  private CompletionStage<ResolvedEffect> complete(
      WorldEffectPlan effect, WorldEffectState state, String diagnostic, boolean cleanupRequired) {
    return journal
        .recordOutcome(effect, state, diagnostic, clock.instant())
        .thenApply(receipt -> new ResolvedEffect(receipt, cleanupRequired));
  }

  private static WorldEffectOutcome failureOutcome(
      WorldEffectOutcome outcome, Throwable failure, String stage) {
    if (failure == null) {
      return Objects.requireNonNull(outcome, "world effect outcome");
    }
    Throwable cause = unwrap(failure);
    return new WorldEffectOutcome(
        WorldEffectOutcome.Status.AMBIGUOUS,
        true,
        stage + " threw before a verified outcome: " + cause.getClass().getSimpleName());
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable current = failure;
    while (current instanceof CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static void validateLifecycle(
      IslandAggregateSnapshot island, IslandWorldPreparationPlan plan) {
    Objects.requireNonNull(island, "island");
    Objects.requireNonNull(plan, "plan");
    var slot = island.primarySlot().orElseThrow();
    boolean preparationLifecycle =
        island.lifecycleState() == IslandLifecycleState.CREATING
            || island.lifecycleState() == IslandLifecycleState.RESETTING;
    if (!island.islandId().equals(plan.islandId())
        || !preparationLifecycle
        || island.version() != plan.expectedIslandVersion()
        || island.pendingOperationId().isEmpty()
        || !island.pendingOperationId().orElseThrow().equals(plan.operationId())
        || !slot.slotId().equals(plan.slotId())
        || slot.state() != SlotState.PREPARING
        || slot.version() != plan.expectedSlotVersion()) {
      throw new IllegalStateException(
          "world preparation requires matching CREATING-or-RESETTING/PREPARING durable state");
    }
  }

  private record ResolvedEffect(WorldEffectReceipt receipt, boolean cleanupRequired) {}
}
