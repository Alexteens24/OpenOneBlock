package dev.openoneblock.paper.command;

import dev.openoneblock.api.id.OperationId;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Non-blocking command mutation submission carrying its externally traceable operation identity.
 *
 * @param operationId generated critical-operation identity
 * @param completion eventual application result
 * @param <T> result type
 */
public record MutationSubmission<T>(OperationId operationId, CompletionStage<T> completion) {
  /** Validates a complete submission. */
  public MutationSubmission {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(completion, "completion");
  }
}
