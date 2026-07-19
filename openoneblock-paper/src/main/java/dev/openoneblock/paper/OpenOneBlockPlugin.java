package dev.openoneblock.paper;

import dev.openoneblock.paper.bootstrap.FoundationBootstrapCoordinator;
import dev.openoneblock.paper.bootstrap.FoundationRuntime;
import dev.openoneblock.paper.bootstrap.PaperFoundationBootstrapEnvironment;
import dev.openoneblock.paper.bootstrap.PluginRuntimeLifecycle;
import dev.openoneblock.paper.bootstrap.PluginRuntimeState;
import dev.openoneblock.paper.command.CommandFailureMapper;
import dev.openoneblock.paper.command.CommandMessageRenderer;
import dev.openoneblock.paper.command.OpenOneBlockCommand;
import dev.openoneblock.paper.command.PaperCommandMessenger;
import dev.openoneblock.paper.command.PaperIslandCommandGateway;
import dev.openoneblock.paper.protection.BukkitProtectionQueryFactory;
import dev.openoneblock.paper.protection.PaperProtectionListener;
import dev.openoneblock.paper.scheduler.PaperPlatformTaskScheduler;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper entry point and composition root for OpenOneBlock. */
public final class OpenOneBlockPlugin extends JavaPlugin {
  private final PluginRuntimeLifecycle lifecycle = new PluginRuntimeLifecycle();
  private final AtomicBoolean protectionRegistered = new AtomicBoolean();
  private volatile FoundationBootstrapCoordinator bootstrap;

  /** Creates the Paper composition root. */
  public OpenOneBlockPlugin() {}

  @Override
  public void onEnable() {
    lifecycle.transitionTo(PluginRuntimeState.BOOTSTRAPPING);
    PaperPlatformTaskScheduler scheduler = new PaperPlatformTaskScheduler(this, getServer());
    OpenOneBlockCommand command =
        new OpenOneBlockCommand(
            lifecycle,
            new PaperIslandCommandGateway(getServer(), this::foundationRuntime),
            new PaperCommandMessenger(
                this, scheduler, new CommandMessageRenderer(this::foundationRuntime)),
            new CommandFailureMapper(),
            getLogger());
    getLifecycleManager()
        .registerEventHandler(
            LifecycleEvents.COMMANDS,
            event ->
                event
                    .registrar()
                    .register(
                        "oneblock", "Build your own OneBlock experience.", List.of("ob"), command));
    FoundationBootstrapCoordinator created =
        new FoundationBootstrapCoordinator(
            lifecycle, new PaperFoundationBootstrapEnvironment(this, scheduler));
    bootstrap = created;
    created
        .start()
        .whenComplete(
            (runtime, failure) -> {
              if (failure == null) {
                registerProtection(scheduler, runtime);
              } else {
                getLogger().log(Level.SEVERE, "OpenOneBlock startup failed closed", failure);
              }
            });
  }

  private void registerProtection(PaperPlatformTaskScheduler scheduler, FoundationRuntime runtime) {
    scheduler
        .global(
            () -> {
              if (protectionRegistered.compareAndSet(false, true)) {
                getServer()
                    .getPluginManager()
                    .registerEvents(
                        new PaperProtectionListener(
                            () -> foundationRuntime().map(FoundationRuntime::protection),
                            new BukkitProtectionQueryFactory("openoneblock.admin.bypass")),
                        this);
              }
              return null;
            })
        .whenComplete(
            (ignored, failure) -> {
              if (failure == null) {
                getLogger()
                    .info(
                        "OpenOneBlock foundation is READY with "
                            + runtime.worldProjections().size()
                            + " verified world projection(s) and native protection active.");
              } else {
                getLogger().log(Level.SEVERE, "Native protection registration failed", failure);
                scheduler.global(
                    () -> {
                      getServer().getPluginManager().disablePlugin(this);
                      return null;
                    });
              }
            });
  }

  @Override
  public void onDisable() {
    FoundationBootstrapCoordinator activeBootstrap = bootstrap;
    if (activeBootstrap != null) {
      try {
        activeBootstrap
            .shutdown(Duration.ofSeconds(10))
            .toCompletableFuture()
            .get(15, TimeUnit.SECONDS);
      } catch (Exception exception) {
        getLogger().log(Level.SEVERE, "OpenOneBlock shutdown did not complete cleanly", exception);
      }
    } else {
      PluginRuntimeState current = lifecycle.state();
      if (current != PluginRuntimeState.STOPPED && current != PluginRuntimeState.SHUTTING_DOWN) {
        lifecycle.transitionTo(PluginRuntimeState.SHUTTING_DOWN);
      }
      if (lifecycle.state() == PluginRuntimeState.SHUTTING_DOWN) {
        lifecycle.transitionTo(PluginRuntimeState.STOPPED);
      }
    }
    getServer().getGlobalRegionScheduler().cancelTasks(this);
    getServer().getAsyncScheduler().cancelTasks(this);
    getServer().getScheduler().cancelTasks(this);
    bootstrap = null;
    protectionRegistered.set(false);
  }

  /**
   * Returns the lifecycle gate used by command and gameplay adapters.
   *
   * @return current plugin lifecycle gate
   */
  public PluginRuntimeLifecycle runtimeLifecycle() {
    return lifecycle;
  }

  /**
   * Returns the service graph only when startup has reached {@link PluginRuntimeState#READY}.
   *
   * @return published foundation runtime
   */
  public Optional<FoundationRuntime> foundationRuntime() {
    FoundationBootstrapCoordinator activeBootstrap = bootstrap;
    return activeBootstrap == null ? Optional.empty() : activeBootstrap.runtime();
  }
}
