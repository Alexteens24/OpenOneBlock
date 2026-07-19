package dev.openoneblock.paper.config;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * One adjacent, comment-preserving managed-file migration.
 *
 * @param fileName safe managed file name
 * @param fromVersion source schema version
 * @param toVersion target schema version
 * @param transformer text transformation which must update {@code schema-version}
 */
public record ManagedConfigMigration(
    String fileName, int fromVersion, int toVersion, UnaryOperator<String> transformer) {
  /** Validates an unambiguous adjacent migration edge. */
  public ManagedConfigMigration {
    Objects.requireNonNull(fileName, "fileName");
    Objects.requireNonNull(transformer, "transformer");
    if (!fileName.matches("[a-z0-9_-]+\\.yml")) {
      throw new IllegalArgumentException("unsafe managed config file name: " + fileName);
    }
    if (fromVersion < 0 || toVersion != fromVersion + 1) {
      throw new IllegalArgumentException("config migrations must advance exactly one version");
    }
  }
}
