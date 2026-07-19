package dev.openoneblock.paper.packaging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class DistributableJarTest {
  @Test
  void containsPluginMetadataRuntimeModulesAndSqliteButNotPaperApi() throws IOException {
    String artifactPath = System.getProperty("openoneblock.distributableJar");
    assertNotNull(artifactPath, "Gradle must provide the distributable JAR path");

    try (JarFile jar = new JarFile(Path.of(artifactPath).toFile())) {
      assertNotNull(jar.getEntry("plugin.yml"));
      assertNotNull(jar.getEntry("defaults/config.yml"));
      assertNotNull(jar.getEntry("defaults/worlds.yml"));
      assertNotNull(jar.getEntry("defaults/islands.yml"));
      assertNotNull(jar.getEntry("defaults/roles.yml"));
      assertNotNull(jar.getEntry("defaults/messages.yml"));
      assertNotNull(jar.getEntry("dev/openoneblock/paper/OpenOneBlockPlugin.class"));
      assertNotNull(jar.getEntry("dev/openoneblock/paper/command/OpenOneBlockCommand.class"));
      assertNotNull(jar.getEntry("dev/openoneblock/core/island/IslandAggregateSnapshot.class"));
      assertNotNull(jar.getEntry("org/sqlite/JDBC.class"));
      assertNotNull(jar.getEntry("dev/openoneblock/internal/snakeyaml/Yaml.class"));
      assertFalse(jar.stream().anyMatch(entry -> entry.getName().startsWith("org/bukkit/")));
      assertFalse(
          jar.stream().anyMatch(entry -> entry.getName().startsWith("org/yaml/snakeyaml/")));

      String metadata =
          new String(
              jar.getInputStream(jar.getEntry("plugin.yml")).readAllBytes(),
              StandardCharsets.UTF_8);
      assertTrue(metadata.contains("name: OpenOneBlock"));
      assertTrue(metadata.contains("main: dev.openoneblock.paper.OpenOneBlockPlugin"));
      assertTrue(metadata.contains("version: '0.1.0-SNAPSHOT'"));
      assertTrue(metadata.contains("api-version: '1.21.11'"));
      assertTrue(metadata.contains("openoneblock.command.create:"));
      assertFalse(metadata.contains("folia-supported: true"));
    }
  }
}
