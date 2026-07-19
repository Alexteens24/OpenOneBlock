package dev.openoneblock.paper.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validates configured island build height against worlds after global-scheduler provisioning. */
public final class ProvisionedWorldHeightValidator {
  /** Creates a stateless provisioned-world validator. */
  public ProvisionedWorldHeightValidator() {}

  /**
   * Ensures every configured projection exists and contains the configured build interval.
   *
   * @param configuration validated candidate configuration
   * @param provisionedHeights actual Paper world height metadata
   * @throws ConfigurationValidationException when a world is missing or cannot contain the interval
   */
  public void validate(
      FoundationConfigurationSnapshot configuration,
      List<ProvisionedWorldHeight> provisionedHeights)
      throws ConfigurationValidationException {
    Objects.requireNonNull(configuration, "configuration");
    Objects.requireNonNull(provisionedHeights, "provisionedHeights");
    Map<String, ProvisionedWorldHeight> byName = new HashMap<>();
    for (ProvisionedWorldHeight height : provisionedHeights) {
      if (byName.putIfAbsent(height.worldName(), height) != null) {
        throw new IllegalArgumentException(
            "duplicate provisioned world height: " + height.worldName());
      }
    }

    List<ConfigurationProblem> problems = new ArrayList<>();
    for (var world : configuration.worlds()) {
      ProvisionedWorldHeight actual = byName.get(world.worldName());
      String path = "shards[" + world.shardGroupId() + "].dimensions[" + world.dimensionId() + "]";
      if (actual == null) {
        problems.add(
            new ConfigurationProblem(
                "worlds.yml",
                path,
                "provisioned Paper world",
                "missing " + world.worldName(),
                "provision the configured world before publishing runtime state"));
        continue;
      }
      if (configuration.buildHeight().minimumY() < actual.minimumY()
          || configuration.buildHeight().maximumYExclusive() > actual.maximumYExclusive()) {
        problems.add(
            new ConfigurationProblem(
                "worlds.yml",
                "build-height",
                "range inside ["
                    + actual.minimumY()
                    + ", "
                    + actual.maximumYExclusive()
                    + ") for "
                    + world.worldName(),
                "["
                    + configuration.buildHeight().minimumY()
                    + ", "
                    + configuration.buildHeight().maximumYExclusive()
                    + ")",
                "reduce the configured build interval or repair the target world"));
      }
    }
    if (!problems.isEmpty()) {
      throw new ConfigurationValidationException(problems);
    }
  }

  /**
   * Actual height interval reported by one provisioned Paper world.
   *
   * @param worldName configured world name
   * @param minimumY inclusive minimum world Y
   * @param maximumYExclusive exclusive maximum world Y
   */
  public record ProvisionedWorldHeight(String worldName, int minimumY, int maximumYExclusive) {
    /** Validates actual world height metadata. */
    public ProvisionedWorldHeight {
      Objects.requireNonNull(worldName, "worldName");
      if (minimumY >= maximumYExclusive) {
        throw new IllegalArgumentException("invalid provisioned world height interval");
      }
    }
  }
}
