package dev.openoneblock.core.operation;

/** Durable operator guidance for an operation that did not finish normally. */
public enum OperationRetryClassification {
  /** No retry is necessary because the operation completed successfully. */
  NONE,
  /** Startup recovery may safely resume the operation from durable evidence. */
  AUTOMATIC,
  /** An operator may explicitly retry after reviewing the operation. */
  MANUAL,
  /** World or external state must be reconciled before any retry. */
  RECONCILE
}
