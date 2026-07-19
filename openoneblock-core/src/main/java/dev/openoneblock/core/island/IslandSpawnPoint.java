package dev.openoneblock.core.island;

import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.core.world.WorldSpawnPosition;
import java.util.Objects;

/**
 * Immutable persisted island spawn definition.
 *
 * @param spawnId namespaced spawn identity
 * @param position precise verified target
 * @param primary whether this is the default home
 */
public record IslandSpawnPoint(NamespacedId spawnId, WorldSpawnPosition position, boolean primary) {
  /** Validates required spawn metadata. */
  public IslandSpawnPoint {
    Objects.requireNonNull(spawnId, "spawnId");
    Objects.requireNonNull(position, "position");
  }
}
