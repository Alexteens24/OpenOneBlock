package dev.openoneblock.core.island;

import dev.openoneblock.api.id.PlayerId;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Read-only persistence boundary for player command projections. */
public interface IslandQueryRepository {
  /**
   * Finds the active island and primary home visible to a member.
   *
   * @param playerId active member identity
   * @return optional immutable home projection
   */
  CompletionStage<Optional<IslandHomeSnapshot>> findActiveHome(PlayerId playerId);

  /**
   * Finds the active island summary visible to a member.
   *
   * @param playerId active member identity
   * @return optional immutable info projection
   */
  CompletionStage<Optional<IslandInfoSnapshot>> findActiveInfo(PlayerId playerId);
}
