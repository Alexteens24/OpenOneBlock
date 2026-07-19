package dev.openoneblock.paper.command;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.island.CreateIslandCommand;
import dev.openoneblock.core.island.CreateIslandResult;
import dev.openoneblock.paper.bootstrap.FoundationRuntime;
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

  /**
   * Creates the production command gateway.
   *
   * @param server active Paper server
   * @param runtime READY-only service graph supplier
   */
  public PaperIslandCommandGateway(Server server, Supplier<Optional<FoundationRuntime>> runtime) {
    this(server, runtime, IslandId::generate, OperationId::generate);
  }

  PaperIslandCommandGateway(
      Server server,
      Supplier<Optional<FoundationRuntime>> runtime,
      Supplier<IslandId> islandIds,
      Supplier<OperationId> operationIds) {
    this.server = Objects.requireNonNull(server, "server");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.islandIds = Objects.requireNonNull(islandIds, "islandIds");
    this.operationIds = Objects.requireNonNull(operationIds, "operationIds");
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
