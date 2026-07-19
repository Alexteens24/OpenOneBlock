package dev.openoneblock.core.operation;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.OperationId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Read-only persistence boundary for non-loading operation diagnostics. */
public interface IslandOperationQueryRepository {
  /** Finds one operation by its globally unique identity. */
  CompletionStage<Optional<IslandOperationSnapshot>> find(OperationId operationId);

  /** Lists newest operations, optionally restricted to one island. */
  CompletionStage<List<IslandOperationSnapshot>> list(Optional<IslandId> islandId, int limit);
}
