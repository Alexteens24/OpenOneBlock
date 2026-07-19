package dev.openoneblock.paper.event;

import dev.openoneblock.api.event.IslandCreatedEvent;
import dev.openoneblock.core.island.IslandCreatedEventPublisher;
import dev.openoneblock.core.platform.PlatformTaskScheduler;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.bukkit.Server;

/** Publishes creation events through Bukkit only from the supported global scheduler. */
public final class BukkitIslandCreatedEventPublisher implements IslandCreatedEventPublisher {
  private final Server server;
  private final PlatformTaskScheduler scheduler;

  /**
   * Creates a Bukkit event publisher.
   *
   * @param server active Paper server
   * @param scheduler ownership-aware scheduler
   */
  public BukkitIslandCreatedEventPublisher(Server server, PlatformTaskScheduler scheduler) {
    this.server = Objects.requireNonNull(server, "server");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<Void> publish(IslandCreatedEvent event) {
    Objects.requireNonNull(event, "event");
    return scheduler.global(
        () -> {
          server.getPluginManager().callEvent(new PaperIslandCreatedEvent(event));
          return null;
        });
  }
}
