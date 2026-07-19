package dev.openoneblock.paper.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PluginRuntimeLifecycleTest {
  @Test
  void reachesReadyOnlyThroughBootstrapAndRecovery() {
    PluginRuntimeLifecycle lifecycle = new PluginRuntimeLifecycle();

    assertEquals(PluginRuntimeState.STOPPED, lifecycle.state());
    assertFalse(lifecycle.isReady());

    lifecycle.transitionTo(PluginRuntimeState.BOOTSTRAPPING);
    lifecycle.transitionTo(PluginRuntimeState.RECOVERING);
    lifecycle.transitionTo(PluginRuntimeState.READY);

    assertTrue(lifecycle.isReady());
  }

  @Test
  void supportsDegradedStartupAndOrderlyShutdown() {
    PluginRuntimeLifecycle lifecycle = new PluginRuntimeLifecycle();

    lifecycle.transitionTo(PluginRuntimeState.BOOTSTRAPPING);
    lifecycle.transitionTo(PluginRuntimeState.DEGRADED);
    lifecycle.transitionTo(PluginRuntimeState.SHUTTING_DOWN);
    lifecycle.transitionTo(PluginRuntimeState.STOPPED);

    assertEquals(PluginRuntimeState.STOPPED, lifecycle.state());
  }

  @Test
  void rejectsSkippingRecoveryAndRestartingWhileActive() {
    PluginRuntimeLifecycle lifecycle = new PluginRuntimeLifecycle();

    lifecycle.transitionTo(PluginRuntimeState.BOOTSTRAPPING);

    assertThrows(
        IllegalStateException.class, () -> lifecycle.transitionTo(PluginRuntimeState.READY));
    assertThrows(
        IllegalStateException.class,
        () -> lifecycle.transitionTo(PluginRuntimeState.BOOTSTRAPPING));
  }
}
