package dev.openoneblock.paper.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Computes the durable identity of world layout rules that cannot drift implicitly. */
public final class WorldGeometryFingerprint {
  private static final int VOID_GENERATOR_SCHEMA = 1;

  private WorldGeometryFingerprint() {}

  /**
   * Hashes only layout-affecting configuration, excluding messages and operational tuning.
   *
   * @param configuration validated startup configuration
   * @return lowercase SHA-256 geometry identity
   */
  public static String from(FoundationConfigurationSnapshot configuration) {
    Objects.requireNonNull(configuration, "configuration");
    String canonical =
        "void-generator="
            + VOID_GENERATOR_SCHEMA
            + ";cell-size="
            + configuration.grid().cellSize()
            + ";initial-border="
            + configuration.grid().initialBorder()
            + ";maximum-border="
            + configuration.grid().maximumBorder()
            + ";safety-gap="
            + configuration.grid().safetyGap()
            + ";minimum-y="
            + configuration.buildHeight().minimumY()
            + ";maximum-y-exclusive="
            + configuration.buildHeight().maximumYExclusive();
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JVM does not provide SHA-256", exception);
    }
  }
}
