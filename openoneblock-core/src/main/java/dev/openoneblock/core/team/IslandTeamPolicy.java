package dev.openoneblock.core.team;

import java.time.Duration;
import java.util.Objects;

/** Immutable application-owned limits applied before team commands enter an island lane. */
public record IslandTeamPolicy(int maximumSize, Duration invitationExpiry) {
  /** Validates bounded policy values. */
  public IslandTeamPolicy {
    Objects.requireNonNull(invitationExpiry, "invitationExpiry");
    if (maximumSize < 1 || invitationExpiry.isZero() || invitationExpiry.isNegative()) {
      throw new IllegalArgumentException("team size and invitation expiry must be positive");
    }
  }
}
