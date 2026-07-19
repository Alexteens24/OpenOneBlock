package dev.openoneblock.protection;

/** Tri-state result consumed by platform event adapters. */
public enum ProtectionOutcome {
  /** Explicitly permit the managed interaction. */
  ALLOW,
  /** Explicitly reject the interaction. */
  DENY,
  /** Leave an unmanaged interaction to other systems. */
  PASS
}
