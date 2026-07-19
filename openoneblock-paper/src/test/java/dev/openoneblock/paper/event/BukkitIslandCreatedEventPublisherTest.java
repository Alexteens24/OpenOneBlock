package dev.openoneblock.paper.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.openoneblock.api.event.IslandCreatedEvent;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.platform.PlatformTaskScheduler;
import dev.openoneblock.core.platform.RegionTaskTarget;
import dev.openoneblock.core.platform.ScheduledWork;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

class BukkitIslandCreatedEventPublisherTest {
  @Test
  void publishesImmutablePayloadOnlyFromGlobalScheduler() {
    IslandCreatedEvent payload =
        new IslandCreatedEvent(
            IslandId.generate(),
            PlayerId.of(UUID.randomUUID()),
            OperationId.generate(),
            WorldId.of(UUID.randomUUID()),
            Instant.parse("2026-07-19T09:00:00Z"),
            true);
    AtomicReference<Event> published = new AtomicReference<>();
    PluginManager pluginManager =
        proxy(
            PluginManager.class,
            (proxy, method, arguments) -> {
              if (method.getName().equals("callEvent")) {
                published.set((Event) arguments[0]);
                return null;
              }
              return defaultValue(proxy, method, arguments);
            });
    Server server =
        proxy(
            Server.class,
            (proxy, method, arguments) ->
                method.getName().equals("getPluginManager")
                    ? pluginManager
                    : defaultValue(proxy, method, arguments));
    GlobalOnlyScheduler scheduler = new GlobalOnlyScheduler();

    new BukkitIslandCreatedEventPublisher(server, scheduler)
        .publish(payload)
        .toCompletableFuture()
        .join();

    assertEquals(1, scheduler.globalDispatches.get());
    assertEquals(payload, ((PaperIslandCreatedEvent) published.get()).event());
  }

  private static final class GlobalOnlyScheduler implements PlatformTaskScheduler {
    private final AtomicInteger globalDispatches = new AtomicInteger();

    @Override
    public <T> CompletionStage<T> global(ScheduledWork<T> work) {
      globalDispatches.incrementAndGet();
      try {
        return CompletableFuture.completedFuture(work.execute());
      } catch (Exception failure) {
        return CompletableFuture.failedFuture(failure);
      }
    }

    @Override
    public <T> CompletionStage<T> region(RegionTaskTarget target, ScheduledWork<T> work) {
      return CompletableFuture.failedFuture(new AssertionError("unexpected region dispatch"));
    }

    @Override
    public <T> CompletionStage<T> async(ScheduledWork<T> work) {
      return CompletableFuture.failedFuture(new AssertionError("unexpected async dispatch"));
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
