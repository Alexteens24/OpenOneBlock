package dev.openoneblock.core.locator;

/** Post-commit publication port for the runtime slot locator projection. */
@FunctionalInterface
public interface CommittedSlotPublisher {
  /**
   * Publishes one database-committed non-free slot entry.
   *
   * @param entry committed locator entry
   * @return deterministic publication decision
   */
  LocatorPublishDecision publishCommitted(SlotLocatorEntry entry);

  /**
   * Removes an ownership projection only when it still matches the committed pre-release entry.
   *
   * @param releasedEntry final non-free entry observed by the release transaction
   * @return deterministic removal decision
   */
  default LocatorRemovalDecision removeCommitted(SlotLocatorEntry releasedEntry) {
    return LocatorRemovalDecision.UNSUPPORTED;
  }
}
