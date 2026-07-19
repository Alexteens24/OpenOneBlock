package dev.openoneblock.core.locator;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Authoritative persistence port for world registration, restart verification, and admin adoption.
 */
public interface WorldProjectionCatalog {
  /**
   * Registers an empty catalog atomically or verifies an existing catalog without mutating drift.
   *
   * @param observed complete configured and provisioned world identity set
   * @return asynchronous verification result
   */
  CompletionStage<WorldProjectionVerification> verifyOrRegister(
      List<WorldProjectionDefinition> observed);

  /**
   * Explicitly adopts a replacement after an admin inspected drift and its current version.
   *
   * @param request idempotent optimistic adoption request
   * @return authoritative adopted row
   */
  CompletionStage<PersistedWorldProjection> adopt(WorldProjectionAdoptionRequest request);
}
