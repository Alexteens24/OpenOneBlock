package dev.openoneblock.core.locator;

/** Exact reason startup refused to trust configured or provisioned world identity. */
public enum WorldProjectionDriftKind {
  /** Configuration expects a projection absent from persistence. */
  MISSING_PERSISTED_PROJECTION,

  /** Persistence contains a projection no longer present in configuration. */
  UNCONFIGURED_PERSISTED_PROJECTION,

  /** Configured world name differs from the authoritative row. */
  WORLD_NAME_CHANGED,

  /** Provisioned world UUID differs from the authoritative row. */
  WORLD_ID_CHANGED,

  /** Provisioned environment differs from the authoritative row. */
  ENVIRONMENT_CHANGED,

  /** Grid or build policy fingerprint differs from the authoritative row. */
  GEOMETRY_FINGERPRINT_CHANGED,

  /** An explicitly blocked projection cannot enter the runtime registry. */
  PROJECTION_BLOCKED
}
