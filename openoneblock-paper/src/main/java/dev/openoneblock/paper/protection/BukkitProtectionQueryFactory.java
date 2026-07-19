package dev.openoneblock.paper.protection;

import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.PlayerId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.protection.ProtectionAction;
import dev.openoneblock.protection.ProtectionActor;
import dev.openoneblock.protection.ProtectionPosition;
import dev.openoneblock.protection.ProtectionQuery;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

/** Converts region-owned Bukkit state into immutable platform-neutral protection queries. */
public final class BukkitProtectionQueryFactory {
  private final String administratorPermission;

  /**
   * Creates a query factory.
   *
   * @param administratorPermission explicit bypass permission
   */
  public BukkitProtectionQueryFactory(String administratorPermission) {
    this.administratorPermission =
        Objects.requireNonNull(administratorPermission, "administratorPermission");
  }

  /**
   * Creates an immutable query from Bukkit locations already owned by the current event thread.
   *
   * @param player optional player actor
   * @param action canonical attempted action
   * @param source optional source location
   * @param destination optional destination location
   * @param target optional Bukkit target key
   * @param cause namespaced cause
   * @return immutable engine query
   */
  public ProtectionQuery create(
      Optional<Player> player,
      ProtectionAction action,
      Optional<Location> source,
      Optional<Location> destination,
      Optional<NamespacedKey> target,
      NamespacedId cause) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(target, "target");
    ProtectionActor actor =
        player
            .<ProtectionActor>map(
                value ->
                    new ProtectionActor(
                        Optional.of(PlayerId.of(value.getUniqueId())),
                        value.hasPermission(administratorPermission)))
            .orElseGet(ProtectionActor::environment);
    return new ProtectionQuery(
        actor,
        action,
        source.map(BukkitProtectionQueryFactory::position),
        destination.map(BukkitProtectionQueryFactory::position),
        target.map(BukkitProtectionQueryFactory::namespacedId),
        cause,
        Map.of());
  }

  private static ProtectionPosition position(Location location) {
    return new ProtectionPosition(
        WorldId.of(location.getWorld().getUID()),
        location.getBlockX(),
        location.getBlockY(),
        location.getBlockZ());
  }

  private static NamespacedId namespacedId(NamespacedKey key) {
    return NamespacedId.of(key.getNamespace(), key.getKey());
  }
}
