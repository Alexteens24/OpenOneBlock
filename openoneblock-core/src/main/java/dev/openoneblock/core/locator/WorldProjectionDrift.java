package dev.openoneblock.core.locator;

import dev.openoneblock.api.id.DimensionId;
import dev.openoneblock.api.id.ShardGroupId;
import java.util.Objects;

/**
 * One fail-closed persisted/configured/provisioned identity mismatch.
 *
 * @param shardGroupId affected shard group
 * @param dimensionId affected dimension
 * @param kind exact mismatch kind
 * @param expected authoritative expected value
 * @param actual candidate actual value
 */
public record WorldProjectionDrift(
    ShardGroupId shardGroupId,
    DimensionId dimensionId,
    WorldProjectionDriftKind kind,
    String expected,
    String actual) {
  /** Validates diagnostic values. */
  public WorldProjectionDrift {
    Objects.requireNonNull(shardGroupId, "shardGroupId");
    Objects.requireNonNull(dimensionId, "dimensionId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(actual, "actual");
  }
}
