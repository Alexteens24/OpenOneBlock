package dev.openoneblock.paper.config;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Raised after a candidate configuration has been fully parsed and found unsafe. */
@SuppressWarnings("serial")
public final class ConfigurationValidationException extends Exception {
  private static final long serialVersionUID = 1L;

  /** Immutable failures retained for startup diagnostics and reload results. */
  private final List<ConfigurationProblem> problems;

  /**
   * Creates an aggregate validation failure.
   *
   * @param problems non-empty actionable failures
   */
  public ConfigurationValidationException(List<ConfigurationProblem> problems) {
    super(message(problems));
    if (problems.isEmpty()) {
      throw new IllegalArgumentException("problems must not be empty");
    }
    this.problems = List.copyOf(problems);
  }

  /**
   * Returns every detected problem.
   *
   * @return immutable problem list
   */
  public List<ConfigurationProblem> problems() {
    return problems;
  }

  private static String message(List<ConfigurationProblem> problems) {
    Objects.requireNonNull(problems, "problems");
    return problems.stream()
        .map(ConfigurationProblem::diagnostic)
        .collect(
            Collectors.joining(
                System.lineSeparator(), "Invalid OpenOneBlock configuration:\n", ""));
  }
}
