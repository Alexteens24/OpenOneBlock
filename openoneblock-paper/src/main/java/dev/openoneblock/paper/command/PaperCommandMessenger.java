package dev.openoneblock.paper.command;

import dev.openoneblock.core.platform.PlatformTaskScheduler;
import dev.openoneblock.paper.scheduler.PaperEntityTaskHandle;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Sends command responses through the global or owning entity scheduler. */
public final class PaperCommandMessenger implements CommandMessenger {
  private final Plugin plugin;
  private final PlatformTaskScheduler scheduler;
  private final CommandMessageRenderer renderer;
  private final Logger logger;

  /**
   * Creates the production command messenger.
   *
   * @param plugin active plugin
   * @param scheduler ownership-aware scheduler
   * @param renderer locale-key renderer
   */
  public PaperCommandMessenger(
      Plugin plugin, PlatformTaskScheduler scheduler, CommandMessageRenderer renderer) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.logger = plugin.getLogger();
  }

  /** {@inheritDoc} */
  @Override
  public void send(CommandSender sender, String key, Map<String, ?> placeholders) {
    Objects.requireNonNull(sender, "sender");
    var message = renderer.render(key, placeholders);
    var delivery =
        sender instanceof Player player
            ? new PaperEntityTaskHandle(plugin, player)
                .schedule(
                    () -> {
                      player.sendMessage(message);
                      return null;
                    })
            : scheduler.global(
                () -> {
                  sender.sendMessage(message);
                  return null;
                });
    delivery.whenComplete(
        (ignored, failure) -> {
          if (failure != null) {
            logger.log(Level.WARNING, "Could not deliver command response " + key, failure);
          }
        });
  }
}
