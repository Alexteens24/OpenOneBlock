package dev.openoneblock.paper.bootstrap;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe lifecycle gate for the Paper composition root.
 *
 * <p>Gameplay and command adapters must consult {@link #isReady()} before accepting work. Invalid
 * transitions fail immediately instead of silently exposing a partially initialized runtime.
 */
public final class PluginRuntimeLifecycle {
  private static final Map<PluginRuntimeState, Set<PluginRuntimeState>> ALLOWED_TRANSITIONS =
      allowedTransitions();

  private final AtomicReference<PluginRuntimeState> state =
      new AtomicReference<>(PluginRuntimeState.STOPPED);

  /** Creates a lifecycle gate in the {@link PluginRuntimeState#STOPPED} state. */
  public PluginRuntimeLifecycle() {}

  /**
   * Returns the current lifecycle state.
   *
   * @return current lifecycle state
   */
  public PluginRuntimeState state() {
    return state.get();
  }

  /**
   * Returns whether gameplay and commands may accept new work.
   *
   * @return {@code true} only in {@link PluginRuntimeState#READY}
   */
  public boolean isReady() {
    return state() == PluginRuntimeState.READY;
  }

  /**
   * Atomically changes the lifecycle state if the requested transition is legal.
   *
   * @param next requested next state
   * @throws IllegalStateException if another transition won the race or the edge is not legal
   */
  public void transitionTo(PluginRuntimeState next) {
    Objects.requireNonNull(next, "next");
    PluginRuntimeState current = state();
    if (!ALLOWED_TRANSITIONS.get(current).contains(next)) {
      throw new IllegalStateException(
          "Invalid plugin lifecycle transition: " + current + " -> " + next);
    }
    if (!state.compareAndSet(current, next)) {
      throw new IllegalStateException(
          "Plugin lifecycle changed concurrently from " + current + " to " + state());
    }
  }

  private static Map<PluginRuntimeState, Set<PluginRuntimeState>> allowedTransitions() {
    EnumMap<PluginRuntimeState, Set<PluginRuntimeState>> transitions =
        new EnumMap<>(PluginRuntimeState.class);
    transitions.put(PluginRuntimeState.STOPPED, EnumSet.of(PluginRuntimeState.BOOTSTRAPPING));
    transitions.put(
        PluginRuntimeState.BOOTSTRAPPING,
        EnumSet.of(
            PluginRuntimeState.RECOVERING,
            PluginRuntimeState.DEGRADED,
            PluginRuntimeState.SHUTTING_DOWN));
    transitions.put(
        PluginRuntimeState.RECOVERING,
        EnumSet.of(
            PluginRuntimeState.READY,
            PluginRuntimeState.DEGRADED,
            PluginRuntimeState.SHUTTING_DOWN));
    transitions.put(
        PluginRuntimeState.READY,
        EnumSet.of(PluginRuntimeState.DEGRADED, PluginRuntimeState.SHUTTING_DOWN));
    transitions.put(PluginRuntimeState.DEGRADED, EnumSet.of(PluginRuntimeState.SHUTTING_DOWN));
    transitions.put(PluginRuntimeState.SHUTTING_DOWN, EnumSet.of(PluginRuntimeState.STOPPED));
    return Map.copyOf(transitions);
  }
}
