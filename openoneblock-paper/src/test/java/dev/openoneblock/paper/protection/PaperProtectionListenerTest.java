package dev.openoneblock.paper.protection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.id.DimensionId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.grid.CoordinateRange;
import dev.openoneblock.core.grid.GridConfiguration;
import dev.openoneblock.core.grid.GridGeometry;
import dev.openoneblock.core.locator.InMemorySlotLocatorIndex;
import dev.openoneblock.core.locator.IslandLocationResolver;
import dev.openoneblock.core.locator.WorldProjection;
import dev.openoneblock.core.locator.WorldProjectionRegistry;
import dev.openoneblock.protection.InMemoryIslandProtectionIndex;
import dev.openoneblock.protection.ProtectionEngine;
import dev.openoneblock.protection.RolePermissionRegistry;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Test;

class PaperProtectionListenerTest {
  private static final UUID MANAGED_WORLD = UUID.fromString("58d6237e-455b-4016-a244-ab3b83194efa");
  private static final UUID UNMANAGED_WORLD =
      UUID.fromString("a2e22b26-cc6b-4b40-8bac-4ef571948491");

  @Test
  void cancelsManagedFailClosedDecisionButLeavesUnmanagedWorldUntouched() {
    PaperProtectionListener listener =
        new PaperProtectionListener(
            () -> java.util.Optional.of(engine()),
            new BukkitProtectionQueryFactory("openoneblock.admin.bypass"));
    Player player = player();
    BlockBreakEvent managed = new BlockBreakEvent(block(MANAGED_WORLD), player);
    BlockBreakEvent unmanaged = new BlockBreakEvent(block(UNMANAGED_WORLD), player);

    listener.onBlockBreak(managed);
    listener.onBlockBreak(unmanaged);

    assertTrue(managed.isCancelled());
    assertFalse(unmanaged.isCancelled());
  }

  private static ProtectionEngine engine() {
    ShardGroupId shard = ShardGroupId.parse("openoneblock:primary");
    GridGeometry geometry =
        new GridGeometry(GridConfiguration.DEFAULT, new CoordinateRange(-30_000_000, 30_000_001));
    WorldProjectionRegistry worlds =
        new WorldProjectionRegistry(
            List.of(
                new WorldProjection(
                    WorldId.of(MANAGED_WORLD),
                    shard,
                    DimensionId.of("openoneblock", "overworld"))));
    InMemorySlotLocatorIndex slots = new InMemorySlotLocatorIndex();
    return new ProtectionEngine(
        new IslandLocationResolver(worlds, ignored -> geometry, slots),
        ignored -> geometry,
        new InMemoryIslandProtectionIndex(),
        new RolePermissionRegistry(
            Map.of(NamespacedId.of("openoneblock", "visitor"), Set.of()),
            Set.of(),
            NamespacedId.of("openoneblock", "visitor")),
        List.of());
  }

  private static Block block(UUID worldId) {
    World world = world(worldId);
    return (Block)
        Proxy.newProxyInstance(
            Block.class.getClassLoader(),
            new Class<?>[] {Block.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "getLocation" -> new Location(world, 0, 64, 0);
                  case "getType" -> Material.STONE;
                  default -> null;
                });
  }

  private static World world(UUID worldId) {
    return (World)
        Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, arguments) -> method.getName().equals("getUID") ? worldId : null);
  }

  private static Player player() {
    UUID playerId = UUID.fromString("0a47fa55-c7a9-4948-b3c4-4cc85dc673df");
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "getUniqueId" -> playerId;
                  case "hasPermission" -> false;
                  default -> null;
                });
  }
}
