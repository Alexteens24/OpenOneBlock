package dev.openoneblock.paper.island;

import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.core.island.IslandDestinationPreparer;
import dev.openoneblock.core.platform.PlatformTaskScheduler;
import dev.openoneblock.core.world.WorldSpawnPosition;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;

/** Prepares an exact persisted destination chunk without blocking a server ownership thread. */
public final class PaperIslandDestinationPreparer implements IslandDestinationPreparer {
  private final Server server;
  private final PlatformTaskScheduler scheduler;

  /**
   * Creates the Paper destination preparer.
   *
   * @param server active Paper server
   * @param scheduler ownership-aware scheduler
   */
  public PaperIslandDestinationPreparer(Server server, PlatformTaskScheduler scheduler) {
    this.server = Objects.requireNonNull(server, "server");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<Void> prepare(WorldSpawnPosition destination, OperationId operationId) {
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(operationId, "operationId");
    World world = server.getWorld(destination.worldId().value());
    if (world == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("Home world is not loaded: " + destination.worldId()));
    }
    var block = destination.feetBlock();
    int chunkX = Math.floorDiv(block.x(), 16);
    int chunkZ = Math.floorDiv(block.z(), 16);
    return scheduler
        .region(block.regionTarget(), () -> world.getChunkAtAsync(chunkX, chunkZ, true))
        .thenCompose(stage -> stage)
        .thenAccept(chunk -> verifyChunk(chunk, world, chunkX, chunkZ, operationId));
  }

  private static void verifyChunk(
      Chunk chunk, World world, int chunkX, int chunkZ, OperationId operationId) {
    if (chunk == null
        || !chunk.getWorld().getUID().equals(world.getUID())
        || chunk.getX() != chunkX
        || chunk.getZ() != chunkZ
        || !chunk.isLoaded()) {
      throw new IllegalStateException(
          "Destination chunk preparation could not be verified for operation " + operationId);
    }
  }
}
