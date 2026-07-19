package dev.openoneblock.paper.command;

import java.util.Map;
import org.bukkit.command.CommandSender;

/** Locale-keyed ownership-aware command response boundary. */
@FunctionalInterface
public interface CommandMessenger {
  /**
   * Sends one keyed response with literal placeholder values.
   *
   * @param sender response target
   * @param key locale-ready message identity
   * @param placeholders placeholder name to literal value
   */
  void send(CommandSender sender, String key, Map<String, ?> placeholders);

  /**
   * Sends one response without placeholders.
   *
   * @param sender response target
   * @param key locale-ready message identity
   */
  default void send(CommandSender sender, String key) {
    send(sender, key, Map.of());
  }
}
