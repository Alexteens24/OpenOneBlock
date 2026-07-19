package dev.openoneblock.paper.command;

import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.core.island.CreateIslandResult;
import dev.openoneblock.paper.bootstrap.PluginRuntimeLifecycle;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Paper lifecycle command handler for `/oneblock` and `/ob`. */
public final class OpenOneBlockCommand implements BasicCommand {
  private static final List<String> ROOT_SUGGESTIONS = List.of("create", "help");

  private final PluginRuntimeLifecycle lifecycle;
  private final IslandCommandGateway islands;
  private final CommandMessenger messages;
  private final CommandFailureMapper failures;
  private final Logger logger;

  /**
   * Creates the minimal player command surface.
   *
   * @param lifecycle fail-closed runtime gate
   * @param islands application command gateway
   * @param messages ownership-aware keyed responses
   * @param failures centralized exception mapper
   * @param logger operator diagnostics
   */
  public OpenOneBlockCommand(
      PluginRuntimeLifecycle lifecycle,
      IslandCommandGateway islands,
      CommandMessenger messages,
      CommandFailureMapper failures,
      Logger logger) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.islands = Objects.requireNonNull(islands, "islands");
    this.messages = Objects.requireNonNull(messages, "messages");
    this.failures = Objects.requireNonNull(failures, "failures");
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  /** {@inheritDoc} */
  @Override
  public void execute(CommandSourceStack source, String[] args) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(args, "args");
    CommandSender sender = source.getSender();
    String subcommand = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
    switch (subcommand) {
      case "help" -> help(sender);
      case "create" -> create(sender);
      default -> messages.send(sender, "command.unknown");
    }
  }

  /** {@inheritDoc} */
  @Override
  public List<String> suggest(CommandSourceStack source, String[] args) {
    if (args.length > 1) {
      return List.of();
    }
    String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
    return ROOT_SUGGESTIONS.stream()
        .filter(candidate -> candidate.startsWith(prefix))
        .filter(candidate -> source.getSender().hasPermission(permissionFor(candidate)))
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  public String permission() {
    return OpenOneBlockPermissions.COMMAND;
  }

  private void help(CommandSender sender) {
    if (!sender.hasPermission(OpenOneBlockPermissions.HELP)) {
      messages.send(sender, "command.no-permission");
      return;
    }
    messages.send(sender, "command.help");
  }

  private void create(CommandSender sender) {
    if (!(sender instanceof Player player)) {
      messages.send(sender, "command.player-only");
      return;
    }
    if (!player.hasPermission(OpenOneBlockPermissions.CREATE)) {
      messages.send(player, "command.no-permission");
      return;
    }
    if (!lifecycle.isReady()) {
      messages.send(player, "command.not-ready");
      return;
    }
    MutationSubmission<CreateIslandResult> submission;
    try {
      submission = islands.create(PlayerId.of(player.getUniqueId()));
    } catch (RuntimeException failure) {
      var operationId = dev.openoneblock.api.id.OperationId.generate();
      respondFailure(player, operationId, failure);
      return;
    }
    messages.send(
        player, "command.create.started", Map.of("operation_id", submission.operationId()));
    submission
        .completion()
        .whenComplete(
            (result, failure) -> {
              if (failure != null) {
                respondFailure(player, submission.operationId(), failure);
                return;
              }
              messages.send(
                  player,
                  result.replay() ? "command.create.replay" : "command.create.success",
                  Map.of(
                      "island_id", result.island().islandId(),
                      "operation_id", submission.operationId()));
            });
  }

  private void respondFailure(
      CommandSender sender, dev.openoneblock.api.id.OperationId operationId, Throwable failure) {
    CommandFailure mapped = failures.map(failure, operationId);
    messages.send(sender, mapped.messageKey(), mapped.placeholders());
    if (mapped.log()) {
      logger.log(
          Level.SEVERE, "OpenOneBlock command operation " + operationId + " failed", failure);
    }
  }

  private static String permissionFor(String command) {
    return command.equals("create") ? OpenOneBlockPermissions.CREATE : OpenOneBlockPermissions.HELP;
  }
}
