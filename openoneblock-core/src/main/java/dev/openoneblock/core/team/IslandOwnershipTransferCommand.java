package dev.openoneblock.core.team;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import java.time.Instant;
import java.util.Objects;

/** Atomic ownership transfer command. */
public record IslandOwnershipTransferCommand(
    IslandId islandId,
    OperationId operationId,
    PlayerId currentOwnerPlayerId,
    PlayerId newOwnerPlayerId,
    NamespacedId previousOwnerRoleId,
    long expectedIslandVersion,
    Instant requestedAt) {
  /** Validates command shape. */
  public IslandOwnershipTransferCommand {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(currentOwnerPlayerId, "currentOwnerPlayerId");
    Objects.requireNonNull(newOwnerPlayerId, "newOwnerPlayerId");
    Objects.requireNonNull(previousOwnerRoleId, "previousOwnerRoleId");
    Objects.requireNonNull(requestedAt, "requestedAt");
    if (expectedIslandVersion < 0 || currentOwnerPlayerId.equals(newOwnerPlayerId)) {
      throw new IllegalArgumentException("invalid ownership transfer command");
    }
  }
}
