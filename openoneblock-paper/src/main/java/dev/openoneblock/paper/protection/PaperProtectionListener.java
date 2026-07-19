package dev.openoneblock.paper.protection;

import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.protection.ProtectionAction;
import dev.openoneblock.protection.ProtectionDecision;
import dev.openoneblock.protection.ProtectionEngine;
import dev.openoneblock.protection.ProtectionOutcome;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.projectiles.ProjectileSource;

/** Thin Paper event adapter for the native immutable protection engine. */
public final class PaperProtectionListener implements Listener {
  private static final NamespacedId PLAYER_CAUSE = NamespacedId.of("minecraft", "player");
  private static final NamespacedId PISTON_CAUSE = NamespacedId.of("minecraft", "piston");
  private static final NamespacedId FLUID_CAUSE = NamespacedId.of("minecraft", "fluid");
  private static final NamespacedId EXPLOSION_CAUSE = NamespacedId.of("minecraft", "explosion");
  private static final NamespacedId FIRE_CAUSE = NamespacedId.of("minecraft", "fire");

  private final Supplier<Optional<ProtectionEngine>> engine;
  private final BukkitProtectionQueryFactory queries;

  /**
   * Creates a listener that has no access to repositories or chunk services.
   *
   * @param engine currently published native engine
   * @param queries Bukkit query converter
   */
  public PaperProtectionListener(
      Supplier<Optional<ProtectionEngine>> engine, BukkitProtectionQueryFactory queries) {
    this.engine = Objects.requireNonNull(engine, "engine");
    this.queries = Objects.requireNonNull(queries, "queries");
  }

  /**
   * Protects normal block breaks; Magic Blocks remain reserved for their dedicated pipeline.
   *
   * @param event block break attempt
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockBreak(BlockBreakEvent event) {
    event.setCancelled(
        denied(
            Optional.of(event.getPlayer()),
            ProtectionAction.BLOCK_BREAK,
            Optional.of(event.getBlock().getLocation()),
            Optional.empty(),
            Optional.of(event.getBlock().getType().getKey()),
            PLAYER_CAUSE));
  }

  /**
   * Protects block placement at its destination.
   *
   * @param event block placement attempt
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockPlace(BlockPlaceEvent event) {
    event.setCancelled(
        denied(
            Optional.of(event.getPlayer()),
            ProtectionAction.BLOCK_PLACE,
            Optional.of(event.getBlockAgainst().getLocation()),
            Optional.of(event.getBlockPlaced().getLocation()),
            Optional.of(event.getBlockPlaced().getType().getKey()),
            PLAYER_CAUSE));
  }

  /**
   * Protects bucket extraction.
   *
   * @param event bucket fill attempt
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBucketFill(PlayerBucketFillEvent event) {
    event.setCancelled(
        denied(
            Optional.of(event.getPlayer()),
            ProtectionAction.BUCKET_FILL,
            Optional.of(event.getBlockClicked().getLocation()),
            Optional.empty(),
            Optional.of(event.getBucket().getKey()),
            PLAYER_CAUSE));
  }

  /**
   * Protects both the clicked source and bucket destination.
   *
   * @param event bucket empty attempt
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBucketEmpty(PlayerBucketEmptyEvent event) {
    Block clicked = event.getBlockClicked();
    Block destination = clicked.getRelative(event.getBlockFace());
    event.setCancelled(
        denied(
            Optional.of(event.getPlayer()),
            ProtectionAction.BUCKET_EMPTY,
            Optional.of(clicked.getLocation()),
            Optional.of(destination.getLocation()),
            Optional.of(event.getBucket().getKey()),
            PLAYER_CAUSE));
  }

  /**
   * Protects container and redstone controls without intercepting unrelated item use.
   *
   * @param event player interaction attempt
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPlayerInteract(PlayerInteractEvent event) {
    Block clicked = event.getClickedBlock();
    if (clicked == null) {
      return;
    }
    ProtectionAction action;
    if (clicked.getState(false) instanceof Container
        || clicked.getState(false) instanceof InventoryHolder) {
      action = ProtectionAction.CONTAINER_OPEN;
    } else if (isRedstoneControl(clicked)) {
      action = ProtectionAction.REDSTONE_USE;
    } else {
      return;
    }
    event.setCancelled(
        denied(
            Optional.of(event.getPlayer()),
            action,
            Optional.of(clicked.getLocation()),
            Optional.empty(),
            Optional.of(clicked.getType().getKey()),
            PLAYER_CAUSE));
  }

  /**
   * Protects direct player interaction with entities.
   *
   * @param event entity interaction attempt
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityInteract(PlayerInteractEntityEvent event) {
    event.setCancelled(
        denied(
            Optional.of(event.getPlayer()),
            ProtectionAction.ENTITY_INTERACT,
            Optional.of(event.getRightClicked().getLocation()),
            Optional.empty(),
            Optional.of(event.getRightClicked().getType().getKey()),
            PLAYER_CAUSE));
  }

  /**
   * Protects player and player-projectile entity damage.
   *
   * @param event entity damage attempt
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityDamage(EntityDamageByEntityEvent event) {
    Optional<Player> player = responsiblePlayer(event.getDamager());
    if (player.isEmpty()) {
      return;
    }
    event.setCancelled(
        denied(
            player,
            ProtectionAction.ENTITY_DAMAGE,
            Optional.of(event.getEntity().getLocation()),
            Optional.empty(),
            Optional.of(event.getEntity().getType().getKey()),
            PLAYER_CAUSE));
  }

  /**
   * Protects teleport destinations while allowing normal entry from non-OOB worlds.
   *
   * @param event player teleport attempt
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onTeleport(PlayerTeleportEvent event) {
    Location destination = event.getTo();
    if (destination == null) {
      return;
    }
    event.setCancelled(
        denied(
            Optional.of(event.getPlayer()),
            ProtectionAction.TELEPORT,
            Optional.of(destination),
            Optional.empty(),
            Optional.empty(),
            NamespacedId.of(
                "minecraft", event.getCause().name().toLowerCase(java.util.Locale.ROOT))));
  }

  /**
   * Protects every block moved by an extending piston.
   *
   * @param event piston extension
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPistonExtend(BlockPistonExtendEvent event) {
    for (Block block : event.getBlocks()) {
      if (denied(
          Optional.empty(),
          ProtectionAction.PISTON_MOVE,
          Optional.of(block.getLocation()),
          Optional.of(block.getRelative(event.getDirection()).getLocation()),
          Optional.of(block.getType().getKey()),
          PISTON_CAUSE)) {
        event.setCancelled(true);
        return;
      }
    }
  }

  /**
   * Protects every block pulled by a retracting piston.
   *
   * @param event piston retraction
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPistonRetract(BlockPistonRetractEvent event) {
    for (Block block : event.getBlocks()) {
      if (denied(
          Optional.empty(),
          ProtectionAction.PISTON_MOVE,
          Optional.of(block.getLocation()),
          Optional.of(block.getRelative(event.getDirection().getOppositeFace()).getLocation()),
          Optional.of(block.getType().getKey()),
          PISTON_CAUSE)) {
        event.setCancelled(true);
        return;
      }
    }
  }

  /**
   * Protects fluid flow at both ends of the mutation.
   *
   * @param event fluid flow attempt
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onFluidFlow(BlockFromToEvent event) {
    event.setCancelled(
        denied(
            Optional.empty(),
            ProtectionAction.FLUID_FLOW,
            Optional.of(event.getBlock().getLocation()),
            Optional.of(event.getToBlock().getLocation()),
            Optional.of(event.getBlock().getType().getKey()),
            FLUID_CAUSE));
  }

  /**
   * Protects the complete affected set of entity explosions.
   *
   * @param event entity explosion
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityExplosion(EntityExplodeEvent event) {
    if (explosionDenied(event.getLocation(), event.blockList())) {
      event.setCancelled(true);
    }
  }

  /**
   * Protects the complete affected set of block explosions.
   *
   * @param event block explosion
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockExplosion(BlockExplodeEvent event) {
    if (explosionDenied(event.getBlock().getLocation(), event.blockList())) {
      event.setCancelled(true);
    }
  }

  /**
   * Protects every block spread at both source and destination.
   *
   * @param event block spread attempt
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockSpread(BlockSpreadEvent event) {
    boolean fire =
        event.getSource().getType() == Material.FIRE
            || event.getSource().getType() == Material.SOUL_FIRE;
    event.setCancelled(
        denied(
            Optional.empty(),
            fire ? ProtectionAction.FIRE_SPREAD : ProtectionAction.BLOCK_PLACE,
            Optional.of(event.getSource().getLocation()),
            Optional.of(event.getBlock().getLocation()),
            Optional.of(event.getNewState().getType().getKey()),
            fire ? FIRE_CAUSE : NamespacedId.of("minecraft", "environment")));
  }

  private boolean explosionDenied(Location source, List<Block> affected) {
    for (Block block : affected) {
      if (denied(
          Optional.empty(),
          ProtectionAction.EXPLOSION_DAMAGE,
          Optional.of(source),
          Optional.of(block.getLocation()),
          Optional.of(block.getType().getKey()),
          EXPLOSION_CAUSE)) {
        return true;
      }
    }
    return false;
  }

  private boolean denied(
      Optional<Player> player,
      ProtectionAction action,
      Optional<Location> source,
      Optional<Location> destination,
      Optional<org.bukkit.NamespacedKey> target,
      NamespacedId cause) {
    ProtectionDecision decision =
        engine
            .get()
            .map(
                value ->
                    value.evaluate(
                        queries.create(player, action, source, destination, target, cause)))
            .orElseGet(() -> ProtectionDecision.deny("runtime-not-ready"));
    return decision.outcome() == ProtectionOutcome.DENY;
  }

  private static boolean isRedstoneControl(Block block) {
    return block.getBlockData() instanceof Powerable
        || block.getBlockData() instanceof AnaloguePowerable
        || block.getBlockData() instanceof Openable
        || block.getBlockData() instanceof Switch;
  }

  private static Optional<Player> responsiblePlayer(Entity entity) {
    if (entity instanceof Player player) {
      return Optional.of(player);
    }
    if (entity instanceof Projectile projectile) {
      ProjectileSource shooter = projectile.getShooter();
      if (shooter instanceof Player player) {
        return Optional.of(player);
      }
    }
    return Optional.empty();
  }
}
