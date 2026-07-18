package dev.openoneblock.core.operation;

import dev.openoneblock.api.island.IslandLifecycleState;
import java.util.Objects;

/** Generic identity, optimistic-version, and gameplay lifecycle preconditions. */
public final class IslandOperationPreconditions {
  private IslandOperationPreconditions() {}

  /**
   * Evaluates request metadata against a snapshot without mutating either value.
   *
   * @param request submitted operation metadata
   * @param snapshot current immutable island header
   * @return the first failed precondition, or {@code ALLOWED}
   */
  public static OperationPreconditionDecision evaluate(
      IslandOperationRequest request, IslandStateSnapshot snapshot) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(snapshot, "snapshot");
    if (!request.islandId().equals(snapshot.islandId())) {
      return OperationPreconditionDecision.ISLAND_MISMATCH;
    }
    if (request.expectedVersion() != snapshot.version()) {
      return OperationPreconditionDecision.VERSION_MISMATCH;
    }
    if (request.operationClass() == IslandOperationClass.GAMEPLAY
        && snapshot.lifecycleState() != IslandLifecycleState.ACTIVE) {
      return OperationPreconditionDecision.GAMEPLAY_REQUIRES_ACTIVE;
    }
    return OperationPreconditionDecision.ALLOWED;
  }
}
