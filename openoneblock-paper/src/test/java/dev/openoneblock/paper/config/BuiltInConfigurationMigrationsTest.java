package dev.openoneblock.paper.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuiltInConfigurationMigrationsTest {
  @TempDir Path dataDirectory;

  @Test
  void upgradesPreviouslyShippedTeamConfigsWithBackupsAndPreservesCustomRoles() throws Exception {
    new DefaultConfigurationInstaller(getClass().getClassLoader()).install(dataDirectory);
    Path islands = dataDirectory.resolve("islands.yml");
    Path roles = dataDirectory.resolve("roles.yml");
    Files.writeString(
        islands,
        Files.readString(islands)
            .replace("schema-version: 2", "schema-version: 1")
            .replace("team:\n  maximum-size: 8\n  invitation-expiry-seconds: 300\n", ""));
    Files.writeString(
        roles,
        """
        # previously shipped operator-owned roles
        schema-version: 1
        roles:
          owner:
            inherits: []
            permissions: ['*']
          member:
            inherits: []
            permissions: [BLOCK_BREAK]
          visitor:
            inherits: []
            permissions: []
          banned:
            inherits: []
            permissions: []
          builder:
            inherits: [member]
            permissions: [BLOCK_PLACE]
        """);

    ConfigurationMigrator.MigrationReport report =
        BuiltInConfigurationMigrations.migrator()
            .migrate(dataDirectory, BuiltInConfigurationMigrations.targetVersions());
    FoundationConfigurationSnapshot loaded =
        new FoundationConfigurationLoader().load(dataDirectory);

    assertEquals(2, report.backupsByFile().size());
    assertTrue(Files.exists(dataDirectory.resolve("islands.yml.v1.bak")));
    assertTrue(Files.exists(dataDirectory.resolve("roles.yml.v1.bak")));
    assertEquals(8, loaded.team().maximumSize());
    assertEquals(1000, loaded.roles().get("owner").authority());
    assertEquals(400, loaded.roles().get("builder").authority());
    assertTrue(
        loaded
            .roles()
            .keySet()
            .containsAll(
                java.util.Set.of(
                    "co_owner", "moderator", "member", "trusted", "visitor", "banned")));
  }
}
