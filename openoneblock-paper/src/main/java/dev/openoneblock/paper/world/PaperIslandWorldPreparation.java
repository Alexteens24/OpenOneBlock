package dev.openoneblock.paper.world;

import dev.openoneblock.core.platform.PlatformTaskScheduler;
import dev.openoneblock.core.platform.RegionTaskTarget;
import dev.openoneblock.core.runtime.ChunkCoverage;
import dev.openoneblock.core.world.IslandStructurePlacement;
import dev.openoneblock.core.world.IslandWorldPreparation;
import dev.openoneblock.core.world.WorldBlockPosition;
import dev.openoneblock.core.world.WorldEffectOutcome;
import dev.openoneblock.core.world.WorldEffectPlan;
import dev.openoneblock.core.world.WorldSpawnPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Minimal Vanilla preparation provider with region-owned mutation and async snapshot inspection.
 */
public final class PaperIslandWorldPreparation implements IslandWorldPreparation {
  private final Server server;
  private final PlatformTaskScheduler scheduler;
  private final IslandStructurePlacement structures;
  private final VanillaMaterialAccess materials;

  /**
   * Creates a Vanilla preparer with an explicit optional structure provider.
   *
   * @param server active Paper server
   * @param scheduler ownership-aware scheduler
   * @param structures optional structure-provider port
   */
  public PaperIslandWorldPreparation(
      Server server, PlatformTaskScheduler scheduler, IslandStructurePlacement structures) {
    this(server, scheduler, structures, new BukkitVanillaMaterialAccess());
  }

  PaperIslandWorldPreparation(
      Server server,
      PlatformTaskScheduler scheduler,
      IslandStructurePlacement structures,
      VanillaMaterialAccess materials) {
    this.server = Objects.requireNonNull(server, "server");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.structures = Objects.requireNonNull(structures, "structures");
    this.materials = Objects.requireNonNull(materials, "materials");
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<WorldEffectOutcome> execute(WorldEffectPlan effect) {
    Objects.requireNonNull(effect, "effect");
    World world;
    try {
      world = requireWorld(effect);
    } catch (RuntimeException exception) {
      return CompletableFuture.failedFuture(exception);
    }
    return switch (effect) {
      case WorldEffectPlan.VerifyCleanRegion clean -> verifyCleanRegion(world, clean);
      case WorldEffectPlan.SetVanillaBlock block -> setVanillaBlock(world, block);
      case WorldEffectPlan.VerifySafeSpawn spawn -> verifySafeSpawn(world, spawn.spawn());
      case WorldEffectPlan.PlaceStructure structure -> structures.execute(structure);
    };
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<WorldEffectOutcome> verify(WorldEffectPlan effect) {
    Objects.requireNonNull(effect, "effect");
    World world;
    try {
      world = requireWorld(effect);
    } catch (RuntimeException exception) {
      return CompletableFuture.failedFuture(exception);
    }
    return switch (effect) {
      case WorldEffectPlan.VerifyCleanRegion clean -> verifyCleanRegion(world, clean);
      case WorldEffectPlan.SetVanillaBlock block -> verifyVanillaBlock(world, block);
      case WorldEffectPlan.VerifySafeSpawn spawn -> verifySafeSpawn(world, spawn.spawn());
      case WorldEffectPlan.PlaceStructure structure -> structures.verify(structure);
    };
  }

  private CompletionStage<WorldEffectOutcome> verifyCleanRegion(
      World world, WorldEffectPlan.VerifyCleanRegion effect) {
    requireWorldHeight(world, effect.minimumY(), effect.maximumYExclusive());
    List<CompletionStage<ChunkSnapshot>> captures =
        ChunkCoverage.covering(effect.worldId(), effect.bounds()).stream()
            .map(target -> captureLoadedSnapshot(world, target))
            .toList();
    return sequence(captures)
        .thenCompose(snapshots -> scheduler.async(() -> inspectSnapshots(effect, snapshots)));
  }

  private CompletionStage<ChunkSnapshot> captureLoadedSnapshot(
      World world, RegionTaskTarget target) {
    return scheduler.region(
        target,
        () -> {
          Chunk chunk = world.getChunkAt(target.chunkX(), target.chunkZ(), false);
          if (chunk == null
              || !chunk.isLoaded()
              || chunk.getX() != target.chunkX()
              || chunk.getZ() != target.chunkZ()) {
            throw new IllegalStateException(
                "required chunk ticket is absent for " + target.chunkX() + "," + target.chunkZ());
          }
          return chunk.getChunkSnapshot(false, false, false);
        });
  }

  private CompletionStage<WorldEffectOutcome> setVanillaBlock(
      World world, WorldEffectPlan.SetVanillaBlock effect) {
    Material material = materials.resolve(effect.blockType().toString());
    requireWorldHeight(world, effect.position().y(), Math.addExact(effect.position().y(), 1));
    return scheduler.region(
        effect.position().regionTarget(),
        () -> {
          Block target = blockAt(world, effect.position());
          target.setType(material, false);
          if (target.getType() != material) {
            return failure(true, "Paper did not retain the requested Vanilla block state");
          }
          return success("exact Vanilla Magic Block state verified");
        });
  }

  private CompletionStage<WorldEffectOutcome> verifyVanillaBlock(
      World world, WorldEffectPlan.SetVanillaBlock effect) {
    Material material = materials.resolve(effect.blockType().toString());
    return scheduler.region(
        effect.position().regionTarget(),
        () -> {
          Material actual = blockAt(world, effect.position()).getType();
          if (actual == material) {
            return success("exact Vanilla block state already present");
          }
          return new WorldEffectOutcome(
              WorldEffectOutcome.Status.NOT_APPLIED,
              false,
              "target contains "
                  + materials.key(actual)
                  + " instead of "
                  + materials.key(material));
        });
  }

  private CompletionStage<WorldEffectOutcome> verifySafeSpawn(
      World world, WorldSpawnPosition spawn) {
    WorldBlockPosition feet = spawn.feetBlock();
    requireWorldHeight(world, Math.subtractExact(feet.y(), 1), Math.addExact(feet.y(), 2));
    return scheduler.region(
        feet.regionTarget(),
        () -> {
          Block floor = world.getBlockAt(feet.x(), feet.y() - 1, feet.z());
          Block feetBlock = world.getBlockAt(feet.x(), feet.y(), feet.z());
          Block headBlock = world.getBlockAt(feet.x(), feet.y() + 1, feet.z());
          if (!floor.isCollidable()
              || floor.isLiquid()
              || !feetBlock.isPassable()
              || feetBlock.isLiquid()
              || !headBlock.isPassable()
              || headBlock.isLiquid()) {
            return failure(
                false, "spawn requires a collidable floor and passable feet/head blocks");
          }
          return success("safe spawn floor, feet, and head space verified");
        });
  }

  private World requireWorld(WorldEffectPlan effect) {
    World world = server.getWorld(effect.worldId().value());
    if (world == null || !world.getUID().equals(effect.worldId().value())) {
      throw new IllegalStateException("world effect target is not a loaded verified projection");
    }
    return world;
  }

  private static void requireWorldHeight(World world, int minimumY, int maximumYExclusive) {
    if (minimumY < world.getMinHeight() || maximumYExclusive > world.getMaxHeight()) {
      throw new IllegalArgumentException("world effect exceeds provisioned world height");
    }
  }

  private static Block blockAt(World world, WorldBlockPosition position) {
    return world.getBlockAt(position.x(), position.y(), position.z());
  }

  private WorldEffectOutcome inspectSnapshots(
      WorldEffectPlan.VerifyCleanRegion effect, List<ChunkSnapshot> snapshots) {
    for (ChunkSnapshot snapshot : snapshots) {
      int baseX = Math.multiplyExact(snapshot.getX(), 16);
      int baseZ = Math.multiplyExact(snapshot.getZ(), 16);
      int minimumX = Math.max(effect.bounds().minX(), baseX);
      int maximumX = Math.min(effect.bounds().maxXExclusive(), baseX + 16);
      int minimumZ = Math.max(effect.bounds().minZ(), baseZ);
      int maximumZ = Math.min(effect.bounds().maxZExclusive(), baseZ + 16);
      int minimumSection = Math.floorDiv(effect.minimumY(), 16);
      int maximumSection = Math.floorDiv(effect.maximumYExclusive() - 1, 16);
      for (int section = minimumSection; section <= maximumSection; section++) {
        if (snapshot.isSectionEmpty(section)) {
          continue;
        }
        int minimumY = Math.max(effect.minimumY(), section * 16);
        int maximumY = Math.min(effect.maximumYExclusive(), section * 16 + 16);
        for (int x = minimumX; x < maximumX; x++) {
          for (int z = minimumZ; z < maximumZ; z++) {
            for (int y = minimumY; y < maximumY; y++) {
              Material material = snapshot.getBlockType(x - baseX, y, z - baseZ);
              if (!materials.isAir(material)) {
                return failure(
                    false,
                    "clean-cell verification found "
                        + materials.key(material)
                        + " at "
                        + x
                        + ","
                        + y
                        + ","
                        + z);
              }
            }
          }
        }
      }
    }
    return success("reserved region is verified empty");
  }

  private static WorldEffectOutcome success(String diagnostic) {
    return new WorldEffectOutcome(WorldEffectOutcome.Status.VERIFIED_SUCCESS, false, diagnostic);
  }

  private static WorldEffectOutcome failure(boolean cleanupRequired, String diagnostic) {
    return new WorldEffectOutcome(
        WorldEffectOutcome.Status.VERIFIED_FAILURE, cleanupRequired, diagnostic);
  }

  private static <T> CompletionStage<List<T>> sequence(List<? extends CompletionStage<T>> stages) {
    CompletableFuture<?>[] futures =
        stages.stream().map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new);
    return CompletableFuture.allOf(futures)
        .thenApply(
            ignored -> {
              List<T> values = new ArrayList<>(stages.size());
              stages.forEach(stage -> values.add(stage.toCompletableFuture().join()));
              return List.copyOf(values);
            });
  }

  interface VanillaMaterialAccess {
    Material resolve(String namespacedId);

    boolean isAir(Material material);

    String key(Material material);
  }

  private static final class BukkitVanillaMaterialAccess implements VanillaMaterialAccess {
    @Override
    public Material resolve(String namespacedId) {
      Material material = Material.matchMaterial(namespacedId);
      if (material == null || !material.isBlock()) {
        throw new IllegalArgumentException(
            "unknown or non-block Vanilla material: " + namespacedId);
      }
      return material;
    }

    @Override
    public boolean isAir(Material material) {
      return material.isAir();
    }

    @Override
    public String key(Material material) {
      return material.getKey().toString();
    }
  }
}
