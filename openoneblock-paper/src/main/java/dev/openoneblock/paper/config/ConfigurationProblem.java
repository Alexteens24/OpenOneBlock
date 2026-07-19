package dev.openoneblock.paper.config;

import java.io.Serializable;
import java.util.Objects;

/**
 * One actionable configuration validation failure.
 *
 * @param file managed file name
 * @param path dotted configuration path
 * @param expected expected type or constraint
 * @param actual actual type or value
 * @param remediation concise operator action
 */
public record ConfigurationProblem(
    String file, String path, String expected, String actual, String remediation)
    implements Serializable {
  /** Validates diagnostic fields. */
  public ConfigurationProblem {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(actual, "actual");
    Objects.requireNonNull(remediation, "remediation");
  }

  /**
   * Returns a stable operator-facing diagnostic.
   *
   * @return formatted diagnostic
   */
  public String diagnostic() {
    return file + ":" + path + " expected " + expected + ", got " + actual + "; " + remediation;
  }
}
