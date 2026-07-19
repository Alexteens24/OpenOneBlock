package dev.openoneblock.paper.command;

import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.core.island.CreateIslandResult;
import dev.openoneblock.core.island.IslandDeletionResult;
import dev.openoneblock.core.island.IslandHomeResult;
import dev.openoneblock.core.island.IslandInfoSnapshot;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Application boundary used by the Paper command parser without exposing persistence APIs. */
@FunctionalInterface
public interface IslandCommandGateway {
  /**
   * Submits a new idempotent creation operation.
   *
   * @param owner player requesting creation
   * @return operation identity and non-blocking completion
   */
  MutationSubmission<CreateIslandResult> create(PlayerId owner);

  /**
   * Submits a safe home teleport operation.
   *
   * @param player active member
   * @return operation identity and non-blocking completion
   */
  default MutationSubmission<IslandHomeResult> home(PlayerId player) {
    return new MutationSubmission<>(
        dev.openoneblock.api.id.OperationId.generate(),
        CompletableFuture.failedFuture(new UnsupportedOperationException("home is unavailable")));
  }

  /**
   * Queries the active island summary.
   *
   * @param player active member
   * @return immutable asynchronous summary
   */
  default CompletionStage<IslandInfoSnapshot> info(PlayerId player) {
    return CompletableFuture.failedFuture(new UnsupportedOperationException("info is unavailable"));
  }

  /**
   * Issues a one-time exact-version deletion challenge.
   *
   * @param player requesting owner
   * @return asynchronous confirmation challenge
   */
  default CompletionStage<ConfirmationChallenge> requestDelete(PlayerId player) {
    return CompletableFuture.failedFuture(
        new UnsupportedOperationException("delete is unavailable"));
  }

  /**
   * Consumes a deletion confirmation and submits the durable operation.
   *
   * @param player confirming owner
   * @param token one-time confirmation token
   * @return traceable non-blocking deletion submission
   */
  default MutationSubmission<IslandDeletionResult> confirmDelete(PlayerId player, String token) {
    return new MutationSubmission<>(
        dev.openoneblock.api.id.OperationId.generate(),
        CompletableFuture.failedFuture(new UnsupportedOperationException("delete is unavailable")));
  }
}
