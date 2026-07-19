package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.core.magic.InitialMagicBlock;
import dev.openoneblock.core.world.WorldEffectKey;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Optimistic atomic activation request after every required world effect was verified.
 *
 * @param islandId island being activated
 * @param operationId durable creation operation
 * @param expectedIslandVersion observed creating-island version
 * @param expectedSlotVersion observed preparing-slot version
 * @param primarySpawn verified primary spawn
 * @param magicBlock verified initial Magic Block
 * @param initialPhaseId compiled initial phase
 * @param requiredEffects complete verified world-effect keys
 * @param activatedAt caller-supplied activation time
 */
public record IslandCreationActivationRequest(
    IslandId islandId,
    OperationId operationId,
    long expectedIslandVersion,
    long expectedSlotVersion,
    IslandSpawnPoint primarySpawn,
    InitialMagicBlock magicBlock,
    NamespacedId initialPhaseId,
    List<WorldEffectKey> requiredEffects,
    Instant activatedAt) {
  /** Validates activation metadata before persistence work. */
  public IslandCreationActivationRequest {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(primarySpawn, "primarySpawn");
    Objects.requireNonNull(magicBlock, "magicBlock");
    Objects.requireNonNull(initialPhaseId, "initialPhaseId");
    requiredEffects = List.copyOf(requiredEffects);
    Objects.requireNonNull(activatedAt, "activatedAt");
    if (expectedIslandVersion < 0 || expectedSlotVersion < 0) {
      throw new IllegalArgumentException("expected versions must be non-negative");
    }
    if (!primarySpawn.primary()) {
      throw new IllegalArgumentException("creation activation requires a primary spawn");
    }
    if (!primarySpawn.position().worldId().equals(magicBlock.position().worldId())) {
      throw new IllegalArgumentException("spawn and Magic Block must share one world projection");
    }
    if (requiredEffects.isEmpty()) {
      throw new IllegalArgumentException("activation requires verified world effects");
    }
    for (WorldEffectKey key : requiredEffects) {
      Objects.requireNonNull(key, "required effect");
      if (!key.operationId().equals(operationId)) {
        throw new IllegalArgumentException("required effect belongs to another operation");
      }
    }
  }
}
