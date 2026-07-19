package dev.openoneblock.core.runtime;

/** Bounded reasons that may temporarily retain an island's required chunks. */
public enum IslandActivityReason {
  /** At least one player is present on the island. */
  ONLINE_PLAYER,

  /** Creation, reset, or migration is preparing world state. */
  WORLD_PREPARATION,

  /** Verified cleanup is clearing blocks or entities. */
  CLEANUP,

  /** A Magic Block regeneration effect is pending. */
  MAGIC_BLOCK_REGENERATION,

  /** A scheduled rule or world action is imminent. */
  SCHEDULED_WORLD_ACTION,

  /** An admin inspection explicitly requires loaded chunks. */
  ADMIN_INSPECTION
}
