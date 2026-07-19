package dev.openoneblock.paper.command;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.island.CreateIslandCommand;
import dev.openoneblock.core.island.CreateIslandResult;
import dev.openoneblock.core.island.IslandDeletionConflictException;
import dev.openoneblock.core.island.IslandDeletionRequest;
import dev.openoneblock.core.island.IslandDeletionResult;
import dev.openoneblock.core.island.IslandHomeResult;
import dev.openoneblock.core.island.IslandInfoSnapshot;
import dev.openoneblock.core.island.PlayerIslandNotFoundException;
import dev.openoneblock.paper.bootstrap.FoundationRuntime;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.Server;
import org.bukkit.World;

/** Resolves validated runtime defaults and submits command mutations to application services. */
public final class PaperIslandCommandGateway implements IslandCommandGateway {
  private final Server server;
  private final Supplier<Optional<FoundationRuntime>> runtime;
  private final Supplier<IslandId> islandIds;
  private final Supplier<OperationId> operationIds;
  private final ConfirmationTokenRegistry confirmations;
  private final Clock clock;

  /**
   * Creates the production command gateway.
   *
   * @param server active Paper server
   * @param runtime READY-only service graph supplier
   */
  public PaperIslandCommandGateway(Server server, Supplier<Optional<FoundationRuntime>> runtime) {
    this(
        server,
        runtime,
        IslandId::generate,
        OperationId::generate,
        new ConfirmationTokenRegistry(Clock.systemUTC(), Duration.ofSeconds(30), 10_000),
        Clock.systemUTC());
  }

  PaperIslandCommandGateway(
      Server server,
      Supplier<Optional<FoundationRuntime>> runtime,
      Supplier<IslandId> islandIds,
      Supplier<OperationId> operationIds) {
    this(
        server,
        runtime,
        islandIds,
        operationIds,
        new ConfirmationTokenRegistry(Clock.systemUTC(), Duration.ofSeconds(30), 10_000),
        Clock.systemUTC());
  }

  PaperIslandCommandGateway(
      Server server,
      Supplier<Optional<FoundationRuntime>> runtime,
      Supplier<IslandId> islandIds,
      Supplier<OperationId> operationIds,
      ConfirmationTokenRegistry confirmations,
      Clock clock) {
    this.server = Objects.requireNonNull(server, "server");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.islandIds = Objects.requireNonNull(islandIds, "islandIds");
    this.operationIds = Objects.requireNonNull(operationIds, "operationIds");
    this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** {@inheritDoc} */
  @Override
  public MutationSubmission<CreateIslandResult> create(PlayerId owner) {
    Objects.requireNonNull(owner, "owner");
    OperationId operationId = operationIds.get();
    try {
      return create(owner, operationId);
    } catch (RuntimeException failure) {
      return new MutationSubmission<>(
          operationId, java.util.concurrent.CompletableFuture.failedFuture(failure));
    }
  }

  /** {@inheritDoc} */
  @Override
  public MutationSubmission<IslandHomeResult> home(PlayerId player) {
    Objects.requireNonNull(player, "player");
    OperationId operationId = operationIds.get();
    Optional<FoundationRuntime> active = runtime.get();
    if (active.isEmpty()) {
      return new MutationSubmission<>(
          operationId,
          java.util.concurrent.CompletableFuture.failedFuture(
              new CommandRuntimeUnavailableException("not-ready")));
    }
    return new MutationSubmission<>(
        operationId, active.orElseThrow().islandHome().home(player, operationId));
  }

  /** {@inheritDoc} */
  @Override
  public java.util.concurrent.CompletionStage<IslandInfoSnapshot> info(PlayerId player) {
    Objects.requireNonNull(player, "player");
    Optional<FoundationRuntime> active = runtime.get();
    if (active.isEmpty()) {
      return java.util.concurrent.CompletableFuture.failedFuture(
          new CommandRuntimeUnavailableException("not-ready"));
    }
    return active
        .orElseThrow()
        .islandQueries()
        .findActiveInfo(player)
        .thenCompose(
            info ->
                info.<java.util.concurrent.CompletionStage<IslandInfoSnapshot>>map(
                        java.util.concurrent.CompletableFuture::completedFuture)
                    .orElseGet(
                        () ->
                            java.util.concurrent.CompletableFuture.failedFuture(
                                new PlayerIslandNotFoundException(player))));
  }

  /** {@inheritDoc} */
  @Override
  public java.util.concurrent.CompletionStage<ConfirmationChallenge> requestDelete(
      PlayerId player) {
    Objects.requireNonNull(player, "player");
    return info(player)
        .thenCompose(
            island -> {
              if (!island.ownerId().equals(player)) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                    new IslandDeletionConflictException("Deletion requires the island owner"));
              }
              return java.util.concurrent.CompletableFuture.completedFuture(
                  confirmations.issue(
                      ConfirmationAction.DELETE,
                      player,
                      island.islandId(),
                      island.islandVersion()));
            });
  }

  /** {@inheritDoc} */
  @Override
  public MutationSubmission<IslandDeletionResult> confirmDelete(PlayerId player, String token) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(token, "token");
    OperationId operationId = operationIds.get();
    try {
      ConfirmationChallenge challenge =
          confirmations.consume(token, player, ConfirmationAction.DELETE);
      FoundationRuntime active =
          runtime.get().orElseThrow(() -> new CommandRuntimeUnavailableException("not-ready"));
      var height = active.configuration().buildHeight();
      IslandDeletionRequest request =
          new IslandDeletionRequest(
              challenge.islandId(),
              operationId,
              player,
              challenge.islandVersion(),
              height.minimumY(),
              height.maximumYExclusive(),
              clock.instant());
      return new MutationSubmission<>(operationId, active.islandDeletion().delete(request));
    } catch (RuntimeException failure) {
      return new MutationSubmission<>(
          operationId, java.util.concurrent.CompletableFuture.failedFuture(failure));
    }
  }

  private MutationSubmission<CreateIslandResult> create(PlayerId owner, OperationId operationId) {
    FoundationRuntime active =
        runtime.get().orElseThrow(() -> new CommandRuntimeUnavailableException("not-ready"));
    var worldSpec =
        active.configuration().worlds().stream()
            .filter(spec -> spec.environment() == World.Environment.NORMAL)
            .findFirst()
            .orElseGet(() -> active.configuration().worlds().getFirst());
    World world = server.getWorld(worldSpec.worldName());
    if (world == null) {
      throw new CommandRuntimeUnavailableException("primary-world-unloaded");
    }
    WorldId worldId = WorldId.of(world.getUID());
    var projection =
        active
            .worldProjections()
            .resolve(worldId)
            .orElseThrow(() -> new CommandRuntimeUnavailableException("primary-world-unverified"));
    if (!projection.shardGroupId().equals(worldSpec.shardGroupId())) {
      throw new CommandRuntimeUnavailableException("primary-world-shard-drift");
    }
    IslandId islandId = islandIds.get();
    var defaults = active.configuration().defaults();
    CreateIslandCommand command =
        new CreateIslandCommand(
            islandId,
            operationId,
            owner,
            worldSpec.shardGroupId(),
            worldId,
            defaults.profileId(),
            defaults.phaseId());
    return new MutationSubmission<>(operationId, active.islandCreation().create(command));
  }
}
