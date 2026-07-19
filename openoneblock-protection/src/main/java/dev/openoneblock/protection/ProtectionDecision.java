package dev.openoneblock.protection;

import dev.openoneblock.api.id.NamespacedId;
import java.util.Objects;

/**
 * Immutable protection result with a stable machine-readable reason.
 *
 * @param outcome tri-state outcome
 * @param reason stable namespaced diagnostic identity
 */
public record ProtectionDecision(ProtectionOutcome outcome, NamespacedId reason) {
  public static final NamespacedId MANAGED_ALLOW = NamespacedId.of("openoneblock", "managed-allow");
  public static final NamespacedId UNMANAGED_WORLD =
      NamespacedId.of("openoneblock", "unmanaged-world");

  /** Validates decision metadata. */
  public ProtectionDecision {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(reason, "reason");
  }

  /**
   * Creates an allow decision.
   *
   * @return managed allow decision
   */
  public static ProtectionDecision allow() {
    return new ProtectionDecision(ProtectionOutcome.ALLOW, MANAGED_ALLOW);
  }

  /**
   * Creates a pass decision for locations outside managed worlds.
   *
   * @return unmanaged pass
   */
  public static ProtectionDecision pass() {
    return new ProtectionDecision(ProtectionOutcome.PASS, UNMANAGED_WORLD);
  }

  /**
   * Creates a deny decision with a namespaced reason value.
   *
   * @param reason value in the {@code openoneblock} namespace
   * @return deny decision
   */
  public static ProtectionDecision deny(String reason) {
    return new ProtectionDecision(ProtectionOutcome.DENY, NamespacedId.of("openoneblock", reason));
  }
}
