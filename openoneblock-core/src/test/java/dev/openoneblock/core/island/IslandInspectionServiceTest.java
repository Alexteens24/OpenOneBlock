package dev.openoneblock.core.island;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.grid.GridPosition;
import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.api.island.IslandLifecycleState;
import dev.openoneblock.core.grid.HorizontalBounds;
import dev.openoneblock.core.runtime.IslandActivityReason;
import dev.openoneblock.core.runtime.IslandChunkTicketLease;
import dev.openoneblock.core.runtime.IslandRuntimeHeader;
import dev.openoneblock.core.runtime.IslandRuntimeManager;
import dev.openoneblock.core.runtime.IslandRuntimeState;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotState;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IslandInspectionServiceTest {
  private static final IslandId ISLAND = IslandId.generate();
  private static final WorldId WORLD = WorldId.of(java.util.UUID.randomUUID());

  @Test
  void inspectionNeverLoadsChunksAndOnlyEnrichesAnAlreadyCachedRuntime() {
    AtomicInteger acquisitions = new AtomicInteger();
    IslandRuntimeManager runtimes =
        new IslandRuntimeManager(
            request -> {
              acquisitions.incrementAndGet();
              return CompletableFuture.completedFuture(
                  new IslandChunkTicketLease() {
                    @Override
                    public int chunkCount() {
                      return request.chunks().size();
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<Void> release() {
                      return CompletableFuture.completedFuture(null);
                    }
                  });
            },
            Duration.ofSeconds(1));
    IslandInspectionService service =
        new IslandInspectionService(
            islandId -> CompletableFuture.completedFuture(Optional.of(snapshot())), runtimes);

    IslandInspectionSnapshot unloaded =
        service.inspect(ISLAND).toCompletableFuture().join().orElseThrow();

    assertTrue(unloaded.runtime().isEmpty());
    assertEquals(0, acquisitions.get());

    var lease =
        runtimes
            .retain(
                new IslandRuntimeHeader(
                    ISLAND, WORLD, new GridPosition(0, 0), new HorizontalBounds(-32, -32, 32, 32)),
                IslandActivityReason.ADMIN_INSPECTION,
                OperationId.generate())
            .toCompletableFuture()
            .join();
    IslandInspectionSnapshot active =
        service.inspect(ISLAND).toCompletableFuture().join().orElseThrow();

    assertEquals(1, acquisitions.get());
    assertEquals(IslandRuntimeState.ACTIVE, active.runtime().orElseThrow().state());
    assertTrue(active.runtime().orElseThrow().loadedChunkCount() > 0);
    lease.release().toCompletableFuture().join();
  }

  private static IslandInspectionSnapshot snapshot() {
    Instant now = Instant.parse("2026-07-19T00:00:00Z");
    return new IslandInspectionSnapshot(
        ISLAND,
        PlayerId.of(java.util.UUID.randomUUID()),
        IslandLifecycleState.ACTIVE,
        4,
        Optional.of(ShardGroupId.parse("openoneblock:primary")),
        Optional.of(new GridPosition(0, 0)),
        Optional.of(SlotId.generate()),
        Optional.of(SlotState.ACTIVE),
        Optional.of(3L),
        64,
        384,
        Optional.of(NamespacedId.parse("openoneblock:plains")),
        Optional.of(8L),
        1,
        Optional.empty(),
        Optional.empty(),
        now);
  }
}
