package dev.openoneblock.protection;

import dev.openoneblock.api.id.IslandId;

/** Post-commit port keeping the hot-path projection coherent with authoritative persistence. */
public interface CommittedIslandProtectionPublisher {
  /** No-op publisher for persistence tests or deployments without gameplay adapters. */
  CommittedIslandProtectionPublisher NO_OP =
      new CommittedIslandProtectionPublisher() {
        @Override
        public void publishCommitted(IslandId islandId) {}

        @Override
        public void removeCommitted(IslandId islandId, long committedVersion) {}
      };

  /**
   * Loads and publishes the committed projection for one island.
   *
   * @param islandId committed island identity
   */
  void publishCommitted(IslandId islandId);

  /**
   * Removes an archived projection without overwriting a newer publication.
   *
   * @param islandId archived island identity
   * @param committedVersion committed archive version
   */
  void removeCommitted(IslandId islandId, long committedVersion);
}
