package dev.openoneblock.paper.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationMigratorTest {
  @TempDir Path dataDirectory;

  @Test
  void migrationIsCommentPreservingBackedUpAndIdempotent() throws Exception {
    Path config = dataDirectory.resolve("config.yml");
    String original = "# operator comment\nschema-version: 0\nold-name: value\n";
    Files.writeString(config, original);
    ConfigurationMigrator migrator =
        new ConfigurationMigrator(
            List.of(
                new ManagedConfigMigration(
                    "config.yml",
                    0,
                    1,
                    text ->
                        text.replace("schema-version: 0", "schema-version: 1")
                            .replace("old-name:", "new-name:"))));

    ConfigurationMigrator.MigrationReport first =
        migrator.migrate(dataDirectory, Map.of("config.yml", 1));
    String migrated = Files.readString(config);
    Path backup = first.backupsByFile().get("config.yml");

    assertTrue(migrated.startsWith("# operator comment"));
    assertTrue(migrated.contains("new-name: value"));
    assertEquals(original, Files.readString(backup));
    assertTrue(migrator.migrate(dataDirectory, Map.of("config.yml", 1)).backupsByFile().isEmpty());

    migrator.restoreBackup(dataDirectory, "config.yml", 0);
    assertEquals(original, Files.readString(config));
  }

  @Test
  void failedTransformationLeavesOriginalAndRecoveryBackupUntouched() throws Exception {
    Path config = dataDirectory.resolve("config.yml");
    String original = "schema-version: 0\nvalue: safe\n";
    Files.writeString(config, original);
    ConfigurationMigrator migrator =
        new ConfigurationMigrator(
            List.of(
                new ManagedConfigMigration(
                    "config.yml", 0, 1, text -> text.replace("value: safe", "value: changed"))));

    assertThrows(
        ConfigurationMigrationException.class,
        () -> migrator.migrate(dataDirectory, Map.of("config.yml", 1)));

    assertEquals(original, Files.readString(config));
    assertEquals(original, Files.readString(dataDirectory.resolve("config.yml.v0.bak")));
  }
}
