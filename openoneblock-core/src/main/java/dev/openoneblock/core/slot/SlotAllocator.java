package dev.openoneblock.core.slot;

import java.util.concurrent.CompletionStage;

/** Asynchronous application port for durable slot reservation. */
@FunctionalInterface
public interface SlotAllocator {
  /**
   * Reserves or returns the idempotent existing outcome for one allocation request.
   *
   * @param request immutable allocation request
   * @return full completion of the durable allocation and locator publication
   */
  CompletionStage<AllocatedSlot> allocate(SlotAllocationRequest request);
}
