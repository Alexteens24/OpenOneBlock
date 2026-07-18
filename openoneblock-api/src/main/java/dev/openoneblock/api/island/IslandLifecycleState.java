package dev.openoneblock.api.island;

/** Persisted lifecycle state of an island. */
public enum IslandLifecycleState {
  /** Identity and slot reservation are being established. */
  ALLOCATING,
  /** World preparation may have begun. */
  CREATING,
  /** All activation invariants are verified and gameplay may run. */
  ACTIVE,
  /** Stable island is intentionally unavailable for maintenance. */
  LOCKED,
  /** Existing world state is being cleared and rebuilt. */
  RESETTING,
  /** Ownership is moving between slots or shard groups. */
  MIGRATING,
  /** World ownership is being cleaned before archival. */
  DELETING,
  /** Safety cannot currently be proven automatically. */
  BROKEN,
  /** Historical island no longer owning a world slot. */
  ARCHIVED
}
