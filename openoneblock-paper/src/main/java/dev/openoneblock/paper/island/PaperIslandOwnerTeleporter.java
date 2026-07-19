package dev.openoneblock.paper.island;

import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.core.island.IslandOwnerTeleporter;
import dev.openoneblock.core.platform.EntityTaskHandle;
import dev.openoneblock.core.world.WorldSpawnPosition;
import dev.openoneblock.paper.scheduler.PaperEntityTaskHandle;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Online owner teleport adapter using the entity scheduler and Paper asynchronous teleport API. */
public final class PaperIslandOwnerTeleporter implements IslandOwnerTeleporter {
  private final Server server;
  private final Function<Player, EntityTaskHandle> entityTasks;

  /**
   * Creates an online-player teleport provider.
   *
   * @param plugin task-owning plugin
   * @param server active Paper server
   */
  public PaperIslandOwnerTeleporter(Plugin plugin, Server server) {
    this(plugin, server, player -> new PaperEntityTaskHandle(plugin, player));
  }

  PaperIslandOwnerTeleporter(
      Plugin plugin, Server server, Function<Player, EntityTaskHandle> entityTasks) {
    Objects.requireNonNull(plugin, "plugin");
    this.server = Objects.requireNonNull(server, "server");
    this.entityTasks = Objects.requireNonNull(entityTasks, "entityTasks");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<Void> teleport(
      PlayerId ownerId, WorldSpawnPosition destination, OperationId operationId) {
    Objects.requireNonNull(ownerId, "ownerId");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(operationId, "operationId");
    Player player = server.getPlayer(ownerId.value());
    if (player == null || !player.isOnline()) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("creation owner is no longer online"));
    }
    World world = server.getWorld(destination.worldId().value());
    if (world == null || !world.getUID().equals(destination.worldId().value())) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("creation spawn world is not loaded"));
    }
    Location location =
        new Location(
            world,
            destination.x(),
            destination.y(),
            destination.z(),
            destination.yaw(),
            destination.pitch());
    return entityTasks
        .apply(player)
        .schedule(() -> player.teleportAsync(location))
        .thenCompose(stage -> stage)
        .thenCompose(
            teleported ->
                teleported
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(
                        new IllegalStateException("Paper rejected creation owner teleport")));
  }
}
