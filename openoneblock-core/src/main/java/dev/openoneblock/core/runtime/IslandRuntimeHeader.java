package dev.openoneblock.core.runtime;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.grid.HorizontalBounds;
import java.util.Objects;

/**
 * Minimal immutable offline-safe island metadata needed to acquire chunk ownership.
 *
 * @param islandId durable island identity
 * @param worldId verified shared world UUID
 * @param gridPosition logical cell identity
 * @param requiredBounds horizontal bounds required by the operation
 */
public record IslandRuntimeHeader(
    IslandId islandId,
    WorldId worldId,
    GridPosition gridPosition,
    HorizontalBounds requiredBounds) {
  /** Validates minimal runtime metadata without loading an aggregate or world. */
  public IslandRuntimeHeader {
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(gridPosition, "gridPosition");
    Objects.requireNonNull(requiredBounds, "requiredBounds");
  }
}
