package dev.openoneblock.paper.command;

/** Destructive command identity bound into a one-time confirmation token. */
public enum ConfirmationAction {
  /** Archive an island after verified cleanup. */
  DELETE,

  /** Clean and rebuild an island in place. */
  RESET
}
