package dev.openoneblock.paper.bootstrap;

import dev.openoneblock.paper.config.FoundationConfigurationSnapshot;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/** Ordered, rollback-capable platform operations used by the startup state machine. */
public interface FoundationBootstrapEnvironment {
  /**
   * Installs missing defaults and parses one validated candidate without publishing it.
   *
   * @return validated immutable configuration
   */
  CompletionStage<FoundationConfigurationSnapshot> loadConfiguration();

  /**
   * Creates bounded infrastructure and completes SQL schema migration.
   *
   * @param configuration validated startup configuration
   * @return initialization completion
   */
  CompletionStage<Void> initializeInfrastructure(FoundationConfigurationSnapshot configuration);

  /**
   * Provisions worlds, validates actual metadata, and verifies persisted identity.
   *
   * @param configuration validated startup configuration
   * @return world verification completion
   */
  CompletionStage<Void> provisionAndVerifyWorlds(FoundationConfigurationSnapshot configuration);

  /**
   * Rebuilds runtime projections and performs fail-closed durable-operation recovery.
   *
   * @param configuration validated startup configuration
   * @return complete unpublished runtime
   */
  CompletionStage<FoundationRuntime> recover(FoundationConfigurationSnapshot configuration);

  /**
   * Stops work and closes every resource created by any successful earlier stage.
   *
   * @param drainTimeout maximum accepted-work drain duration
   * @return cleanup completion
   */
  CompletionStage<Void> shutdown(Duration drainTimeout);
}
