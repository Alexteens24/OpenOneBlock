package dev.openoneblock.core.locator;

import dev.openoneblock.api.id.DimensionId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Configured and observed identity of one provisioned shared world.
 *
 * @param shardGroupId logical shard group
 * @param dimensionId dimension identity inside the shard
 * @param worldName configured safe world name
 * @param worldId actual provisioned world UUID
 * @param environment actual world environment
 * @param geometryFingerprint deterministic grid and build policy identity
 */
public record WorldProjectionDefinition(
    ShardGroupId shardGroupId,
    DimensionId dimensionId,
    String worldName,
    WorldId worldId,
    WorldEnvironment environment,
    String geometryFingerprint) {
  private static final Pattern SAFE_WORLD_NAME = Pattern.compile("[a-z0-9._-]+");
  private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

  /** Validates persisted projection identity fields. */
  public WorldProjectionDefinition {
    Objects.requireNonNull(shardGroupId, "shardGroupId");
    Objects.requireNonNull(dimensionId, "dimensionId");
    Objects.requireNonNull(worldName, "worldName");
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(environment, "environment");
    Objects.requireNonNull(geometryFingerprint, "geometryFingerprint");
    if (!SAFE_WORLD_NAME.matcher(worldName).matches()) {
      throw new IllegalArgumentException("unsafe configured world name: " + worldName);
    }
    if (!SHA_256.matcher(geometryFingerprint).matches()) {
      throw new IllegalArgumentException("geometryFingerprint must be lowercase SHA-256 hex");
    }
  }

  /**
   * Returns the hot-path projection view.
   *
   * @return immutable world UUID projection
   */
  public WorldProjection toRuntimeProjection() {
    return new WorldProjection(worldId, shardGroupId, dimensionId);
  }
}
