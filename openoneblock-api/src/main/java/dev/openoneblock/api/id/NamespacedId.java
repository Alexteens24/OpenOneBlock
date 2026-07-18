package dev.openoneblock.api.id;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A validated, canonical identifier for configured OpenOneBlock content.
 *
 * @param namespace provider or configuration namespace
 * @param value path-like value within the namespace
 */
public record NamespacedId(String namespace, String value) implements Comparable<NamespacedId> {
  private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9._-]+");
  private static final Pattern VALUE_PATTERN = Pattern.compile("[a-z0-9/._-]+");

  /** Validates and creates an identifier from separate components. */
  public NamespacedId {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(value, "value");
    if (!NAMESPACE_PATTERN.matcher(namespace).matches()) {
      throw new IllegalArgumentException("Invalid namespace: " + namespace);
    }
    if (!VALUE_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid namespaced identifier value: " + value);
    }
  }

  /**
   * Creates a validated identifier.
   *
   * @param namespace provider or configuration namespace
   * @param value path-like value within the namespace
   * @return the validated identifier
   */
  public static NamespacedId of(String namespace, String value) {
    return new NamespacedId(namespace, value);
  }

  /**
   * Parses the strict {@code namespace:value} representation.
   *
   * @param input canonical identifier text
   * @return the validated identifier
   */
  public static NamespacedId parse(String input) {
    Objects.requireNonNull(input, "input");
    int separator = input.indexOf(':');
    if (separator <= 0 || separator != input.lastIndexOf(':') || separator == input.length() - 1) {
      throw new IllegalArgumentException(
          "Expected a namespaced identifier in namespace:value form");
    }
    return new NamespacedId(input.substring(0, separator), input.substring(separator + 1));
  }

  @Override
  public int compareTo(NamespacedId other) {
    int namespaceComparison = namespace.compareTo(other.namespace);
    return namespaceComparison != 0 ? namespaceComparison : value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return namespace + ':' + value;
  }
}
