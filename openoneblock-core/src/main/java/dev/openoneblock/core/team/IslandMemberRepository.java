package dev.openoneblock.core.team;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.island.MemberView;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Authoritative asynchronous membership query port. */
@FunctionalInterface
public interface IslandMemberRepository {
  /**
   * Loads active members in deterministic owner-first order.
   *
   * @param islandId island identity
   * @return immutable active membership list
   */
  CompletionStage<List<MemberView>> findActiveMembers(IslandId islandId);
}
