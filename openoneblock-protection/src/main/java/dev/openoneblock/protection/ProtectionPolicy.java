package dev.openoneblock.protection;

/** Additional bounded policy evaluated after native membership and border checks. */
@FunctionalInterface
public interface ProtectionPolicy {
  /**
   * Returns {@code PASS} when the policy has no opinion.
   *
   * @param query immutable attempted interaction
   * @param island resolved immutable island projection
   * @return policy decision
   */
  ProtectionDecision evaluate(ProtectionQuery query, IslandProtectionSnapshot island);
}
