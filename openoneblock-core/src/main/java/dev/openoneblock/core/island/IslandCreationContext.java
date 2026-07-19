package dev.openoneblock.core.island;

import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.WorldId;
import java.util.Objects;

/**
 * Durable gameplay and world inputs required to replay one creation plan after restart.
 *
 * @param primaryWorldId verified primary world projection
 * @param profileId initial Magic Block profile
 * @param phaseId initial progression phase
 * @param starterBlockId initial Vanilla Magic Block content
 * @param magicBlockY vertical Magic Block coordinate
 * @param minimumY inclusive preparation height
 * @param maximumYExclusive exclusive preparation height
 */
public record IslandCreationContext(
    WorldId primaryWorldId,
    NamespacedId profileId,
    NamespacedId phaseId,
    NamespacedId starterBlockId,
    int magicBlockY,
    int minimumY,
    int maximumYExclusive) {
  /** Validates every replay-critical creation input. */
  public IslandCreationContext {
    Objects.requireNonNull(primaryWorldId, "primaryWorldId");
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(phaseId, "phaseId");
    Objects.requireNonNull(starterBlockId, "starterBlockId");
    if (!starterBlockId.namespace().equals("minecraft")) {
      throw new IllegalArgumentException("starter block must use minecraft namespace");
    }
    if (minimumY >= maximumYExclusive) {
      throw new IllegalArgumentException("creation build height is empty");
    }
    if (magicBlockY < minimumY || magicBlockY >= maximumYExclusive - 1) {
      throw new IllegalArgumentException("Magic Block and spawn must fit creation build height");
    }
  }

  String fingerprintDescriptor() {
    return String.join(
        "|",
        primaryWorldId.toString(),
        profileId.toString(),
        phaseId.toString(),
        starterBlockId.toString(),
        Integer.toString(magicBlockY),
        Integer.toString(minimumY),
        Integer.toString(maximumYExclusive));
  }
}
