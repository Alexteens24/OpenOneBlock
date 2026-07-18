package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.PlayerId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Durable application port for the allocation stage of island creation and recovery reads. */
public interface IslandCreationRepository {
  /**
   * Atomically reserves a slot, inserts the island and owner, and records the operation.
   *
   * @param request idempotent creation intent
   * @return committed allocation snapshot after locator publication
   */
  CompletionStage<IslandAggregateSnapshot> createAllocation(IslandCreationRequest request);

  /**
   * Atomically advances island, primary slot, and durable creation operation state.
   *
   * @param request optimistic and idempotent stage transition
   * @return committed aggregate snapshot after locator publication
   */
  CompletionStage<IslandAggregateSnapshot> advanceCreation(IslandCreationTransitionRequest request);

  /**
   * Reads one authoritative island snapshot without loading world state.
   *
   * @param islandId island identity
   * @return matching snapshot if persisted
   */
  CompletionStage<Optional<IslandAggregateSnapshot>> findById(IslandId islandId);

  /**
   * Finds the island for a player's active owner or member assignment.
   *
   * @param playerId player identity
   * @return active membership's island snapshot if present
   */
  CompletionStage<Optional<IslandAggregateSnapshot>> findByActiveMember(PlayerId playerId);

  /**
   * Lists creation operations requiring startup recovery without consulting world state.
   *
   * @return allocating or creating islands ordered by creation time and identity
   */
  CompletionStage<List<IslandAggregateSnapshot>> findPendingCreations();
}
