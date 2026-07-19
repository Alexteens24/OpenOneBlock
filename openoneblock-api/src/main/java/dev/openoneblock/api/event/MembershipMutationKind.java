package dev.openoneblock.api.event;

/** Public classification of a committed island-team mutation. */
public enum MembershipMutationKind {
  INVITED,
  INVITATION_ACCEPTED,
  INVITATION_DECLINED,
  KICKED,
  LEFT,
  BANNED,
  UNBANNED,
  TRUSTED,
  UNTRUSTED,
  PROMOTED,
  DEMOTED,
  OWNERSHIP_TRANSFERRED
}
