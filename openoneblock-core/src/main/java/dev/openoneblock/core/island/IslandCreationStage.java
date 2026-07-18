package dev.openoneblock.core.island;

/** Durable state-machine step in the island creation pipeline. */
public enum IslandCreationStage {
  /** World preparation is about to begin after the allocation transaction. */
  BEGIN_PREPARATION,
  /** Every required world invariant was verified and gameplay may be enabled. */
  ACTIVATE
}
