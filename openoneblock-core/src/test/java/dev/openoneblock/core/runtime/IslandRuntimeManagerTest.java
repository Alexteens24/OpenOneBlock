package dev.openoneblock.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.grid.HorizontalBounds;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IslandRuntimeManagerTest {
  private static final IslandId ISLAND = IslandId.parse("00000000-0000-0000-0000-000000000001");
  private static final WorldId WORLD =
      WorldId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));
  private static final IslandRuntimeHeader HEADER =
      new IslandRuntimeHeader(
          ISLAND, WORLD, new GridPosition(0, 0), new HorizontalBounds(-32, -32, 32, 32));

  @Test
  void concurrentReasonsShareTicketsAndFinalReleaseUnloadsEverything() {
    FakeTicketController controller = new FakeTicketController();
    IslandRuntimeManager manager = new IslandRuntimeManager(controller, Duration.ofSeconds(2));

    IslandActivityLease player =
        join(manager.retain(HEADER, IslandActivityReason.ONLINE_PLAYER, OperationId.generate()));
    IslandActivityLease preparation =
        join(
            manager.retain(HEADER, IslandActivityReason.WORLD_PREPARATION, OperationId.generate()));

    assertEquals(1, controller.acquisitions.get());
    assertEquals(16, manager.loadedChunkCount());
    IslandRuntimeSnapshot active = manager.snapshot(ISLAND).orElseThrow();
    assertEquals(IslandRuntimeState.ACTIVE, active.state());
    assertEquals(2, active.activityCounts().size());

    join(player.release());
    assertEquals(0, controller.releases.get());
    assertTrue(manager.snapshot(ISLAND).isPresent());

    join(preparation.release());
    assertEquals(1, controller.releases.get());
    assertEquals(0, manager.loadedChunkCount());
    assertTrue(manager.snapshot(ISLAND).isEmpty());
  }

  @Test
  void duplicateLeaseReleaseIsIdempotent() {
    FakeTicketController controller = new FakeTicketController();
    IslandRuntimeManager manager = new IslandRuntimeManager(controller, Duration.ofSeconds(2));
    IslandActivityLease lease =
        join(manager.retain(HEADER, IslandActivityReason.CLEANUP, OperationId.generate()));

    join(lease.release());
    join(lease.release());

    assertEquals(1, controller.releases.get());
  }

  @Test
  void failedAcquisitionLeavesNoRuntimeOrLoadedChunkMetric() {
    FakeTicketController controller = new FakeTicketController();
    controller.acquisitionFailure = new IllegalStateException("chunk load failed");
    IslandRuntimeManager manager = new IslandRuntimeManager(controller, Duration.ofSeconds(2));

    CompletionException exception =
        assertThrows(
            CompletionException.class,
            () ->
                join(
                    manager.retain(
                        HEADER, IslandActivityReason.WORLD_PREPARATION, OperationId.generate())));

    assertInstanceOf(IllegalStateException.class, exception.getCause());
    assertEquals(0, manager.loadedChunkCount());
    assertTrue(manager.snapshot(ISLAND).isEmpty());
  }

  @Test
  void shutdownWaitsForInFlightAcquisitionThenReleasesItsLease() {
    FakeTicketController controller = new FakeTicketController();
    controller.delayedAcquisition = new CompletableFuture<>();
    IslandRuntimeManager manager = new IslandRuntimeManager(controller, Duration.ofSeconds(2));
    CompletionStage<IslandActivityLease> activity =
        manager.retain(HEADER, IslandActivityReason.SCHEDULED_WORLD_ACTION, OperationId.generate());
    CompletionStage<Void> shutdown = manager.shutdown();

    controller.delayedAcquisition.complete(controller.lease);
    join(shutdown);

    assertThrows(CompletionException.class, () -> join(activity));
    assertEquals(1, controller.releases.get());
    assertEquals(0, manager.loadedChunkCount());
    assertTrue(manager.snapshot(ISLAND).isEmpty());
  }

  @Test
  void shutdownReleasesActiveTicketsAndRejectsNewActivity() {
    FakeTicketController controller = new FakeTicketController();
    IslandRuntimeManager manager = new IslandRuntimeManager(controller, Duration.ofSeconds(2));
    join(manager.retain(HEADER, IslandActivityReason.ADMIN_INSPECTION, OperationId.generate()));

    join(manager.shutdown());

    assertEquals(1, controller.releases.get());
    assertEquals(0, manager.loadedChunkCount());
    assertTrue(manager.snapshot(ISLAND).isEmpty());
    assertThrows(
        CompletionException.class,
        () ->
            join(
                manager.retain(
                    HEADER, IslandActivityReason.ADMIN_INSPECTION, OperationId.generate())));
  }

  @Test
  void conflictingOfflineHeaderCannotShareExistingRuntime() {
    FakeTicketController controller = new FakeTicketController();
    IslandRuntimeManager manager = new IslandRuntimeManager(controller, Duration.ofSeconds(2));
    IslandActivityLease lease =
        join(manager.retain(HEADER, IslandActivityReason.ONLINE_PLAYER, OperationId.generate()));
    IslandRuntimeHeader conflicting =
        new IslandRuntimeHeader(
            ISLAND, WORLD, new GridPosition(1, 0), new HorizontalBounds(480, -32, 544, 32));

    CompletionException exception =
        assertThrows(
            CompletionException.class,
            () ->
                join(
                    manager.retain(
                        conflicting,
                        IslandActivityReason.WORLD_PREPARATION,
                        OperationId.generate())));

    assertInstanceOf(IllegalStateException.class, exception.getCause());
    assertFalse(exception.getCause().getMessage().isEmpty());
    join(lease.release());
  }

  private static <T> T join(CompletionStage<T> stage) {
    return stage.toCompletableFuture().join();
  }

  private static final class FakeTicketController implements IslandChunkTicketController {
    private final AtomicInteger acquisitions = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();
    private final FakeTicketLease lease = new FakeTicketLease(releases);
    private volatile RuntimeException acquisitionFailure;
    private volatile CompletableFuture<IslandChunkTicketLease> delayedAcquisition;

    @Override
    public CompletionStage<IslandChunkTicketLease> acquire(IslandChunkTicketRequest request) {
      acquisitions.incrementAndGet();
      if (acquisitionFailure != null) {
        return CompletableFuture.failedFuture(acquisitionFailure);
      }
      return delayedAcquisition == null
          ? CompletableFuture.completedFuture(lease)
          : delayedAcquisition;
    }
  }

  private static final class FakeTicketLease implements IslandChunkTicketLease {
    private final AtomicInteger releases;
    private final CompletableFuture<Void> release = new CompletableFuture<>();

    private FakeTicketLease(AtomicInteger releases) {
      this.releases = releases;
    }

    @Override
    public int chunkCount() {
      return 16;
    }

    @Override
    public CompletionStage<Void> release() {
      if (release.complete(null)) {
        releases.incrementAndGet();
      }
      return release;
    }
  }
}
