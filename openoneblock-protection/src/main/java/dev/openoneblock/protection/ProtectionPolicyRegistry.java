package dev.openoneblock.protection;

import dev.openoneblock.api.id.NamespacedId;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded copy-on-write policy registry with deterministic priority and lazy expiry. */
public final class ProtectionPolicyRegistry {
  private final int maximumPolicies;
  private final AtomicReference<Snapshot> policies =
      new AtomicReference<>(new Snapshot(Map.of(), List.of(), Optional.empty()));

  /**
   * Creates a bounded registry.
   *
   * @param maximumPolicies hard registration capacity
   */
  public ProtectionPolicyRegistry(int maximumPolicies) {
    if (maximumPolicies <= 0) {
      throw new IllegalArgumentException("maximumPolicies must be positive");
    }
    this.maximumPolicies = maximumPolicies;
  }

  /**
   * Registers or replaces a permanent policy.
   *
   * @param policyId stable identity
   * @param priority deterministic priority
   * @param policy evaluator
   */
  public void register(NamespacedId policyId, int priority, ProtectionPolicy policy) {
    register(new RegisteredProtectionPolicy(policyId, priority, Optional.empty(), policy));
  }

  /**
   * Registers or replaces a temporary policy without allocating a cleanup task.
   *
   * @param policyId stable identity
   * @param priority deterministic priority
   * @param expiresAt exclusive expiry instant
   * @param policy evaluator
   */
  public void registerTemporary(
      NamespacedId policyId, int priority, Instant expiresAt, ProtectionPolicy policy) {
    register(new RegisteredProtectionPolicy(policyId, priority, Optional.of(expiresAt), policy));
  }

  /**
   * Removes a policy registration.
   *
   * @param policyId stable identity
   * @return whether a registration was removed
   */
  public boolean remove(NamespacedId policyId) {
    Objects.requireNonNull(policyId, "policyId");
    while (true) {
      Snapshot current = policies.get();
      if (!current.byId().containsKey(policyId)) {
        return false;
      }
      Map<NamespacedId, RegisteredProtectionPolicy> replacement = new HashMap<>(current.byId());
      replacement.remove(policyId);
      if (policies.compareAndSet(current, snapshot(replacement))) {
        return true;
      }
    }
  }

  /**
   * Returns active policies in deterministic execution order.
   *
   * @param now evaluation instant
   * @return immutable ordered active policies
   */
  public List<RegisteredProtectionPolicy> activeAt(Instant now) {
    Objects.requireNonNull(now, "now");
    while (true) {
      Snapshot current = policies.get();
      if (current.nextExpiry().isEmpty() || now.isBefore(current.nextExpiry().orElseThrow())) {
        return current.ordered();
      }
      Map<NamespacedId, RegisteredProtectionPolicy> active = new HashMap<>();
      current
          .byId()
          .forEach(
              (policyId, policy) -> {
                if (policy.activeAt(now)) {
                  active.put(policyId, policy);
                }
              });
      Snapshot replacement = snapshot(active);
      if (policies.compareAndSet(current, replacement)) {
        return replacement.ordered();
      }
    }
  }

  /**
   * Returns the bounded registration count.
   *
   * @return current registrations
   */
  public int size() {
    return policies.get().byId().size();
  }

  private void register(RegisteredProtectionPolicy policy) {
    Objects.requireNonNull(policy, "policy");
    while (true) {
      Snapshot current = policies.get();
      if (!current.byId().containsKey(policy.policyId())
          && current.byId().size() >= maximumPolicies) {
        throw new IllegalStateException("protection policy capacity is exhausted");
      }
      Map<NamespacedId, RegisteredProtectionPolicy> replacement = new HashMap<>(current.byId());
      replacement.put(policy.policyId(), policy);
      if (policies.compareAndSet(current, snapshot(replacement))) {
        return;
      }
    }
  }

  private static Snapshot snapshot(Map<NamespacedId, RegisteredProtectionPolicy> registrations) {
    Map<NamespacedId, RegisteredProtectionPolicy> byId = Map.copyOf(registrations);
    List<RegisteredProtectionPolicy> ordered = byId.values().stream().sorted().toList();
    Optional<Instant> nextExpiry =
        ordered.stream().flatMap(policy -> policy.expiresAt().stream()).min(Instant::compareTo);
    return new Snapshot(byId, ordered, nextExpiry);
  }

  private record Snapshot(
      Map<NamespacedId, RegisteredProtectionPolicy> byId,
      List<RegisteredProtectionPolicy> ordered,
      Optional<Instant> nextExpiry) {}
}
