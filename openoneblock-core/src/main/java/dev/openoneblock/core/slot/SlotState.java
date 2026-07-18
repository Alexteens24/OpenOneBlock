package dev.openoneblock.core.slot;

/** Persisted lifecycle state of a logical grid slot. */
public enum SlotState {
  /** Available for allocation and without an owner. */
  FREE,
  /** Durably assigned before world work begins. */
  RESERVED,
  /** World projections are being prepared or reset. */
  PREPARING,
  /** Verified world slot serving an active island. */
  ACTIVE,
  /** World projections are being cleaned before release. */
  CLEANING,
  /** Cleanup or ownership could not be proven safe. */
  QUARANTINED
}
