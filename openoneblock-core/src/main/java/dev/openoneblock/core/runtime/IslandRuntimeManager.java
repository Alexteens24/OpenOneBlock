package dev.openoneblock.core.runtime;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared reference-counted island runtime manager with no per-island repeating tasks. */
public final class IslandRuntimeManager {
  private final IslandChunkTicketController ticketController;
  private final Duration ticketTimeout;
  private final ConcurrentMap<IslandId, RuntimeEntry> entries = new ConcurrentHashMap<>();
  private final AtomicInteger loadedChunks = new AtomicInteger();
  private final AtomicBoolean accepting = new AtomicBoolean(true);

  /**
   * Creates one shared runtime manager.
   *
   * @param ticketController platform ticket adapter
   * @param ticketTimeout bounded acquisition and release duration
   */
  public IslandRuntimeManager(
      IslandChunkTicketController ticketController, Duration ticketTimeout) {
    this.ticketController = Objects.requireNonNull(ticketController, "ticketController");
    this.ticketTimeout = Objects.requireNonNull(ticketTimeout, "ticketTimeout");
    if (ticketTimeout.isNegative() || ticketTimeout.isZero()) {
      throw new IllegalArgumentException("ticketTimeout must be positive");
    }
  }

  /**
   * Retains one activity reason, sharing a single ticket set with every concurrent reason.
   *
   * @param header immutable minimal island runtime metadata
   * @param reason activity reason
   * @param operationId diagnostic operation identity
   * @return lease after the complete ticket set is acquired
   */
  public CompletionStage<IslandActivityLease> retain(
      IslandRuntimeHeader header, IslandActivityReason reason, OperationId operationId) {
    Objects.requireNonNull(header, "header");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(operationId, "operationId");
    if (!accepting.get()) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("island runtime manager is shutting down"));
    }
    RuntimeEntry entry =
        entries.computeIfAbsent(header.islandId(), ignored -> new RuntimeEntry(header));
    return entry.retain(header, reason, operationId);
  }

  /**
   * Returns transient diagnostics without loading chunks or durable aggregates.
   *
   * @param islandId island identity
   * @return current runtime snapshot
   */
  public Optional<IslandRuntimeSnapshot> snapshot(IslandId islandId) {
    RuntimeEntry entry = entries.get(Objects.requireNonNull(islandId, "islandId"));
    return entry == null ? Optional.empty() : Optional.of(entry.snapshot());
  }

  /**
   * Returns the current count of verified acquired plugin tickets.
   *
   * @return loaded island chunk metric
   */
  public int loadedChunkCount() {
    return loadedChunks.get();
  }

  /**
   * Stops new activity and releases every ticket, including acquisitions still in progress.
   *
   * @return combined shutdown completion
   */
  public CompletionStage<Void> shutdown() {
    if (!accepting.compareAndSet(true, false)) {
      return allReleased();
    }
    List<CompletionStage<Void>> releases = new ArrayList<>();
    entries.values().forEach(entry -> releases.add(entry.forceRelease()));
    return combine(releases);
  }

  private CompletionStage<Void> allReleased() {
    return combine(entries.values().stream().map(RuntimeEntry::forceRelease).toList());
  }

  private CompletionStage<IslandChunkTicketLease> acquire(
      RuntimeEntry entry, OperationId operationId) {
    IslandChunkTicketRequest request =
        new IslandChunkTicketRequest(
            operationId,
            entry.header.islandId(),
            ChunkCoverage.covering(entry.header.worldId(), entry.header.requiredBounds()),
            ticketTimeout);
    CompletionStage<IslandChunkTicketLease> raw = ticketController.acquire(request);
    return boundedAcquire(raw, request);
  }

  private CompletionStage<IslandChunkTicketLease> boundedAcquire(
      CompletionStage<IslandChunkTicketLease> raw, IslandChunkTicketRequest request) {
    CompletableFuture<IslandChunkTicketLease> bounded = new CompletableFuture<>();
    raw.whenComplete(
        (lease, failure) -> {
          if (failure != null) {
            bounded.completeExceptionally(unwrap(failure));
          } else if (!bounded.complete(lease)) {
            lease.release();
          }
        });
    CompletableFuture.delayedExecutor(
            ticketTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .execute(
            () ->
                bounded.completeExceptionally(
                    new TimeoutException(
                        "Timed out acquiring "
                            + request.chunks().size()
                            + " chunks for operation "
                            + request.operationId())));
    return bounded;
  }

  private CompletionStage<Void> boundedRelease(IslandChunkTicketLease lease) {
    CompletableFuture<Void> release = lease.release().toCompletableFuture();
    return release.orTimeout(ticketTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
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

  private final class RuntimeEntry {
    private final IslandRuntimeHeader header;
    private final EnumMap<IslandActivityReason, Integer> reasons =
        new EnumMap<>(IslandActivityReason.class);

    private IslandRuntimeState state = IslandRuntimeState.PREPARING;
    private CompletionStage<IslandChunkTicketLease> acquisition;
    private IslandChunkTicketLease tickets;
    private CompletionStage<Void> unloading;
    private boolean forceRelease;

    private RuntimeEntry(IslandRuntimeHeader header) {
      this.header = header;
    }

    private synchronized CompletionStage<IslandActivityLease> retain(
        IslandRuntimeHeader requestedHeader, IslandActivityReason reason, OperationId operationId) {
      if (!header.equals(requestedHeader)) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("runtime header changed for island " + header.islandId()));
      }
      if (!accepting.get() || forceRelease) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("island runtime manager is shutting down"));
      }
      if (state == IslandRuntimeState.UNLOADING) {
        return unloading.thenCompose(
            ignored -> IslandRuntimeManager.this.retain(header, reason, operationId));
      }
      reasons.merge(reason, 1, Integer::sum);
      if (tickets != null) {
        state = IslandRuntimeState.ACTIVE;
        return CompletableFuture.completedFuture(new ActivityLease(this, reason));
      }
      if (acquisition == null) {
        state = IslandRuntimeState.PREPARING;
        acquisition = acquire(this, operationId).whenComplete(this::completeAcquisition);
      }
      return acquisition.thenApply(ignored -> activityLease(reason));
    }

    private synchronized IslandActivityLease activityLease(IslandActivityReason reason) {
      if (forceRelease || !accepting.get() || state != IslandRuntimeState.ACTIVE) {
        throw new IllegalStateException("runtime stopped before activity became ready");
      }
      return new ActivityLease(this, reason);
    }

    private synchronized void completeAcquisition(
        IslandChunkTicketLease acquired, Throwable failure) {
      if (failure != null) {
        reasons.clear();
        acquisition = null;
        entries.remove(header.islandId(), this);
        return;
      }
      tickets = acquired;
      loadedChunks.addAndGet(acquired.chunkCount());
      acquisition = null;
      if (forceRelease || reasons.isEmpty()) {
        beginUnload();
      } else {
        state = IslandRuntimeState.ACTIVE;
      }
    }

    private synchronized CompletionStage<Void> release(IslandActivityReason reason) {
      Integer count = reasons.get(reason);
      if (count == null) {
        return CompletableFuture.completedFuture(null);
      }
      if (count == 1) {
        reasons.remove(reason);
      } else {
        reasons.put(reason, count - 1);
      }
      if (!reasons.isEmpty() || tickets == null) {
        return CompletableFuture.completedFuture(null);
      }
      state = IslandRuntimeState.IDLE;
      return beginUnload();
    }

    private synchronized CompletionStage<Void> forceRelease() {
      forceRelease = true;
      reasons.clear();
      if (unloading != null) {
        return unloading;
      }
      if (tickets != null) {
        return beginUnload();
      }
      if (acquisition != null) {
        return acquisition
            .handle((ignored, failure) -> null)
            .thenCompose(ignored -> forceRelease());
      }
      entries.remove(header.islandId(), this);
      state = IslandRuntimeState.UNLOADED;
      return CompletableFuture.completedFuture(null);
    }

    private synchronized CompletionStage<Void> beginUnload() {
      if (unloading != null) {
        return unloading;
      }
      IslandChunkTicketLease acquired = tickets;
      if (acquired == null) {
        return CompletableFuture.completedFuture(null);
      }
      state = IslandRuntimeState.UNLOADING;
      CompletionStage<Void> release = boundedRelease(acquired);
      unloading =
          release.whenComplete(
              (ignored, failure) -> {
                synchronized (this) {
                  if (failure == null) {
                    loadedChunks.addAndGet(-acquired.chunkCount());
                    tickets = null;
                    state = IslandRuntimeState.UNLOADED;
                    entries.remove(header.islandId(), this);
                  }
                }
              });
      return unloading;
    }

    private synchronized IslandRuntimeSnapshot snapshot() {
      return new IslandRuntimeSnapshot(
          header.islandId(),
          state,
          new EnumMap<>(reasons),
          tickets == null ? 0 : tickets.chunkCount());
    }
  }

  private static final class ActivityLease implements IslandActivityLease {
    private final RuntimeEntry entry;
    private final IslandActivityReason reason;
    private final AtomicBoolean released = new AtomicBoolean();
    private final AtomicReferenceCompletion release = new AtomicReferenceCompletion();

    private ActivityLease(RuntimeEntry entry, IslandActivityReason reason) {
      this.entry = entry;
      this.reason = reason;
    }

    @Override
    public IslandId islandId() {
      return entry.header.islandId();
    }

    @Override
    public IslandActivityReason reason() {
      return reason;
    }

    @Override
    public CompletionStage<Void> release() {
      if (released.compareAndSet(false, true)) {
        release.complete(entry.release(reason));
      }
      return release.stage();
    }
  }

  private static final class AtomicReferenceCompletion {
    private final CompletableFuture<CompletionStage<Void>> assigned = new CompletableFuture<>();

    void complete(CompletionStage<Void> stage) {
      assigned.complete(stage);
    }

    CompletionStage<Void> stage() {
      return assigned.thenCompose(stage -> stage);
    }
  }
}
