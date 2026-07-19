package dev.openoneblock.core.magic;

import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.core.world.WorldBlockPosition;
import java.util.Objects;

/**
 * Immutable first Magic Block projection committed during island activation.
 *
 * @param magicBlockId island-local namespaced identity
 * @param position exact verified block position
 * @param profileId compiled content profile identity
 * @param currentContentId exact initial content identity
 */
public record InitialMagicBlock(
    NamespacedId magicBlockId,
    WorldBlockPosition position,
    NamespacedId profileId,
    NamespacedId currentContentId) {
  /** Validates complete initial Magic Block metadata. */
  public InitialMagicBlock {
    Objects.requireNonNull(magicBlockId, "magicBlockId");
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(currentContentId, "currentContentId");
  }
}
