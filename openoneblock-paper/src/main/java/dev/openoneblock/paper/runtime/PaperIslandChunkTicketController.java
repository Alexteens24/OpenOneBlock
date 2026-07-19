package dev.openoneblock.paper.runtime;

import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.platform.PlatformTaskScheduler;
import dev.openoneblock.core.platform.RegionTaskTarget;
import dev.openoneblock.core.runtime.IslandChunkTicketController;
import dev.openoneblock.core.runtime.IslandChunkTicketLease;
import dev.openoneblock.core.runtime.IslandChunkTicketRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** Paper ticket adapter that loads and mutates each chunk only on its owning region. */
public final class PaperIslandChunkTicketController implements IslandChunkTicketController {
  private final Plugin plugin;
  private final Server server;
  private final PlatformTaskScheduler scheduler;
  private final ConcurrentMap<RegionTaskTarget, TicketReference> references =
      new ConcurrentHashMap<>();

  /**
   * Creates a Paper plugin-ticket controller.
   *
   * @param plugin ticket-owning plugin
   * @param server active Paper server
   * @param scheduler ownership-aware scheduler
   */
  public PaperIslandChunkTicketController(
      Plugin plugin, Server server, PlatformTaskScheduler scheduler) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.server = Objects.requireNonNull(server, "server");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
  }

  /**
   * Removes this plugin's tickets directly while Paper is disabling the plugin and therefore no
   * longer accepts scheduled region work. OpenOneBlock does not advertise Folia support until a
   * native pre-disable drain hook exists; callers must invoke this only from Paper's disable
   * thread.
   */
  public void emergencyReleaseAllOnDisable() {
    for (World world : server.getWorlds()) {
      world.removePluginChunkTickets(plugin);
    }
    references.clear();
  }

  /** {@inheritDoc} */
  @Override
  public CompletionStage<IslandChunkTicketLease> acquire(IslandChunkTicketRequest request) {
    Objects.requireNonNull(request, "request");
    World world;
    try {
      world = validateAndResolveWorld(request);
    } catch (RuntimeException exception) {
      return CompletableFuture.failedFuture(exception);
    }

    AcquisitionSession session = new AcquisitionSession();
    List<CompletionStage<Void>> acquisitions =
        request.chunks().stream().map(target -> acquireOne(world, target, session)).toList();
    CompletableFuture<IslandChunkTicketLease> result = new CompletableFuture<>();
    combine(acquisitions)
        .whenComplete(
            (ignored, failure) -> {
              if (failure == null && session.finish()) {
                result.complete(new PaperTicketLease(world, request.chunks()));
              } else if (failure != null) {
                failAndRollback(world, session, unwrap(failure), result);
              }
            });
    CompletableFuture.delayedExecutor(request.timeout().toMillis(), TimeUnit.MILLISECONDS)
        .execute(
            () ->
                failAndRollback(
                    world,
                    session,
                    new TimeoutException(
                        "Timed out preparing chunk tickets for operation " + request.operationId()),
                    result));
    return result;
  }

  private CompletionStage<Void> acquireOne(
      World world, RegionTaskTarget target, AcquisitionSession session) {
    return scheduler
        .region(target, () -> world.getChunkAtAsync(target.chunkX(), target.chunkZ(), true))
        .thenCompose(stage -> stage)
        .thenCompose(
            chunk ->
                scheduler.region(
                    target,
                    () -> {
                      verifyLoadedChunk(world, target, chunk);
                      retainTicket(chunk, target);
                      if (!session.record(target)) {
                        return releaseOne(world, target)
                            .thenCompose(
                                ignored ->
                                    CompletableFuture.<Void>failedFuture(
                                        new IllegalStateException(
                                            "ticket acquisition was cancelled")));
                      }
                      return CompletableFuture.<Void>completedFuture(null);
                    }))
        .thenCompose(stage -> stage);
  }

  private void retainTicket(Chunk chunk, RegionTaskTarget target) {
    references.compute(
        target,
        (ignored, current) -> {
          if (current != null) {
            current.count++;
            return current;
          }
          if (!chunk.addPluginChunkTicket(plugin)) {
            throw new IllegalStateException(
                "Paper refused unmanaged plugin ticket for chunk "
                    + target.chunkX()
                    + ","
                    + target.chunkZ());
          }
          return new TicketReference(1);
        });
  }

  private CompletionStage<Void> releaseOne(World world, RegionTaskTarget target) {
    return scheduler.region(
        target,
        () -> {
          references.compute(
              target,
              (ignored, current) -> {
                if (current == null) {
                  return null;
                }
                if (current.count > 1) {
                  current.count--;
                  return current;
                }
                if (!world.removePluginChunkTicket(target.chunkX(), target.chunkZ(), plugin)) {
                  throw new IllegalStateException(
                      "Paper refused plugin ticket release for chunk "
                          + target.chunkX()
                          + ","
                          + target.chunkZ());
                }
                return null;
              });
          return null;
        });
  }

  private CompletionStage<Void> releaseAll(World world, List<RegionTaskTarget> targets) {
    return combine(targets.stream().map(target -> releaseOne(world, target)).toList());
  }

  private void failAndRollback(
      World world,
      AcquisitionSession session,
      Throwable failure,
      CompletableFuture<IslandChunkTicketLease> result) {
    List<RegionTaskTarget> acquired = session.cancel();
    if (acquired == null) {
      return;
    }
    CompletionStage<Void> cleanup =
        acquired.isEmpty() ? CompletableFuture.completedFuture(null) : releaseAll(world, acquired);
    cleanup.whenComplete(
        (ignored, cleanupFailure) -> {
          if (cleanupFailure != null) {
            failure.addSuppressed(unwrap(cleanupFailure));
          }
          result.completeExceptionally(failure);
        });
  }

  private World validateAndResolveWorld(IslandChunkTicketRequest request) {
    Set<RegionTaskTarget> unique = new HashSet<>();
    WorldId worldId = request.chunks().getFirst().worldId();
    for (RegionTaskTarget target : request.chunks()) {
      if (!target.worldId().equals(worldId)) {
        throw new IllegalArgumentException("one ticket request cannot span multiple worlds");
      }
      if (!unique.add(target)) {
        throw new IllegalArgumentException("ticket request contains duplicate chunks");
      }
    }
    World world = server.getWorld(worldId.value());
    if (world == null) {
      throw new IllegalStateException("ticket target world is not loaded: " + worldId);
    }
    return world;
  }

  private static void verifyLoadedChunk(World world, RegionTaskTarget target, Chunk chunk) {
    if (chunk == null
        || !chunk.isLoaded()
        || chunk.getX() != target.chunkX()
        || chunk.getZ() != target.chunkZ()
        || !chunk.getWorld().getUID().equals(world.getUID())) {
      throw new IllegalStateException(
          "Paper returned an invalid loaded chunk for " + target.chunkX() + "," + target.chunkZ());
    }
  }

  private static CompletionStage<Void> combine(List<? extends CompletionStage<Void>> stages) {
    CompletableFuture<?>[] futures =
        stages.stream().map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new);
    return CompletableFuture.allOf(futures);
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable current = failure;
    while (current instanceof CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static final class AcquisitionSession {
    private final List<RegionTaskTarget> acquired = new ArrayList<>();
    private boolean active = true;

    synchronized boolean record(RegionTaskTarget target) {
      if (!active) {
        return false;
      }
      acquired.add(target);
      return true;
    }

    synchronized boolean finish() {
      if (!active) {
        return false;
      }
      active = false;
      return true;
    }

    synchronized List<RegionTaskTarget> cancel() {
      if (!active) {
        return null;
      }
      active = false;
      return List.copyOf(acquired);
    }
  }

  private static final class TicketReference {
    private int count;

    private TicketReference(int count) {
      this.count = count;
    }
  }

  private final class PaperTicketLease implements IslandChunkTicketLease {
    private final World world;
    private final List<RegionTaskTarget> targets;
    private CompletionStage<Void> release;

    private PaperTicketLease(World world, List<RegionTaskTarget> targets) {
      this.world = world;
      this.targets = List.copyOf(targets);
    }

    @Override
    public int chunkCount() {
      return targets.size();
    }

    @Override
    public synchronized CompletionStage<Void> release() {
      if (release == null) {
        release = releaseAll(world, targets);
      }
      return release;
    }
  }
}
