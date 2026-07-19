package dev.openoneblock.paper.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.core.execution.IslandExecutionLanes;
import dev.openoneblock.core.grid.CoordinateRange;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.island.CreateIslandService;
import dev.openoneblock.core.island.IslandAggregateSnapshot;
import dev.openoneblock.core.island.IslandCreationActivationRequest;
import dev.openoneblock.core.island.IslandCreationCleanupCompletionRequest;
import dev.openoneblock.core.island.IslandCreationFailureRequest;
import dev.openoneblock.core.island.IslandCreationRepository;
import dev.openoneblock.core.island.IslandCreationRequest;
import dev.openoneblock.core.island.IslandCreationTransitionRequest;
import dev.openoneblock.core.locator.InMemorySlotLocatorIndex;
import dev.openoneblock.core.locator.WorldProjectionRegistry;
import dev.openoneblock.core.runtime.IslandChunkTicketLease;
import dev.openoneblock.core.runtime.IslandRuntimeManager;
import dev.openoneblock.core.world.IslandWorldPreparation;
import dev.openoneblock.core.world.WorldEffectJournal;
import dev.openoneblock.core.world.WorldEffectKey;
import dev.openoneblock.core.world.WorldEffectOutcome;
import dev.openoneblock.core.world.WorldEffectPlan;
import dev.openoneblock.core.world.WorldEffectReceipt;
import dev.openoneblock.core.world.WorldEffectState;
import dev.openoneblock.core.world.WorldPreparationCoordinator;
import dev.openoneblock.paper.config.DefaultConfigurationInstaller;
import dev.openoneblock.paper.config.FoundationConfigurationLoader;
import dev.openoneblock.paper.config.FoundationConfigurationSnapshot;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FoundationBootstrapCoordinatorTest {
  @TempDir Path dataDirectory;

  private FoundationConfigurationSnapshot configuration;

  @BeforeEach
  void loadConfiguration() throws Exception {
    new DefaultConfigurationInstaller(getClass().getClassLoader()).install(dataDirectory);
    configuration = new FoundationConfigurationLoader().load(dataDirectory);
  }

  @Test
  void publishesRuntimeOnlyAfterOrderedRecoveryAndShutsDownOnce() {
    PluginRuntimeLifecycle lifecycle = bootstrappingLifecycle();
    FakeEnvironment environment = new FakeEnvironment(configuration);
    FoundationBootstrapCoordinator coordinator =
        new FoundationBootstrapCoordinator(lifecycle, environment);

    FoundationRuntime runtime = coordinator.start().toCompletableFuture().join();

    assertEquals(List.of("config", "infrastructure", "worlds", "recovery"), environment.steps);
    assertEquals(PluginRuntimeState.READY, lifecycle.state());
    assertSame(runtime, coordinator.runtime().orElseThrow());
    assertTrue(runtime.islandLanes().isAccepting());

    CompletionStage<Void> first = coordinator.shutdown(Duration.ofSeconds(1));
    CompletionStage<Void> second = coordinator.shutdown(Duration.ofSeconds(1));
    first.toCompletableFuture().join();

    assertSame(first, second);
    assertEquals(1, environment.shutdowns.get());
    assertEquals(PluginRuntimeState.STOPPED, lifecycle.state());
    assertTrue(coordinator.runtime().isEmpty());
  }

  @Test
  void infrastructureFailureRollsBackAndNeverRunsWorldOrRecoveryStages() {
    PluginRuntimeLifecycle lifecycle = bootstrappingLifecycle();
    FakeEnvironment environment = new FakeEnvironment(configuration);
    environment.failureStep = "infrastructure";
    FoundationBootstrapCoordinator coordinator =
        new FoundationBootstrapCoordinator(lifecycle, environment);

    assertThrows(CompletionException.class, () -> coordinator.start().toCompletableFuture().join());

    assertEquals(List.of("config", "infrastructure"), environment.steps);
    assertEquals(1, environment.shutdowns.get());
    assertEquals(PluginRuntimeState.DEGRADED, lifecycle.state());
    assertTrue(coordinator.runtime().isEmpty());
  }

  @Test
  void recoveryFailureNeverPublishesPartiallyBuiltRuntime() {
    PluginRuntimeLifecycle lifecycle = bootstrappingLifecycle();
    FakeEnvironment environment = new FakeEnvironment(configuration);
    environment.failureStep = "recovery";
    FoundationBootstrapCoordinator coordinator =
        new FoundationBootstrapCoordinator(lifecycle, environment);

    assertThrows(CompletionException.class, () -> coordinator.start().toCompletableFuture().join());

    assertEquals(PluginRuntimeState.DEGRADED, lifecycle.state());
    assertFalse(lifecycle.isReady());
    assertTrue(coordinator.runtime().isEmpty());
    assertEquals(1, environment.shutdowns.get());
  }

  @Test
  void shutdownDuringConfigLoadPreventsLaterInfrastructureActivation() {
    PluginRuntimeLifecycle lifecycle = bootstrappingLifecycle();
    FakeEnvironment environment = new FakeEnvironment(configuration);
    environment.delayedConfiguration = new CompletableFuture<>();
    FoundationBootstrapCoordinator coordinator =
        new FoundationBootstrapCoordinator(lifecycle, environment);
    CompletionStage<FoundationRuntime> startup = coordinator.start();

    coordinator.shutdown(Duration.ofSeconds(1)).toCompletableFuture().join();
    environment.delayedConfiguration.complete(configuration);

    assertThrows(CompletionException.class, () -> startup.toCompletableFuture().join());
    assertEquals(List.of("config"), environment.steps);
    assertEquals(1, environment.shutdowns.get());
    assertEquals(PluginRuntimeState.STOPPED, lifecycle.state());
    assertTrue(coordinator.runtime().isEmpty());
  }

  @Test
  void startCannotBeInvokedTwice() {
    PluginRuntimeLifecycle lifecycle = bootstrappingLifecycle();
    FoundationBootstrapCoordinator coordinator =
        new FoundationBootstrapCoordinator(lifecycle, new FakeEnvironment(configuration));

    coordinator.start().toCompletableFuture().join();

    assertThrows(CompletionException.class, () -> coordinator.start().toCompletableFuture().join());
  }

  private static PluginRuntimeLifecycle bootstrappingLifecycle() {
    PluginRuntimeLifecycle lifecycle = new PluginRuntimeLifecycle();
    lifecycle.transitionTo(PluginRuntimeState.BOOTSTRAPPING);
    return lifecycle;
  }

  private static FoundationRuntime runtime(FoundationConfigurationSnapshot configuration) {
    WorldEffectJournal worldEffects = unavailableWorldEffects();
    IslandCreationRepository repository = new EmptyIslandRepository();
    IslandExecutionLanes lanes = new IslandExecutionLanes(Runnable::run, 4);
    IslandRuntimeManager runtimes =
        new IslandRuntimeManager(
            request ->
                CompletableFuture.completedFuture(
                    new IslandChunkTicketLease() {
                      @Override
                      public int chunkCount() {
                        return request.chunks().size();
                      }

                      @Override
                      public CompletionStage<Void> release() {
                        return CompletableFuture.completedFuture(null);
                      }
                    }),
            Duration.ofSeconds(1));
    WorldProjectionRegistry worlds = new WorldProjectionRegistry(List.of());
    GridGeometry geometry =
        new GridGeometry(configuration.grid(), new CoordinateRange(-30_000_000, 30_000_001));
    WorldPreparationCoordinator preparation =
        new WorldPreparationCoordinator(
            worldEffects,
            new IslandWorldPreparation() {
              @Override
              public CompletionStage<WorldEffectOutcome> execute(WorldEffectPlan effect) {
                return CompletableFuture.failedFuture(
                    new AssertionError("unexpected world preparation"));
              }

              @Override
              public CompletionStage<WorldEffectOutcome> verify(WorldEffectPlan effect) {
                return CompletableFuture.failedFuture(
                    new AssertionError("unexpected world verification"));
              }
            },
            java.time.Clock.systemUTC());
    int minimumY = configuration.buildHeight().minimumY();
    int maximumY = configuration.buildHeight().maximumYExclusive();
    CreateIslandService creation =
        new CreateIslandService(
            repository,
            lanes,
            runtimes,
            worlds,
            ignored -> geometry,
            NamespacedId.parse("minecraft:grass_block"),
            64,
            minimumY,
            maximumY,
            preparation,
            plan ->
                CompletableFuture.completedFuture(
                    new dev.openoneblock.core.world.IslandCleanup.Result(
                        dev.openoneblock.core.world.IslandCleanup.Status.VERIFIED_CLEAN,
                        "test cleanup verified")),
            java.time.Clock.systemUTC());
    return new FoundationRuntime(
        configuration,
        worlds,
        new InMemorySlotLocatorIndex(),
        repository,
        lanes,
        runtimes,
        worldEffects,
        preparation,
        creation);
  }

  private static WorldEffectJournal unavailableWorldEffects() {
    return new WorldEffectJournal() {
      @Override
      public CompletionStage<WorldEffectReceipt> register(
          WorldEffectPlan effect, Instant recordedAt) {
        return unavailable();
      }

      @Override
      public CompletionStage<WorldEffectReceipt> markDispatched(
          WorldEffectPlan effect, Instant dispatchedAt) {
        return unavailable();
      }

      @Override
      public CompletionStage<WorldEffectReceipt> recordOutcome(
          WorldEffectPlan effect,
          WorldEffectState outcome,
          String diagnostic,
          Instant completedAt) {
        return unavailable();
      }

      @Override
      public CompletionStage<Optional<WorldEffectReceipt>> find(WorldEffectKey key) {
        return unavailable();
      }

      @Override
      public CompletionStage<List<WorldEffectReceipt>> findByOperation(
          dev.openoneblock.api.id.OperationId operationId) {
        return unavailable();
      }

      private <T> CompletionStage<T> unavailable() {
        return CompletableFuture.failedFuture(new AssertionError("unexpected effect journal use"));
      }
    };
  }

  private static final class FakeEnvironment implements FoundationBootstrapEnvironment {
    private final FoundationConfigurationSnapshot configuration;
    private final List<String> steps = new ArrayList<>();
    private final AtomicInteger shutdowns = new AtomicInteger();
    private volatile String failureStep;
    private volatile CompletableFuture<FoundationConfigurationSnapshot> delayedConfiguration;

    private FakeEnvironment(FoundationConfigurationSnapshot configuration) {
      this.configuration = configuration;
    }

    @Override
    public CompletionStage<FoundationConfigurationSnapshot> loadConfiguration() {
      steps.add("config");
      if (delayedConfiguration != null) {
        return delayedConfiguration;
      }
      return result("config", configuration);
    }

    @Override
    public CompletionStage<Void> initializeInfrastructure(FoundationConfigurationSnapshot ignored) {
      steps.add("infrastructure");
      return result("infrastructure", null);
    }

    @Override
    public CompletionStage<Void> provisionAndVerifyWorlds(FoundationConfigurationSnapshot ignored) {
      steps.add("worlds");
      return result("worlds", null);
    }

    @Override
    public CompletionStage<FoundationRuntime> recover(FoundationConfigurationSnapshot ignored) {
      steps.add("recovery");
      return result("recovery", runtime(configuration));
    }

    @Override
    public CompletionStage<Void> shutdown(Duration ignored) {
      shutdowns.incrementAndGet();
      return CompletableFuture.completedFuture(null);
    }

    private <T> CompletionStage<T> result(String step, T value) {
      return step.equals(failureStep)
          ? CompletableFuture.failedFuture(new IllegalStateException(step + " failed"))
          : CompletableFuture.completedFuture(value);
    }
  }

  private static final class EmptyIslandRepository implements IslandCreationRepository {
    @Override
    public CompletionStage<IslandAggregateSnapshot> createAllocation(
        IslandCreationRequest request) {
      return unsupported();
    }

    @Override
    public CompletionStage<IslandAggregateSnapshot> advanceCreation(
        IslandCreationTransitionRequest request) {
      return unsupported();
    }

    @Override
    public CompletionStage<IslandAggregateSnapshot> activateCreation(
        IslandCreationActivationRequest request) {
      return unsupported();
    }

    @Override
    public CompletionStage<IslandAggregateSnapshot> abortCreationBeforeWorldWork(
        IslandCreationFailureRequest request) {
      return unsupported();
    }

    @Override
    public CompletionStage<IslandAggregateSnapshot> beginCreationCleanup(
        IslandCreationFailureRequest request) {
      return unsupported();
    }

    @Override
    public CompletionStage<IslandAggregateSnapshot> completeCreationCleanup(
        IslandCreationCleanupCompletionRequest request) {
      return unsupported();
    }

    @Override
    public CompletionStage<Optional<IslandAggregateSnapshot>> findById(IslandId islandId) {
      return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletionStage<Optional<IslandAggregateSnapshot>> findByActiveMember(
        PlayerId playerId) {
      return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletionStage<List<IslandAggregateSnapshot>> findPendingCreations() {
      return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletionStage<List<IslandCreationRequest>> findPendingCreationRequests() {
      return CompletableFuture.completedFuture(List.of());
    }

    private static <T> CompletionStage<T> unsupported() {
      return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }
  }
}
