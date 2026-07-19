package dev.openoneblock.paper.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Installs missing commented defaults without overwriting operator-owned files. */
public final class DefaultConfigurationInstaller {
  private static final List<String> FILES =
      List.of("config.yml", "worlds.yml", "islands.yml", "roles.yml", "messages.yml");
  private static final List<String> DIRECTORIES =
      List.of("phases", "rules", "structures", "loot", "profiles", "integrations", "data");

  private final ClassLoader resources;

  /**
   * Creates an installer using the supplied plugin resource loader.
   *
   * @param resources class loader containing {@code defaults/}
   */
  public DefaultConfigurationInstaller(ClassLoader resources) {
    this.resources = Objects.requireNonNull(resources, "resources");
  }

  /**
   * Creates the complete data layout and copies only missing default files.
   *
   * @param dataDirectory plugin data directory
   * @throws IOException when a directory or default cannot be installed
   */
  public void install(Path dataDirectory) throws IOException {
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    Files.createDirectories(dataDirectory);
    for (String directory : DIRECTORIES) {
      Files.createDirectories(dataDirectory.resolve(directory));
    }
    for (String file : FILES) {
      installFile(dataDirectory, file);
    }
  }

  private void installFile(Path dataDirectory, String file) throws IOException {
    Path destination = dataDirectory.resolve(file);
    if (Files.exists(destination)) {
      return;
    }
    try (InputStream input = resources.getResourceAsStream("defaults/" + file)) {
      if (input == null) {
        throw new IOException("Missing bundled default: " + file);
      }
      Files.copy(input, destination);
    }
  }
}
