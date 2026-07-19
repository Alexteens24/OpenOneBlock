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
    messages.put("command.help", "OpenOneBlock: /ob create | home | info | reset | delete");
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
    messages.put("command.no-island", "You do not belong to an active island.");
    messages.put("command.home.started", "Preparing island home... Operation: {operation_id}");
    messages.put("command.home.success", "Teleported to island {island_id}.");
    messages.put(
        "command.home.unsafe", "Your stored island home is unsafe; ask an admin to repair it.");
    messages.put("command.home.failed", "Could not teleport home. Operation: {operation_id}");
    messages.put(
        "command.info",
        "Island {island_id} | Owner {owner_id} | Role {role_id} | Phase {phase_id} | "
            + "Border {current_border}/{maximum_border} | Breaks {total_breaks} | "
            + "Sequence {sequence} | Members {members} | Version {version}");
    messages.put(
        "command.delete.confirm",
        "This permanently deletes island {island_id}. Run /ob delete confirm {token} before {expires_at}.");
    messages.put("command.delete.usage", "Use /ob delete, then /ob delete confirm <token>.");
    messages.put("command.delete.started", "Deleting island safely... Operation: {operation_id}");
    messages.put("command.delete.success", "Island {island_id} was deleted safely.");
    messages.put(
        "command.delete.quarantined",
        "Cleanup could not be proven safe; the island slot was quarantined. Operation: {operation_id}");
    messages.put(
        "command.delete.conflict",
        "Island ownership or version changed; request a new confirmation.");
    messages.put(
        "command.delete.failed", "Island deletion failed safely. Operation: {operation_id}");
    messages.put(
        "command.reset.confirm",
        "This rebuilds island {island_id}. Run /ob reset confirm {token} before {expires_at}.");
    messages.put("command.reset.usage", "Use /ob reset, then /ob reset confirm <token>.");
    messages.put("command.reset.started", "Resetting island safely... Operation: {operation_id}");
    messages.put("command.reset.success", "Island {island_id} was reset safely.");
    messages.put("command.reset.replay", "Island {island_id} was already reset.");
    messages.put(
        "command.reset.quarantined",
        "Reset could not be proven safe; the island slot was quarantined. Operation: {operation_id}");
    messages.put(
        "command.reset.conflict",
        "Island ownership or version changed; request a new reset confirmation.");
    messages.put("command.reset.failed", "Island reset failed safely. Operation: {operation_id}");
    messages.put(
        "command.confirmation.invalid", "Confirmation token is invalid, expired, or already used.");
    return Map.copyOf(messages);
  }
}
