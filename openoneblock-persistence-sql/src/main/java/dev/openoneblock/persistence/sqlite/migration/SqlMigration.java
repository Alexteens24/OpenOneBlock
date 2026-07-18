package dev.openoneblock.persistence.sqlite.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Immutable ordered SQL migration.
 *
 * @param version positive monotonically increasing version
 * @param description stable human-readable description
 * @param statements ordered single SQL statements
 */
public record SqlMigration(int version, String description, List<String> statements) {
  /** Validates and defensively copies migration content. */
  public SqlMigration {
    if (version <= 0) {
      throw new IllegalArgumentException("migration version must be positive");
    }
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(statements, "statements");
    if (description.isBlank()
        || statements.isEmpty()
        || statements.stream().anyMatch(String::isBlank)) {
      throw new IllegalArgumentException("migration description and statements must not be blank");
    }
    statements = List.copyOf(statements);
  }

  /**
   * Returns a stable SHA-256 checksum of version, description, and SQL statements.
   *
   * @return lowercase hexadecimal checksum
   */
  public String checksum() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(Integer.toString(version).getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(description.getBytes(StandardCharsets.UTF_8));
      for (String statement : statements) {
        digest.update((byte) 0);
        digest.update(statement.getBytes(StandardCharsets.UTF_8));
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JVM does not provide SHA-256", exception);
    }
  }
}
