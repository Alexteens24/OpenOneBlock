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
 * Exact-version administrative intent to retry a quarantined deletion cleanup.
 *
 * @param islandId quarantined island
 * @param operationId unique retry operation
 * @param requestedBy administrator requesting the retry
 * @param expectedIslandVersion confirmed broken-island version
 * @param expectedSlotVersion confirmed quarantined-slot version
 * @param minimumY inclusive cleanup floor
 * @param maximumYExclusive exclusive cleanup ceiling
 * @param requestedAt durable request time
 */
public record IslandCleanupRetryRequest(
    IslandId islandId,
    OperationId operationId,
    PlayerId requestedBy,
    long expectedIslandVersion,
    long expectedSlotVersion,
    int minimumY,
    int maximumYExclusive,
    Instant requestedAt) {
  /** Validates retry confirmation data. */
  public IslandCleanupRetryRequest {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(requestedBy, "requestedBy");
    Objects.requireNonNull(requestedAt, "requestedAt");
    if (expectedIslandVersion < 0 || expectedSlotVersion < 0) {
      throw new IllegalArgumentException("expected versions must be non-negative");
    }
    if (minimumY >= maximumYExclusive) {
      throw new IllegalArgumentException("cleanup height must not be empty");
    }
  }

  /**
   * Returns the canonical idempotency fingerprint.
   *
   * @return lowercase SHA-256 fingerprint
   */
  public String fingerprint() {
    String descriptor =
        String.join(
            "|",
            "deletion-cleanup-retry",
            islandId.toString(),
            requestedBy.toString(),
            Long.toString(expectedIslandVersion),
            Long.toString(expectedSlotVersion),
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
