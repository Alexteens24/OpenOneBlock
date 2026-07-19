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
            (ownerId, destination, operationId) -> CompletableFuture.completedFuture(null),
            event -> CompletableFuture.completedFuture(null),
            java.time.Clock.systemUTC());
    dev.openoneblock.core.island.IslandQueryRepository queries =
        new dev.openoneblock.core.island.IslandQueryRepository() {
          @Override
          public CompletionStage<Optional<dev.openoneblock.core.island.IslandHomeSnapshot>>
              findActiveHome(PlayerId playerId) {
            return CompletableFuture.completedFuture(Optional.empty());
          }

          @Override
          public CompletionStage<Optional<dev.openoneblock.core.island.IslandInfoSnapshot>>
              findActiveInfo(PlayerId playerId) {
            return CompletableFuture.completedFuture(Optional.empty());
          }
        };
    dev.openoneblock.core.island.IslandHomeService home =
        new dev.openoneblock.core.island.IslandHomeService(
            queries,
            worlds,
            ignored -> geometry,
            minimumY,
            maximumY,
            65,
            (destination, operationId) -> CompletableFuture.completedFuture(null),
            (playerId, destination, operationId) -> CompletableFuture.completedFuture(null));
    dev.openoneblock.core.island.IslandInspectionService inspection =
        new dev.openoneblock.core.island.IslandInspectionService(
            islandId -> CompletableFuture.completedFuture(Optional.empty()), runtimes);
    dev.openoneblock.core.island.IslandDeletionRepository deletions =
        new dev.openoneblock.core.island.IslandDeletionRepository() {
          @Override
          public CompletionStage<dev.openoneblock.core.island.IslandDeletionProgress> beginDeletion(
              dev.openoneblock.core.island.IslandDeletionRequest request) {
            return CompletableFuture.failedFuture(new AssertionError("unexpected deletion"));
          }

          @Override
          public CompletionStage<dev.openoneblock.core.island.IslandDeletionProgress>
              completeDeletion(dev.openoneblock.core.island.IslandDeletionCompletion completion) {
            return CompletableFuture.failedFuture(new AssertionError("unexpected deletion"));
          }

          @Override
          public CompletionStage<List<dev.openoneblock.core.island.IslandDeletionRequest>>
              findPendingDeletions() {
            return CompletableFuture.completedFuture(List.of());
          }

          @Override
          public CompletionStage<dev.openoneblock.core.island.IslandDeletionProgress>
              beginCleanupRetry(dev.openoneblock.core.island.IslandCleanupRetryRequest request) {
            return CompletableFuture.failedFuture(new AssertionError("unexpected cleanup retry"));
          }

          @Override
          public CompletionStage<List<dev.openoneblock.core.island.IslandCleanupRetryRequest>>
              findPendingCleanupRetries() {
            return CompletableFuture.completedFuture(List.of());
          }
        };
    dev.openoneblock.core.island.DeleteIslandService deletion =
        new dev.openoneblock.core.island.DeleteIslandService(
            deletions,
            lanes,
            runtimes,
            worlds,
            ignored -> geometry,
            plan ->
                CompletableFuture.completedFuture(
                    new dev.openoneblock.core.world.IslandCleanup.Result(
                        dev.openoneblock.core.world.IslandCleanup.Status.VERIFIED_CLEAN,
                        "test deletion cleanup")),
            java.time.Clock.systemUTC());
    dev.openoneblock.core.island.IslandResetRepository resets =
        new dev.openoneblock.core.island.IslandResetRepository() {
          @Override
          public CompletionStage<dev.openoneblock.core.island.IslandResetProgress> beginReset(
              dev.openoneblock.core.island.IslandResetRequest request) {
            return CompletableFuture.failedFuture(new AssertionError("unexpected reset"));
          }

          @Override
          public CompletionStage<dev.openoneblock.core.island.IslandResetProgress> completeCleanup(
              dev.openoneblock.core.island.IslandResetCleanupCompletion completion) {
            return CompletableFuture.failedFuture(new AssertionError("unexpected reset"));
          }

          @Override
          public CompletionStage<dev.openoneblock.core.island.IslandResetProgress>
              beginPreparationFailure(
                  dev.openoneblock.core.island.IslandResetPreparationFailure failure) {
            return CompletableFuture.failedFuture(new AssertionError("unexpected reset"));
          }

          @Override
          public CompletionStage<dev.openoneblock.core.island.IslandResetProgress> activateReset(
              dev.openoneblock.core.island.IslandResetActivation activation) {
            return CompletableFuture.failedFuture(new AssertionError("unexpected reset"));
          }

          @Override
          public CompletionStage<List<dev.openoneblock.core.island.IslandResetRequest>>
              findPendingResets() {
            return CompletableFuture.completedFuture(List.of());
          }
        };
    dev.openoneblock.core.world.IslandCleanup cleanup =
        plan ->
            CompletableFuture.completedFuture(
                new dev.openoneblock.core.world.IslandCleanup.Result(
                    dev.openoneblock.core.world.IslandCleanup.Status.VERIFIED_CLEAN,
                    "test reset cleanup"));
    dev.openoneblock.core.island.ResetIslandService reset =
        new dev.openoneblock.core.island.ResetIslandService(
            resets,
            lanes,
            runtimes,
            worlds,
            ignored -> geometry,
            new dev.openoneblock.core.world.IslandCellCleanupCoordinator(
                runtimes, worlds, ignored -> geometry, cleanup),
            preparation,
            java.time.Clock.systemUTC());
    InMemorySlotLocatorIndex locator = new InMemorySlotLocatorIndex();
    dev.openoneblock.protection.InMemoryIslandProtectionIndex protectionIndex =
        new dev.openoneblock.protection.InMemoryIslandProtectionIndex();
    dev.openoneblock.protection.ProtectionEngine protection =
        new dev.openoneblock.protection.ProtectionEngine(
            new dev.openoneblock.core.locator.IslandLocationResolver(
                worlds, ignored -> geometry, locator),
            ignored -> geometry,
            protectionIndex,
            new dev.openoneblock.paper.config.ProtectionConfigurationCompiler()
                .compile(configuration.roles()),
            List.of());
    dev.openoneblock.core.island.IslandRepairRepository repairs =
        new dev.openoneblock.core.island.IslandRepairRepository() {
          @Override
          public CompletionStage<dev.openoneblock.core.island.IslandRepairProgress> beginRepair(
              dev.openoneblock.core.island.IslandRepairRequest request) {
            return CompletableFuture.failedFuture(new AssertionError("unexpected repair"));
          }

          @Override
          public CompletionStage<dev.openoneblock.core.island.IslandRepairProgress> completeRepair(
              dev.openoneblock.core.island.IslandRepairCompletion completion) {
            return CompletableFuture.failedFuture(new AssertionError("unexpected repair"));
          }

          @Override
          public CompletionStage<List<dev.openoneblock.core.island.IslandRepairRequest>>
              findPendingRepairs() {
            return CompletableFuture.completedFuture(List.of());
          }
        };
    dev.openoneblock.core.island.RepairIslandService repair =
        new dev.openoneblock.core.island.RepairIslandService(
            repairs,
            (request, island) ->
                CompletableFuture.failedFuture(new AssertionError("unexpected repair")),
            lanes,
            java.time.Clock.systemUTC());
    return new FoundationRuntime(
        configuration,
        worlds,
        locator,
        protectionIndex,
        protection,
        new dev.openoneblock.paper.config.ProtectionConfigurationCompiler()
            .compileIslandRoles(configuration.roles()),
        islandId -> CompletableFuture.completedFuture(List.of()),
        (playerId, observedAt) -> CompletableFuture.completedFuture(List.of()),
        unavailableTeamService(lanes),
        repository,
        lanes,
        runtimes,
        worldEffects,
        preparation,
        creation,
        queries,
        home,
        inspection,
        deletions,
        deletion,
        repairs,
        repair,
        resets,
        reset);
  }

  private static dev.openoneblock.core.team.IslandTeamService unavailableTeamService(
      IslandExecutionLanes lanes) {
    dev.openoneblock.core.team.IslandTeamRepository unavailable =
        new dev.openoneblock.core.team.IslandTeamRepository() {
          @Override
          public java.util.concurrent.CompletionStage<dev.openoneblock.core.team.TeamMutationResult>
              invite(dev.openoneblock.core.team.IslandInvitationCommand command) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("test"));
          }

          @Override
          public java.util.concurrent.CompletionStage<dev.openoneblock.core.team.TeamMutationResult>
              respond(dev.openoneblock.core.team.IslandInvitationResponseCommand command) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("test"));
          }

          @Override
          public java.util.concurrent.CompletionStage<dev.openoneblock.core.team.TeamMutationResult>
              mutate(dev.openoneblock.core.team.IslandMembershipCommand command) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("test"));
          }

          @Override
          public java.util.concurrent.CompletionStage<dev.openoneblock.core.team.TeamMutationResult>
              transferOwnership(dev.openoneblock.core.team.IslandOwnershipTransferCommand command) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("test"));
          }
        };
    return new dev.openoneblock.core.team.IslandTeamService(
        unavailable,
        lanes,
        dev.openoneblock.core.team.IslandMembershipEventPublisher.NO_OP,
        new dev.openoneblock.core.team.IslandTeamPolicy(8, java.time.Duration.ofMinutes(5)));
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
