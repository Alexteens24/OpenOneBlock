package dev.openoneblock.paper.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/** Applies registered adjacent config migrations with backups and same-filesystem atomic writes. */
public final class ConfigurationMigrator {
  private final Map<MigrationKey, ManagedConfigMigration> migrations;

  /**
   * Creates a migrator and rejects ambiguous migration edges.
   *
   * @param migrations registered comment-preserving migrations
   */
  public ConfigurationMigrator(List<ManagedConfigMigration> migrations) {
    Objects.requireNonNull(migrations, "migrations");
    Map<MigrationKey, ManagedConfigMigration> indexed = new HashMap<>();
    for (ManagedConfigMigration migration : migrations) {
      MigrationKey key = new MigrationKey(migration.fileName(), migration.fromVersion());
      if (indexed.putIfAbsent(key, migration) != null) {
        throw new IllegalArgumentException("duplicate config migration edge: " + key);
      }
    }
    this.migrations = Map.copyOf(indexed);
  }

  /**
   * Migrates each requested managed file to its target version.
   *
   * <p>A file is transformed entirely in memory, validated after every edge, and then published by
   * one atomic replacement. Its original content is retained as {@code file.vN.bak}.
   *
   * @param dataDirectory plugin data directory
   * @param targetVersions managed file to desired schema version
   * @return immutable report of changed files and backups
   * @throws ConfigurationMigrationException when an edge, file, or transformed schema is invalid
   */
  public MigrationReport migrate(Path dataDirectory, Map<String, Integer> targetVersions)
      throws ConfigurationMigrationException {
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    Objects.requireNonNull(targetVersions, "targetVersions");
    Map<String, Path> changed = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> target : targetVersions.entrySet()) {
      Path backup = migrateFile(dataDirectory, target.getKey(), target.getValue());
      if (backup != null) {
        changed.put(target.getKey(), backup);
      }
    }
    return new MigrationReport(changed);
  }

  /**
   * Restores a known version backup through an atomic replacement.
   *
   * @param dataDirectory plugin data directory
   * @param fileName safe managed file name
   * @param backedUpVersion schema version encoded in the backup name
   * @throws ConfigurationMigrationException when the backup cannot be verified or restored
   */
  public void restoreBackup(Path dataDirectory, String fileName, int backedUpVersion)
      throws ConfigurationMigrationException {
    Path file = safeFile(dataDirectory, fileName);
    Path backup = backupPath(file, backedUpVersion);
    try {
      String content = Files.readString(backup, StandardCharsets.UTF_8);
      int actualVersion = schemaVersion(fileName, content);
      if (actualVersion != backedUpVersion) {
        throw new ConfigurationMigrationException(
            "Backup schema mismatch for "
                + fileName
                + ": expected "
                + backedUpVersion
                + ", got "
                + actualVersion);
      }
      atomicWrite(file, content);
    } catch (IOException exception) {
      throw new ConfigurationMigrationException("Failed to restore backup " + backup, exception);
    }
  }

  private Path migrateFile(Path directory, String fileName, int targetVersion)
      throws ConfigurationMigrationException {
    Path file = safeFile(directory, fileName);
    String original;
    try {
      original = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new ConfigurationMigrationException("Failed to read managed config " + file, exception);
    }
    int initialVersion = schemaVersion(fileName, original);
    if (initialVersion > targetVersion) {
      throw new ConfigurationMigrationException(
          fileName
              + " uses future schema "
              + initialVersion
              + "; this build supports up to "
              + targetVersion);
    }
    if (initialVersion == targetVersion) {
      return null;
    }

    Path backup = backupPath(file, initialVersion);
    try {
      if (Files.notExists(backup)) {
        Files.copy(file, backup);
      } else if (!Files.readString(backup, StandardCharsets.UTF_8).equals(original)) {
        throw new ConfigurationMigrationException(
            "Existing migration backup does not match current "
                + fileName
                + "; preserve both files and repair manually");
      }
    } catch (IOException exception) {
      throw new ConfigurationMigrationException(
          "Failed to create migration backup " + backup, exception);
    }

    String candidate = original;
    int version = initialVersion;
    while (version < targetVersion) {
      ManagedConfigMigration migration = migrations.get(new MigrationKey(fileName, version));
      if (migration == null) {
        throw new ConfigurationMigrationException(
            "No migration registered for " + fileName + " schema " + version);
      }
      try {
        candidate =
            Objects.requireNonNull(migration.transformer().apply(candidate), "migration result");
      } catch (RuntimeException exception) {
        throw new ConfigurationMigrationException(
            "Migration failed for " + fileName + " schema " + version, exception);
      }
      int transformedVersion = schemaVersion(fileName, candidate);
      if (transformedVersion != migration.toVersion()) {
        throw new ConfigurationMigrationException(
            "Migration for "
                + fileName
                + " must produce schema "
                + migration.toVersion()
                + ", got "
                + transformedVersion);
      }
      version = transformedVersion;
    }

    try {
      atomicWrite(file, candidate);
      return backup;
    } catch (IOException exception) {
      throw new ConfigurationMigrationException(
          "Failed to publish migrated config " + file + "; original backup is " + backup,
          exception);
    }
  }

  private static int schemaVersion(String fileName, String content)
      throws ConfigurationMigrationException {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setAllowRecursiveKeys(false);
    try {
      Object loaded = new Yaml(new SafeConstructor(options)).load(content);
      if (loaded instanceof Map<?, ?> map && map.get("schema-version") instanceof Number number) {
        return number.intValue();
      }
      throw new ConfigurationMigrationException(
          fileName + " must contain an integer schema-version before migration");
    } catch (YAMLException exception) {
      throw new ConfigurationMigrationException(
          fileName + " is not valid YAML and cannot be migrated safely", exception);
    }
  }

  private static Path safeFile(Path directory, String fileName)
      throws ConfigurationMigrationException {
    if (!fileName.matches("[a-z0-9_-]+\\.yml")) {
      throw new ConfigurationMigrationException("Unsafe managed config file name: " + fileName);
    }
    return directory.resolve(fileName);
  }

  private static Path backupPath(Path file, int version) {
    return file.resolveSibling(file.getFileName() + ".v" + version + ".bak");
  }

  private static void atomicWrite(Path destination, String content) throws IOException {
    Path parent = Objects.requireNonNull(destination.getParent(), "destination parent");
    Path temporary =
        Files.createTempFile(parent, destination.getFileName().toString(), ".migration");
    try {
      Files.writeString(temporary, content, StandardCharsets.UTF_8);
      Files.move(
          temporary,
          destination,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  /**
   * Immutable migration outcome.
   *
   * @param backupsByFile changed file to retained original backup
   */
  public record MigrationReport(Map<String, Path> backupsByFile) {
    /** Defensively copies changed file results. */
    public MigrationReport {
      backupsByFile = Map.copyOf(backupsByFile);
    }
  }

  private record MigrationKey(String fileName, int fromVersion) {}
}
