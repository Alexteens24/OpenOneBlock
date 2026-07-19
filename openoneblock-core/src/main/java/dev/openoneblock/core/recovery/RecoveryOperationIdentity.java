package dev.openoneblock.core.recovery;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import java.util.Objects;

/** Complete correlation identity for one startup recovery attempt. */
public record RecoveryOperationIdentity(
    OperationId operationId, IslandId islandId, PlayerId playerId, String operationKind) {
  /** Validates recovery correlation metadata. */
  public RecoveryOperationIdentity {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(operationKind, "operationKind");
    if (operationKind.isBlank() || operationKind.length() > 96) {
      throw new IllegalArgumentException("operationKind must contain between 1 and 96 characters");
    }
  }
}
