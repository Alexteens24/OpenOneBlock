package dev.openoneblock.paper.command;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.PlayerId;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bounded in-memory one-time confirmation registry; restart intentionally invalidates all tokens.
 */
public final class ConfirmationTokenRegistry {
  private static final int TOKEN_BYTES = 18;

  private final Clock clock;
  private final Duration lifetime;
  private final int maximumEntries;
  private final Supplier<String> tokens;
  private final Map<String, ConfirmationChallenge> challenges = new HashMap<>();

  /**
   * Creates a production cryptographic confirmation registry.
   *
   * @param clock expiry clock
   * @param lifetime positive token lifetime
   * @param maximumEntries positive registry bound
   */
  public ConfirmationTokenRegistry(Clock clock, Duration lifetime, int maximumEntries) {
    this(clock, lifetime, maximumEntries, secureTokenSupplier(new SecureRandom()));
  }

  ConfirmationTokenRegistry(
      Clock clock, Duration lifetime, int maximumEntries, Supplier<String> tokens) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
    if (lifetime.isNegative() || lifetime.isZero()) {
      throw new IllegalArgumentException("confirmation lifetime must be positive");
    }
    if (maximumEntries <= 0) {
      throw new IllegalArgumentException("maximumEntries must be positive");
    }
    this.maximumEntries = maximumEntries;
    this.tokens = Objects.requireNonNull(tokens, "tokens");
  }

  /**
   * Replaces any prior challenge for the same player/action and binds a new exact target version.
   *
   * @param action destructive action
   * @param playerId authorized player
   * @param islandId exact target island
   * @param islandVersion expected aggregate version
   * @return newly issued challenge
   */
  public synchronized ConfirmationChallenge issue(
      ConfirmationAction action, PlayerId playerId, IslandId islandId, long islandVersion) {
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(islandId, "islandId");
    if (islandVersion < 0) {
      throw new IllegalArgumentException("islandVersion must be non-negative");
    }
    Instant now = clock.instant();
    purgeExpired(now);
    challenges
        .values()
        .removeIf(
            challenge -> challenge.playerId().equals(playerId) && challenge.action() == action);
    while (challenges.size() >= maximumEntries) {
      String oldest =
          challenges.entrySet().stream()
              .min(
                  Map.Entry.comparingByValue(
                      Comparator.comparing(ConfirmationChallenge::expiresAt)))
              .orElseThrow()
              .getKey();
      challenges.remove(oldest);
    }
    String token;
    do {
      token = tokens.get();
      if (token == null || token.isBlank()) {
        throw new IllegalStateException("confirmation token generator returned an empty token");
      }
    } while (challenges.containsKey(token));
    ConfirmationChallenge challenge =
        new ConfirmationChallenge(
            token, action, playerId, islandId, islandVersion, now.plus(lifetime));
    challenges.put(token, challenge);
    return challenge;
  }

  /**
   * Atomically consumes a token only for its exact player and action.
   *
   * @param token submitted token
   * @param playerId submitting player
   * @param action submitted action
   * @return bound target and expected version
   */
  public synchronized ConfirmationChallenge consume(
      String token, PlayerId playerId, ConfirmationAction action) {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(action, "action");
    ConfirmationChallenge challenge = challenges.get(token);
    if (challenge == null) {
      throw new ConfirmationRejectedException("unknown-or-used");
    }
    Instant now = clock.instant();
    if (!now.isBefore(challenge.expiresAt())) {
      challenges.remove(token);
      throw new ConfirmationRejectedException("expired");
    }
    if (!challenge.playerId().equals(playerId)) {
      throw new ConfirmationRejectedException("wrong-player");
    }
    if (challenge.action() != action) {
      throw new ConfirmationRejectedException("wrong-action");
    }
    challenges.remove(token);
    return challenge;
  }

  private void purgeExpired(Instant now) {
    challenges.values().removeIf(challenge -> !now.isBefore(challenge.expiresAt()));
  }

  private static Supplier<String> secureTokenSupplier(SecureRandom random) {
    return () -> {
      byte[] bytes = new byte[TOKEN_BYTES];
      random.nextBytes(bytes);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    };
  }
}
