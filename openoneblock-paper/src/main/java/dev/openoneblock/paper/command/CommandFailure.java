package dev.openoneblock.paper.command;

import java.util.Map;
import java.util.Objects;

/**
 * Stable user-facing command failure mapping.
 *
 * @param messageKey locale-ready key
 * @param placeholders safe response values
 * @param log whether the underlying failure requires operator diagnostics
 */
public record CommandFailure(String messageKey, Map<String, ?> placeholders, boolean log) {
  /** Validates and copies the mapping. */
  public CommandFailure {
    Objects.requireNonNull(messageKey, "messageKey");
    placeholders = Map.copyOf(placeholders);
  }
}
