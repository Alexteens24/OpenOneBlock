package dev.openoneblock.paper.config;

import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.core.grid.GridConfiguration;
import dev.openoneblock.paper.world.SharedWorldSpec;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Complete immutable candidate used by startup and atomic reload.
 *
 * @param database database and write queue policy
 * @param executors bounded executor policy
 * @param observability diagnostic feature switches
 * @param grid validated shared slot geometry
 * @param buildHeight allowed vertical island range
 * @param worlds shared world projections
 * @param operations lifecycle operation policy
 * @param team island membership limits
 * @param magicBlock starter Magic Block policy
 * @param defaults initial progression identifiers
 * @param roles role inheritance and permissions
 * @param messages locale and message formatting policy
 * @param fingerprint deterministic configuration identity
 */
public record FoundationConfigurationSnapshot(
    DatabaseSettings database,
    ExecutorSettings executors,
    ObservabilitySettings observability,
    GridConfiguration grid,
    BuildHeight buildHeight,
    List<SharedWorldSpec> worlds,
    OperationSettings operations,
    TeamSettings team,
    MagicBlockSettings magicBlock,
    DefaultProgression defaults,
    Map<String, RoleSettings> roles,
    MessageSettings messages,
    String fingerprint) {
  /** Validates and defensively copies the candidate snapshot. */
  public FoundationConfigurationSnapshot {
    Objects.requireNonNull(database, "database");
    Objects.requireNonNull(executors, "executors");
    Objects.requireNonNull(observability, "observability");
    Objects.requireNonNull(grid, "grid");
    Objects.requireNonNull(buildHeight, "buildHeight");
    worlds = List.copyOf(worlds);
    Objects.requireNonNull(operations, "operations");
    Objects.requireNonNull(team, "team");
    Objects.requireNonNull(magicBlock, "magicBlock");
    Objects.requireNonNull(defaults, "defaults");
    roles = Map.copyOf(roles);
    Objects.requireNonNull(messages, "messages");
    Objects.requireNonNull(fingerprint, "fingerprint");
  }

  /**
   * Database and write-behind limits.
   *
   * @param type configured database provider
   * @param file safe data-directory-relative SQLite file
   * @param busyTimeoutMillis per-connection busy timeout
   * @param maximumAttempts total immediate transaction attempts
   * @param minimumBackoffMillis minimum retry delay
   * @param maximumBackoffMillis maximum retry delay
   * @param queueCapacity bounded write queue capacity
   * @param batchSize maximum write-behind batch size
   */
  public record DatabaseSettings(
      String type,
      String file,
      int busyTimeoutMillis,
      int maximumAttempts,
      long minimumBackoffMillis,
      long maximumBackoffMillis,
      int queueCapacity,
      int batchSize) {}

  /**
   * Bounded worker pool settings.
   *
   * @param sqlThreads database worker count
   * @param computationThreads non-Minecraft computation worker count
   * @param queueCapacity per-pool bounded queue capacity
   */
  public record ExecutorSettings(int sqlThreads, int computationThreads, int queueCapacity) {}

  /**
   * Diagnostics and telemetry switches.
   *
   * @param debug verbose diagnostic logging
   * @param audit durable audit logging
   * @param metrics runtime metrics publication
   */
  public record ObservabilitySettings(boolean debug, boolean audit, boolean metrics) {}

  /**
   * Inclusive minimum and exclusive maximum Y coordinates.
   *
   * @param minimumY inclusive island build minimum
   * @param maximumYExclusive exclusive island build maximum
   */
  public record BuildHeight(int minimumY, int maximumYExclusive) {}

  /**
   * Island creation, reset, delete, and quarantine policy.
   *
   * @param creationTimeoutSeconds creation operation timeout
   * @param resetTimeoutSeconds reset operation timeout
   * @param deleteTimeoutSeconds deletion operation timeout
   * @param quarantineCleanupFailures failures before mandatory quarantine
   */
  public record OperationSettings(
      int creationTimeoutSeconds,
      int resetTimeoutSeconds,
      int deleteTimeoutSeconds,
      int quarantineCleanupFailures) {}

  /**
   * Bounded island-team policy.
   *
   * @param maximumSize maximum simultaneous active memberships including the owner
   * @param invitationExpirySeconds lifetime of a newly issued invitation
   */
  public record TeamSettings(int maximumSize, long invitationExpirySeconds) {
    /** Validates bounded team policy. */
    public TeamSettings {
      if (maximumSize < 1 || invitationExpirySeconds < 1) {
        throw new IllegalArgumentException("team size and invitation expiry must be positive");
      }
    }
  }

  /**
   * Starter Magic Block content and regeneration delay.
   *
   * @param starterMaterial uppercase Vanilla material identity
   * @param regenerationDelayTicks non-negative regeneration delay
   */
  public record MagicBlockSettings(String starterMaterial, long regenerationDelayTicks) {}

  /**
   * Initial phase and content profile identities.
   *
   * @param phaseId default phase identity
   * @param profileId default Magic Block profile identity
   */
  public record DefaultProgression(NamespacedId phaseId, NamespacedId profileId) {}

  /**
   * One immutable role definition.
   *
   * @param authority team-management rank
   * @param inherits parent role names
   * @param permissions granted island action permissions
   */
  public record RoleSettings(int authority, List<String> inherits, Set<String> permissions) {
    /** Defensively copies role collections. */
    public RoleSettings {
      inherits = List.copyOf(inherits);
      permissions = Set.copyOf(permissions);
      if (authority < 0) {
        throw new IllegalArgumentException("role authority must be non-negative");
      }
    }
  }

  /**
   * Locale and formatting behavior plus currently registered message text.
   *
   * @param locale configured message locale
   * @param miniMessage whether MiniMessage formatting is enabled
   * @param legacyColors whether legacy color codes are accepted
   * @param messages immutable message key to text mapping
   */
  public record MessageSettings(
      String locale, boolean miniMessage, boolean legacyColors, Map<String, String> messages) {
    /** Defensively copies message text. */
    public MessageSettings {
      messages = Map.copyOf(messages);
    }
  }
}
