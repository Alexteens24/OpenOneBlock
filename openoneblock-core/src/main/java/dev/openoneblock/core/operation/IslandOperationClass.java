package dev.openoneblock.core.operation;

/** Classifies how an island operation interacts with gameplay admission. */
public enum IslandOperationClass {
  /** Normal gameplay that requires an active island and an open gameplay gate. */
  GAMEPLAY,
  /** Serialized domain mutation that does not close gameplay admission by itself. */
  MUTATION,
  /** Maintenance operation that closes gameplay admission as soon as it is accepted. */
  LOCKING
}
