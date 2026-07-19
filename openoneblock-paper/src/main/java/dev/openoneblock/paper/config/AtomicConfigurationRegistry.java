package dev.openoneblock.paper.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Publishes only complete validated configuration snapshots. */
public final class AtomicConfigurationRegistry {
  private final AtomicReference<FoundationConfigurationSnapshot> active = new AtomicReference<>();

  /** Creates an empty registry which rejects active lookups until first publication. */
  public AtomicConfigurationRegistry() {}

  /**
   * Returns the current immutable snapshot when startup has published one.
   *
   * @return active snapshot
   */
  public Optional<FoundationConfigurationSnapshot> active() {
    return Optional.ofNullable(active.get());
  }

  /**
   * Parses a candidate completely and swaps it only when validation succeeds.
   *
   * @param loader strict configuration loader
   * @param dataDirectory managed plugin directory
   * @return accepted snapshot or rejected diagnostics
   */
  public ReloadResult reload(FoundationConfigurationLoader loader, Path dataDirectory) {
    Objects.requireNonNull(loader, "loader");
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    try {
      FoundationConfigurationSnapshot candidate = loader.load(dataDirectory);
      active.set(candidate);
      return new ReloadResult.Accepted(candidate);
    } catch (ConfigurationValidationException exception) {
      return new ReloadResult.Rejected(exception.problems());
    }
  }

  /** Result of a parse-validate-publish attempt. */
  public sealed interface ReloadResult {
    /**
     * Successfully published candidate.
     *
     * @param snapshot new active snapshot
     */
    record Accepted(FoundationConfigurationSnapshot snapshot) implements ReloadResult {
      /** Validates the accepted candidate. */
      public Accepted {
        Objects.requireNonNull(snapshot, "snapshot");
      }
    }

    /**
     * Rejected candidate; the old registry remains untouched.
     *
     * @param problems candidate validation failures
     */
    record Rejected(List<ConfigurationProblem> problems) implements ReloadResult {
      /** Defensively copies rejected diagnostics. */
      public Rejected {
        problems = List.copyOf(problems);
      }
    }
  }
}
