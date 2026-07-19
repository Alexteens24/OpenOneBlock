package dev.openoneblock.core.runtime;

/** Transient chunk/runtime state which is never treated as durable island lifecycle truth. */
public enum IslandRuntimeState {
  /** No runtime entry or chunk ticket is retained. */
  UNLOADED,

  /** Required chunks and tickets are being acquired. */
  PREPARING,

  /** At least one activity reason owns the prepared runtime. */
  ACTIVE,

  /** The final activity was released and unloading is about to begin. */
  IDLE,

  /** Tickets are being released and new activity waits for completion. */
  UNLOADING
}
