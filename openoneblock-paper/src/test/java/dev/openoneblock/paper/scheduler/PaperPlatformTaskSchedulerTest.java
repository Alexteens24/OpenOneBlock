package dev.openoneblock.paper.scheduler;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.platform.EntityTaskUnavailableException;
import dev.openoneblock.core.platform.RegionTaskTarget;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class PaperPlatformTaskSchedulerTest {
  private static final UUID WORLD_UUID = UUID.fromString("1b74a82f-f2ee-4784-aa20-aa961e5b494d");

  private final Plugin plugin = proxy(Plugin.class, PaperPlatformTaskSchedulerTest::defaultValue);

  @Test
  void dispatchesGlobalAsyncAndRegionWorkThroughOwnershipAwareSchedulers() throws Exception {
    World world = proxy(World.class, PaperPlatformTaskSchedulerTest::defaultValue);
    AtomicReference<String> regionInvocation = new AtomicReference<>();
    Server server = server(world, regionInvocation);
    PaperPlatformTaskScheduler scheduler = new PaperPlatformTaskScheduler(plugin, server);

    assertEquals("global", await(scheduler.global(() -> "global")));
    assertEquals(42, await(scheduler.async(() -> 42)));
    assertEquals(
        "region",
        await(
            scheduler.region(new RegionTaskTarget(WorldId.of(WORLD_UUID), -3, 7), () -> "region")));
    assertEquals("-3:7", regionInvocation.get());
  }

  @Test
  void reportsMissingWorldAndScheduledWorkFailuresThroughCompletion() {
    PaperPlatformTaskScheduler scheduler =
        new PaperPlatformTaskScheduler(plugin, server(null, new AtomicReference<>()));

    ExecutionException missingWorld =
        assertThrows(
            ExecutionException.class,
            () ->
                await(
                    scheduler.region(
                        new RegionTaskTarget(WorldId.of(WORLD_UUID), 0, 0), () -> null)));
    assertInstanceOf(IllegalStateException.class, missingWorld.getCause());

    ExecutionException workFailure =
        assertThrows(
            ExecutionException.class,
            () ->
                await(
                    scheduler.global(
                        () -> {
                          throw new IllegalArgumentException("expected test failure");
                        })));
    assertInstanceOf(IllegalArgumentException.class, workFailure.getCause());
  }

  @Test
  void entityHandleRunsOnEntitySchedulerAndFailsWhenEntityRetires() throws Exception {
    EntityScheduler executing =
        proxy(
            EntityScheduler.class,
            (ignored, method, arguments) -> {
              if (method.getName().equals("execute")) {
                ((Runnable) arguments[1]).run();
                return true;
              }
              return defaultValue(ignored, method, arguments);
            });
    PaperEntityTaskHandle live = new PaperEntityTaskHandle(plugin, entityWithScheduler(executing));
    assertEquals("entity", await(live.schedule(() -> "entity")));

    EntityScheduler retired =
        proxy(
            EntityScheduler.class,
            (ignored, method, arguments) -> {
              if (method.getName().equals("execute")) {
                ((Runnable) arguments[2]).run();
                return true;
              }
              return defaultValue(ignored, method, arguments);
            });
    PaperEntityTaskHandle unavailable =
        new PaperEntityTaskHandle(plugin, entityWithScheduler(retired));
    ExecutionException failure =
        assertThrows(ExecutionException.class, () -> await(unavailable.schedule(() -> "never")));
    assertInstanceOf(EntityTaskUnavailableException.class, failure.getCause());
  }

  private Server server(World world, AtomicReference<String> regionInvocation) {
    GlobalRegionScheduler global =
        proxy(
            GlobalRegionScheduler.class,
            (ignored, method, arguments) -> {
              if (method.getName().equals("execute")) {
                ((Runnable) arguments[1]).run();
                return null;
              }
              return defaultValue(ignored, method, arguments);
            });
    AsyncScheduler async =
        proxy(
            AsyncScheduler.class,
            (ignored, method, arguments) -> {
              if (method.getName().equals("runNow")) {
                scheduledConsumer(arguments[1]).accept(scheduledTask());
                return scheduledTask();
              }
              return defaultValue(ignored, method, arguments);
            });
    RegionScheduler region =
        proxy(
            RegionScheduler.class,
            (ignored, method, arguments) -> {
              if (method.getName().equals("execute")) {
                regionInvocation.set(arguments[2] + ":" + arguments[3]);
                ((Runnable) arguments[4]).run();
                return null;
              }
              return defaultValue(ignored, method, arguments);
            });
    return proxy(
        Server.class,
        (ignored, method, arguments) ->
            switch (method.getName()) {
              case "getGlobalRegionScheduler" -> global;
              case "getAsyncScheduler" -> async;
              case "getRegionScheduler" -> region;
              case "getWorld" -> world;
              default -> defaultValue(ignored, method, arguments);
            });
  }

  private static Entity entityWithScheduler(EntityScheduler scheduler) {
    return proxy(
        Entity.class,
        (ignored, method, arguments) ->
            method.getName().equals("getScheduler")
                ? scheduler
                : defaultValue(ignored, method, arguments));
  }

  @SuppressWarnings("unchecked")
  private static Consumer<ScheduledTask> scheduledConsumer(Object argument) {
    return (Consumer<ScheduledTask>) argument;
  }

  private static ScheduledTask scheduledTask() {
    return proxy(ScheduledTask.class, PaperPlatformTaskSchedulerTest::defaultValue);
  }

  private static <T> T await(CompletionStage<T> completion) throws Exception {
    return completion.toCompletableFuture().get(5, SECONDS);
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
