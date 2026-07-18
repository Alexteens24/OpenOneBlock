package dev.openoneblock.core.execution;

/** Synchronous reason why a lane did not accept submitted work. */
public enum LaneRejectionReason {
  /** Registry shutdown has begun and no new work is accepted. */
  SHUTTING_DOWN,
  /** A locking operation has closed gameplay admission for the current lane. */
  GAMEPLAY_GATED,
  /** The configured per-island in-flight limit has been reached. */
  QUEUE_FULL
}
