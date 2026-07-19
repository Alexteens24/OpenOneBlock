package dev.openoneblock.core.team;

/** Supported direct membership/access mutations. */
public enum MembershipCommandKind {
  KICK,
  LEAVE,
  BAN,
  UNBAN,
  TRUST,
  UNTRUST,
  PROMOTE,
  DEMOTE
}
