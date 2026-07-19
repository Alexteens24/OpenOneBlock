package dev.openoneblock.paper.world;

import dev.openoneblock.api.id.DimensionId;
import dev.openoneblock.api.id.ShardGroupId;
import java.util.Objects;
import java.util.regex.Pattern;
import org.bukkit.World;

/**
 * Validated configuration for one shared void-world dimension projection.
 *
 * @param worldName safe server world name
 * @param shardGroupId logical shard group
 * @param dimensionId configured dimension identity
 * @param environment Vanilla environment metadata
 * @param seed deterministic world seed
 */
public record SharedWorldSpec(
    String worldName,
    ShardGroupId shardGroupId,
    DimensionId dimensionId,
    World.Environment environment,
    long seed) {
  private static final Pattern SAFE_WORLD_NAME = Pattern.compile("[a-z0-9._-]+");

  /** Validates world identity and prevents path-like configured names. */
  public SharedWorldSpec {
    Objects.requireNonNull(worldName, "worldName");
    Objects.requireNonNull(shardGroupId, "shardGroupId");
    Objects.requireNonNull(dimensionId, "dimensionId");
    Objects.requireNonNull(environment, "environment");
    if (!SAFE_WORLD_NAME.matcher(worldName).matches()) {
      throw new IllegalArgumentException("Unsafe shared world name: " + worldName);
    }
  }
}
