package dev.openoneblock.paper.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.platform.PlatformTaskScheduler;
import dev.openoneblock.core.platform.RegionTaskTarget;
import dev.openoneblock.core.platform.ScheduledWork;
import dev.openoneblock.core.world.WorldSpawnPosition;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class PaperIslandDestinationPreparerTest {
  private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

  @Test
  void dispatchesAsyncChunkPreparationFromOwningRegion() {
    AtomicReference<RegionTaskTarget> target = new AtomicReference<>();
    World world = world(true);
    Server server = server(world);
    PaperIslandDestinationPreparer preparer =
        new PaperIslandDestinationPreparer(server, immediateScheduler(target));
    WorldSpawnPosition destination =
        new WorldSpawnPosition(WorldId.of(WORLD_UUID), 33.5, 65, -17.5, 0, 0);

    preparer.prepare(destination, OperationId.generate()).toCompletableFuture().join();

    assertEquals(new RegionTaskTarget(WorldId.of(WORLD_UUID), 2, -2), target.get());
  }

  @Test
  void rejectsUnverifiedChunkOutcome() {
    World world = world(false);
    PaperIslandDestinationPreparer preparer =
        new PaperIslandDestinationPreparer(
            server(world), immediateScheduler(new AtomicReference<>()));

    CompletionException failure =
        assertThrows(
            CompletionException.class,
            () ->
                preparer
                    .prepare(
                        new WorldSpawnPosition(WorldId.of(WORLD_UUID), 0.5, 65, 0.5, 0, 0),
                        OperationId.generate())
                    .toCompletableFuture()
                    .join());

    assertInstanceOf(IllegalStateException.class, failure.getCause());
  }

  private static World world(boolean chunkLoaded) {
    AtomicReference<World> worldReference = new AtomicReference<>();
    World world =
        proxy(
            World.class,
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "getUID" -> WORLD_UUID;
                  case "getChunkAtAsync" ->
                      CompletableFuture.completedFuture(
                          chunk(
                              worldReference, (int) arguments[0], (int) arguments[1], chunkLoaded));
                  default -> defaultValue(proxy, method, arguments);
                });
    worldReference.set(world);
    return world;
  }

  private static Chunk chunk(AtomicReference<World> world, int chunkX, int chunkZ, boolean loaded) {
    return proxy(
        Chunk.class,
        (proxy, method, arguments) ->
            switch (method.getName()) {
              case "getWorld" -> world.get();
              case "getX" -> chunkX;
              case "getZ" -> chunkZ;
              case "isLoaded" -> loaded;
              default -> defaultValue(proxy, method, arguments);
            });
  }

  private static Server server(World world) {
    return proxy(
        Server.class,
        (proxy, method, arguments) ->
            method.getName().equals("getWorld") ? world : defaultValue(proxy, method, arguments));
  }

  private static PlatformTaskScheduler immediateScheduler(
      AtomicReference<RegionTaskTarget> target) {
    return new PlatformTaskScheduler() {
      @Override
      public <T> CompletionStage<T> global(ScheduledWork<T> work) {
        throw new AssertionError("unexpected global dispatch");
      }

      @Override
      public <T> CompletionStage<T> region(RegionTaskTarget region, ScheduledWork<T> work) {
        target.set(region);
        try {
          return CompletableFuture.completedFuture(work.execute());
        } catch (Exception exception) {
          return CompletableFuture.failedFuture(exception);
        }
      }

      @Override
      public <T> CompletionStage<T> async(ScheduledWork<T> work) {
        throw new AssertionError("unexpected async dispatch");
      }
    };
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
