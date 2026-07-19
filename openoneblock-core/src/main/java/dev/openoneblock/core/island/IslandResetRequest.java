package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.WorldId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Durable exact-version owner intent to rebuild an island in its existing logical slot.
 *
 * @param islandId target island
 * @param operationId idempotency identity
 * @param requestedBy authorized owner
 * @param expectedIslandVersion confirmation-bound aggregate version
 * @param primaryWorldId verified primary world projection
 * @param phaseId reset target phase
 * @param profileId reset target Magic Block profile
 * @param starterBlockId reset starter Vanilla block
 * @param magicBlockY reset Magic Block height
 * @param minimumY inclusive cleanup and preparation height
 * @param maximumYExclusive exclusive cleanup and preparation height
 * @param requestedAt caller clock instant
 */
public record IslandResetRequest(
    IslandId islandId,
    OperationId operationId,
    PlayerId requestedBy,
    long expectedIslandVersion,
    WorldId primaryWorldId,
    NamespacedId phaseId,
    NamespacedId profileId,
    NamespacedId starterBlockId,
    int magicBlockY,
    int minimumY,
    int maximumYExclusive,
    Instant requestedAt) {
  /** Validates every input needed to replay the reset after restart. */
  public IslandResetRequest {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(requestedBy, "requestedBy");
    Objects.requireNonNull(primaryWorldId, "primaryWorldId");
    Objects.requireNonNull(phaseId, "phaseId");
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(starterBlockId, "starterBlockId");
    Objects.requireNonNull(requestedAt, "requestedAt");
    if (expectedIslandVersion < 0) {
      throw new IllegalArgumentException("expectedIslandVersion must be non-negative");
    }
    if (!starterBlockId.namespace().equals("minecraft")) {
      throw new IllegalArgumentException("starter block must use minecraft namespace");
    }
    if (minimumY >= maximumYExclusive) {
      throw new IllegalArgumentException("reset height must not be empty");
    }
    if (magicBlockY < minimumY || magicBlockY >= maximumYExclusive - 1) {
      throw new IllegalArgumentException("Magic Block and spawn must fit reset height");
    }
  }

  /**
   * Returns the stable SHA-256 identity of this reset intent.
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
            primaryWorldId.toString(),
            phaseId.toString(),
            profileId.toString(),
            starterBlockId.toString(),
            Integer.toString(magicBlockY),
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
