package dev.openoneblock.protection;

import dev.openoneblock.api.id.NamespacedId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministically ordered native or temporary protection policy registration.
 *
 * @param policyId stable namespaced identity
 * @param priority higher values run first
 * @param expiresAt optional exclusive expiry instant
 * @param policy immutable evaluator
 */
public record RegisteredProtectionPolicy(
    NamespacedId policyId, int priority, Optional<Instant> expiresAt, ProtectionPolicy policy)
    implements Comparable<RegisteredProtectionPolicy> {
  /** Validates registration metadata. */
  public RegisteredProtectionPolicy {
    Objects.requireNonNull(policyId, "policyId");
    Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(policy, "policy");
  }

  /**
   * Returns whether this registration is active at the supplied instant.
   *
   * @param now evaluation instant
   * @return whether the policy has not expired
   */
  public boolean activeAt(Instant now) {
    Objects.requireNonNull(now, "now");
    return expiresAt.isEmpty() || now.isBefore(expiresAt.orElseThrow());
  }

  /**
   * Orders higher priorities first and then stable IDs ascending.
   *
   * @param other other registration
   * @return comparison result
   */
  @Override
  public int compareTo(RegisteredProtectionPolicy other) {
    int priorityComparison = Integer.compare(other.priority, priority);
    return priorityComparison != 0 ? priorityComparison : policyId.compareTo(other.policyId);
  }
}
