package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Durable exact-version owner intent to clean and archive an island.
 *
 * @param islandId target island
 * @param operationId idempotency identity
 * @param requestedBy authorized owner
 * @param expectedIslandVersion confirmation-bound aggregate version
 * @param minimumY inclusive cleanup height
 * @param maximumYExclusive exclusive cleanup height
 * @param requestedAt caller clock instant
 */
public record IslandDeletionRequest(
    IslandId islandId,
    OperationId operationId,
    PlayerId requestedBy,
    long expectedIslandVersion,
    int minimumY,
    int maximumYExclusive,
    Instant requestedAt) {
  /** Validates complete deletion intent. */
  public IslandDeletionRequest {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(requestedBy, "requestedBy");
    Objects.requireNonNull(requestedAt, "requestedAt");
    if (expectedIslandVersion < 0) {
      throw new IllegalArgumentException("expectedIslandVersion must be non-negative");
    }
    if (minimumY >= maximumYExclusive) {
      throw new IllegalArgumentException("cleanup height must not be empty");
    }
  }

  /**
   * Returns a stable fingerprint excluding request time and operation identity.
   *
   * @return lowercase SHA-256 fingerprint
   */
  public String fingerprint() {
    String descriptor =
        String.join(
            "|",
            islandId.toString(),
            requestedBy.toString(),
            Long.toString(expectedIslandVersion),
            Integer.toString(minimumY),
            Integer.toString(maximumYExclusive));
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
