package dev.openoneblock.core.operation;

/** Stable outcome vocabulary for durable operational audit entries. */
public enum AuditOutcome {
  /** Work was accepted and is about to execute. */
  STARTED,
  /** Work completed with a verified result. */
  SUCCEEDED,
  /** Work failed without sufficient evidence of success. */
  FAILED,
  /** External or world state cannot be proven and needs reconciliation. */
  AMBIGUOUS,
  /** Preconditions rejected work before it could execute. */
  REJECTED
}
