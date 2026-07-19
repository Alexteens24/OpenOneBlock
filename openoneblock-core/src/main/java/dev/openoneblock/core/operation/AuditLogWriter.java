package dev.openoneblock.core.operation;

import java.util.concurrent.CompletionStage;

/** Append-only durable audit persistence boundary. */
@FunctionalInterface
public interface AuditLogWriter {
  /** Appends one normalized entry after validating all correlation identities. */
  CompletionStage<Void> append(AuditEntry entry);
}
