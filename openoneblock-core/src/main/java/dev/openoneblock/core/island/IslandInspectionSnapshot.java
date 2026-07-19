package dev.openoneblock.core.island;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.runtime.IslandRuntimeSnapshot;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotState;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable read-only admin projection that never loads an island world or chunk.
 *
 * @param islandId island identity
 * @param ownerId current owner identity
 * @param lifecycleState durable island lifecycle
 * @param islandVersion aggregate version
 * @param shardGroupId owning shard when a primary slot remains
 * @param gridPosition logical cell when a primary slot remains
 * @param slotId primary slot identity when present
 * @param slotState primary slot lifecycle when present
 * @param slotVersion primary slot version when present
 * @param currentBorderSize current playable border
 * @param maximumBorderSize reserved maximum border
 * @param phaseId current phase when initialized
 * @param magicBlockSequence primary Magic Block sequence when initialized
 * @param activeMemberCount active membership count
 * @param pendingOperationId current lifecycle operation when present
 * @param runtime transient runtime diagnostics when currently cached
 * @param updatedAt last durable island update time
 */
public record IslandInspectionSnapshot(
    IslandId islandId,
    PlayerId ownerId,
    IslandLifecycleState lifecycleState,
    long islandVersion,
    Optional<ShardGroupId> shardGroupId,
    Optional<GridPosition> gridPosition,
    Optional<SlotId> slotId,
    Optional<SlotState> slotState,
    Optional<Long> slotVersion,
    int currentBorderSize,
    int maximumBorderSize,
    Optional<NamespacedId> phaseId,
    Optional<Long> magicBlockSequence,
    int activeMemberCount,
    Optional<OperationId> pendingOperationId,
    Optional<IslandRuntimeSnapshot> runtime,
    Instant updatedAt) {
  /** Validates coherent optional slot and runtime diagnostics. */
  public IslandInspectionSnapshot {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(ownerId, "ownerId");
    Objects.requireNonNull(lifecycleState, "lifecycleState");
    Objects.requireNonNull(shardGroupId, "shardGroupId");
    Objects.requireNonNull(gridPosition, "gridPosition");
    Objects.requireNonNull(slotId, "slotId");
    Objects.requireNonNull(slotState, "slotState");
    Objects.requireNonNull(slotVersion, "slotVersion");
    Objects.requireNonNull(phaseId, "phaseId");
    Objects.requireNonNull(magicBlockSequence, "magicBlockSequence");
    Objects.requireNonNull(pendingOperationId, "pendingOperationId");
    Objects.requireNonNull(runtime, "runtime");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (islandVersion < 0 || currentBorderSize <= 0 || maximumBorderSize < currentBorderSize) {
      throw new IllegalArgumentException("invalid island version or border");
    }
    boolean hasSlot = slotId.isPresent();
    if (shardGroupId.isPresent() != hasSlot
        || gridPosition.isPresent() != hasSlot
        || slotState.isPresent() != hasSlot
        || slotVersion.isPresent() != hasSlot) {
      throw new IllegalArgumentException("slot diagnostics must be all present or all absent");
    }
    slotVersion.ifPresent(
        version -> {
          if (version < 0) {
            throw new IllegalArgumentException("slotVersion must be non-negative");
          }
        });
    magicBlockSequence.ifPresent(
        sequence -> {
          if (sequence < 0) {
            throw new IllegalArgumentException("magicBlockSequence must be non-negative");
          }
        });
    if (activeMemberCount < 0) {
      throw new IllegalArgumentException("activeMemberCount must be non-negative");
    }
    runtime.ifPresent(
        snapshot -> {
          if (!snapshot.islandId().equals(islandId)) {
            throw new IllegalArgumentException("runtime belongs to another island");
          }
        });
  }

  /**
   * Returns this durable projection enriched with current transient runtime diagnostics.
   *
   * @param currentRuntime already-cached runtime snapshot, if present
   * @return enriched immutable inspection projection
   */
  public IslandInspectionSnapshot withRuntime(Optional<IslandRuntimeSnapshot> currentRuntime) {
    return new IslandInspectionSnapshot(
        islandId,
        ownerId,
        lifecycleState,
        islandVersion,
        shardGroupId,
        gridPosition,
        slotId,
        slotState,
        slotVersion,
        currentBorderSize,
        maximumBorderSize,
        phaseId,
        magicBlockSequence,
        activeMemberCount,
        pendingOperationId,
        Objects.requireNonNull(currentRuntime, "currentRuntime"),
        updatedAt);
  }
}
