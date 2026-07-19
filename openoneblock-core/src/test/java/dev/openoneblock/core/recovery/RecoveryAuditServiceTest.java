package dev.openoneblock.core.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.core.operation.AuditEntry;
import dev.openoneblock.core.operation.AuditOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RecoveryAuditServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-19T02:00:00Z");
  private static final RecoveryOperationIdentity IDENTITY =
      new RecoveryOperationIdentity(
          OperationId.parse("00000000-0000-0000-0000-000000000141"),
          IslandId.parse("00000000-0000-0000-0000-000000000142"),
          PlayerId.of(UUID.fromString("00000000-0000-0000-0000-000000000143")),
          "ISLAND_RESET");

  @Test
  void appendsStartedAndSucceededAroundRecovery() {
    List<AuditEntry> entries = new ArrayList<>();
    RecoveryAuditService audit =
        new RecoveryAuditService(
            entry -> {
              entries.add(entry);
              return CompletableFuture.completedFuture(null);
            },
            Clock.fixed(NOW, ZoneOffset.UTC));

    String result =
        audit
            .recover(IDENTITY, () -> CompletableFuture.completedFuture("recovered"))
            .toCompletableFuture()
            .join();

    assertEquals("recovered", result);
    assertEquals(List.of(AuditOutcome.STARTED, AuditOutcome.SUCCEEDED), outcomes(entries));
    assertEquals("RECOVERY_ISLAND_RESET", entries.getFirst().eventType());
    assertEquals(IDENTITY.playerId(), entries.getFirst().playerId().orElseThrow());
  }

  @Test
  void preservesRecoveryFailureAndSuppressesTerminalAuditFailure() {
    IllegalStateException recoveryFailure = new IllegalStateException("world UUID drift");
    IllegalArgumentException auditFailure = new IllegalArgumentException("audit unavailable");
    AtomicInteger appends = new AtomicInteger();
    RecoveryAuditService audit =
        new RecoveryAuditService(
            entry ->
                appends.incrementAndGet() == 1
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(auditFailure),
            Clock.fixed(NOW, ZoneOffset.UTC));

    CompletionException thrown =
        assertThrows(
            CompletionException.class,
            () ->
                audit
                    .recover(IDENTITY, () -> CompletableFuture.failedFuture(recoveryFailure))
                    .toCompletableFuture()
                    .join());

    assertSame(recoveryFailure, thrown.getCause());
    assertEquals(List.of(auditFailure), List.of(recoveryFailure.getSuppressed()));
  }

  @Test
  void doesNotStartRecoveryWhenStartedAuditCannotBePersisted() {
    AtomicInteger recoveryCalls = new AtomicInteger();
    RecoveryAuditService audit =
        new RecoveryAuditService(
            entry -> CompletableFuture.failedFuture(new IllegalStateException("database offline")),
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThrows(
        CompletionException.class,
        () ->
            audit
                .recover(
                    IDENTITY,
                    () -> {
                      recoveryCalls.incrementAndGet();
                      return CompletableFuture.completedFuture("unexpected");
                    })
                .toCompletableFuture()
                .join());
    assertEquals(0, recoveryCalls.get());
  }

  private static List<AuditOutcome> outcomes(List<AuditEntry> entries) {
    return entries.stream().map(AuditEntry::outcome).toList();
  }
}
