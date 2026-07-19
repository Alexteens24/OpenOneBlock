package dev.openoneblock.core.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import dev.openoneblock.core.island.IslandAggregateSnapshot;
import dev.openoneblock.core.slot.AllocatedSlot;
import dev.openoneblock.core.slot.SlotId;
import dev.openoneblock.core.slot.SlotState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorldPreparationCoordinatorTest {
  private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final OperationId OPERATION =
      OperationId.parse("00000000-0000-0000-0000-000000000021");
  private static final IslandId ISLAND = IslandId.parse("00000000-0000-0000-0000-000000000022");
  private static final PlayerId OWNER = PlayerId.parse("00000000-0000-0000-0000-000000000023");
  private static final SlotId SLOT = SlotId.parse("00000000-0000-0000-0000-000000000024");
  private static final WorldId WORLD = WorldId.parse("00000000-0000-0000-0000-000000000025");
  private static final HorizontalBounds RESERVED = new HorizontalBounds(-32, -32, 32, 32);

  @Test
  void recordsDispatchBeforePlatformExecutionAndReplaysVerifiedEffects() {
    InMemoryJournal journal = new InMemoryJournal();
    AtomicInteger executions = new AtomicInteger();
    IslandWorldPreparation platform =
        new IslandWorldPreparation() {
          @Override
          public CompletionStage<WorldEffectOutcome> execute(WorldEffectPlan effect) {
            assertEquals(WorldEffectState.DISPATCHED, journal.receipts.get(effect.key()).state());
            executions.incrementAndGet();
            return success("target verified");
          }

          @Override
          public CompletionStage<WorldEffectOutcome> verify(WorldEffectPlan effect) {
            throw new AssertionError("verified replay must not inspect the world again");
          }
        };
    WorldPreparationCoordinator coordinator =
        new WorldPreparationCoordinator(journal, platform, CLOCK);

    WorldPreparationReport first = join(coordinator.prepare(snapshot(), plan()));
    WorldPreparationReport replay = join(coordinator.prepare(snapshot(), plan()));

    assertEquals(WorldPreparationReport.Status.VERIFIED_SUCCESS, first.status());
    assertEquals(WorldPreparationReport.Status.VERIFIED_SUCCESS, replay.status());
    assertEquals(3, executions.get());
    assertEquals(3, journal.receipts.size());
    assertTrue(journal.receipts.values().stream().allMatch(r -> r.dispatchAttempts() == 1));
  }

  @Test
  void recoversDispatchedIdempotentEffectByVerificationBeforeRetry() {
    InMemoryJournal journal = new InMemoryJournal();
    WorldEffectPlan effect = plan().effects().get(1);
    join(journal.register(effect, NOW));
    join(journal.markDispatched(effect, NOW));
    AtomicInteger verifications = new AtomicInteger();
    AtomicInteger executions = new AtomicInteger();
    IslandWorldPreparation platform =
        new IslandWorldPreparation() {
          @Override
          public CompletionStage<WorldEffectOutcome> execute(WorldEffectPlan candidate) {
            executions.incrementAndGet();
            return success("reapplied exact block state");
          }

          @Override
          public CompletionStage<WorldEffectOutcome> verify(WorldEffectPlan candidate) {
            verifications.incrementAndGet();
            return CompletableFuture.completedFuture(
                new WorldEffectOutcome(
                    WorldEffectOutcome.Status.NOT_APPLIED,
                    false,
                    "target block is provably absent"));
          }
        };

    WorldPreparationReport report =
        join(new WorldPreparationCoordinator(journal, platform, CLOCK).prepare(snapshot(), plan()));

    assertEquals(WorldPreparationReport.Status.VERIFIED_SUCCESS, report.status());
    assertEquals(1, verifications.get());
    assertEquals(3, executions.get());
    assertEquals(1, journal.receipts.get(effect.key()).dispatchAttempts());
  }

  @Test
  void partialMutationFailureRequiresCleanupAndStopsLaterEffects() {
    InMemoryJournal journal = new InMemoryJournal();
    AtomicInteger executions = new AtomicInteger();
    IslandWorldPreparation platform =
        new IslandWorldPreparation() {
          @Override
          public CompletionStage<WorldEffectOutcome> execute(WorldEffectPlan effect) {
            int invocation = executions.incrementAndGet();
            if (invocation == 2) {
              return CompletableFuture.completedFuture(
                  new WorldEffectOutcome(
                      WorldEffectOutcome.Status.VERIFIED_FAILURE,
                      true,
                      "second region rejected block mutation"));
            }
            return success("verified");
          }

          @Override
          public CompletionStage<WorldEffectOutcome> verify(WorldEffectPlan effect) {
            throw new AssertionError("unexpected recovery verification");
          }
        };

    WorldPreparationReport report =
        join(new WorldPreparationCoordinator(journal, platform, CLOCK).prepare(snapshot(), plan()));

    assertEquals(WorldPreparationReport.Status.VERIFIED_FAILURE, report.status());
    assertTrue(report.cleanupRequired());
    assertEquals(2, report.receipts().size());
    assertEquals(2, executions.get());
  }

  @Test
  void platformExceptionBecomesDurableAmbiguousEvidence() {
    InMemoryJournal journal = new InMemoryJournal();
    IslandWorldPreparation platform =
        new IslandWorldPreparation() {
          @Override
          public CompletionStage<WorldEffectOutcome> execute(WorldEffectPlan effect) {
            return CompletableFuture.failedFuture(new IllegalStateException("region retired"));
          }

          @Override
          public CompletionStage<WorldEffectOutcome> verify(WorldEffectPlan effect) {
            throw new AssertionError("unexpected recovery verification");
          }
        };

    WorldPreparationReport report =
        join(new WorldPreparationCoordinator(journal, platform, CLOCK).prepare(snapshot(), plan()));

    assertEquals(WorldPreparationReport.Status.RECONCILIATION_REQUIRED, report.status());
    assertEquals(WorldEffectState.AMBIGUOUS, report.receipts().getFirst().state());
    assertTrue(report.cleanupRequired());
  }

  @Test
  void rejectsLifecycleOrSlotStateBeforeRegisteringEffects() {
    InMemoryJournal journal = new InMemoryJournal();
    WorldPreparationCoordinator coordinator =
        new WorldPreparationCoordinator(journal, unexpectedPlatform(), CLOCK);
    IslandAggregateSnapshot active =
        snapshot(IslandLifecycleState.ACTIVE, SlotState.ACTIVE, Optional.empty());

    CompletionException exception =
        assertThrows(CompletionException.class, () -> join(coordinator.prepare(active, plan())));

    assertInstanceOf(IllegalStateException.class, exception.getCause());
    assertTrue(journal.receipts.isEmpty());
  }

  @Test
  void planRejectsMagicBlockSpawnAndStructureOutsideReservedRegion() {
    List<WorldEffectPlan> outsideBlock = new ArrayList<>(plan().effects());
    outsideBlock.set(
        1,
        new WorldEffectPlan.SetVanillaBlock(
            new WorldEffectKey(OPERATION, 1),
            ISLAND,
            new WorldBlockPosition(WORLD, 32, 64, 0),
            NamespacedId.parse("minecraft:grass_block")));
    assertThrows(IllegalArgumentException.class, () -> plan(outsideBlock));

    List<WorldEffectPlan> outsideSpawn = new ArrayList<>(plan().effects());
    outsideSpawn.set(
        2,
        new WorldEffectPlan.VerifySafeSpawn(
            new WorldEffectKey(OPERATION, 2),
            ISLAND,
            new WorldSpawnPosition(WORLD, 0.5, 320.0, 0.5, 0, 0)));
    assertThrows(IllegalArgumentException.class, () -> plan(outsideSpawn));

    List<WorldEffectPlan> structure =
        List.of(
            new WorldEffectPlan.PlaceStructure(
                new WorldEffectKey(OPERATION, 0),
                ISLAND,
                WORLD,
                NamespacedId.parse("openoneblock:starter"),
                new WorldBlockPosition(WORLD, -32, 64, 0),
                WorldEffectPlan.Rotation.NONE,
                WorldEffectPlan.Mirror.NONE,
                new HorizontalBounds(-33, -1, 1, 1),
                64,
                70));
    assertThrows(IllegalArgumentException.class, () -> plan(structure));
  }

  private static IslandWorldPreparation unexpectedPlatform() {
    return new IslandWorldPreparation() {
      @Override
      public CompletionStage<WorldEffectOutcome> execute(WorldEffectPlan effect) {
        throw new AssertionError("platform must not be called");
      }

      @Override
      public CompletionStage<WorldEffectOutcome> verify(WorldEffectPlan effect) {
        throw new AssertionError("platform must not be called");
      }
    };
  }

  private static CompletionStage<WorldEffectOutcome> success(String diagnostic) {
    return CompletableFuture.completedFuture(
        new WorldEffectOutcome(WorldEffectOutcome.Status.VERIFIED_SUCCESS, false, diagnostic));
  }

  private static IslandAggregateSnapshot snapshot() {
    return snapshot(IslandLifecycleState.CREATING, SlotState.PREPARING, Optional.of(OPERATION));
  }

  private static IslandAggregateSnapshot snapshot(
      IslandLifecycleState lifecycle, SlotState slotState, Optional<OperationId> pending) {
    AllocatedSlot slot =
        new AllocatedSlot(
            SLOT,
            ShardGroupId.parse("openoneblock:primary"),
            0,
            new GridPosition(0, 0),
            slotState,
            ISLAND,
            2);
    return new IslandAggregateSnapshot(
        ISLAND, OWNER, lifecycle, Optional.of(slot), 64, 384, 2, pending, NOW, NOW);
  }

  private static IslandWorldPreparationPlan plan() {
    List<WorldEffectPlan> effects =
        List.of(
            new WorldEffectPlan.VerifyCleanRegion(
                new WorldEffectKey(OPERATION, 0), ISLAND, WORLD, RESERVED, -64, 320),
            new WorldEffectPlan.SetVanillaBlock(
                new WorldEffectKey(OPERATION, 1),
                ISLAND,
                new WorldBlockPosition(WORLD, 0, 64, 0),
                NamespacedId.parse("minecraft:grass_block")),
            new WorldEffectPlan.VerifySafeSpawn(
                new WorldEffectKey(OPERATION, 2),
                ISLAND,
                new WorldSpawnPosition(WORLD, 0.5, 65, 0.5, 0, 0)));
    return plan(effects);
  }

  private static IslandWorldPreparationPlan plan(List<WorldEffectPlan> effects) {
    return new IslandWorldPreparationPlan(
        OPERATION, ISLAND, 2, SLOT, 2, WORLD, RESERVED, -64, 320, effects);
  }

  private static <T> T join(CompletionStage<T> stage) {
    return stage.toCompletableFuture().join();
  }

  private static final class InMemoryJournal implements WorldEffectJournal {
    private final Map<WorldEffectKey, WorldEffectReceipt> receipts = new LinkedHashMap<>();

    @Override
    public synchronized CompletionStage<WorldEffectReceipt> register(
        WorldEffectPlan effect, Instant recordedAt) {
      WorldEffectReceipt existing = receipts.get(effect.key());
      if (existing != null) {
        if (!existing.fingerprint().equals(effect.fingerprint())) {
          return CompletableFuture.failedFuture(new IllegalStateException("fingerprint conflict"));
        }
        return CompletableFuture.completedFuture(existing);
      }
      WorldEffectReceipt created =
          new WorldEffectReceipt(
              effect.key(),
              effect.islandId(),
              effect.kind(),
              effect.safety(),
              effect.descriptor(),
              effect.fingerprint(),
              WorldEffectState.NOT_STARTED,
              0,
              Optional.empty(),
              recordedAt,
              Optional.empty(),
              Optional.empty(),
              recordedAt);
      receipts.put(effect.key(), created);
      return CompletableFuture.completedFuture(created);
    }

    @Override
    public synchronized CompletionStage<WorldEffectReceipt> markDispatched(
        WorldEffectPlan effect, Instant dispatchedAt) {
      WorldEffectReceipt current = require(effect);
      if (current.state() == WorldEffectState.DISPATCHED) {
        return CompletableFuture.completedFuture(current);
      }
      if (current.state() != WorldEffectState.NOT_STARTED) {
        return CompletableFuture.failedFuture(new IllegalStateException("terminal receipt"));
      }
      WorldEffectReceipt dispatched =
          copy(
              current,
              WorldEffectState.DISPATCHED,
              1,
              Optional.empty(),
              Optional.of(dispatchedAt),
              Optional.empty(),
              dispatchedAt);
      receipts.put(effect.key(), dispatched);
      return CompletableFuture.completedFuture(dispatched);
    }

    @Override
    public synchronized CompletionStage<WorldEffectReceipt> recordOutcome(
        WorldEffectPlan effect, WorldEffectState outcome, String diagnostic, Instant completedAt) {
      WorldEffectReceipt current = require(effect);
      if (current.state().terminal()) {
        return CompletableFuture.completedFuture(current);
      }
      if (current.state() != WorldEffectState.DISPATCHED || !outcome.terminal()) {
        return CompletableFuture.failedFuture(new IllegalStateException("invalid transition"));
      }
      WorldEffectReceipt completed =
          copy(
              current,
              outcome,
              current.dispatchAttempts(),
              Optional.of(diagnostic),
              current.dispatchedAt(),
              Optional.of(completedAt),
              completedAt);
      receipts.put(effect.key(), completed);
      return CompletableFuture.completedFuture(completed);
    }

    @Override
    public synchronized CompletionStage<Optional<WorldEffectReceipt>> find(WorldEffectKey key) {
      return CompletableFuture.completedFuture(Optional.ofNullable(receipts.get(key)));
    }

    @Override
    public synchronized CompletionStage<List<WorldEffectReceipt>> findByOperation(
        OperationId operationId) {
      return CompletableFuture.completedFuture(
          receipts.values().stream()
              .filter(receipt -> receipt.key().operationId().equals(operationId))
              .toList());
    }

    private WorldEffectReceipt require(WorldEffectPlan effect) {
      WorldEffectReceipt receipt = receipts.get(effect.key());
      if (receipt == null || !receipt.fingerprint().equals(effect.fingerprint())) {
        throw new IllegalStateException("missing or conflicting receipt");
      }
      return receipt;
    }

    private static WorldEffectReceipt copy(
        WorldEffectReceipt current,
        WorldEffectState state,
        int attempts,
        Optional<String> diagnostic,
        Optional<Instant> dispatchedAt,
        Optional<Instant> completedAt,
        Instant updatedAt) {
      return new WorldEffectReceipt(
          current.key(),
          current.islandId(),
          current.kind(),
          current.safety(),
          current.descriptor(),
          current.fingerprint(),
          state,
          attempts,
          diagnostic,
          current.createdAt(),
          dispatchedAt,
          completedAt,
          updatedAt);
    }
  }
}
