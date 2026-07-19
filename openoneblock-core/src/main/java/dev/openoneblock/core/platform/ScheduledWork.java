package dev.openoneblock.core.platform;

/**
 * Value-returning work dispatched through a platform ownership scheduler.
 *
 * @param <T> completion value type
 */
@FunctionalInterface
public interface ScheduledWork<T> {
  /**
   * Executes on the scheduler-selected thread or region.
   *
   * @return work result
   * @throws Exception when the scheduled operation fails
   */
  T execute() throws Exception;
}
