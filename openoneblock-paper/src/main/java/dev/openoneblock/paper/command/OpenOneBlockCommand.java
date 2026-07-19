package dev.openoneblock.paper.command;

import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.core.island.CreateIslandResult;
import dev.openoneblock.core.island.IslandDeletionResult;
import dev.openoneblock.core.island.IslandHomeResult;
import dev.openoneblock.core.island.IslandInfoSnapshot;
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
  private static final List<String> ROOT_SUGGESTIONS =
      List.of("create", "home", "info", "delete", "help");

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
      case "home" -> home(sender);
      case "info" -> info(sender);
      case "delete" -> delete(sender, args);
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

  private void home(CommandSender sender) {
    Player player = requirePlayer(sender);
    if (player == null || !requirePermission(player, OpenOneBlockPermissions.HOME)) {
      return;
    }
    if (!requireReady(player)) {
      return;
    }
    MutationSubmission<IslandHomeResult> submission =
        islands.home(PlayerId.of(player.getUniqueId()));
    messages.send(player, "command.home.started", Map.of("operation_id", submission.operationId()));
    submission
        .completion()
        .whenComplete(
            (result, failure) -> {
              if (failure != null) {
                respondHomeFailure(player, submission.operationId(), failure);
                return;
              }
              messages.send(
                  player,
                  "command.home.success",
                  Map.of(
                      "island_id", result.islandId(),
                      "operation_id", result.operationId()));
            });
  }

  private void info(CommandSender sender) {
    Player player = requirePlayer(sender);
    if (player == null || !requirePermission(player, OpenOneBlockPermissions.INFO)) {
      return;
    }
    if (!requireReady(player)) {
      return;
    }
    islands
        .info(PlayerId.of(player.getUniqueId()))
        .whenComplete(
            (info, failure) -> {
              if (failure != null) {
                CommandFailure mapped = failures.mapInfo(failure);
                messages.send(player, mapped.messageKey(), mapped.placeholders());
                if (mapped.log()) {
                  logger.log(Level.SEVERE, "OpenOneBlock island info query failed", failure);
                }
                return;
              }
              sendInfo(player, info);
            });
  }

  private void sendInfo(Player player, IslandInfoSnapshot info) {
    messages.send(
        player,
        "command.info",
        Map.ofEntries(
            Map.entry("island_id", info.islandId()),
            Map.entry("owner_id", info.ownerId()),
            Map.entry("role_id", info.requesterRoleId()),
            Map.entry("phase_id", info.phaseId()),
            Map.entry("current_border", info.currentBorderSize()),
            Map.entry("maximum_border", info.maximumBorderSize()),
            Map.entry("total_breaks", info.totalBreaks()),
            Map.entry("sequence", info.magicBlockSequence()),
            Map.entry("members", info.activeMemberCount()),
            Map.entry("version", info.islandVersion())));
  }

  private void delete(CommandSender sender, String[] args) {
    Player player = requirePlayer(sender);
    if (player == null || !requirePermission(player, OpenOneBlockPermissions.DELETE)) {
      return;
    }
    if (!requireReady(player)) {
      return;
    }
    PlayerId playerId = PlayerId.of(player.getUniqueId());
    if (args.length == 1) {
      islands
          .requestDelete(playerId)
          .whenComplete(
              (challenge, failure) -> {
                if (failure != null) {
                  respondDeleteFailure(player, null, failure);
                  return;
                }
                messages.send(
                    player,
                    "command.delete.confirm",
                    Map.of(
                        "island_id", challenge.islandId(),
                        "token", challenge.token(),
                        "expires_at", challenge.expiresAt()));
              });
      return;
    }
    if (args.length != 3 || !args[1].equalsIgnoreCase("confirm") || args[2].isBlank()) {
      messages.send(player, "command.delete.usage");
      return;
    }
    MutationSubmission<IslandDeletionResult> submission = islands.confirmDelete(playerId, args[2]);
    messages.send(
        player, "command.delete.started", Map.of("operation_id", submission.operationId()));
    submission
        .completion()
        .whenComplete(
            (result, failure) -> {
              if (failure != null) {
                respondDeleteFailure(player, submission.operationId(), failure);
                return;
              }
              messages.send(
                  player,
                  "command.delete.success",
                  Map.of(
                      "island_id", result.islandId(),
                      "operation_id", result.operationId()));
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

  private void respondHomeFailure(
      CommandSender sender, dev.openoneblock.api.id.OperationId operationId, Throwable failure) {
    CommandFailure mapped = failures.mapHome(failure, operationId);
    messages.send(sender, mapped.messageKey(), mapped.placeholders());
    if (mapped.log()) {
      logger.log(Level.SEVERE, "OpenOneBlock home operation " + operationId + " failed", failure);
    }
  }

  private void respondDeleteFailure(
      CommandSender sender, dev.openoneblock.api.id.OperationId operationId, Throwable failure) {
    CommandFailure mapped = failures.mapDelete(failure, operationId);
    messages.send(sender, mapped.messageKey(), mapped.placeholders());
    if (mapped.log()) {
      logger.log(Level.SEVERE, "OpenOneBlock delete operation " + operationId + " failed", failure);
    }
  }

  private Player requirePlayer(CommandSender sender) {
    if (sender instanceof Player player) {
      return player;
    }
    messages.send(sender, "command.player-only");
    return null;
  }

  private boolean requirePermission(Player player, String permission) {
    if (player.hasPermission(permission)) {
      return true;
    }
    messages.send(player, "command.no-permission");
    return false;
  }

  private boolean requireReady(Player player) {
    if (lifecycle.isReady()) {
      return true;
    }
    messages.send(player, "command.not-ready");
    return false;
  }

  private static String permissionFor(String command) {
    return switch (command) {
      case "create" -> OpenOneBlockPermissions.CREATE;
      case "home" -> OpenOneBlockPermissions.HOME;
      case "info" -> OpenOneBlockPermissions.INFO;
      case "delete" -> OpenOneBlockPermissions.DELETE;
      default -> OpenOneBlockPermissions.HELP;
    };
  }
}
