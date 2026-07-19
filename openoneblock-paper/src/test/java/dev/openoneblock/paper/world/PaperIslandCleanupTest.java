package dev.openoneblock.paper.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.grid.HorizontalBounds;
import dev.openoneblock.core.platform.EntityTaskHandle;
import dev.openoneblock.core.platform.PlatformTaskScheduler;
import dev.openoneblock.core.platform.RegionTaskTarget;
import dev.openoneblock.core.platform.ScheduledWork;
import dev.openoneblock.core.world.IslandCleanup;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class PaperIslandCleanupTest {
  private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  private static final WorldId WORLD_ID = WorldId.of(WORLD_UUID);
  private static final IslandCleanup.Plan PLAN =
      new IslandCleanup.Plan(
          OperationId.parse("00000000-0000-0000-0000-0000000000d2"),
          IslandId.parse("00000000-0000-0000-0000-0000000000d3"),
          WORLD_ID,
          new HorizontalBounds(0, 0, 16, 16),
          0,
          16);

  @Test
  void capturesScansClearsAndVerifiesBlocksThroughOwningRegion() {
    ImmediateScheduler scheduler = new ImmediateScheduler();
    FakeWorld world = new FakeWorld(scheduler);
    world.blocks.put(new BlockKey(5, 7, 9), Material.GRASS_BLOCK);

    IslandCleanup.Result result = join(cleanup(world, scheduler).cleanup(PLAN));

    assertEquals(IslandCleanup.Status.VERIFIED_CLEAN, result.status());
    assertEquals(Material.AIR, world.blocks.get(new BlockKey(5, 7, 9)));
    assertEquals(0, scheduler.ownershipViolations.get());
    assertEquals(2, scheduler.asyncDispatches.get());
  }

  @Test
  void reportsVerifiedFailureWhenPaperDoesNotClearASelectedBlock() {
    ImmediateScheduler scheduler = new ImmediateScheduler();
    FakeWorld world = new FakeWorld(scheduler);
    world.blocks.put(new BlockKey(1, 2, 3), Material.STONE);
    world.refuseMutation = true;

    IslandCleanup.Result result = join(cleanup(world, scheduler).cleanup(PLAN));

    assertEquals(IslandCleanup.Status.VERIFIED_FAILURE, result.status());
    assertEquals(Material.STONE, world.blocks.get(new BlockKey(1, 2, 3)));
  }

  @Test
  void absentTicketFailsWithoutLoadingTheChunk() {
    ImmediateScheduler scheduler = new ImmediateScheduler();
    FakeWorld world = new FakeWorld(scheduler);
    world.chunkLoaded = false;

    CompletionException failure =
        assertThrows(
            CompletionException.class, () -> join(cleanup(world, scheduler).cleanup(PLAN)));

    assertInstanceOf(IllegalStateException.class, failure.getCause());
    assertEquals(0, scheduler.asyncDispatches.get());
  }

  @Test
  void removesNonPlayerEntitiesThroughEntityOwnedTasksAndVerifiesAbsence() {
    ImmediateScheduler scheduler = new ImmediateScheduler();
    FakeWorld world = new FakeWorld(scheduler);
    AtomicBoolean valid = world.addEntity(false, 2, 3, 4);
    AtomicInteger entityDispatches = new AtomicInteger();

    IslandCleanup.Result result = join(cleanup(world, scheduler, entityDispatches).cleanup(PLAN));

    assertEquals(IslandCleanup.Status.VERIFIED_CLEAN, result.status());
    assertEquals(false, valid.get());
    assertEquals(1, entityDispatches.get());
  }

  @Test
  void playerInsideCleanupBoundsFailsClosedWithoutRemovingThePlayer() {
    ImmediateScheduler scheduler = new ImmediateScheduler();
    FakeWorld world = new FakeWorld(scheduler);
    AtomicBoolean valid = world.addEntity(true, 2, 3, 4);
    AtomicInteger entityDispatches = new AtomicInteger();

    IslandCleanup.Result result = join(cleanup(world, scheduler, entityDispatches).cleanup(PLAN));

    assertEquals(IslandCleanup.Status.VERIFIED_FAILURE, result.status());
    assertEquals(true, valid.get());
    assertEquals(0, entityDispatches.get());
  }

  private static PaperIslandCleanup cleanup(FakeWorld world, ImmediateScheduler scheduler) {
    return cleanup(world, scheduler, new AtomicInteger());
  }

  private static PaperIslandCleanup cleanup(
      FakeWorld world, ImmediateScheduler scheduler, AtomicInteger entityDispatches) {
    Plugin plugin = proxy(Plugin.class, PaperIslandCleanupTest::defaultValue);
    return new PaperIslandCleanup(
        plugin,
        world.server(),
        scheduler,
        material -> material == Material.AIR,
        entity -> immediateEntityHandle(entityDispatches));
  }

  private static EntityTaskHandle immediateEntityHandle(AtomicInteger dispatches) {
    return new EntityTaskHandle() {
      @Override
      public <T> CompletionStage<T> schedule(ScheduledWork<T> work) {
        dispatches.incrementAndGet();
        try {
          return CompletableFuture.completedFuture(work.execute());
        } catch (Exception failure) {
          return CompletableFuture.failedFuture(failure);
        }
      }
    };
  }

  private static <T> T join(CompletionStage<T> stage) {
    return stage.toCompletableFuture().join();
  }

  private record BlockKey(int x, int y, int z) {}

  private static final class FakeWorld {
    private final ImmediateScheduler scheduler;
    private final Map<BlockKey, Material> blocks = new HashMap<>();
    private final List<Entity> entities = new ArrayList<>();
    private final World world;
    private boolean chunkLoaded = true;
    private boolean refuseMutation;

    private FakeWorld(ImmediateScheduler scheduler) {
      this.scheduler = scheduler;
      this.world = proxy(World.class, this::invokeWorld);
    }

    private Server server() {
      return proxy(
          Server.class,
          (proxy, method, arguments) ->
              method.getName().equals("getWorld") && WORLD_UUID.equals(arguments[0])
                  ? world
                  : defaultValue(proxy, method, arguments));
    }

    private AtomicBoolean addEntity(boolean player, double x, double y, double z) {
      AtomicBoolean valid = new AtomicBoolean(true);
      Class<? extends Entity> type = player ? Player.class : Entity.class;
      Entity entity =
          proxy(
              type,
              (proxy, method, arguments) ->
                  switch (method.getName()) {
                    case "getLocation" -> new Location(world, x, y, z);
                    case "remove" -> {
                      valid.set(false);
                      yield null;
                    }
                    case "isValid" -> valid.get();
                    default -> defaultValue(proxy, method, arguments);
                  });
      entities.add(entity);
      return valid;
    }

    private Object invokeWorld(Object proxy, Method method, Object[] arguments) {
      return switch (method.getName()) {
        case "getUID" -> WORLD_UUID;
        case "getMinHeight" -> -64;
        case "getMaxHeight" -> 320;
        case "getBlockAt" -> block((int) arguments[0], (int) arguments[1], (int) arguments[2]);
        case "getChunkAt" -> chunk((int) arguments[0], (int) arguments[1]);
        default -> defaultValue(proxy, method, arguments);
      };
    }

    private Block block(int x, int y, int z) {
      BlockKey key = new BlockKey(x, y, z);
      return proxy(
          Block.class,
          (proxy, method, arguments) ->
              switch (method.getName()) {
                case "getType" -> blocks.getOrDefault(key, Material.AIR);
                case "setType" -> {
                  requireOwner(RegionTaskTarget.fromBlock(WORLD_ID, x, z));
                  if (!refuseMutation) {
                    blocks.put(key, (Material) arguments[0]);
                  }
                  yield null;
                }
                default -> defaultValue(proxy, method, arguments);
              });
    }

    private Chunk chunk(int chunkX, int chunkZ) {
      RegionTaskTarget target = new RegionTaskTarget(WORLD_ID, chunkX, chunkZ);
      requireOwner(target);
      return proxy(
          Chunk.class,
          (proxy, method, arguments) ->
              switch (method.getName()) {
                case "isLoaded" -> chunkLoaded;
                case "getChunkSnapshot" -> snapshot(chunkX, chunkZ);
                case "getEntities" ->
                    entities.stream().filter(Entity::isValid).toArray(Entity[]::new);
                default -> defaultValue(proxy, method, arguments);
              });
    }

    private ChunkSnapshot snapshot(int chunkX, int chunkZ) {
      return proxy(
          ChunkSnapshot.class,
          (proxy, method, arguments) ->
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
                default -> defaultValue(proxy, method, arguments);
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
