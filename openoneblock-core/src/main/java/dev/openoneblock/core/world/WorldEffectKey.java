package dev.openoneblock.core.world;

import dev.openoneblock.api.id.OperationId;
import java.util.Objects;

/**
 * Stable idempotency key derived from an operation and deterministic effect index.
 *
 * @param operationId parent operation
 * @param effectIndex deterministic zero-based index
 */
public record WorldEffectKey(OperationId operationId, int effectIndex)
    implements Comparable<WorldEffectKey> {
  /** Validates a non-negative stable action index. */
  public WorldEffectKey {
    Objects.requireNonNull(operationId, "operationId");
    if (effectIndex < 0) {
      throw new IllegalArgumentException("effectIndex must be non-negative");
    }
  }

  @Override
  public int compareTo(WorldEffectKey other) {
    int operationComparison = operationId.compareTo(other.operationId);
    return operationComparison != 0
        ? operationComparison
        : Integer.compare(effectIndex, other.effectIndex);
  }

  @Override
  public String toString() {
    return operationId + ":" + effectIndex;
  }
}
