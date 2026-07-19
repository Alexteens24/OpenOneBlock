package dev.openoneblock.paper.bootstrap;

/** Describes how far the Paper composition root has progressed through its lifecycle. */
public enum PluginRuntimeState {
  /** Initial services are being constructed and validated. */
  BOOTSTRAPPING,

  /** Durable operations are being inspected and recovered. */
  RECOVERING,

  /** Commands and gameplay may accept new work. */
  READY,

  /** Startup failed safely or a runtime capability became unavailable. */
  DEGRADED,

  /** New work is rejected while accepted work and resources are drained. */
  SHUTTING_DOWN,

  /** No plugin-owned runtime services are active. */
  STOPPED
}
