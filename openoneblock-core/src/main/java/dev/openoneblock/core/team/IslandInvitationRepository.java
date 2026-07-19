package dev.openoneblock.core.team;

import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.island.IslandInvitationView;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Read-only immutable invitation query boundary. */
public interface IslandInvitationRepository {
  /** Finds non-expired pending invitations for one player in deterministic order. */
  CompletionStage<List<IslandInvitationView>> findPendingInvitations(
      PlayerId playerId, Instant observedAt);
}
