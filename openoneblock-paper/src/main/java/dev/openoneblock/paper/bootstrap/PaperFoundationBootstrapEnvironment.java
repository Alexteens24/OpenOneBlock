package dev.openoneblock.paper.bootstrap;

import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.execution.IslandExecutionLanes;
import dev.openoneblock.core.grid.CoordinateRange;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.island.CreateIslandService;
import dev.openoneblock.core.island.IslandCreationRepository;
import dev.openoneblock.core.island.IslandHomeService;
import dev.openoneblock.core.locator.InMemorySlotLocatorIndex;
import dev.openoneblock.core.locator.WorldEnvironment;
import dev.openoneblock.core.locator.WorldProjectionDefinition;
import dev.openoneblock.core.locator.WorldProjectionRegistry;
import dev.openoneblock.core.locator.WorldProjectionVerification;
import dev.openoneblock.core.platform.PlatformTaskScheduler;
import dev.openoneblock.core.runtime.IslandRuntimeManager;
import dev.openoneblock.core.world.WorldPreparationCoordinator;
import dev.openoneblock.paper.config.DefaultConfigurationInstaller;
import dev.openoneblock.paper.config.FoundationConfigurationLoader;
import dev.openoneblock.paper.config.FoundationConfigurationSnapshot;
import dev.openoneblock.paper.config.ProvisionedWorldHeightValidator;
import dev.openoneblock.paper.config.ProvisionedWorldHeightValidator.ProvisionedWorldHeight;
import dev.openoneblock.paper.config.WorldGeometryFingerprint;
import dev.openoneblock.paper.event.BukkitIslandCreatedEventPublisher;
import dev.openoneblock.paper.island.PaperIslandDestinationPreparer;
import dev.openoneblock.paper.island.PaperIslandOwnerTeleporter;
import dev.openoneblock.paper.runtime.PaperIslandChunkTicketController;
import dev.openoneblock.paper.world.BukkitVoidWorldFactory;
import dev.openoneblock.paper.world.PaperIslandCleanup;
import dev.openoneblock.paper.world.PaperIslandWorldPreparation;
import dev.openoneblock.paper.world.PaperSharedWorldManager;
import dev.openoneblock.paper.world.ProvisionedSharedWorld;
import dev.openoneblock.paper.world.SharedWorldSpec;
import dev.openoneblock.paper.world.UnavailableIslandStructurePlacement;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.island.SqliteIslandCreationRepository;
import dev.openoneblock.persistence.sqlite.island.SqliteIslandQueryRepository;
import dev.openoneblock.persistence.sqlite.migration.SqliteSchemaMigrator;
import dev.openoneblock.persistence.sqlite.slot.SqliteSlotLocatorSnapshotSource;
import dev.openoneblock.persistence.sqlite.world.SqliteWorldEffectJournal;
import dev.openoneblock.persistence.sqlite.world.SqliteWorldProjectionCatalog;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** Production Paper environment for ordered foundation bootstrap and rollback. */
public final class PaperFoundationBootstrapEnvironment implements FoundationBootstrapEnvironment {
  private static final CoordinateRange MINECRAFT_COORDINATES =
      new CoordinateRange(-30_000_000, 30_000_001);
  private static final int MAXIMUM_IN_FLIGHT_PER_ISLAND = 64;

  private final Plugin plugin;
  private final PlatformTaskScheduler scheduler;
  private final Path dataDirectory;
  private final AtomicReference<CompletionStage<Void>> shutdown = new AtomicReference<>();

  private volatile PluginExecutors executors;
  private volatile SqliteConnectionFactory connectionFactory;
  private volatile GridGeometry geometry;
  private volatile WorldProjectionRegistry worldProjections;
  private volatile PaperIslandChunkTicketController chunkTickets;
  private volatile FoundationRuntime runtime;

  /**
   * Creates the production bootstrap environment.
   *
   * @param plugin active Paper plugin
   * @param scheduler ownership-aware platform scheduler
   */
  public PaperFoundationBootstrapEnvironment(Plugin plugin, PlatformTaskScheduler scheduler) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.dataDirectory = plugin.getDataFolder().toPath();
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<FoundationConfigurationSnapshot> loadConfiguration() {
    return scheduler.async(
        () -> {
          new DefaultConfigurationInstaller(plugin.getClass().getClassLoader())
              .install(dataDirectory);
          return new FoundationConfigurationLoader().load(dataDirectory);
        });
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<Void> initializeInfrastructure(
      FoundationConfigurationSnapshot configuration) {
    Objects.requireNonNull(configuration, "configuration");
    if (executors != null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("foundation infrastructure already initialized"));
    }
    PluginExecutors createdExecutors = new PluginExecutors(configuration.executors());
    Path databaseFile = dataDirectory.resolve(configuration.database().file()).normalize();
    SqliteConnectionFactory createdFactory =
        new SqliteConnectionFactory(databaseFile, configuration.database().busyTimeoutMillis());
    GridGeometry createdGeometry = new GridGeometry(configuration.grid(), MINECRAFT_COORDINATES);
    executors = createdExecutors;
    connectionFactory = createdFactory;
    geometry = createdGeometry;
    return CompletableFuture.runAsync(
        () -> new SqliteSchemaMigrator(createdFactory).migrate(), createdExecutors.database());
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<Void> provisionAndVerifyWorlds(
      FoundationConfigurationSnapshot configuration) {
    PluginExecutors activeExecutors = requireExecutors();
    SqliteConnectionFactory activeFactory = requireConnectionFactory();
    PaperSharedWorldManager worldManager =
        new PaperSharedWorldManager(scheduler, new BukkitVoidWorldFactory(plugin.getServer()));
    List<CompletionStage<ProvisionedSharedWorld>> stages =
        configuration.worlds().stream().map(worldManager::provision).toList();
    return sequence(stages)
        .thenCompose(
            provisioned -> {
              validateWorldHeights(configuration, provisioned);
              List<WorldProjectionDefinition> definitions = definitions(configuration, provisioned);
              SqliteWorldProjectionCatalog catalog =
                  new SqliteWorldProjectionCatalog(
                      activeFactory, activeExecutors.database(), Clock.systemUTC());
              return catalog.verifyOrRegister(definitions);
            })
        .thenAccept(
            verification -> {
              if (verification instanceof WorldProjectionVerification.Drifted drifted) {
                throw new WorldProjectionDriftException(drifted.drifts());
              }
              WorldProjectionVerification.Verified verified =
                  (WorldProjectionVerification.Verified) verification;
              worldProjections = verified.toRuntimeRegistry();
            });
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<FoundationRuntime> recover(FoundationConfigurationSnapshot configuration) {
    PluginExecutors activeExecutors = requireExecutors();
    SqliteConnectionFactory activeFactory = requireConnectionFactory();
    WorldProjectionRegistry activeWorlds =
        Objects.requireNonNull(worldProjections, "world projections not verified");
    return new SqliteSlotLocatorSnapshotSource(activeFactory, activeExecutors.database())
        .loadCommittedEntries()
        .thenApply(InMemorySlotLocatorIndex::rebuild)
        .thenCompose(
            locator -> {
              Function<dev.openoneblock.api.id.ShardGroupId, GridGeometry> geometryByShard =
                  ignored -> requireGeometry();
              IslandCreationRepository repository =
                  new SqliteIslandCreationRepository(
                      activeFactory, geometryByShard, locator, activeExecutors.database());
              IslandExecutionLanes lanes =
                  new IslandExecutionLanes(
                      activeExecutors.computation(), MAXIMUM_IN_FLIGHT_PER_ISLAND);
              PaperIslandChunkTicketController ticketController =
                  new PaperIslandChunkTicketController(plugin, plugin.getServer(), scheduler);
              IslandRuntimeManager runtimeManager =
                  new IslandRuntimeManager(
                      ticketController,
                      Duration.ofSeconds(configuration.operations().creationTimeoutSeconds()));
              SqliteWorldEffectJournal effectJournal =
                  new SqliteWorldEffectJournal(activeFactory, activeExecutors.database());
              Clock clock = Clock.systemUTC();
              WorldPreparationCoordinator preparationCoordinator =
                  new WorldPreparationCoordinator(
                      effectJournal,
                      new PaperIslandWorldPreparation(
                          plugin.getServer(), scheduler, new UnavailableIslandStructurePlacement()),
                      clock);
              int minimumY = configuration.buildHeight().minimumY();
              int maximumYExclusive = configuration.buildHeight().maximumYExclusive();
              int magicBlockY = Math.max(minimumY, Math.min(64, maximumYExclusive - 2));
              NamespacedId starterBlock =
                  NamespacedId.of(
                      "minecraft",
                      configuration.magicBlock().starterMaterial().toLowerCase(Locale.ROOT));
              PaperIslandOwnerTeleporter playerTeleporter =
                  new PaperIslandOwnerTeleporter(plugin, plugin.getServer());
              SqliteIslandQueryRepository queryRepository =
                  new SqliteIslandQueryRepository(activeFactory, activeExecutors.database());
              IslandHomeService homeService =
                  new IslandHomeService(
                      queryRepository,
                      activeWorlds,
                      geometryByShard,
                      minimumY,
                      maximumYExclusive,
                      new PaperIslandDestinationPreparer(plugin.getServer(), scheduler),
                      playerTeleporter);
              CreateIslandService creationService =
                  new CreateIslandService(
                      repository,
                      lanes,
                      runtimeManager,
                      activeWorlds,
                      geometryByShard,
                      starterBlock,
                      magicBlockY,
                      minimumY,
                      maximumYExclusive,
                      preparationCoordinator,
                      new PaperIslandCleanup(plugin, plugin.getServer(), scheduler),
                      playerTeleporter,
                      new BukkitIslandCreatedEventPublisher(plugin.getServer(), scheduler),
                      clock);
              FoundationRuntime recovered =
                  new FoundationRuntime(
                      configuration,
                      activeWorlds,
                      locator,
                      repository,
                      lanes,
                      runtimeManager,
                      effectJournal,
                      preparationCoordinator,
                      creationService,
                      queryRepository,
                      homeService);
              chunkTickets = ticketController;
              runtime = recovered;
              return repository
                  .findPendingCreationRequests()
                  .thenCompose(
                      pending ->
                          sequence(pending.stream().map(creationService::recoverPending).toList()))
                  .thenApply(ignored -> recovered);
            });
  }

  /** {@inheritDoc} */
  @Override
  public synchronized CompletionStage<Void> shutdown(Duration drainTimeout) {
    CompletionStage<Void> existing = shutdown.get();
    if (existing != null) {
      return existing;
    }
    FoundationRuntime activeRuntime = runtime;
    PaperIslandChunkTicketController activeTickets = chunkTickets;
    if (!plugin.isEnabled() && activeTickets != null) {
      activeTickets.emergencyReleaseAllOnDisable();
    }
    CompletableFuture<Void> laneDrain =
        activeRuntime == null
            ? CompletableFuture.completedFuture(null)
            : activeRuntime
                .islandLanes()
                .shutdownGracefully()
                .toCompletableFuture()
                .orTimeout(drainTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    CompletionStage<Void> drain =
        laneDrain
            .handle((ignored, failure) -> failure)
            .thenCompose(
                laneFailure -> {
                  CompletionStage<Void> ticketDrain =
                      activeRuntime == null
                          ? CompletableFuture.completedFuture(null)
                          : activeRuntime.islandRuntimes().shutdown();
                  return ticketDrain.handle(
                      (ignored, ticketFailure) -> {
                        Throwable combined = combineFailures(laneFailure, ticketFailure);
                        if (combined != null) {
                          throw new CompletionException(combined);
                        }
                        return null;
                      });
                });
    CompletionStage<Void> candidate =
        drain.handle(
            (ignored, failure) -> {
              runtime = null;
              chunkTickets = null;
              worldProjections = null;
              connectionFactory = null;
              geometry = null;
              PluginExecutors activeExecutors = executors;
              executors = null;
              if (activeExecutors != null) {
                activeExecutors.close(drainTimeout);
              }
              if (failure != null) {
                throw new CompletionException(failure);
              }
              return null;
            });
    if (shutdown.compareAndSet(null, candidate)) {
      return candidate;
    }
    return shutdown.get();
  }

  private static Throwable combineFailures(Throwable first, Throwable second) {
    Throwable primary = unwrap(first);
    Throwable additional = unwrap(second);
    if (primary == null) {
      return additional;
    }
    if (additional != null && additional != primary) {
      primary.addSuppressed(additional);
    }
    return primary;
  }

  private static Throwable unwrap(Throwable failure) {
    if (failure == null) {
      return null;
    }
    Throwable current = failure;
    while (current instanceof CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private void validateWorldHeights(
      FoundationConfigurationSnapshot configuration, List<ProvisionedSharedWorld> provisioned) {
    List<ProvisionedWorldHeight> heights =
        provisioned.stream()
            .map(
                entry ->
                    new ProvisionedWorldHeight(
                        entry.world().getName(),
                        entry.world().getMinHeight(),
                        entry.world().getMaxHeight()))
            .toList();
    try {
      new ProvisionedWorldHeightValidator().validate(configuration, heights);
    } catch (dev.openoneblock.paper.config.ConfigurationValidationException exception) {
      throw new CompletionException(exception);
    }
  }

  private static List<WorldProjectionDefinition> definitions(
      FoundationConfigurationSnapshot configuration, List<ProvisionedSharedWorld> provisioned) {
    String fingerprint = WorldGeometryFingerprint.from(configuration);
    List<WorldProjectionDefinition> definitions = new ArrayList<>();
    for (int index = 0; index < provisioned.size(); index++) {
      SharedWorldSpec specification = configuration.worlds().get(index);
      World world = provisioned.get(index).world();
      definitions.add(
          new WorldProjectionDefinition(
              specification.shardGroupId(),
              specification.dimensionId(),
              specification.worldName(),
              WorldId.of(world.getUID()),
              WorldEnvironment.valueOf(world.getEnvironment().name()),
              fingerprint));
    }
    return List.copyOf(definitions);
  }

  private static <T> CompletionStage<List<T>> sequence(List<CompletionStage<T>> stages) {
    CompletableFuture<?>[] futures =
        stages.stream().map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new);
    return CompletableFuture.allOf(futures)
        .thenApply(
            ignored -> stages.stream().map(stage -> stage.toCompletableFuture().join()).toList());
  }

  private PluginExecutors requireExecutors() {
    return Objects.requireNonNull(executors, "foundation executors not initialized");
  }

  private SqliteConnectionFactory requireConnectionFactory() {
    return Objects.requireNonNull(connectionFactory, "SQLite factory not initialized");
  }

  private GridGeometry requireGeometry() {
    return Objects.requireNonNull(geometry, "grid geometry not initialized");
  }
}
