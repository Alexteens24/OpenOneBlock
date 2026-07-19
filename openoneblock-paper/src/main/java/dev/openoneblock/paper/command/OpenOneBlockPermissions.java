package dev.openoneblock.paper.command;

/** Central permission identities used by the Paper command surface. */
public final class OpenOneBlockPermissions {
  /** Permission to discover and use the root player command. */
  public static final String COMMAND = "openoneblock.command";

  /** Permission to create a new island. */
  public static final String CREATE = "openoneblock.command.create";

  /** Permission to view help. */
  public static final String HELP = "openoneblock.command.help";

  /** Permission to teleport to an active island home. */
  public static final String HOME = "openoneblock.command.home";

  /** Permission to view the caller's active island summary. */
  public static final String INFO = "openoneblock.command.info";

  /** Permission to request and confirm owner island deletion. */
  public static final String DELETE = "openoneblock.command.delete";

  /** Permission to request and confirm owner island reset. */
  public static final String RESET = "openoneblock.command.reset";

  /** Permission to inspect persisted island and cached runtime diagnostics. */
  public static final String ADMIN_INSPECT = "openoneblock.admin.inspect";

  /** Permission to list and inspect durable lifecycle operations. */
  public static final String ADMIN_OPERATION = "openoneblock.admin.operation";

  private OpenOneBlockPermissions() {}
}
