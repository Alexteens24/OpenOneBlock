package dev.openoneblock.paper.command;

import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.core.island.CreateIslandRejectedException;
import dev.openoneblock.core.island.IslandCreationFailedException;
import dev.openoneblock.core.island.IslandMembershipConflictException;
import dev.openoneblock.core.island.IslandPostActivationDeliveryException;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Central deterministic exception-to-message mapping for command adapters. */
public final class CommandFailureMapper {
  /** Creates a stateless mapper. */
  public CommandFailureMapper() {}

  /**
   * Maps one possibly wrapped application failure.
   *
   * @param failure asynchronous failure
   * @param operationId trace identity
   * @return stable response mapping
   */
  public CommandFailure map(Throwable failure, OperationId operationId) {
    Throwable cause = unwrap(failure);
    if (cause instanceof CommandRuntimeUnavailableException) {
      return new CommandFailure("command.not-ready", Map.of(), false);
    }
    if (cause instanceof IslandMembershipConflictException conflict) {
      return new CommandFailure(
          "command.create.already-member", Map.of("island_id", conflict.existingIslandId()), false);
    }
    if (cause instanceof IslandPostActivationDeliveryException delivery) {
      return new CommandFailure(
          "command.create.delivery-failed",
          Map.of("island_id", delivery.result().island().islandId()),
          true);
    }
    if (cause instanceof IslandCreationFailedException) {
      return new CommandFailure("command.create.failed", Map.of("operation_id", operationId), true);
    }
    if (cause instanceof CreateIslandRejectedException) {
      return new CommandFailure(
          "command.create.failed", Map.of("operation_id", operationId), false);
    }
    return new CommandFailure("command.internal-error", Map.of("operation_id", operationId), true);
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable current = failure;
    while ((current instanceof CompletionException || current instanceof ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }
}
