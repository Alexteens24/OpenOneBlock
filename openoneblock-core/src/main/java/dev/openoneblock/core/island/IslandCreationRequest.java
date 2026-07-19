package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable intent to atomically establish an island, owner membership, and reserved slot.
 *
 * @param islandId new stable island identity
 * @param ownerId initial owner identity
 * @param shardGroupId target shared-world shard group
 * @param operationId idempotency and recovery identity
 * @param initialBorderSize initial playable border size
 * @param maximumBorderSize maximum reserved border size
 * @param context durable replay-critical world and gameplay inputs
 * @param requestedAt caller-supplied instant from an injected clock
 */
public record IslandCreationRequest(
    IslandId islandId,
    PlayerId ownerId,
    ShardGroupId shardGroupId,
    OperationId operationId,
    int initialBorderSize,
    int maximumBorderSize,
    IslandCreationContext context,
    Instant requestedAt) {
  /** Validates creation metadata before persistence work begins. */
  public IslandCreationRequest {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(ownerId, "ownerId");
    Objects.requireNonNull(shardGroupId, "shardGroupId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(requestedAt, "requestedAt");
    if (initialBorderSize <= 0 || maximumBorderSize < initialBorderSize) {
      throw new IllegalArgumentException("invalid island border sizes");
    }
  }

  /**
   * Returns the deterministic identity of immutable creation intent, excluding request time.
   *
   * @return lowercase SHA-256 fingerprint
   */
  public String fingerprint() {
    String descriptor =
        String.join(
            "|",
            islandId.toString(),
            ownerId.toString(),
            shardGroupId.toString(),
            Integer.toString(initialBorderSize),
            Integer.toString(maximumBorderSize),
            context.fingerprintDescriptor());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(descriptor.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JVM does not provide SHA-256", exception);
    }
  }
}
