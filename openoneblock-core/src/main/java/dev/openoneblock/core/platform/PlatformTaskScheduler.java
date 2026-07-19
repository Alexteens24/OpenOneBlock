package dev.openoneblock.core.platform;

import java.util.concurrent.CompletionStage;

/** Paper/Folia-neutral dispatch boundary for global, region-owned, and asynchronous work. */
public interface PlatformTaskScheduler {
  /**
   * Dispatches work owned by the server's global region.
   *
   * @param work global state operation
   * @param <T> result type
   * @return full execution completion
   */
  <T> CompletionStage<T> global(ScheduledWork<T> work);

  /**
   * Dispatches block, chunk, or world work to the region owning the target chunk.
   *
   * @param target region ownership target
   * @param work region-owned operation
   * @param <T> result type
   * @return full execution completion
   */
  <T> CompletionStage<T> region(RegionTaskTarget target, ScheduledWork<T> work);

  /**
   * Dispatches work independent of Minecraft state to an asynchronous scheduler.
   *
   * @param work non-Minecraft-state operation
   * @param <T> result type
   * @return full execution completion
   */
  <T> CompletionStage<T> async(ScheduledWork<T> work);
}
