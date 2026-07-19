package dev.openoneblock.protection;

import dev.openoneblock.api.id.WorldId;
import java.util.Objects;

/**
 * Platform-neutral immutable block location.
 *
 * @param worldId stable platform world UUID
 * @param blockX block X coordinate
 * @param blockY block Y coordinate
 * @param blockZ block Z coordinate
 */
public record ProtectionPosition(WorldId worldId, int blockX, int blockY, int blockZ) {
  /** Validates the world identity. */
  public ProtectionPosition {
    Objects.requireNonNull(worldId, "worldId");
  }
}
