package dev.openoneblock.paper.bootstrap;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.paper.config.FoundationConfigurationSnapshot.ExecutorSettings;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PluginExecutorsTest {
  @Test
  void databasePoolIsBoundedNamedAndClosesWithoutLeakingWorkers() throws Exception {
    PluginExecutors executors = new PluginExecutors(new ExecutorSettings(1, 1, 1));
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      executors
          .database()
          .execute(
              () -> {
                running.countDown();
                try {
                  release.await();
                } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                }
              });
      assertTrue(running.await(2, TimeUnit.SECONDS));
      executors.database().execute(() -> {});

      assertThrows(RejectedExecutionException.class, () -> executors.database().execute(() -> {}));
    } finally {
      release.countDown();
      executors.close(Duration.ofSeconds(2));
    }

    assertTrue(executors.database().isTerminated());
    assertTrue(executors.computation().isTerminated());
  }

  @Test
  void closeCalledByOwnedWorkerNeverWaitsForItself() throws Exception {
    PluginExecutors executors = new PluginExecutors(new ExecutorSettings(1, 1, 1));

    executors
        .database()
        .submit(() -> executors.close(Duration.ofSeconds(2)))
        .get(1, TimeUnit.SECONDS);

    assertTrue(executors.database().isShutdown());
    assertTrue(executors.computation().isTerminated());
  }
}
