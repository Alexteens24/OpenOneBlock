package dev.openoneblock.paper.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.platform.EntityTaskHandle;
import dev.openoneblock.core.platform.ScheduledWork;
import dev.openoneblock.core.world.WorldSpawnPosition;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class PaperIslandOwnerTeleporterTest {
  private static final UUID PLAYER_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
  private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
  private static final WorldSpawnPosition DESTINATION =
      new WorldSpawnPosition(WorldId.of(WORLD_UUID), 12.5, 65, -3.5, 90, 10);

  @Test
  void teleportsOnlineOwnerThroughEntityTaskAndVerifiesPaperOutcome() {
    AtomicReference<Location> destination = new AtomicReference<>();
    AtomicInteger entityDispatches = new AtomicInteger();
    Fixture fixture = fixture(true, true, destination);
    PaperIslandOwnerTeleporter teleporter =
        new PaperIslandOwnerTeleporter(
            fixture.plugin(), fixture.server(), player -> immediateHandle(entityDispatches));

    join(teleporter.teleport(PlayerId.of(PLAYER_UUID), DESTINATION, OperationId.generate()));

    assertEquals(1, entityDispatches.get());
    assertEquals(12.5, destination.get().getX());
    assertEquals(65, destination.get().getY());
    assertEquals(-3.5, destination.get().getZ());
    assertEquals(90, destination.get().getYaw());
    assertEquals(10, destination.get().getPitch());
  }

  @Test
  void offlineOwnerFailsBeforeEntityDispatch() {
    AtomicInteger entityDispatches = new AtomicInteger();
    Fixture fixture = fixture(false, true, new AtomicReference<>());
    PaperIslandOwnerTeleporter teleporter =
        new PaperIslandOwnerTeleporter(
            fixture.plugin(), fixture.server(), player -> immediateHandle(entityDispatches));

    CompletionException failure =
        assertThrows(
            CompletionException.class,
            () ->
                join(
                    teleporter.teleport(
                        PlayerId.of(PLAYER_UUID), DESTINATION, OperationId.generate())));

    assertInstanceOf(IllegalStateException.class, failure.getCause());
    assertEquals(0, entityDispatches.get());
  }

  @Test
  void rejectedPaperTeleportCompletesExceptionally() {
    Fixture fixture = fixture(true, false, new AtomicReference<>());
    PaperIslandOwnerTeleporter teleporter =
        new PaperIslandOwnerTeleporter(
            fixture.plugin(), fixture.server(), player -> immediateHandle(new AtomicInteger()));

    CompletionException failure =
        assertThrows(
            CompletionException.class,
            () ->
                join(
                    teleporter.teleport(
                        PlayerId.of(PLAYER_UUID), DESTINATION, OperationId.generate())));

    assertInstanceOf(IllegalStateException.class, failure.getCause());
  }

  private static Fixture fixture(
      boolean online, boolean teleportOutcome, AtomicReference<Location> destination) {
    World world =
        proxy(
            World.class,
            (proxy, method, arguments) ->
                method.getName().equals("getUID")
                    ? WORLD_UUID
                    : defaultValue(proxy, method, arguments));
    Player player =
        proxy(
            Player.class,
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "isOnline" -> online;
                  case "teleportAsync" -> {
                    destination.set((Location) arguments[0]);
                    yield CompletableFuture.completedFuture(teleportOutcome);
                  }
                  default -> defaultValue(proxy, method, arguments);
                });
    Server server =
        proxy(
            Server.class,
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "getPlayer" -> PLAYER_UUID.equals(arguments[0]) ? player : null;
                  case "getWorld" -> WORLD_UUID.equals(arguments[0]) ? world : null;
                  default -> defaultValue(proxy, method, arguments);
                });
    Plugin plugin = proxy(Plugin.class, PaperIslandOwnerTeleporterTest::defaultValue);
    return new Fixture(plugin, server);
  }

  private static EntityTaskHandle immediateHandle(AtomicInteger dispatches) {
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

  private record Fixture(Plugin plugin, Server server) {}
}
