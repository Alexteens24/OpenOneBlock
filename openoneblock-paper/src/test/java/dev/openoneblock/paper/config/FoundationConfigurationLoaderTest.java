package dev.openoneblock.paper.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.core.grid.GridConfiguration;
import dev.openoneblock.paper.config.AtomicConfigurationRegistry.ReloadResult;
import dev.openoneblock.paper.config.ProvisionedWorldHeightValidator.ProvisionedWorldHeight;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FoundationConfigurationLoaderTest {
  @TempDir Path dataDirectory;

  private FoundationConfigurationLoader loader;

  @BeforeEach
  void installDefaults() throws IOException {
    new DefaultConfigurationInstaller(getClass().getClassLoader()).install(dataDirectory);
    loader = new FoundationConfigurationLoader();
  }

  @Test
  void installsCompleteLayoutAndLoadsStableTypedDefaults() throws Exception {
    FoundationConfigurationSnapshot first = loader.load(dataDirectory);
    FoundationConfigurationSnapshot second = loader.load(dataDirectory);

    assertEquals(GridConfiguration.DEFAULT, first.grid());
    assertEquals("sqlite", first.database().type());
    assertEquals("openoneblock_overworld", first.worlds().getFirst().worldName());
    assertEquals("openoneblock:plains", first.defaults().phaseId().toString());
    assertEquals(8, first.team().maximumSize());
    assertEquals(300, first.team().invitationExpirySeconds());
    assertEquals(
        java.util.Set.of(
            "owner", "co_owner", "moderator", "member", "trusted", "visitor", "banned"),
        first.roles().keySet());
    assertEquals(first, second);
    assertEquals(64, first.fingerprint().length());
    assertTrue(Files.isDirectory(dataDirectory.resolve("phases")));
    assertTrue(Files.isDirectory(dataDirectory.resolve("rules")));
    assertTrue(Files.isDirectory(dataDirectory.resolve("structures")));
    assertTrue(Files.isDirectory(dataDirectory.resolve("loot")));
    assertTrue(Files.isDirectory(dataDirectory.resolve("profiles")));
    assertTrue(Files.isDirectory(dataDirectory.resolve("integrations")));
  }

  @Test
  void installerNeverOverwritesOperatorOwnedFiles() throws Exception {
    Path messages = dataDirectory.resolve("messages.yml");
    String operatorText = Files.readString(messages).replace("locale: en_US", "locale: vi_VN");
    Files.writeString(messages, operatorText);

    new DefaultConfigurationInstaller(getClass().getClassLoader()).install(dataDirectory);

    assertTrue(Files.readString(messages).contains("locale: vi_VN"));
    assertEquals("vi_VN", loader.load(dataDirectory).messages().locale());
  }

  @Test
  void invalidGeometryFailsBeforeAnyWorldProvisioning() throws Exception {
    replace("worlds.yml", "cell-size: 512", "cell-size: 500");

    ConfigurationValidationException exception =
        assertThrows(ConfigurationValidationException.class, () -> loader.load(dataDirectory));

    assertTrue(
        exception.problems().stream()
            .anyMatch(
                problem ->
                    problem.file().equals("worlds.yml")
                        && problem.path().equals("grid.cell-size")
                        && problem.expected().equals("valid grid geometry")));
  }

  @Test
  void duplicateWorldNameAndProjectionAreRejected() throws Exception {
    replace(
        "worlds.yml",
        "        seed: 0",
        "        seed: 0\n"
            + "      - id: openoneblock:overworld\n"
            + "        world-name: openoneblock_overworld\n"
            + "        environment: NORMAL\n"
            + "        seed: 1");

    ConfigurationValidationException exception =
        assertThrows(ConfigurationValidationException.class, () -> loader.load(dataDirectory));

    assertTrue(
        exception.problems().stream()
            .anyMatch(problem -> problem.expected().contains("globally unique world name")));
    assertTrue(
        exception.problems().stream()
            .anyMatch(problem -> problem.expected().contains("unique dimension")));
  }

  @Test
  void unknownFieldsReportActionableLocationAndKeepActiveSnapshot() throws Exception {
    AtomicConfigurationRegistry registry = new AtomicConfigurationRegistry();
    ReloadResult.Accepted accepted =
        assertInstanceOf(ReloadResult.Accepted.class, registry.reload(loader, dataDirectory));
    Files.writeString(
        dataDirectory.resolve("config.yml"),
        Files.readString(dataDirectory.resolve("config.yml")) + "obsolete-setting: true\n");

    ReloadResult.Rejected rejected =
        assertInstanceOf(ReloadResult.Rejected.class, registry.reload(loader, dataDirectory));

    assertSame(accepted.snapshot(), registry.active().orElseThrow());
    assertTrue(
        rejected.problems().stream()
            .anyMatch(
                problem ->
                    problem.file().equals("config.yml")
                        && problem.path().equals("obsolete-setting")
                        && problem.actual().equals("unknown field")));
  }

  @Test
  void roleInheritanceCycleIsRejected() throws Exception {
    Files.writeString(
        dataDirectory.resolve("roles.yml"),
        """
        schema-version: 1
        roles:
          owner:
            inherits: [member]
            permissions: ['*']
          member:
            inherits: [owner]
            permissions: [BLOCK_BREAK]
        """);

    ConfigurationValidationException exception =
        assertThrows(ConfigurationValidationException.class, () -> loader.load(dataDirectory));

    assertFalse(exception.problems().isEmpty());
    assertTrue(
        exception.problems().stream()
            .anyMatch(problem -> problem.expected().equals("acyclic role inheritance")));
  }

  @Test
  void unknownRolePermissionIsRejectedInsteadOfSilentlyIgnored() throws Exception {
    replace("roles.yml", "      - BLOCK_BREAK", "      - BLOCK_BRAKE");

    ConfigurationValidationException exception =
        assertThrows(ConfigurationValidationException.class, () -> loader.load(dataDirectory));

    assertTrue(
        exception.problems().stream()
            .anyMatch(
                problem ->
                    problem.file().equals("roles.yml")
                        && problem.path().endsWith("permissions")
                        && problem.expected().contains("known uppercase island permission")));
  }

  @Test
  void atomicReloadKeepsPreviousRolesWhenOwnerWildcardIsRemoved() throws Exception {
    AtomicConfigurationRegistry registry = new AtomicConfigurationRegistry();
    ReloadResult.Accepted accepted =
        assertInstanceOf(ReloadResult.Accepted.class, registry.reload(loader, dataDirectory));
    replace("roles.yml", "      - '*'", "      - BLOCK_BREAK");

    ReloadResult.Rejected rejected =
        assertInstanceOf(ReloadResult.Rejected.class, registry.reload(loader, dataDirectory));

    assertSame(accepted.snapshot(), registry.active().orElseThrow());
    assertTrue(
        rejected.problems().stream()
            .anyMatch(
                problem ->
                    problem.file().equals("roles.yml")
                        && problem.path().equals("roles.owner.permissions")
                        && problem.expected().contains("effective '*'")));
  }

  @Test
  void duplicateYamlKeysAreRejectedBySafeParser() throws Exception {
    Files.writeString(
        dataDirectory.resolve("messages.yml"),
        Files.readString(dataDirectory.resolve("messages.yml")) + "locale: vi_VN\n");

    ConfigurationValidationException exception =
        assertThrows(ConfigurationValidationException.class, () -> loader.load(dataDirectory));

    assertTrue(
        exception.problems().stream()
            .anyMatch(
                problem ->
                    problem.file().equals("messages.yml") && problem.path().equals("<root>")));
  }

  @Test
  void configuredBuildHeightIsCheckedAgainstProvisionedWorld() throws Exception {
    FoundationConfigurationSnapshot configuration = loader.load(dataDirectory);
    ProvisionedWorldHeightValidator validator = new ProvisionedWorldHeightValidator();

    validator.validate(
        configuration, List.of(new ProvisionedWorldHeight("openoneblock_overworld", -64, 320)));
    ConfigurationValidationException exception =
        assertThrows(
            ConfigurationValidationException.class,
            () ->
                validator.validate(
                    configuration,
                    List.of(new ProvisionedWorldHeight("openoneblock_overworld", 0, 256))));

    assertTrue(
        exception.problems().stream()
            .anyMatch(
                problem ->
                    problem.file().equals("worlds.yml") && problem.path().equals("build-height")));
  }

  @Test
  void worldGeometryFingerprintIgnoresLoggingButChangesWithLayout() throws Exception {
    FoundationConfigurationSnapshot original = loader.load(dataDirectory);
    String originalFingerprint = WorldGeometryFingerprint.from(original);
    replace("config.yml", "debug: false", "debug: true");
    FoundationConfigurationSnapshot loggingChanged = loader.load(dataDirectory);
    replace("worlds.yml", "cell-size: 512", "cell-size: 528");
    FoundationConfigurationSnapshot geometryChanged = loader.load(dataDirectory);

    assertEquals(originalFingerprint, WorldGeometryFingerprint.from(loggingChanged));
    assertFalse(originalFingerprint.equals(WorldGeometryFingerprint.from(geometryChanged)));
  }

  private void replace(String file, String target, String replacement) throws IOException {
    Path path = dataDirectory.resolve(file);
    String original = Files.readString(path);
    assertTrue(original.contains(target), "default fixture changed: " + target);
    Files.writeString(path, original.replace(target, replacement));
  }
}
