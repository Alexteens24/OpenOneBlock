package dev.openoneblock.paper.command;

import dev.openoneblock.paper.bootstrap.FoundationRuntime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;

/** Resolves locale-ready message keys from the atomic READY configuration snapshot. */
public final class CommandMessageRenderer {
  private static final Map<String, String> FALLBACKS = fallbacks();

  private final Supplier<Optional<FoundationRuntime>> runtime;

  /**
   * Creates a renderer backed by the current READY runtime.
   *
   * @param runtime READY-only runtime supplier
   */
  public CommandMessageRenderer(Supplier<Optional<FoundationRuntime>> runtime) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
  }

  /**
   * Renders one key with deterministic literal placeholder replacement.
   *
   * @param key message identity
   * @param placeholders placeholder values
   * @return immutable Adventure component
   */
  public Component render(String key, Map<String, ?> placeholders) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(placeholders, "placeholders");
    String template =
        runtime
            .get()
            .map(FoundationRuntime::configuration)
            .map(configuration -> configuration.messages().messages().get(key))
            .orElse(null);
    if (template == null) {
      template = FALLBACKS.getOrDefault(key, "OpenOneBlock: " + key);
    }
    String rendered = template;
    for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
      rendered =
          rendered.replace("{" + entry.getKey() + "}", Objects.toString(entry.getValue(), ""));
    }
    return Component.text(rendered);
  }

  private static Map<String, String> fallbacks() {
    Map<String, String> messages = new LinkedHashMap<>();
    messages.put("command.help", "OpenOneBlock: /ob create");
    messages.put("command.unknown", "Unknown subcommand. Use /ob help.");
    messages.put("command.player-only", "This command can only be used by a player.");
    messages.put("command.no-permission", "You do not have permission to do that.");
    messages.put("command.not-ready", "OpenOneBlock is still starting. Try again shortly.");
    messages.put("command.create.started", "Creating your island… Operation: {operation_id}");
    messages.put("command.create.success", "Island {island_id} is ready.");
    messages.put("command.create.replay", "Island {island_id} was already created.");
    messages.put("command.create.already-member", "You already belong to island {island_id}.");
    messages.put(
        "command.create.failed", "Island creation failed safely. Operation: {operation_id}");
    messages.put(
        "command.create.delivery-failed",
        "Island {island_id} is active, but the final teleport/message delivery failed.");
    messages.put("command.internal-error", "OpenOneBlock could not complete that operation.");
    return Map.copyOf(messages);
  }
}
