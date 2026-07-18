package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.slot.AllocatedSlot;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, internally consistent persisted island aggregate projection.
 *
 * @param islandId stable island identity
 * @param ownerId authoritative owner identity
 * @param lifecycleState persisted island lifecycle
 * @param primarySlot authoritative primary slot, absent only for archived islands
 * @param currentBorderSize current playable border size
 * @param maximumBorderSize maximum reserved border size
 * @param version optimistic aggregate version
 * @param pendingOperationId critical operation awaiting completion
 * @param createdAt creation instant
 * @param updatedAt last persisted mutation instant
 */
public record IslandAggregateSnapshot(
    IslandId islandId,
    PlayerId ownerId,
    IslandLifecycleState lifecycleState,
    Optional<AllocatedSlot> primarySlot,
    int currentBorderSize,
    int maximumBorderSize,
    long version,
    Optional<OperationId> pendingOperationId,
    Instant createdAt,
    Instant updatedAt) {
  /** Validates the aggregate projection and its slot relationship. */
  public IslandAggregateSnapshot {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(ownerId, "ownerId");
    Objects.requireNonNull(lifecycleState, "lifecycleState");
    Objects.requireNonNull(primarySlot, "primarySlot");
    Objects.requireNonNull(pendingOperationId, "pendingOperationId");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (currentBorderSize <= 0 || maximumBorderSize < currentBorderSize) {
      throw new IllegalArgumentException("invalid island border sizes");
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must be non-negative");
    }
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
    if (lifecycleState == IslandLifecycleState.ARCHIVED && primarySlot.isPresent()) {
      throw new IllegalArgumentException("an archived island must not retain a primary slot");
    }
    if (lifecycleState != IslandLifecycleState.ARCHIVED && primarySlot.isEmpty()) {
      throw new IllegalArgumentException("a non-archived island must retain a primary slot");
    }
    primarySlot.ifPresent(
        slot -> {
          if (!slot.ownerIslandId().equals(islandId)) {
            throw new IllegalArgumentException("primary slot belongs to another island");
          }
        });
  }
}
