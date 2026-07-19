package dev.openoneblock.paper.command;

import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.core.island.CreateIslandResult;

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
}
