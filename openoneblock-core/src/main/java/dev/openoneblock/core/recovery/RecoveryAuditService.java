package dev.openoneblock.core.recovery;

import dev.openoneblock.core.operation.AuditEntry;
import dev.openoneblock.core.operation.AuditLogWriter;
import dev.openoneblock.core.operation.AuditOutcome;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Writes durable attempt boundaries around startup recovery work. */
public final class RecoveryAuditService {
  private final AuditLogWriter auditLog;
  private final Clock clock;

  /** Creates a fail-closed recovery audit decorator. */
  public RecoveryAuditService(AuditLogWriter auditLog, Clock clock) {
    this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Appends {@code STARTED}, executes recovery, then appends its terminal outcome.
   *
   * <p>A terminal audit write failure fails startup. When both recovery and its failure audit fail,
   * the original recovery failure remains primary and carries the audit failure as suppressed.
   */
  public <T> CompletionStage<T> recover(
      RecoveryOperationIdentity identity,
      Supplier<? extends CompletionStage<? extends T>> recovery) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(recovery, "recovery");
    return audit(identity, AuditOutcome.STARTED, Optional.empty())
        .thenCompose(ignored -> invoke(recovery))
        .handle((result, failure) -> new RecoveryCompletion<>(result, unwrap(failure)))
        .thenCompose(completion -> finish(identity, completion));
  }

  private <T> CompletionStage<T> finish(
      RecoveryOperationIdentity identity, RecoveryCompletion<T> completion) {
    if (completion.failure() == null) {
      return audit(identity, AuditOutcome.SUCCEEDED, Optional.empty())
          .thenApply(ignored -> Objects.requireNonNull(completion.result(), "recovery result"));
    }
    Throwable recoveryFailure = completion.failure();
    return audit(identity, AuditOutcome.FAILED, Optional.of(failureDetail(recoveryFailure)))
        .handle(
            (ignored, auditFailure) -> {
              Throwable unwrappedAudit = unwrap(auditFailure);
              if (unwrappedAudit != null && unwrappedAudit != recoveryFailure) {
                recoveryFailure.addSuppressed(unwrappedAudit);
              }
              throw new CompletionException(recoveryFailure);
            });
  }

  private CompletionStage<Void> audit(
      RecoveryOperationIdentity identity, AuditOutcome outcome, Optional<String> detail) {
    return auditLog.append(
        new AuditEntry(
            Optional.of(identity.operationId()),
            Optional.of(identity.islandId()),
            OptionalLong.empty(),
            Optional.empty(),
            Optional.of(identity.playerId()),
            "RECOVERY_" + identity.operationKind(),
            clock.instant(),
            outcome,
            detail));
  }

  private static <T> CompletionStage<T> invoke(
      Supplier<? extends CompletionStage<? extends T>> recovery) {
    try {
      CompletionStage<? extends T> stage =
          Objects.requireNonNull(recovery.get(), "recovery completion stage");
      return stage.thenApply(result -> result);
    } catch (Throwable failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  private static String failureDetail(Throwable failure) {
    String type = failure.getClass().getName();
    String message = failure.getMessage();
    String detail = message == null || message.isBlank() ? type : type + ": " + message;
    return detail.length() <= 2_048 ? detail : detail.substring(0, 2_048);
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable current = failure;
    while (current instanceof CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private record RecoveryCompletion<T>(@Nullable T result, @Nullable Throwable failure) {}
}
