package dev.openoneblock.core.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.island.IslandLifecycleState;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IslandOperationContractsTest {
  private static final Instant SUBMITTED_AT = Instant.parse("2026-07-19T00:00:00Z");

  @Test
  void immutableContractsRejectNegativeVersions() {
    IslandId islandId = IslandId.generate();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IslandOperationRequest(
                islandId, OperationId.generate(), -1, SUBMITTED_AT, IslandOperationClass.MUTATION));
    assertThrows(
        IllegalArgumentException.class,
        () -> new IslandStateSnapshot(islandId, IslandLifecycleState.ACTIVE, -1));
  }

  @Test
  void preconditionsFailInStableIdentityVersionLifecycleOrder() {
    IslandId requestedIsland = IslandId.generate();
    IslandOperationRequest gameplay = request(requestedIsland, 10, IslandOperationClass.GAMEPLAY);

    assertEquals(
        OperationPreconditionDecision.ISLAND_MISMATCH,
        IslandOperationPreconditions.evaluate(
            gameplay,
            new IslandStateSnapshot(IslandId.generate(), IslandLifecycleState.ACTIVE, 10)));
    assertEquals(
        OperationPreconditionDecision.VERSION_MISMATCH,
        IslandOperationPreconditions.evaluate(
            gameplay, new IslandStateSnapshot(requestedIsland, IslandLifecycleState.ACTIVE, 11)));
    assertEquals(
        OperationPreconditionDecision.GAMEPLAY_REQUIRES_ACTIVE,
        IslandOperationPreconditions.evaluate(
            gameplay, new IslandStateSnapshot(requestedIsland, IslandLifecycleState.LOCKED, 10)));
    assertEquals(
        OperationPreconditionDecision.ALLOWED,
        IslandOperationPreconditions.evaluate(
            gameplay, new IslandStateSnapshot(requestedIsland, IslandLifecycleState.ACTIVE, 10)));
  }

  @Test
  void nonGameplayLifecycleAuthorizationRemainsAnApplicationPolicy() {
    IslandId islandId = IslandId.generate();
    IslandOperationRequest locking = request(islandId, 4, IslandOperationClass.LOCKING);

    assertEquals(
        OperationPreconditionDecision.ALLOWED,
        IslandOperationPreconditions.evaluate(
            locking, new IslandStateSnapshot(islandId, IslandLifecycleState.BROKEN, 4)));
  }

  private static IslandOperationRequest request(
      IslandId islandId, long expectedVersion, IslandOperationClass operationClass) {
    return new IslandOperationRequest(
        islandId, OperationId.generate(), expectedVersion, SUBMITTED_AT, operationClass);
  }
}
