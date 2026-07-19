package dev.openoneblock.paper.bootstrap;

import dev.openoneblock.paper.config.FoundationConfigurationSnapshot.ExecutorSettings;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Plugin-owned bounded SQL and non-Minecraft computation executors. */
public final class PluginExecutors implements AutoCloseable {
  private static final String DATABASE_PREFIX = "openoneblock-sql";
  private static final String COMPUTATION_PREFIX = "openoneblock-compute";

  private final ExecutorService database;
  private final ExecutorService computation;

  /**
   * Creates bounded fixed-size worker pools using the validated startup settings.
   *
   * @param settings validated pool and queue limits
   */
  public PluginExecutors(ExecutorSettings settings) {
    Objects.requireNonNull(settings, "settings");
    this.database = executor(DATABASE_PREFIX, settings.sqlThreads(), settings.queueCapacity());
    this.computation =
        executor(COMPUTATION_PREFIX, settings.computationThreads(), settings.queueCapacity());
  }

  /**
   * Returns the database-only bounded executor.
   *
   * @return database executor
   */
  public ExecutorService database() {
    return database;
  }

  /**
   * Returns the non-Minecraft computation executor.
   *
   * @return computation executor
   */
  public ExecutorService computation() {
    return computation;
  }

  /**
   * Gracefully closes both pools and interrupts remaining tasks after the timeout.
   *
   * @param timeout total bounded close duration
   */
  public void close(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    database.shutdown();
    computation.shutdown();
    long deadline = System.nanoTime() + timeout.toNanos();
    String currentThread = Thread.currentThread().getName();
    if (!currentThread.startsWith(DATABASE_PREFIX + "-")) {
      await(database, deadline);
    }
    if (!currentThread.startsWith(COMPUTATION_PREFIX + "-")) {
      await(computation, deadline);
    }
  }

  /** Closes both pools with a ten-second bounded timeout. */
  @Override
  public void close() {
    close(Duration.ofSeconds(10));
  }

  private static ExecutorService executor(String prefix, int threads, int capacity) {
    return new ThreadPoolExecutor(
        threads,
        threads,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(capacity),
        new NamedThreadFactory(prefix),
        new ThreadPoolExecutor.AbortPolicy());
  }

  private static void await(ExecutorService executor, long deadlineNanos) {
    long remaining = Math.max(0L, deadlineNanos - System.nanoTime());
    try {
      if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
  }

  private static final class NamedThreadFactory implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger sequence = new AtomicInteger();

    private NamedThreadFactory(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable task) {
      Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
      thread.setDaemon(false);
      thread.setUncaughtExceptionHandler(
          (ignored, failure) ->
              System.getLogger(PluginExecutors.class.getName())
                  .log(System.Logger.Level.ERROR, "Uncaught plugin executor failure", failure));
      return thread;
    }
  }
}
