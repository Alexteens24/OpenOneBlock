package dev.openoneblock.paper.event;

import dev.openoneblock.api.event.IslandMembershipChangedEvent;
import dev.openoneblock.core.platform.PlatformTaskScheduler;
import dev.openoneblock.core.team.IslandMembershipEventPublisher;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.bukkit.Server;

/** Delivers committed team events through Bukkit's supported global scheduler. */
public final class BukkitIslandMembershipEventPublisher implements IslandMembershipEventPublisher {
  private final Server server;
  private final PlatformTaskScheduler scheduler;

  /** Creates a Bukkit team event publisher. */
  public BukkitIslandMembershipEventPublisher(Server server, PlatformTaskScheduler scheduler) {
    this.server = Objects.requireNonNull(server, "server");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<Void> publish(IslandMembershipChangedEvent event) {
    Objects.requireNonNull(event, "event");
    return scheduler.global(
        () -> {
          server.getPluginManager().callEvent(new PaperIslandMembershipChangedEvent(event));
          return null;
        });
  }
}
