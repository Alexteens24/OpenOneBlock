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
}
