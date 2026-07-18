package dev.openoneblock.core.operation;

/** Result of checking immutable operation metadata against an island snapshot header. */
public enum OperationPreconditionDecision {
  /** Identity, version, and generic lifecycle requirements match. */
  ALLOWED,
  /** Request and snapshot refer to different islands. */
  ISLAND_MISMATCH,
  /** The caller observed a different aggregate version. */
  VERSION_MISMATCH,
  /** Gameplay was requested while the island was not active. */
  GAMEPLAY_REQUIRES_ACTIVE
}
