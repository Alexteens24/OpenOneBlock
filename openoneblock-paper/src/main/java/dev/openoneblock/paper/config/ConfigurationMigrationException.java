package dev.openoneblock.paper.config;

/** Raised when a managed file cannot be migrated without risking operator data. */
public final class ConfigurationMigrationException extends Exception {
  private static final long serialVersionUID = 1L;

  /**
   * Creates a migration failure.
   *
   * @param message actionable failure description
   */
  public ConfigurationMigrationException(String message) {
    super(message);
  }

  /**
   * Creates a migration failure with its underlying filesystem or parser cause.
   *
   * @param message actionable failure description
   * @param cause underlying cause
   */
  public ConfigurationMigrationException(String message, Throwable cause) {
    super(message, cause);
  }
}
