package dev.openoneblock.api.island;

/** Durable lifecycle of an island membership invitation. */
public enum InvitationState {
  PENDING,
  ACCEPTED,
  DECLINED,
  REVOKED,
  EXPIRED
}
