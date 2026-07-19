package dev.openoneblock.core.island;

import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.core.world.WorldSpawnPosition;
import java.util.concurrent.CompletionStage;

/** Interactive post-activation owner teleport port. */
@FunctionalInterface
public interface IslandOwnerTeleporter extends IslandPlayerTeleporter {
  /**
   * Teleports the online owner to the verified committed primary spawn.
   *
   * @param ownerId initial owner
   * @param destination verified primary spawn
   * @param operationId creation diagnostic identity
   * @return verified teleport completion
   */
  CompletionStage<Void> teleport(
      PlayerId ownerId, WorldSpawnPosition destination, OperationId operationId);
}
