package dev.openoneblock.paper.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.grid.HorizontalBounds;
import dev.openoneblock.core.platform.PlatformTaskScheduler;
import dev.openoneblock.core.platform.RegionTaskTarget;
import dev.openoneblock.core.platform.ScheduledWork;
import dev.openoneblock.core.world.IslandStructurePlacement;
import dev.openoneblock.core.world.WorldBlockPosition;
import dev.openoneblock.core.world.WorldEffectKey;
import dev.openoneblock.core.world.WorldEffectOutcome;
import dev.openoneblock.core.world.WorldEffectPlan;
import dev.openoneblock.core.world.WorldSpawnPosition;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

class PaperIslandWorldPreparationTest {
  private static final OperationId OPERATION =
      OperationId.parse("00000000-0000-0000-0000-000000000041");
  private static final IslandId ISLAND = IslandId.parse("00000000-0000-0000-0000-000000000042");
  private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000043");
  private static final WorldId WORLD_ID = WorldId.of(WORLD_UUID);

  @Test
  void setsAndVerifiesVanillaBlockOnlyOnOwningRegion() {
    ImmediateScheduler scheduler = new ImmediateScheduler();
    FakeWorld fixture = new FakeWorld(scheduler);
    PaperIslandWorldPreparation preparation = preparation(fixture, scheduler);
    WorldEffectPlan.SetVanillaBlock effect = blockEffect();

    WorldEffectOutcome executed = join(preparation.execute(effect));
    WorldEffectOutcome verified = join(preparation.verify(effect));

    assertEquals(WorldEffectOutcome.Status.VERIFIED_SUCCESS, executed.status());
    assertEquals(WorldEffectOutcome.Status.VERIFIED_SUCCESS, verified.status());
    assertEquals(Material.GRASS_BLOCK, fixture.blocks.get(new BlockKey(0, 64, 0)));
    assertEquals(0, scheduler.ownershipViolations.get());
    assertEquals(effect.position().regionTarget(), scheduler.lastRegion);
  }

  @Test
  void missingExactBlockIsProvablyNotAppliedAndMayBeSafelyReplayed() {
    ImmediateScheduler scheduler = new ImmediateScheduler();
    FakeWorld fixture = new FakeWorld(scheduler);

    WorldEffectOutcome outcome = join(preparation(fixture, scheduler).verify(blockEffect()));

    assertEquals(WorldEffectOutcome.Status.NOT_APPLIED, outcome.status());
    assertTrue(outcome.diagnostic().contains("minecraft:air"));
  }

  @Test
  void validatesSpawnFloorFeetAndHeadInsideTheOwningRegion() {
    ImmediateScheduler scheduler = new ImmediateScheduler();
    FakeWorld fixture = new FakeWorld(scheduler);
    fixture.blocks.put(new BlockKey(0, 64, 0), Material.GRASS_BLOCK);
    WorldEffectPlan.VerifySafeSpawn effect =
        new WorldEffectPlan.VerifySafeSpawn(
            new WorldEffectKey(OPERATION, 0),
            ISLAND,
            new WorldSpawnPosition(WORLD_ID, 0.5, 65, 0.5, 0, 0));

    WorldEffectOutcome safe = join(preparation(fixture, scheduler).execute(effect));
    fixture.blocks.put(new BlockKey(0, 66, 0), Material.STONE);
    WorldEffectOutcome obstructed = join(preparation(fixture, scheduler).verify(effect));

    assertEquals(WorldEffectOutcome.Status.VERIFIED_SUCCESS, safe.status());
    assertEquals(WorldEffectOutcome.Status.VERIFIED_FAILURE, obstructed.status());
    assertEquals(0, scheduler.ownershipViolations.get());
  }

  @Test
  void capturesLoadedChunkOnRegionThenScansThreadSafeSnapshotAsync() {
    ImmediateScheduler scheduler = new ImmediateScheduler();
    FakeWorld fixture = new FakeWorld(scheduler);
    WorldEffectPlan.VerifyCleanRegion effect = cleanEffect();

    WorldEffectOutcome empty = join(preparation(fixture, scheduler).execute(effect));
    fixture.blocks.put(new BlockKey(5, 3, 7), Material.STONE);
    WorldEffectOutcome residue = join(preparation(fixture, scheduler).verify(effect));

    assertEquals(WorldEffectOutcome.Status.VERIFIED_SUCCESS, empty.status());
    assertEquals(WorldEffectOutcome.Status.VERIFIED_FAILURE, residue.status());
    assertTrue(residue.diagnostic().contains("5,3,7"));
    assertEquals(2, scheduler.asyncDispatches.get());
    assertEquals(0, scheduler.ownershipViolations.get());
  }

  @Test
  void refusesToLoadAnUnticketedChunkDuringCleanVerification() {
    ImmediateScheduler scheduler = new ImmediateScheduler();
    FakeWorld fixture = new FakeWorld(scheduler);
    fixture.chunkLoaded = false;

    CompletionException exception =
        assertThrows(
            CompletionException.class,
            () -> join(preparation(fixture, scheduler).execute(cleanEffect())));

    assertInstanceOf(IllegalStateException.class, exception.getCause());
    assertEquals(0, scheduler.asyncDispatches.get());
  }

  @Test
  void delegatesRegisteredStructureEffectsWithoutLinkingWorldEdit() {
    ImmediateScheduler scheduler = new ImmediateScheduler();
    FakeWorld fixture = new FakeWorld(scheduler);
    AtomicInteger calls = new AtomicInteger();
    IslandStructurePlacement structures =
        new IslandStructurePlacement() {
          @Override
          public CompletionStage<WorldEffectOutcome> execute(
              WorldEffectPlan.PlaceStructure effect) {
            calls.incrementAndGet();
            return success("structure provider verified placement");
          }

          @Override
          public CompletionStage<WorldEffectOutcome> verify(WorldEffectPlan.PlaceStructure effect) {
            calls.incrementAndGet();
            return success("structure marker found");
          }
        };
    PaperIslandWorldPreparation preparation =
        new PaperIslandWorldPreparation(fixture.server(), scheduler, structures);
    WorldEffectPlan.PlaceStructure effect =
        new WorldEffectPlan.PlaceStructure(
            new WorldEffectKey(OPERATION, 0),
            ISLAND,
            WORLD_ID,
            NamespacedId.parse("openoneblock:starter"),
            new WorldBlockPosition(WORLD_ID, 0, 64, 0),
            WorldEffectPlan.Rotation.NONE,
            WorldEffectPlan.Mirror.NONE,
            new HorizontalBounds(-4, -4, 4, 4),
            60,
            70);

    assertEquals(
        WorldEffectOutcome.Status.VERIFIED_SUCCESS, join(preparation.execute(effect)).status());
    assertEquals(
        WorldEffectOutcome.Status.VERIFIED_SUCCESS, join(preparation.verify(effect)).status());
    assertEquals(2, calls.get());
  }

  private static PaperIslandWorldPreparation preparation(
      FakeWorld fixture, ImmediateScheduler scheduler) {
    IslandStructurePlacement unavailable =
        new IslandStructurePlacement() {
          @Override
          public CompletionStage<WorldEffectOutcome> execute(
              WorldEffectPlan.PlaceStructure effect) {
            return CompletableFuture.failedFuture(
                new UnsupportedOperationException("no structure provider"));
          }

          @Override
          public CompletionStage<WorldEffectOutcome> verify(WorldEffectPlan.PlaceStructure effect) {
            return CompletableFuture.failedFuture(
                new UnsupportedOperationException("no structure provider"));
          }
        };
    return new PaperIslandWorldPreparation(
        fixture.server(), scheduler, unavailable, testMaterials());
  }

  private static PaperIslandWorldPreparation.VanillaMaterialAccess testMaterials() {
    return new PaperIslandWorldPreparation.VanillaMaterialAccess() {
      @Override
      public Material resolve(String namespacedId) {
        if (namespacedId.equals("minecraft:grass_block")) {
          return Material.GRASS_BLOCK;
        }
        throw new IllegalArgumentException("unknown test material: " + namespacedId);
      }

      @Override
      public boolean isAir(Material material) {
        return material == Material.AIR;
      }

      @Override
      public String key(Material material) {
        return "minecraft:" + material.name().toLowerCase(java.util.Locale.ROOT);
      }
    };
  }

  private static WorldEffectPlan.SetVanillaBlock blockEffect() {
    return new WorldEffectPlan.SetVanillaBlock(
        new WorldEffectKey(OPERATION, 0),
        ISLAND,
        new WorldBlockPosition(WORLD_ID, 0, 64, 0),
        NamespacedId.parse("minecraft:grass_block"));
  }

  private static WorldEffectPlan.VerifyCleanRegion cleanEffect() {
    return new WorldEffectPlan.VerifyCleanRegion(
        new WorldEffectKey(OPERATION, 0),
        ISLAND,
        WORLD_ID,
        new HorizontalBounds(0, 0, 16, 16),
        0,
        16);
  }

  private static CompletionStage<WorldEffectOutcome> success(String diagnostic) {
    return CompletableFuture.completedFuture(
        new WorldEffectOutcome(WorldEffectOutcome.Status.VERIFIED_SUCCESS, false, diagnostic));
  }

  private static <T> T join(CompletionStage<T> stage) {
    return stage.toCompletableFuture().join();
  }

  private record BlockKey(int x, int y, int z) {}

  private static final class FakeWorld {
    private final ImmediateScheduler scheduler;
    private final Map<BlockKey, Material> blocks = new HashMap<>();
    private final World world;
    private boolean chunkLoaded = true;

    private FakeWorld(ImmediateScheduler scheduler) {
      this.scheduler = scheduler;
      this.world = proxy(World.class, this::invokeWorld);
    }

    private Server server() {
      return proxy(
          Server.class,
          (ignored, method, arguments) ->
              method.getName().equals("getWorld") && WORLD_UUID.equals(arguments[0])
                  ? world
                  : defaultValue(ignored, method, arguments));
    }

    private Object invokeWorld(Object ignored, Method method, Object[] arguments) {
      return switch (method.getName()) {
        case "getUID" -> WORLD_UUID;
        case "getMinHeight" -> -64;
        case "getMaxHeight" -> 320;
        case "getBlockAt" -> block((int) arguments[0], (int) arguments[1], (int) arguments[2]);
        case "getChunkAt" -> chunk((int) arguments[0], (int) arguments[1]);
        default -> defaultValue(ignored, method, arguments);
      };
    }

    private Block block(int x, int y, int z) {
      BlockKey key = new BlockKey(x, y, z);
      return proxy(
          Block.class,
          (ignored, method, arguments) ->
              switch (method.getName()) {
                case "getType" -> blocks.getOrDefault(key, Material.AIR);
                case "setType" -> {
                  requireOwner(RegionTaskTarget.fromBlock(WORLD_ID, x, z));
                  blocks.put(key, (Material) arguments[0]);
                  yield null;
                }
                case "isCollidable" -> blocks.getOrDefault(key, Material.AIR) != Material.AIR;
                case "isPassable" -> blocks.getOrDefault(key, Material.AIR) == Material.AIR;
                default -> defaultValue(ignored, method, arguments);
              });
    }

    private Chunk chunk(int chunkX, int chunkZ) {
      RegionTaskTarget target = new RegionTaskTarget(WORLD_ID, chunkX, chunkZ);
      requireOwner(target);
      return proxy(
          Chunk.class,
          (ignored, method, arguments) ->
              switch (method.getName()) {
                case "getX" -> chunkX;
                case "getZ" -> chunkZ;
                case "isLoaded" -> chunkLoaded;
                case "getChunkSnapshot" -> snapshot(chunkX, chunkZ);
                default -> defaultValue(ignored, method, arguments);
              });
    }

    private ChunkSnapshot snapshot(int chunkX, int chunkZ) {
      return proxy(
          ChunkSnapshot.class,
          (ignored, method, arguments) ->
              switch (method.getName()) {
                case "getX" -> chunkX;
                case "getZ" -> chunkZ;
                case "isSectionEmpty" -> sectionEmpty((int) arguments[0], chunkX, chunkZ);
                case "getBlockType" ->
                    blocks.getOrDefault(
                        new BlockKey(
                            chunkX * 16 + (int) arguments[0],
                            (int) arguments[1],
                            chunkZ * 16 + (int) arguments[2]),
                        Material.AIR);
                default -> defaultValue(ignored, method, arguments);
              });
    }

    private boolean sectionEmpty(int section, int chunkX, int chunkZ) {
      return blocks.entrySet().stream()
          .noneMatch(
              entry ->
                  Math.floorDiv(entry.getKey().x(), 16) == chunkX
                      && Math.floorDiv(entry.getKey().z(), 16) == chunkZ
                      && Math.floorDiv(entry.getKey().y(), 16) == section
                      && entry.getValue() != Material.AIR);
    }

    private void requireOwner(RegionTaskTarget expected) {
      if (!expected.equals(scheduler.currentRegion)) {
        scheduler.ownershipViolations.incrementAndGet();
      }
    }
  }

  private static final class ImmediateScheduler implements PlatformTaskScheduler {
    private RegionTaskTarget currentRegion;
    private RegionTaskTarget lastRegion;
    private final AtomicInteger asyncDispatches = new AtomicInteger();
    private final AtomicInteger ownershipViolations = new AtomicInteger();

    @Override
    public <T> CompletionStage<T> global(ScheduledWork<T> work) {
      return CompletableFuture.failedFuture(new AssertionError("unexpected global dispatch"));
    }

    @Override
    public synchronized <T> CompletionStage<T> region(
        RegionTaskTarget target, ScheduledWork<T> work) {
      currentRegion = target;
      lastRegion = target;
      try {
        return CompletableFuture.completedFuture(work.execute());
      } catch (Throwable failure) {
        return CompletableFuture.failedFuture(failure);
      } finally {
        currentRegion = null;
      }
    }

    @Override
    public <T> CompletionStage<T> async(ScheduledWork<T> work) {
      asyncDispatches.incrementAndGet();
      try {
        return CompletableFuture.completedFuture(work.execute());
      } catch (Throwable failure) {
        return CompletableFuture.failedFuture(failure);
      }
    }
  }

  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }

  private static Object defaultValue(Object proxy, Method method, Object[] arguments) {
    return switch (method.getName()) {
      case "toString" -> "test-" + proxy.getClass().getInterfaces()[0].getSimpleName();
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == arguments[0];
      default -> primitiveDefault(method.getReturnType());
    };
  }

  private static Object primitiveDefault(Class<?> type) {
    if (!type.isPrimitive() || type == void.class) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    return 0;
  }
}
