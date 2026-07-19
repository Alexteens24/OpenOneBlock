package dev.openoneblock.core.locator;

import java.util.List;

/** Result of atomically registering or verifying all configured world projections. */
public sealed interface WorldProjectionVerification {
  /**
   * All projections match and may form the runtime registry.
   *
   * @param projections authoritative verified rows
   */
  record Verified(List<PersistedWorldProjection> projections)
      implements WorldProjectionVerification {
    /** Defensively copies and validates runtime projection uniqueness. */
    public Verified {
      projections = List.copyOf(projections);
      new WorldProjectionRegistry(
          projections.stream()
              .map(PersistedWorldProjection::definition)
              .map(WorldProjectionDefinition::toRuntimeProjection)
              .toList());
    }

    /**
     * Builds the immutable O(1) runtime projection registry.
     *
     * @return verified runtime registry
     */
    public WorldProjectionRegistry toRuntimeRegistry() {
      return new WorldProjectionRegistry(
          projections.stream()
              .map(PersistedWorldProjection::definition)
              .map(WorldProjectionDefinition::toRuntimeProjection)
              .toList());
    }
  }

  /**
   * At least one mismatch blocks every projection from runtime publication.
   *
   * @param persisted authoritative rows observed during verification
   * @param drifts complete mismatch diagnostics
   */
  record Drifted(List<PersistedWorldProjection> persisted, List<WorldProjectionDrift> drifts)
      implements WorldProjectionVerification {
    /** Defensively copies drift diagnostics. */
    public Drifted {
      persisted = List.copyOf(persisted);
      drifts = List.copyOf(drifts);
      if (drifts.isEmpty()) {
        throw new IllegalArgumentException("drifts must not be empty");
      }
    }
  }
}
