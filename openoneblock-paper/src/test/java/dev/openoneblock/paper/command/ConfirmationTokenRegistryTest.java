package dev.openoneblock.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.PlayerId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConfirmationTokenRegistryTest {
  private static final PlayerId PLAYER = PlayerId.of(UUID.randomUUID());
  private static final IslandId ISLAND = IslandId.generate();

  @Test
  void tokenIsSingleUseAndBoundToPlayerActionIslandAndVersion() {
    AtomicInteger sequence = new AtomicInteger();
    ConfirmationTokenRegistry registry =
        new ConfirmationTokenRegistry(
            Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC),
            Duration.ofSeconds(30),
            8,
            () -> "token-" + sequence.incrementAndGet());
    ConfirmationChallenge challenge = registry.issue(ConfirmationAction.DELETE, PLAYER, ISLAND, 12);

    ConfirmationChallenge consumed =
        registry.consume(challenge.token(), PLAYER, ConfirmationAction.DELETE);

    assertEquals(ISLAND, consumed.islandId());
    assertEquals(12, consumed.islandVersion());
    assertThrows(
        ConfirmationRejectedException.class,
        () -> registry.consume(challenge.token(), PLAYER, ConfirmationAction.DELETE));
  }

  @Test
  void tokenCannotBeUsedByAnotherPlayerOrAction() {
    ConfirmationTokenRegistry registry = registryAt(Instant.parse("2026-07-19T00:00:00Z"));
    ConfirmationChallenge challenge = registry.issue(ConfirmationAction.DELETE, PLAYER, ISLAND, 2);

    assertEquals(
        "wrong-player",
        assertThrows(
                ConfirmationRejectedException.class,
                () ->
                    registry.consume(
                        challenge.token(),
                        PlayerId.of(UUID.randomUUID()),
                        ConfirmationAction.DELETE))
            .reason());
    assertEquals(
        "wrong-action",
        assertThrows(
                ConfirmationRejectedException.class,
                () -> registry.consume(challenge.token(), PLAYER, ConfirmationAction.RESET))
            .reason());
    assertEquals(
        ISLAND, registry.consume(challenge.token(), PLAYER, ConfirmationAction.DELETE).islandId());
  }

  @Test
  void tokenExpiresAtExclusiveDeadline() {
    Instant issuedAt = Instant.parse("2026-07-19T00:00:00Z");
    MutableClock clock = new MutableClock(issuedAt);
    ConfirmationTokenRegistry registry =
        new ConfirmationTokenRegistry(clock, Duration.ofSeconds(30), 8, () -> "expiring-token");
    ConfirmationChallenge challenge = registry.issue(ConfirmationAction.DELETE, PLAYER, ISLAND, 2);
    clock.instant = issuedAt.plusSeconds(30);

    assertEquals(
        "expired",
        assertThrows(
                ConfirmationRejectedException.class,
                () -> registry.consume(challenge.token(), PLAYER, ConfirmationAction.DELETE))
            .reason());
  }

  private static ConfirmationTokenRegistry registryAt(Instant instant) {
    return new ConfirmationTokenRegistry(
        Clock.fixed(instant, ZoneOffset.UTC), Duration.ofSeconds(30), 8, () -> "fixed-token");
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) {
        throw new IllegalArgumentException("test clock only supports UTC");
      }
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
