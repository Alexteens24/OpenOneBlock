package dev.openoneblock.core.world;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.grid.HorizontalBounds;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Platform port for verified cell cleanup; uncertain results must lead to quarantine. */
public interface IslandCleanup {
  /**
   * Clears and verifies one bounded dimension projection.
   *
   * @param plan immutable cleanup target
   * @return explicit verified cleanup outcome
   */
  CompletionStage<Result> cleanup(Plan plan);

  /**
   * Immutable cleanup target.
   *
   * @param operationId cleanup operation
   * @param islandId owning island
   * @param worldId verified world
   * @param bounds horizontal cleanup bounds
   * @param minimumY inclusive minimum Y
   * @param maximumYExclusive exclusive maximum Y
   */
  record Plan(
      OperationId operationId,
      IslandId islandId,
      WorldId worldId,
      HorizontalBounds bounds,
      int minimumY,
      int maximumYExclusive) {
    /** Validates cleanup bounds. */
    public Plan {
      Objects.requireNonNull(operationId, "operationId");
      Objects.requireNonNull(islandId, "islandId");
      Objects.requireNonNull(worldId, "worldId");
      Objects.requireNonNull(bounds, "bounds");
      if (minimumY >= maximumYExclusive) {
        throw new IllegalArgumentException("cleanup height must not be empty");
      }
    }
  }

  /**
   * Explicit cleanup verification result.
   *
   * @param status cleanup outcome
   * @param diagnostic stable evidence
   */
  record Result(Status status, String diagnostic) {
    /** Validates a useful diagnostic. */
    public Result {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(diagnostic, "diagnostic");
      if (diagnostic.isBlank()) {
        throw new IllegalArgumentException("cleanup diagnostic must not be blank");
      }
    }
  }

  /** Cleanup outcomes used by release/quarantine policy. */
  enum Status {
    /** Every configured cleanup invariant was verified. */
    VERIFIED_CLEAN,
    /** Cleanup provably failed. */
    VERIFIED_FAILURE,
    /** Partial or uncertain cleanup requires quarantine. */
    AMBIGUOUS
  }
}
