package dev.openoneblock.core.world;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Authoritative durable journal which records effect intent before world dispatch. */
public interface WorldEffectJournal {
  /**
   * Registers or replays the exact immutable effect intent in {@code NOT_STARTED}.
   *
   * @param effect immutable intent
   * @param recordedAt receipt time
   * @return committed receipt
   */
  CompletionStage<WorldEffectReceipt> register(WorldEffectPlan effect, Instant recordedAt);

  /**
   * Atomically records dispatch before any platform work is submitted.
   *
   * @param effect exact registered intent
   * @param dispatchedAt dispatch time
   * @return committed dispatched receipt
   */
  CompletionStage<WorldEffectReceipt> markDispatched(WorldEffectPlan effect, Instant dispatchedAt);

  /**
   * Atomically records one verified terminal outcome.
   *
   * @param effect exact registered intent
   * @param outcome terminal evidence state
   * @param diagnostic stable evidence
   * @param completedAt completion time
   * @return committed terminal receipt
   */
  CompletionStage<WorldEffectReceipt> recordOutcome(
      WorldEffectPlan effect, WorldEffectState outcome, String diagnostic, Instant completedAt);

  /**
   * Finds one receipt by stable effect key.
   *
   * @param key effect key
   * @return optional receipt
   */
  CompletionStage<Optional<WorldEffectReceipt>> find(WorldEffectKey key);

  /**
   * Lists all effect evidence for an operation in stable index order.
   *
   * @param operationId parent operation
   * @return ordered immutable receipts
   */
  CompletionStage<List<WorldEffectReceipt>> findByOperation(
      dev.openoneblock.api.id.OperationId operationId);
}
