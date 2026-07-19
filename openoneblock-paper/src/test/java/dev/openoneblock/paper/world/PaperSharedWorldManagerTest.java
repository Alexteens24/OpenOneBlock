package dev.openoneblock.paper.world;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.id.DimensionId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.core.platform.PlatformTaskScheduler;
import dev.openoneblock.core.platform.RegionTaskTarget;
import dev.openoneblock.core.platform.ScheduledWork;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;
import org.junit.jupiter.api.Test;

class PaperSharedWorldManagerTest {
  private static final UUID WORLD_UUID = UUID.fromString("b8902d6f-e26f-4b6d-a2f7-779b1f03111f");
  private static final SharedWorldSpec SPECIFICATION =
      new SharedWorldSpec(
          "openoneblock_overworld",
          ShardGroupId.parse("openoneblock:primary"),
          DimensionId.parse("openoneblock:overworld"),
          World.Environment.NORMAL,
          812_733L);

  @Test
  void createsAndConfiguresAVerifiedVoidWorld() {
    AtomicReference<WorldCreator> capturedCreator = new AtomicReference<>();
    AtomicBoolean spawnFlagsDisabled = new AtomicBoolean();
    AtomicBoolean autoSaveEnabled = new AtomicBoolean();
    World world = world(capturedCreator, spawnFlagsDisabled, autoSaveEnabled, false);
    Server server =
        proxy(
            Server.class,
            (ignored, method, arguments) ->
                switch (method.getName()) {
                  case "getWorld" -> null;
                  case "createWorld" -> {
                    capturedCreator.set((WorldCreator) arguments[0]);
                    yield world;
                  }
                  default -> defaultValue(ignored, method, arguments);
                });

    World created = new BukkitVoidWorldFactory(server).createOrLoad(SPECIFICATION);

    assertEquals(world, created);
    WorldCreator creator = capturedCreator.get();
    assertEquals(SPECIFICATION.worldName(), creator.name());
    assertEquals(SPECIFICATION.environment(), creator.environment());
    assertEquals(SPECIFICATION.seed(), creator.seed());
    assertFalse(creator.generateStructures());
    assertInstanceOf(OpenOneBlockVoidChunkGenerator.class, creator.generator());
    assertTrue(spawnFlagsDisabled.get());
    assertTrue(autoSaveEnabled.get());
  }

  @Test
  void refusesExistingWorldWithUntrustedGeneratorOrNaturalStructures() {
    AtomicReference<WorldCreator> noCreator = new AtomicReference<>();
    World wrongGenerator =
        proxy(
            World.class,
            (ignored, method, arguments) ->
                switch (method.getName()) {
                  case "getEnvironment" -> World.Environment.NORMAL;
                  case "getGenerator" -> null;
                  case "canGenerateStructures" -> false;
                  default -> defaultValue(ignored, method, arguments);
                });
    Server wrongGeneratorServer = existingWorldServer(wrongGenerator);
    assertThrows(
        IllegalStateException.class,
        () -> new BukkitVoidWorldFactory(wrongGeneratorServer).createOrLoad(SPECIFICATION));

    World structuresEnabled = world(noCreator, new AtomicBoolean(), new AtomicBoolean(), true);
    Server structuresServer = existingWorldServer(structuresEnabled);
    assertThrows(
        IllegalStateException.class,
        () -> new BukkitVoidWorldFactory(structuresServer).createOrLoad(SPECIFICATION));
  }

  @Test
  void managerBuildsCoreProjectionOnlyInsideGlobalScheduledWork() throws Exception {
    AtomicBoolean globalDispatched = new AtomicBoolean();
    PlatformTaskScheduler scheduler = new ImmediateGlobalScheduler(globalDispatched);
    World world = world(new AtomicReference<>(), new AtomicBoolean(), new AtomicBoolean(), false);
    PaperSharedWorldManager manager = new PaperSharedWorldManager(scheduler, ignored -> world);

    ProvisionedSharedWorld provisioned =
        manager.provision(SPECIFICATION).toCompletableFuture().get(5, SECONDS);

    assertTrue(globalDispatched.get());
    assertEquals(WORLD_UUID, provisioned.projection().worldId().value());
    assertEquals(SPECIFICATION.shardGroupId(), provisioned.projection().shardGroupId());
    assertEquals(SPECIFICATION.dimensionId(), provisioned.projection().dimensionId());
  }

  @Test
  void rejectsPathLikeOrNonCanonicalWorldNamesBeforePlatformAccess() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SharedWorldSpec(
                "../unsafe",
                SPECIFICATION.shardGroupId(),
                SPECIFICATION.dimensionId(),
                World.Environment.NORMAL,
                1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SharedWorldSpec(
                "OpenOneBlock World",
                SPECIFICATION.shardGroupId(),
                SPECIFICATION.dimensionId(),
                World.Environment.NORMAL,
                1));
  }

  private static Server existingWorldServer(World world) {
    return proxy(
        Server.class,
        (ignored, method, arguments) ->
            method.getName().equals("getWorld") ? world : defaultValue(ignored, method, arguments));
  }

  private static World world(
      AtomicReference<WorldCreator> creator,
      AtomicBoolean spawnFlagsDisabled,
      AtomicBoolean autoSaveEnabled,
      boolean structuresEnabled) {
    return proxy(
        World.class,
        (ignored, method, arguments) ->
            switch (method.getName()) {
              case "getUID" -> WORLD_UUID;
              case "getEnvironment" -> World.Environment.NORMAL;
              case "getGenerator" -> generator(creator);
              case "canGenerateStructures" -> structuresEnabled;
              case "setSpawnFlags" -> {
                spawnFlagsDisabled.set(
                    Boolean.FALSE.equals(arguments[0]) && Boolean.FALSE.equals(arguments[1]));
                yield null;
              }
              case "setGameRule" -> true;
              case "setAutoSave" -> {
                autoSaveEnabled.set(Boolean.TRUE.equals(arguments[0]));
                yield null;
              }
              default -> defaultValue(ignored, method, arguments);
            });
  }

  private static ChunkGenerator generator(AtomicReference<WorldCreator> creator) {
    WorldCreator captured = creator.get();
    return captured == null ? new OpenOneBlockVoidChunkGenerator() : captured.generator();
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

  private static final class ImmediateGlobalScheduler implements PlatformTaskScheduler {
    private final AtomicBoolean globalDispatched;

    private ImmediateGlobalScheduler(AtomicBoolean globalDispatched) {
      this.globalDispatched = globalDispatched;
    }

    @Override
    public <T> CompletionStage<T> global(ScheduledWork<T> work) {
      globalDispatched.set(true);
      try {
        return CompletableFuture.completedFuture(work.execute());
      } catch (Exception exception) {
        return CompletableFuture.failedFuture(exception);
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
}
