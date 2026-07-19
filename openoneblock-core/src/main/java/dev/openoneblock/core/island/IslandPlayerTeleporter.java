package dev.openoneblock.core.island;

import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.core.world.WorldSpawnPosition;
import java.util.concurrent.CompletionStage;

/** Platform-neutral player teleport boundary for verified island destinations. */
@FunctionalInterface
public interface IslandPlayerTeleporter {
  /**
   * Teleports one online player to a verified destination.
   *
   * @param playerId target player
   * @param destination verified persistent destination
   * @param operationId trace identity
   * @return teleport completion
   */
  CompletionStage<Void> teleport(
      PlayerId playerId, WorldSpawnPosition destination, OperationId operationId);
}
