package dev.openoneblock.paper.runtime;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.platform.PlatformTaskScheduler;
import dev.openoneblock.core.platform.RegionTaskTarget;
import dev.openoneblock.core.platform.ScheduledWork;
import dev.openoneblock.core.runtime.IslandChunkTicketLease;
import dev.openoneblock.core.runtime.IslandChunkTicketRequest;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class PaperIslandChunkTicketControllerTest {
  private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000011");
  private static final WorldId WORLD_ID = WorldId.of(WORLD_UUID);
  private static final IslandId ISLAND_ID = IslandId.parse("00000000-0000-0000-0000-000000000012");
  private static final RegionTaskTarget FIRST = new RegionTaskTarget(WORLD_ID, -1, 2);
  private static final RegionTaskTarget SECOND = new RegionTaskTarget(WORLD_ID, 3, 4);

  @Test
  void acquiresAndReleasesEveryTicketOnItsOwningRegion() throws Exception {
    ImmediateRegionScheduler scheduler = new ImmediateRegionScheduler();
    TicketWorld fixture = new TicketWorld(scheduler, Map.of());
    PaperIslandChunkTicketController controller =
        new PaperIslandChunkTicketController(plugin(), fixture.server(), scheduler);

    IslandChunkTicketLease lease =
        controller.acquire(request(List.of(FIRST, SECOND))).toCompletableFuture().get(2, SECONDS);

    assertEquals(2, lease.chunkCount());
    assertEquals(2, fixture.additions.get());
    assertEquals(0, scheduler.ownershipViolations.get());
    assertEquals(List.of(FIRST, FIRST, SECOND, SECOND), scheduler.dispatched.subList(0, 4));

    lease.release().toCompletableFuture().get(2, SECONDS);

    assertEquals(2, fixture.removals.get());
    assertEquals(0, scheduler.ownershipViolations.get());
  }

  @Test
  void partialFailureRollsBackTicketsAlreadyAcquired() {
    ImmediateRegionScheduler scheduler = new ImmediateRegionScheduler();
    TicketWorld fixture = new TicketWorld(scheduler, Map.of(SECOND, ChunkBehavior.INVALID));
    PaperIslandChunkTicketController controller =
        new PaperIslandChunkTicketController(plugin(), fixture.server(), scheduler);

    CompletionException exception =
        assertThrows(
            CompletionException.class,
            () -> controller.acquire(request(List.of(FIRST, SECOND))).toCompletableFuture().join());

    assertInstanceOf(IllegalStateException.class, exception.getCause());
    assertEquals(1, fixture.additions.get());
    assertEquals(1, fixture.removals.get());
    assertTrue(fixture.ticketed.isEmpty());
    assertEquals(0, scheduler.ownershipViolations.get());
  }

  @Test
  void overlappingLogicalLeasesShareTheSinglePaperPluginTicket() throws Exception {
    ImmediateRegionScheduler scheduler = new ImmediateRegionScheduler();
    TicketWorld fixture = new TicketWorld(scheduler, Map.of());
    PaperIslandChunkTicketController controller =
        new PaperIslandChunkTicketController(plugin(), fixture.server(), scheduler);

    IslandChunkTicketLease first =
        controller.acquire(request(List.of(FIRST))).toCompletableFuture().get(2, SECONDS);
    IslandChunkTicketLease second =
        controller.acquire(request(List.of(FIRST))).toCompletableFuture().get(2, SECONDS);

    assertEquals(1, fixture.additions.get());
    first.release().toCompletableFuture().get(2, SECONDS);
    assertEquals(0, fixture.removals.get());
    second.release().toCompletableFuture().get(2, SECONDS);
    assertEquals(1, fixture.removals.get());
    assertTrue(fixture.ticketed.isEmpty());
  }

  @Test
  void disableFallbackRemovesAllPaperTicketsWithoutSchedulingNewWork() throws Exception {
    ImmediateRegionScheduler scheduler = new ImmediateRegionScheduler();
    TicketWorld fixture = new TicketWorld(scheduler, Map.of());
    PaperIslandChunkTicketController controller =
        new PaperIslandChunkTicketController(plugin(), fixture.server(), scheduler);
    IslandChunkTicketLease lease =
        controller.acquire(request(List.of(FIRST, SECOND))).toCompletableFuture().get(2, SECONDS);
    int dispatchesBeforeDisable = scheduler.dispatched.size();

    controller.emergencyReleaseAllOnDisable();

    assertTrue(fixture.ticketed.isEmpty());
    assertEquals(2, fixture.removals.get());
    assertEquals(dispatchesBeforeDisable, scheduler.dispatched.size());
    lease.release().toCompletableFuture().get(2, SECONDS);
    assertEquals(2, fixture.removals.get());
  }

  @Test
  void timeoutRollsBackCompletedChunksAndLateCompletionCannotLeakATicket() throws Exception {
    ImmediateRegionScheduler scheduler = new ImmediateRegionScheduler();
    CompletableFuture<Chunk> delayedChunk = new CompletableFuture<>();
    TicketWorld fixture =
        new TicketWorld(scheduler, Map.of(SECOND, new ChunkBehavior(delayedChunk, true)));
    PaperIslandChunkTicketController controller =
        new PaperIslandChunkTicketController(plugin(), fixture.server(), scheduler);
    IslandChunkTicketRequest request =
        new IslandChunkTicketRequest(
            OperationId.generate(), ISLAND_ID, List.of(FIRST, SECOND), Duration.ofMillis(100));

    Exception failure =
        assertThrows(
            Exception.class,
            () -> controller.acquire(request).toCompletableFuture().get(2, SECONDS));
    assertInstanceOf(java.util.concurrent.ExecutionException.class, failure);
    assertInstanceOf(java.util.concurrent.TimeoutException.class, failure.getCause());
    assertEquals(1, fixture.removals.get());

    delayedChunk.complete(fixture.chunk(SECOND, true));
    await(() -> fixture.removals.get() == 2);

    assertEquals(2, fixture.additions.get());
    assertEquals(2, fixture.removals.get());
    assertTrue(fixture.ticketed.isEmpty());
  }

  @Test
  void rejectsDuplicateTargetsAndMissingWorldBeforeScheduling() {
    ImmediateRegionScheduler scheduler = new ImmediateRegionScheduler();
    TicketWorld fixture = new TicketWorld(scheduler, Map.of());
    PaperIslandChunkTicketController controller =
        new PaperIslandChunkTicketController(plugin(), fixture.server(), scheduler);

    CompletionException duplicate =
        assertThrows(
            CompletionException.class,
            () -> controller.acquire(request(List.of(FIRST, FIRST))).toCompletableFuture().join());
    assertInstanceOf(IllegalArgumentException.class, duplicate.getCause());

    RegionTaskTarget missing =
        new RegionTaskTarget(
            WorldId.of(UUID.fromString("00000000-0000-0000-0000-000000000099")), 0, 0);
    CompletionException absent =
        assertThrows(
            CompletionException.class,
            () -> controller.acquire(request(List.of(missing))).toCompletableFuture().join());
    assertInstanceOf(IllegalStateException.class, absent.getCause());
    assertTrue(scheduler.dispatched.isEmpty());
  }

  private static IslandChunkTicketRequest request(List<RegionTaskTarget> chunks) {
    return new IslandChunkTicketRequest(
        OperationId.generate(), ISLAND_ID, chunks, Duration.ofSeconds(1));
  }

  private static Plugin plugin() {
    return proxy(Plugin.class, PaperIslandChunkTicketControllerTest::defaultValue);
  }

  private static void await(java.util.function.BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + SECONDS.toNanos(2);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertTrue(condition.getAsBoolean());
  }

  private record ChunkBehavior(CompletableFuture<Chunk> future, boolean loaded) {
    private static final ChunkBehavior INVALID = new ChunkBehavior(null, false);
  }

  private static final class TicketWorld {
    private final ImmediateRegionScheduler scheduler;
    private final Map<RegionTaskTarget, ChunkBehavior> behaviors;
    private final Map<RegionTaskTarget, Boolean> ticketed = new ConcurrentHashMap<>();
    private final AtomicInteger additions = new AtomicInteger();
    private final AtomicInteger removals = new AtomicInteger();
    private final AtomicReference<World> world = new AtomicReference<>();

    private TicketWorld(
        ImmediateRegionScheduler scheduler, Map<RegionTaskTarget, ChunkBehavior> behaviors) {
      this.scheduler = scheduler;
      this.behaviors = behaviors;
      world.set(proxy(World.class, this::invokeWorld));
    }

    private Server server() {
      return proxy(
          Server.class,
          (ignored, method, arguments) -> {
            if (method.getName().equals("getWorld") && WORLD_UUID.equals(arguments[0])) {
              return world.get();
            }
            if (method.getName().equals("getWorlds")) {
              return List.of(world.get());
            }
            return defaultValue(ignored, method, arguments);
          });
    }

    private Object invokeWorld(Object ignored, Method method, Object[] arguments) {
      return switch (method.getName()) {
        case "getUID" -> WORLD_UUID;
        case "getChunkAtAsync" -> {
          RegionTaskTarget target =
              new RegionTaskTarget(WORLD_ID, (int) arguments[0], (int) arguments[1]);
          requireOwner(target);
          ChunkBehavior behavior = behaviors.get(target);
          if (behavior != null && behavior.future() != null) {
            yield behavior.future();
          }
          boolean loaded = behavior == null || behavior.loaded();
          yield CompletableFuture.completedFuture(chunk(target, loaded));
        }
        case "removePluginChunkTicket" -> {
          RegionTaskTarget target =
              new RegionTaskTarget(WORLD_ID, (int) arguments[0], (int) arguments[1]);
          requireOwner(target);
          removals.incrementAndGet();
          yield ticketed.remove(target) != null;
        }
        case "removePluginChunkTickets" -> {
          int removed = ticketed.size();
          ticketed.clear();
          removals.addAndGet(removed);
          yield null;
        }
        default -> defaultValue(ignored, method, arguments);
      };
    }

    private Chunk chunk(RegionTaskTarget target, boolean loaded) {
      return proxy(
          Chunk.class,
          (ignored, method, arguments) ->
              switch (method.getName()) {
                case "getWorld" -> world.get();
                case "getX" -> target.chunkX();
                case "getZ" -> target.chunkZ();
                case "isLoaded" -> loaded;
                case "addPluginChunkTicket" -> {
                  requireOwner(target);
                  additions.incrementAndGet();
                  yield ticketed.putIfAbsent(target, Boolean.TRUE) == null;
                }
                default -> defaultValue(ignored, method, arguments);
              });
    }

    private void requireOwner(RegionTaskTarget expected) {
      if (!expected.equals(scheduler.current.get())) {
        scheduler.ownershipViolations.incrementAndGet();
      }
    }
  }

  private static final class ImmediateRegionScheduler implements PlatformTaskScheduler {
    private final ThreadLocal<RegionTaskTarget> current = new ThreadLocal<>();
    private final List<RegionTaskTarget> dispatched = new ArrayList<>();
    private final AtomicInteger ownershipViolations = new AtomicInteger();

    @Override
    public <T> CompletionStage<T> global(ScheduledWork<T> work) {
      return CompletableFuture.failedFuture(new AssertionError("unexpected global dispatch"));
    }

    @Override
    public synchronized <T> CompletionStage<T> region(
        RegionTaskTarget target, ScheduledWork<T> work) {
      dispatched.add(target);
      current.set(target);
      try {
        return CompletableFuture.completedFuture(work.execute());
      } catch (Throwable failure) {
        return CompletableFuture.failedFuture(failure);
      } finally {
        current.remove();
      }
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
