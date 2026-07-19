package dev.openoneblock.paper.bootstrap;

import dev.openoneblock.paper.config.FoundationConfigurationSnapshot;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Coordinates ordered startup, atomic runtime publication, rollback, and graceful shutdown. */
public final class FoundationBootstrapCoordinator {
  private final PluginRuntimeLifecycle lifecycle;
  private final FoundationBootstrapEnvironment environment;
  private final AtomicReference<FoundationRuntime> runtime = new AtomicReference<>();
  private final AtomicBoolean startInvoked = new AtomicBoolean();
  private final AtomicBoolean shutdownRequested = new AtomicBoolean();
  private final AtomicReference<CompletionStage<Void>> shutdown = new AtomicReference<>();

  /**
   * Creates a coordinator for one plugin enable cycle.
   *
   * @param lifecycle fail-closed command and gameplay gate
   * @param environment ordered platform startup operations
   */
  public FoundationBootstrapCoordinator(
      PluginRuntimeLifecycle lifecycle, FoundationBootstrapEnvironment environment) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.environment = Objects.requireNonNull(environment, "environment");
  }

  /**
   * Starts exactly one enable sequence and publishes runtime only after recovery.
   *
   * @return published runtime or exceptional rollback completion
   */
  public CompletionStage<FoundationRuntime> start() {
    if (!startInvoked.compareAndSet(false, true)) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("foundation bootstrap already started"));
    }
    if (lifecycle.state() != PluginRuntimeState.BOOTSTRAPPING) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("plugin must be BOOTSTRAPPING before foundation startup"));
    }

    CompletableFuture<FoundationRuntime> completion = new CompletableFuture<>();
    environment
        .loadConfiguration()
        .thenCompose(
            configuration -> {
              if (shutdownRequested.get()) {
                return cancelledStartup();
              }
              return environment
                  .initializeInfrastructure(configuration)
                  .thenCompose(
                      ignored -> {
                        if (shutdownRequested.get()) {
                          return cancelledStartup();
                        }
                        return environment.provisionAndVerifyWorlds(configuration);
                      })
                  .thenApply(ignored -> configuration);
            })
        .thenCompose(this::beginRecovery)
        .whenComplete((recovered, failure) -> finishStartup(recovered, failure, completion));
    return completion.minimalCompletionStage();
  }

  /**
   * Returns the service graph only after successful atomic publication.
   *
   * @return current published runtime
   */
  public Optional<FoundationRuntime> runtime() {
    return Optional.ofNullable(runtime.get());
  }

  /**
   * Stops startup or active work, drains accepted lanes, and closes resources exactly once.
   *
   * @param drainTimeout maximum graceful lane drain duration
   * @return shutdown completion
   */
  public synchronized CompletionStage<Void> shutdown(Duration drainTimeout) {
    Objects.requireNonNull(drainTimeout, "drainTimeout");
    if (drainTimeout.isNegative() || drainTimeout.isZero()) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("drainTimeout must be positive"));
    }
    CompletionStage<Void> existing = shutdown.get();
    if (existing != null) {
      return existing;
    }
    shutdownRequested.set(true);
    transitionToShuttingDown();
    CompletionStage<Void> candidate =
        environment
            .shutdown(drainTimeout)
            .whenComplete(
                (ignored, failure) -> {
                  runtime.set(null);
                  if (lifecycle.state() == PluginRuntimeState.SHUTTING_DOWN) {
                    lifecycle.transitionTo(PluginRuntimeState.STOPPED);
                  }
                });
    if (shutdown.compareAndSet(null, candidate)) {
      return candidate;
    }
    return shutdown.get();
  }

  private CompletionStage<FoundationRuntime> beginRecovery(
      FoundationConfigurationSnapshot configuration) {
    if (shutdownRequested.get()) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("shutdown requested during foundation bootstrap"));
    }
    lifecycle.transitionTo(PluginRuntimeState.RECOVERING);
    return environment.recover(configuration);
  }

  private synchronized void finishStartup(
      FoundationRuntime recovered,
      Throwable failure,
      CompletableFuture<FoundationRuntime> completion) {
    Throwable cause = unwrap(failure);
    if (cause == null && shutdownRequested.get()) {
      cause = new IllegalStateException("shutdown requested before runtime publication");
    }
    if (cause == null) {
      runtime.set(recovered);
      lifecycle.transitionTo(PluginRuntimeState.READY);
      completion.complete(recovered);
      return;
    }

    if (!shutdownRequested.get()
        && (lifecycle.state() == PluginRuntimeState.BOOTSTRAPPING
            || lifecycle.state() == PluginRuntimeState.RECOVERING)) {
      lifecycle.transitionTo(PluginRuntimeState.DEGRADED);
    }
    Throwable startupFailure = cause;
    CompletionStage<Void> rollback =
        shutdownRequested.get()
            ? Objects.requireNonNull(shutdown.get(), "shutdown completion")
            : environment.shutdown(Duration.ofSeconds(10));
    rollback.whenComplete(
        (ignored, rollbackFailure) -> {
          if (rollbackFailure != null) {
            startupFailure.addSuppressed(unwrap(rollbackFailure));
          }
          completion.completeExceptionally(startupFailure);
        });
  }

  private void transitionToShuttingDown() {
    PluginRuntimeState current = lifecycle.state();
    if (current != PluginRuntimeState.STOPPED && current != PluginRuntimeState.SHUTTING_DOWN) {
      lifecycle.transitionTo(PluginRuntimeState.SHUTTING_DOWN);
    }
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable current = failure;
    while ((current instanceof CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static <T> CompletionStage<T> cancelledStartup() {
    return CompletableFuture.failedFuture(
        new IllegalStateException("shutdown requested during foundation bootstrap"));
  }
}
