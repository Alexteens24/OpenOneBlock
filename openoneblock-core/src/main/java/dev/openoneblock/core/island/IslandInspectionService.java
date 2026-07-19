package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.core.runtime.IslandRuntimeManager;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Combines durable SQL diagnostics with an optional already-cached runtime snapshot. */
public final class IslandInspectionService {
  private final IslandInspectionRepository repository;
  private final IslandRuntimeManager runtimes;

  /**
   * Creates the non-loading inspector.
   *
   * @param repository durable inspection query
   * @param runtimes transient runtime diagnostics
   */
  public IslandInspectionService(
      IslandInspectionRepository repository, IslandRuntimeManager runtimes) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
  }

  /**
   * Inspects an island without acquiring chunk tickets.
   *
   * @param islandId island identity
   * @return optional enriched diagnostics
   */
  public CompletionStage<Optional<IslandInspectionSnapshot>> inspect(IslandId islandId) {
    Objects.requireNonNull(islandId, "islandId");
    return repository
        .findInspection(islandId)
        .thenApply(
            snapshot -> snapshot.map(value -> value.withRuntime(runtimes.snapshot(islandId))));
  }
}
