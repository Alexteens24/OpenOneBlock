package dev.openoneblock.core.locator;

/** Durable world projection verification state. */
public enum WorldProjectionState {
  /** Persisted identity matches the provisioned world observation. */
  VERIFIED,

  /** An operator or recovery workflow explicitly blocked this projection. */
  BLOCKED
}
