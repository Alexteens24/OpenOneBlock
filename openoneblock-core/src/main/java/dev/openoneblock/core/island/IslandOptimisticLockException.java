package dev.openoneblock.core.island;

import dev.openoneblock.api.id.IslandId;
import java.io.Serial;
import java.util.Objects;

/** Signals that a caller tried to mutate an island or slot version it did not observe. */
public final class IslandOptimisticLockException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  private final transient IslandId islandId;

  /** Caller-observed aggregate version. */
  private final long expectedIslandVersion;

  /** Authoritative aggregate version. */
  private final long actualIslandVersion;

  /** Caller-observed slot version. */
  private final long expectedSlotVersion;

  /** Authoritative slot version. */
  private final long actualSlotVersion;

  /**
   * Creates an optimistic-version conflict with the authoritative versions.
   *
   * @param islandId conflicted island
   * @param expectedIslandVersion caller-observed aggregate version
   * @param actualIslandVersion authoritative aggregate version
   * @param expectedSlotVersion caller-observed slot version
   * @param actualSlotVersion authoritative slot version
   */
  public IslandOptimisticLockException(
      IslandId islandId,
      long expectedIslandVersion,
      long actualIslandVersion,
      long expectedSlotVersion,
      long actualSlotVersion) {
    super(
        "Optimistic version conflict for island %s: island %d/%d, slot %d/%d"
            .formatted(
                islandId,
                expectedIslandVersion,
                actualIslandVersion,
                expectedSlotVersion,
                actualSlotVersion));
    this.islandId = Objects.requireNonNull(islandId, "islandId");
    this.expectedIslandVersion = expectedIslandVersion;
    this.actualIslandVersion = actualIslandVersion;
    this.expectedSlotVersion = expectedSlotVersion;
    this.actualSlotVersion = actualSlotVersion;
  }

  /**
   * Returns the conflicted island.
   *
   * @return stable island identity
   */
  public IslandId islandId() {
    return islandId;
  }

  /**
   * Returns the caller's island version.
   *
   * @return expected aggregate version
   */
  public long expectedIslandVersion() {
    return expectedIslandVersion;
  }

  /**
   * Returns the authoritative island version.
   *
   * @return actual aggregate version
   */
  public long actualIslandVersion() {
    return actualIslandVersion;
  }

  /**
   * Returns the caller's slot version.
   *
   * @return expected slot version
   */
  public long expectedSlotVersion() {
    return expectedSlotVersion;
  }

  /**
   * Returns the authoritative slot version.
   *
   * @return actual slot version
   */
  public long actualSlotVersion() {
    return actualSlotVersion;
  }
}
