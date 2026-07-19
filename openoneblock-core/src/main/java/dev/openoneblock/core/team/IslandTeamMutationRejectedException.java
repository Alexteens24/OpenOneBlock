package dev.openoneblock.core.team;

/** Explicit rejection of an invalid or inadmissible team mutation. */
public final class IslandTeamMutationRejectedException extends RuntimeException {
  public IslandTeamMutationRejectedException(String reason) {
    super(reason);
  }

  public IslandTeamMutationRejectedException(String reason, Throwable cause) {
    super(reason, cause);
  }
}
