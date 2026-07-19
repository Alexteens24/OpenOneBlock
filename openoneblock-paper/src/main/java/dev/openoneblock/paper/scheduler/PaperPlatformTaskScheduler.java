package dev.openoneblock.paper.scheduler;

import dev.openoneblock.core.platform.PlatformTaskScheduler;
import dev.openoneblock.core.platform.RegionTaskTarget;
import dev.openoneblock.core.platform.ScheduledWork;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** Paper and Folia adapter using the ownership-aware global, region, and async schedulers. */
public final class PaperPlatformTaskScheduler implements PlatformTaskScheduler {
  private final Plugin plugin;
  private final Server server;

  /**
   * Creates a platform scheduler adapter.
   *
   * @param plugin task-owning plugin
   * @param server active Paper or Folia server
   */
  public PaperPlatformTaskScheduler(Plugin plugin, Server server) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.server = Objects.requireNonNull(server, "server");
  }

  /** {@inheritDoc} */
  @Override
  public <T> CompletionStage<T> global(ScheduledWork<T> work) {
    return dispatch(work, runnable -> server.getGlobalRegionScheduler().execute(plugin, runnable));
  }

  /** {@inheritDoc} */
  @Override
  public <T> CompletionStage<T> region(RegionTaskTarget target, ScheduledWork<T> work) {
    Objects.requireNonNull(target, "target");
    World world = server.getWorld(target.worldId().value());
    if (world == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("Region target world is not loaded: " + target.worldId()));
    }
    return dispatch(
        work,
        runnable ->
            server
                .getRegionScheduler()
                .execute(plugin, world, target.chunkX(), target.chunkZ(), runnable));
  }

  /** {@inheritDoc} */
  @Override
  public <T> CompletionStage<T> async(ScheduledWork<T> work) {
    return dispatch(
        work, runnable -> server.getAsyncScheduler().runNow(plugin, ignoredTask -> runnable.run()));
  }

  private static <T> CompletionStage<T> dispatch(
      ScheduledWork<T> work, Consumer<Runnable> dispatcher) {
    Objects.requireNonNull(work, "work");
    CompletableFuture<T> completion = new CompletableFuture<>();
    try {
      dispatcher.accept(() -> execute(work, completion));
    } catch (Throwable throwable) {
      completion.completeExceptionally(throwable);
    }
    return completion.minimalCompletionStage();
  }

  static <T> void execute(ScheduledWork<T> work, CompletableFuture<T> completion) {
    try {
      completion.complete(work.execute());
    } catch (Throwable throwable) {
      completion.completeExceptionally(throwable);
    }
  }
}
