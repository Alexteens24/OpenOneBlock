package dev.openoneblock.core.runtime;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.core.platform.RegionTaskTarget;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Immutable diagnostic request for all chunks needed by one island runtime.
 *
 * @param operationId operation correlation identity
 * @param islandId island identity
 * @param chunks complete owning chunk targets
 * @param timeout bounded acquisition duration
 */
public record IslandChunkTicketRequest(
    OperationId operationId, IslandId islandId, List<RegionTaskTarget> chunks, Duration timeout) {
  /** Validates and defensively copies ticket targets. */
  public IslandChunkTicketRequest {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(islandId, "islandId");
    chunks = List.copyOf(chunks);
    Objects.requireNonNull(timeout, "timeout");
    if (chunks.isEmpty()) {
      throw new IllegalArgumentException("chunks must not be empty");
    }
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }
}
