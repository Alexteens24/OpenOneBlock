package dev.openoneblock.core.island;

import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.execution.IslandExecutionLanes;
import dev.openoneblock.core.execution.LaneSubmission;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.locator.WorldProjection;
import dev.openoneblock.core.locator.WorldProjectionRegistry;
import dev.openoneblock.core.magic.InitialMagicBlock;
import dev.openoneblock.core.operation.IslandOperationClass;
import dev.openoneblock.core.operation.IslandOperationRequest;
import dev.openoneblock.core.runtime.IslandActivityLease;
import dev.openoneblock.core.runtime.IslandActivityReason;
import dev.openoneblock.core.runtime.IslandRuntimeHeader;
import dev.openoneblock.core.runtime.IslandRuntimeManager;
import dev.openoneblock.core.slot.SlotState;
import dev.openoneblock.core.world.IslandWorldPreparationPlan;
import dev.openoneblock.core.world.MinimalStarterPreparationPlanFactory;
import dev.openoneblock.core.world.WorldEffectPlan;
import dev.openoneblock.core.world.WorldPreparationCoordinator;
import dev.openoneblock.core.world.WorldPreparationReport;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

/** Successful-path crash-safe island creation orchestration inside one sequential island lane. */
public final class CreateIslandService {
  private static final NamespacedId PRIMARY_SPAWN = NamespacedId.parse("openoneblock:home");
  private static final NamespacedId PRIMARY_MAGIC_BLOCK = NamespacedId.parse("openoneblock:main");

  private final IslandCreationRepository repository;
  private final IslandExecutionLanes lanes;
  private final IslandRuntimeManager runtimes;
  private final WorldProjectionRegistry worlds;
  private final Function<dev.openoneblock.api.id.ShardGroupId, GridGeometry> geometryByShard;
  private final NamespacedId starterBlock;
  private final int magicBlockY;
  private final int minimumY;
  private final int maximumYExclusive;
  private final WorldPreparationCoordinator preparation;
  private final Clock clock;

  /**
   * Creates the creation application service from recovered foundation ports.
   *
   * @param repository authoritative creation persistence
   * @param lanes bounded sequential island lanes
   * @param runtimes reference-counted chunk runtime
   * @param worlds verified world projections
   * @param geometryByShard authoritative grid lookup
   * @param starterBlock configured Vanilla starter content
   * @param magicBlockY configured Magic Block height
   * @param minimumY inclusive configured preparation height
   * @param maximumYExclusive exclusive configured preparation height
   * @param preparation durable world preparation coordinator
   * @param clock application clock
   */
  public CreateIslandService(
      IslandCreationRepository repository,
      IslandExecutionLanes lanes,
      IslandRuntimeManager runtimes,
      WorldProjectionRegistry worlds,
      Function<dev.openoneblock.api.id.ShardGroupId, GridGeometry> geometryByShard,
      NamespacedId starterBlock,
      int magicBlockY,
      int minimumY,
      int maximumYExclusive,
      WorldPreparationCoordinator preparation,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.lanes = Objects.requireNonNull(lanes, "lanes");
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.worlds = Objects.requireNonNull(worlds, "worlds");
    this.geometryByShard = Objects.requireNonNull(geometryByShard, "geometryByShard");
    this.starterBlock = Objects.requireNonNull(starterBlock, "starterBlock");
    new MinimalStarterPreparationPlanFactory(starterBlock, magicBlockY);
    if (minimumY >= maximumYExclusive) {
      throw new IllegalArgumentException("creation build height is empty");
    }
    if (magicBlockY < minimumY || magicBlockY >= maximumYExclusive - 1) {
      throw new IllegalArgumentException("Magic Block and spawn must fit creation build height");
    }
    this.magicBlockY = magicBlockY;
    this.minimumY = minimumY;
    this.maximumYExclusive = maximumYExclusive;
    this.preparation = Objects.requireNonNull(preparation, "preparation");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Submits one idempotent creation intent to its new island's locking lane.
   *
   * @param command caller-owned stable creation intent
   * @return committed active island outcome
   */
  public CompletionStage<CreateIslandResult> create(CreateIslandCommand command) {
    Objects.requireNonNull(command, "command");
    Instant submittedAt = clock.instant();
    GridGeometry geometry;
    try {
      geometry = geometry(command.shardGroupId());
    } catch (RuntimeException exception) {
      return CompletableFuture.failedFuture(exception);
    }
    IslandCreationContext context =
        new IslandCreationContext(
            command.primaryWorldId(),
            command.profileId(),
            command.phaseId(),
            starterBlock,
            magicBlockY,
            minimumY,
            maximumYExclusive);
    IslandCreationRequest request =
        new IslandCreationRequest(
            command.islandId(),
            command.ownerId(),
            command.shardGroupId(),
            command.operationId(),
            geometry.configuration().initialBorder(),
            geometry.configuration().maximumBorder(),
            context,
            submittedAt);
    return submit(request);
  }

  /**
   * Replays one complete durable pending intent, preserving its original plan inputs.
   *
   * @param request persisted pending creation request
   * @return committed active island outcome
   */
  public CompletionStage<CreateIslandResult> resume(IslandCreationRequest request) {
    Objects.requireNonNull(request, "request");
    return submit(request);
  }

  private CompletionStage<CreateIslandResult> submit(IslandCreationRequest request) {
    IslandOperationRequest laneRequest =
        new IslandOperationRequest(
            request.islandId(),
            request.operationId(),
            0,
            request.requestedAt(),
            IslandOperationClass.LOCKING);
    LaneSubmission<CreateIslandResult> submission =
        lanes.submit(laneRequest, () -> createInLane(request));
    return switch (submission) {
      case LaneSubmission.Accepted<CreateIslandResult> accepted -> accepted.completion();
      case LaneSubmission.Rejected<CreateIslandResult> rejected ->
          CompletableFuture.failedFuture(new CreateIslandRejectedException(rejected.reason()));
    };
  }

  private CompletionStage<CreateIslandResult> createInLane(IslandCreationRequest request) {
    WorldProjection world;
    GridGeometry geometry;
    try {
      world =
          worlds
              .resolve(request.context().primaryWorldId())
              .orElseThrow(() -> new IllegalArgumentException("primary world is not registered"));
      if (!world.shardGroupId().equals(request.shardGroupId())) {
        throw new IllegalArgumentException("primary world belongs to another shard group");
      }
      geometry = geometry(request.shardGroupId());
      if (request.initialBorderSize() != geometry.configuration().initialBorder()
          || request.maximumBorderSize() != geometry.configuration().maximumBorder()) {
        throw new IllegalArgumentException("creation intent does not match shard grid geometry");
      }
    } catch (RuntimeException exception) {
      return CompletableFuture.failedFuture(exception);
    }
    return repository
        .createAllocation(request)
        .thenCompose(snapshot -> beginOrResume(snapshot, request, world, geometry));
  }

  private CompletionStage<CreateIslandResult> beginOrResume(
      IslandAggregateSnapshot snapshot,
      IslandCreationRequest request,
      WorldProjection world,
      GridGeometry geometry) {
    if (snapshot.lifecycleState() == IslandLifecycleState.ACTIVE) {
      return CompletableFuture.completedFuture(new CreateIslandResult(snapshot, true));
    }
    CompletionStage<IslandAggregateSnapshot> creating;
    if (snapshot.lifecycleState() == IslandLifecycleState.ALLOCATING) {
      var slot = snapshot.primarySlot().orElseThrow();
      creating =
          repository.advanceCreation(
              new IslandCreationTransitionRequest(
                  snapshot.islandId(),
                  request.operationId(),
                  IslandCreationStage.BEGIN_PREPARATION,
                  snapshot.version(),
                  slot.version(),
                  clock.instant()));
    } else if (snapshot.lifecycleState() == IslandLifecycleState.CREATING
        && snapshot.primarySlot().orElseThrow().state() == SlotState.PREPARING) {
      creating = CompletableFuture.completedFuture(snapshot);
    } else {
      return CompletableFuture.failedFuture(
          new IllegalStateException("creation operation is not in a resumable lifecycle state"));
    }
    return creating.thenCompose(current -> retainAndPrepare(current, request, world, geometry));
  }

  private CompletionStage<CreateIslandResult> retainAndPrepare(
      IslandAggregateSnapshot creating,
      IslandCreationRequest request,
      WorldProjection world,
      GridGeometry geometry) {
    var slot = creating.primarySlot().orElseThrow();
    var reserved = geometry.reservedRegion(slot.gridPosition());
    IslandRuntimeHeader header =
        new IslandRuntimeHeader(
            creating.islandId(), world.worldId(), slot.gridPosition(), reserved);
    return runtimes
        .retain(header, IslandActivityReason.WORLD_PREPARATION, request.operationId())
        .thenCompose(
            lease ->
                withLease(lease, () -> prepareAndActivate(creating, request, world, geometry)));
  }

  private CompletionStage<CreateIslandResult> prepareAndActivate(
      IslandAggregateSnapshot creating,
      IslandCreationRequest request,
      WorldProjection world,
      GridGeometry geometry) {
    IslandCreationContext context = request.context();
    IslandWorldPreparationPlan plan =
        new MinimalStarterPreparationPlanFactory(context.starterBlockId(), context.magicBlockY())
            .create(
                creating,
                world.worldId(),
                geometry,
                context.minimumY(),
                context.maximumYExclusive());
    return preparation
        .prepare(creating, plan)
        .thenCompose(
            report -> {
              if (report.status() != WorldPreparationReport.Status.VERIFIED_SUCCESS) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException(
                        "world preparation did not verify: " + report.diagnostic()));
              }
              WorldEffectPlan.SetVanillaBlock block =
                  plan.effects().stream()
                      .filter(WorldEffectPlan.SetVanillaBlock.class::isInstance)
                      .map(WorldEffectPlan.SetVanillaBlock.class::cast)
                      .findFirst()
                      .orElseThrow();
              WorldEffectPlan.VerifySafeSpawn spawn =
                  plan.effects().stream()
                      .filter(WorldEffectPlan.VerifySafeSpawn.class::isInstance)
                      .map(WorldEffectPlan.VerifySafeSpawn.class::cast)
                      .findFirst()
                      .orElseThrow();
              var slot = creating.primarySlot().orElseThrow();
              IslandCreationActivationRequest activation =
                  new IslandCreationActivationRequest(
                      creating.islandId(),
                      request.operationId(),
                      creating.version(),
                      slot.version(),
                      new IslandSpawnPoint(PRIMARY_SPAWN, spawn.spawn(), true),
                      new InitialMagicBlock(
                          PRIMARY_MAGIC_BLOCK,
                          block.position(),
                          context.profileId(),
                          block.blockType()),
                      context.phaseId(),
                      report.receipts().stream().map(receipt -> receipt.key()).toList(),
                      clock.instant());
              return repository
                  .activateCreation(activation)
                  .thenApply(active -> new CreateIslandResult(active, false));
            });
  }

  private static <T> CompletionStage<T> withLease(
      IslandActivityLease lease, Supplier<CompletionStage<T>> workSupplier) {
    CompletableFuture<T> result = new CompletableFuture<>();
    CompletionStage<T> work;
    try {
      work = Objects.requireNonNull(workSupplier.get(), "lease work");
    } catch (Throwable failure) {
      work = CompletableFuture.failedFuture(failure);
    }
    work.whenComplete(
        (value, workFailure) ->
            lease
                .release()
                .whenComplete(
                    (ignored, releaseFailure) -> {
                      Throwable failure = unwrap(workFailure);
                      Throwable cleanupFailure = unwrap(releaseFailure);
                      if (failure != null) {
                        if (cleanupFailure != null && cleanupFailure != failure) {
                          failure.addSuppressed(cleanupFailure);
                        }
                        result.completeExceptionally(failure);
                      } else if (cleanupFailure != null) {
                        result.completeExceptionally(cleanupFailure);
                      } else {
                        result.complete(value);
                      }
                    }));
    return result;
  }

  private GridGeometry geometry(dev.openoneblock.api.id.ShardGroupId shardGroupId) {
    return Objects.requireNonNull(geometryByShard.apply(shardGroupId), "geometry");
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
}
