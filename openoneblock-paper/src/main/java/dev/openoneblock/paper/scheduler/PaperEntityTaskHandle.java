package dev.openoneblock.paper.scheduler;

import dev.openoneblock.core.platform.EntityTaskHandle;
import dev.openoneblock.core.platform.EntityTaskUnavailableException;
import dev.openoneblock.core.platform.ScheduledWork;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** Entity scheduler handle that follows a Paper/Folia entity across region and world changes. */
public final class PaperEntityTaskHandle implements EntityTaskHandle {
  private final Plugin plugin;
  private final Entity entity;

  /**
   * Creates an ownership-following entity handle.
   *
   * @param plugin task-owning plugin
   * @param entity live platform entity
   */
  public PaperEntityTaskHandle(Plugin plugin, Entity entity) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.entity = Objects.requireNonNull(entity, "entity");
  }

  /** {@inheritDoc} */
  @Override
  public <T> CompletionStage<T> schedule(ScheduledWork<T> work) {
    Objects.requireNonNull(work, "work");
    CompletableFuture<T> completion = new CompletableFuture<>();
    Runnable retired = () -> completion.completeExceptionally(new EntityTaskUnavailableException());
    try {
      boolean accepted =
          entity
              .getScheduler()
              .execute(
                  plugin, () -> PaperPlatformTaskScheduler.execute(work, completion), retired, 1);
      if (!accepted) {
        retired.run();
      }
    } catch (Throwable throwable) {
      completion.completeExceptionally(throwable);
    }
    return completion.minimalCompletionStage();
  }
}
