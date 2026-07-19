package dev.openoneblock.paper;

import dev.openoneblock.paper.bootstrap.PluginRuntimeLifecycle;
import dev.openoneblock.paper.bootstrap.PluginRuntimeState;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper entry point and composition root for OpenOneBlock. */
public final class OpenOneBlockPlugin extends JavaPlugin {
  private final PluginRuntimeLifecycle lifecycle = new PluginRuntimeLifecycle();

  /** Creates the Paper composition root. */
  public OpenOneBlockPlugin() {}

  @Override
  public void onEnable() {
    lifecycle.transitionTo(PluginRuntimeState.BOOTSTRAPPING);

    // Infrastructure is deliberately gated until the configuration and recovery milestones
    // are installed. Loading this foundation artifact must not expose incomplete gameplay.
    lifecycle.transitionTo(PluginRuntimeState.DEGRADED);
    getLogger()
        .warning(
            "OpenOneBlock loaded in DEGRADED foundation mode; gameplay and commands are disabled.");
  }

  @Override
  public void onDisable() {
    PluginRuntimeState current = lifecycle.state();
    if (current == PluginRuntimeState.STOPPED) {
      return;
    }
    if (current != PluginRuntimeState.SHUTTING_DOWN) {
      lifecycle.transitionTo(PluginRuntimeState.SHUTTING_DOWN);
    }
    lifecycle.transitionTo(PluginRuntimeState.STOPPED);
  }

  /**
   * Returns the lifecycle gate used by command and gameplay adapters.
   *
   * @return current plugin lifecycle gate
   */
  public PluginRuntimeLifecycle runtimeLifecycle() {
    return lifecycle;
  }
}
