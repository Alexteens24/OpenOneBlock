package dev.openoneblock.persistence.sqlite;

import java.io.Serial;

/** Unchecked infrastructure failure raised after SQLite retry policy is exhausted. */
public class SqlitePersistenceException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates a persistence failure.
   *
   * @param message diagnostic message
   * @param cause underlying SQL or infrastructure error
   */
  public SqlitePersistenceException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Creates a persistence failure without an underlying exception.
   *
   * @param message diagnostic message
   */
  public SqlitePersistenceException(String message) {
    super(message);
  }
}
