package dev.openoneblock.core.locator;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.grid.GridGeometry;
import java.util.Objects;
import java.util.function.Function;

/** O(1) platform-independent location resolver used by protection and gameplay event adapters. */
public final class IslandLocationResolver {
  private final WorldProjectionRegistry worlds;
  private final Function<ShardGroupId, GridGeometry> geometryByShard;
  private final InMemorySlotLocatorIndex slots;

  /**
   * Creates a resolver backed only by immutable configuration and the minimal slot index.
   *
   * @param worlds immutable world UUID registry
   * @param geometryByShard validated shard geometry lookup
   * @param slots committed non-free slot index
   */
  public IslandLocationResolver(
      WorldProjectionRegistry worlds,
      Function<ShardGroupId, GridGeometry> geometryByShard,
      InMemorySlotLocatorIndex slots) {
    this.worlds = Objects.requireNonNull(worlds, "worlds");
    this.geometryByShard = Objects.requireNonNull(geometryByShard, "geometryByShard");
    this.slots = Objects.requireNonNull(slots, "slots");
  }

  /**
   * Resolves one block location without database, chunk, metadata, or aggregate access.
   *
   * @param worldId platform world UUID
   * @param blockX block X coordinate
   * @param blockZ block Z coordinate
   * @return explicit unmanaged, out-of-range, empty, resolved, or conflicted result
   */
  public IslandLocationLookup lookup(WorldId worldId, int blockX, int blockZ) {
    Objects.requireNonNull(worldId, "worldId");
    WorldProjection projection = worlds.resolve(worldId).orElse(null);
    if (projection == null) {
      return new IslandLocationLookup.UnmanagedWorld(worldId);
    }
    GridGeometry geometry =
        Objects.requireNonNull(
            geometryByShard.apply(projection.shardGroupId()),
            "No grid geometry configured for shard " + projection.shardGroupId());
    GridPosition gridPosition;
    try {
      gridPosition = geometry.gridAt(blockX, blockZ);
    } catch (IllegalArgumentException exception) {
      return new IslandLocationLookup.OutsideManagedRange(projection, blockX, blockZ);
    }
    return switch (slots.lookup(projection.shardGroupId(), gridPosition)) {
      case SlotLocatorLookup.Empty ignored ->
          new IslandLocationLookup.EmptyCell(projection, gridPosition);
      case SlotLocatorLookup.Resolved resolved ->
          new IslandLocationLookup.Resolved(projection, resolved.entry());
      case SlotLocatorLookup.Conflicted conflicted ->
          new IslandLocationLookup.Conflicted(projection, conflicted);
    };
  }
}
