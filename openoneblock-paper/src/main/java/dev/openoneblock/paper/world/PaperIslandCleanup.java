package dev.openoneblock.paper.world;

import dev.openoneblock.core.platform.EntityTaskHandle;
import dev.openoneblock.core.platform.PlatformTaskScheduler;
import dev.openoneblock.core.platform.RegionTaskTarget;
import dev.openoneblock.core.runtime.ChunkCoverage;
import dev.openoneblock.core.world.IslandCleanup;
import dev.openoneblock.paper.scheduler.PaperEntityTaskHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Native bounded cleanup used by creation rollback before the WorldEdit bridge is available. */
public final class PaperIslandCleanup implements IslandCleanup {
  private static final int CHUNKS_PER_BATCH = 16;

  private final Server server;
  private final PlatformTaskScheduler scheduler;
  private final AirMaterialAccess materials;
  private final Function<Entity, EntityTaskHandle> entityTasks;

  /**
   * Creates an ownership-safe cleanup provider.
   *
   * @param plugin task-owning plugin
   * @param server active Paper server
   * @param scheduler ownership-aware scheduler
   */
  public PaperIslandCleanup(Plugin plugin, Server server, PlatformTaskScheduler scheduler) {
    this(
        plugin,
        server,
        scheduler,
        Material::isAir,
        entity -> new PaperEntityTaskHandle(plugin, entity));
  }

  PaperIslandCleanup(
      Plugin plugin,
      Server server,
      PlatformTaskScheduler scheduler,
      AirMaterialAccess materials,
      Function<Entity, EntityTaskHandle> entityTasks) {
    Objects.requireNonNull(plugin, "plugin");
    this.server = Objects.requireNonNull(server, "server");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.materials = Objects.requireNonNull(materials, "materials");
    this.entityTasks = Objects.requireNonNull(entityTasks, "entityTasks");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<Result> cleanup(Plan plan) {
    Objects.requireNonNull(plan, "plan");
    World world = server.getWorld(plan.worldId().value());
    if (world == null || !world.getUID().equals(plan.worldId().value())) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("cleanup world is not a loaded verified projection"));
    }
    if (plan.minimumY() < world.getMinHeight() || plan.maximumYExclusive() > world.getMaxHeight()) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("cleanup exceeds provisioned world height"));
    }
    List<RegionTaskTarget> chunks = ChunkCoverage.covering(plan.worldId(), plan.bounds());
    return processBatch(world, plan, chunks, 0);
  }

  private CompletionStage<Result> processBatch(
      World world, Plan plan, List<RegionTaskTarget> chunks, int offset) {
    if (offset >= chunks.size()) {
      return CompletableFuture.completedFuture(
          new Result(Status.VERIFIED_CLEAN, "all bounded blocks and entities were removed"));
    }
    int end = Math.min(chunks.size(), offset + CHUNKS_PER_BATCH);
    List<CompletionStage<ChunkCleanupResult>> stages =
        chunks.subList(offset, end).stream()
            .map(target -> cleanupChunk(world, plan, target))
            .toList();
    return sequence(stages)
        .thenCompose(
            results -> {
              for (ChunkCleanupResult result : results) {
                if (!result.clean()) {
                  return CompletableFuture.completedFuture(
                      new Result(Status.VERIFIED_FAILURE, result.diagnostic()));
                }
              }
              return processBatch(world, plan, chunks, end);
            });
  }

  private CompletionStage<ChunkCleanupResult> cleanupChunk(
      World world, Plan plan, RegionTaskTarget target) {
    return capture(world, target)
        .thenCompose(snapshot -> scheduler.async(() -> inspect(plan, snapshot)))
        .thenCompose(dirty -> clearBlocksAndFindEntities(world, plan, target, dirty))
        .thenCompose(
            discovery -> {
              if (discovery.playerPresent()) {
                return CompletableFuture.completedFuture(
                    new ChunkCleanupResult(false, "player present inside creation cleanup bounds"));
              }
              List<CompletionStage<Boolean>> removals =
                  discovery.entities().stream()
                      .map(
                          entity ->
                              entityTasks
                                  .apply(entity)
                                  .schedule(
                                      () -> {
                                        entity.remove();
                                        return !entity.isValid();
                                      }))
                      .toList();
              return sequence(removals)
                  .thenCompose(
                      removed ->
                          removed.stream().allMatch(Boolean::booleanValue)
                              ? verifyChunk(world, plan, target)
                              : CompletableFuture.completedFuture(
                                  new ChunkCleanupResult(
                                      false, "Paper did not retire every cleanup entity")));
            });
  }

  private CompletionStage<ChunkSnapshot> capture(World world, RegionTaskTarget target) {
    return scheduler.region(
        target,
        () -> {
          Chunk chunk = world.getChunkAt(target.chunkX(), target.chunkZ(), false);
          if (chunk == null || !chunk.isLoaded()) {
            throw new IllegalStateException("required cleanup chunk ticket is absent");
          }
          return chunk.getChunkSnapshot(false, false, false);
        });
  }

  private CompletionStage<EntityDiscovery> clearBlocksAndFindEntities(
      World world, Plan plan, RegionTaskTarget target, DirtyBlocks dirty) {
    return scheduler.region(
        target,
        () -> {
          for (int packed : dirty.positions()) {
            int localX = packed & 15;
            int localZ = (packed >>> 4) & 15;
            int y = plan.minimumY() + (packed >>> 8);
            var block =
                world.getBlockAt(
                    Math.addExact(Math.multiplyExact(target.chunkX(), 16), localX),
                    y,
                    Math.addExact(Math.multiplyExact(target.chunkZ(), 16), localZ));
            block.setType(Material.AIR, false);
            if (!materials.isAir(block.getType())) {
              return new EntityDiscovery(List.of(), false);
            }
          }
          Chunk chunk = world.getChunkAt(target.chunkX(), target.chunkZ(), false);
          List<Entity> entities = new ArrayList<>();
          boolean playerPresent = false;
          for (Entity entity : chunk.getEntities()) {
            var location = entity.getLocation();
            if (plan.bounds().contains(location.getBlockX(), location.getBlockZ())
                && location.getY() >= plan.minimumY()
                && location.getY() < plan.maximumYExclusive()) {
              if (entity instanceof Player) {
                playerPresent = true;
              } else {
                entities.add(entity);
              }
            }
          }
          return new EntityDiscovery(List.copyOf(entities), playerPresent);
        });
  }

  private CompletionStage<ChunkCleanupResult> verifyChunk(
      World world, Plan plan, RegionTaskTarget target) {
    return capture(world, target)
        .thenCompose(snapshot -> scheduler.async(() -> inspect(plan, snapshot)))
        .thenCompose(
            remaining ->
                scheduler.region(
                    target,
                    () -> {
                      if (remaining.positions().length != 0) {
                        return new ChunkCleanupResult(
                            false, "bounded block cleanup verification found residue");
                      }
                      Chunk chunk = world.getChunkAt(target.chunkX(), target.chunkZ(), false);
                      for (Entity entity : chunk.getEntities()) {
                        var location = entity.getLocation();
                        if (plan.bounds().contains(location.getBlockX(), location.getBlockZ())
                            && location.getY() >= plan.minimumY()
                            && location.getY() < plan.maximumYExclusive()) {
                          return new ChunkCleanupResult(
                              false, "bounded entity cleanup verification found residue");
                        }
                      }
                      return new ChunkCleanupResult(true, "chunk cleanup verified");
                    }));
  }

  private DirtyBlocks inspect(Plan plan, ChunkSnapshot snapshot) {
    int[] positions = new int[32];
    int count = 0;
    int baseX = Math.multiplyExact(snapshot.getX(), 16);
    int baseZ = Math.multiplyExact(snapshot.getZ(), 16);
    int minimumX = Math.max(plan.bounds().minX(), baseX);
    int maximumX = Math.min(plan.bounds().maxXExclusive(), baseX + 16);
    int minimumZ = Math.max(plan.bounds().minZ(), baseZ);
    int maximumZ = Math.min(plan.bounds().maxZExclusive(), baseZ + 16);
    for (int section = Math.floorDiv(plan.minimumY(), 16);
        section <= Math.floorDiv(plan.maximumYExclusive() - 1, 16);
        section++) {
      if (snapshot.isSectionEmpty(section)) {
        continue;
      }
      int minimumY = Math.max(plan.minimumY(), section * 16);
      int maximumY = Math.min(plan.maximumYExclusive(), section * 16 + 16);
      for (int x = minimumX; x < maximumX; x++) {
        for (int z = minimumZ; z < maximumZ; z++) {
          for (int y = minimumY; y < maximumY; y++) {
            if (!materials.isAir(snapshot.getBlockType(x - baseX, y, z - baseZ))) {
              if (count == positions.length) {
                positions = java.util.Arrays.copyOf(positions, Math.multiplyExact(count, 2));
              }
              positions[count++] = ((y - plan.minimumY()) << 8) | ((z - baseZ) << 4) | (x - baseX);
            }
          }
        }
      }
    }
    return new DirtyBlocks(java.util.Arrays.copyOf(positions, count));
  }

  private static <T> CompletionStage<List<T>> sequence(List<? extends CompletionStage<T>> stages) {
    CompletableFuture<?>[] futures =
        stages.stream().map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new);
    return CompletableFuture.allOf(futures)
        .thenApply(
            ignored -> stages.stream().map(stage -> stage.toCompletableFuture().join()).toList());
  }

  private record DirtyBlocks(int[] positions) {}

  private record EntityDiscovery(List<Entity> entities, boolean playerPresent) {}

  private record ChunkCleanupResult(boolean clean, String diagnostic) {}

  @FunctionalInterface
  interface AirMaterialAccess {
    boolean isAir(Material material);
  }
}
