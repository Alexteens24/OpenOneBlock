package dev.openoneblock.paper.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.protection.ProtectionAction;
import dev.openoneblock.protection.ProtectionQuery;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class BukkitProtectionQueryFactoryTest {
  private static final UUID WORLD_ID = UUID.fromString("d0a5b32e-c7bf-45cb-bdf8-707840db2f9e");
  private static final UUID PLAYER_ID = UUID.fromString("ead93569-c506-4e29-a22c-b936c04f4302");

  @Test
  void copiesPlayerBypassLocationsTargetAndCauseIntoImmutableQuery() {
    BukkitProtectionQueryFactory factory =
        new BukkitProtectionQueryFactory("openoneblock.admin.bypass");
    Player player = player(Set.of("openoneblock.admin.bypass"));
    World world = world();

    ProtectionQuery query =
        factory.create(
            Optional.of(player),
            ProtectionAction.BLOCK_PLACE,
            Optional.of(new Location(world, -0.1, 64.9, 2.99)),
            Optional.of(new Location(world, 0, 65, 3)),
            Optional.of(NamespacedKey.minecraft("stone")),
            NamespacedId.of("minecraft", "player"));

    assertEquals(Optional.of(PlayerId.of(PLAYER_ID)), query.actor().playerId());
    assertTrue(query.actor().administrator());
    assertEquals(WorldId.of(WORLD_ID), query.source().orElseThrow().worldId());
    assertEquals(-1, query.source().orElseThrow().blockX());
    assertEquals(64, query.source().orElseThrow().blockY());
    assertEquals(2, query.source().orElseThrow().blockZ());
    assertEquals(3, query.destination().orElseThrow().blockZ());
    assertEquals(Optional.of(NamespacedId.of("minecraft", "stone")), query.target());
    assertEquals(NamespacedId.of("minecraft", "player"), query.cause());
  }

  @Test
  void environmentQueryHasNoPlayerOrBypass() {
    ProtectionQuery query =
        new BukkitProtectionQueryFactory("openoneblock.admin.bypass")
            .create(
                Optional.empty(),
                ProtectionAction.FLUID_FLOW,
                Optional.of(new Location(world(), 0, 64, 0)),
                Optional.empty(),
                Optional.empty(),
                NamespacedId.of("minecraft", "water"));

    assertTrue(query.actor().playerId().isEmpty());
    assertFalse(query.actor().administrator());
  }

  private static World world() {
    return (World)
        Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, arguments) -> method.getName().equals("getUID") ? WORLD_ID : null);
  }

  private static Player player(Set<String> permissions) {
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "getUniqueId" -> PLAYER_ID;
                  case "hasPermission" -> permissions.contains(arguments[0]);
                  default -> null;
                });
  }
}
