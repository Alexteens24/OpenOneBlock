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
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.projectiles.ProjectileSource;

/** Remaining environment and mechanical adapters for native cross-island protection. */
public final class PaperMechanicalProtectionListener implements Listener {
  private static final NamespacedId ENVIRONMENT = NamespacedId.of("minecraft", "environment");
  private static final NamespacedId ENTITY = NamespacedId.of("minecraft", "entity");
  private static final NamespacedId FIRE = NamespacedId.of("minecraft", "fire");
  private static final NamespacedId MECHANICAL = NamespacedId.of("minecraft", "mechanical");
  private static final NamespacedId PORTAL = NamespacedId.of("minecraft", "portal");
  private static final NamespacedId PROJECTILE = NamespacedId.of("minecraft", "projectile");

  private final Supplier<Optional<ProtectionEngine>> engine;
  private final BukkitProtectionQueryFactory queries;

  /**
   * Creates a mechanical listener with no persistence or chunk-service dependency.
   *
   * @param engine currently published native engine
   * @param queries Bukkit query converter
   */
  public PaperMechanicalProtectionListener(
      Supplier<Optional<ProtectionEngine>> engine, BukkitProtectionQueryFactory queries) {
    this.engine = Objects.requireNonNull(engine, "engine");
    this.queries = Objects.requireNonNull(queries, "queries");
  }

  /**
   * Protects entity block mutation, including mob grief and falling blocks.
   *
   * @param event entity block mutation
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityChangeBlock(EntityChangeBlockEvent event) {
    ProtectionAction action =
        isAir(event.getTo()) ? ProtectionAction.BLOCK_BREAK : ProtectionAction.BLOCK_PLACE;
    event.setCancelled(
        denied(
            responsiblePlayer(event.getEntity()),
            action,
            Optional.of(event.getEntity().getLocation()),
            Optional.of(event.getBlock().getLocation()),
            Optional.of(event.getTo().getKey()),
            ENTITY));
  }

  /**
   * Protects non-player entity interaction with physical blocks.
   *
   * @param event entity interaction
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityInteract(EntityInteractEvent event) {
    event.setCancelled(
        denied(
            responsiblePlayer(event.getEntity()),
            ProtectionAction.REDSTONE_USE,
            Optional.of(event.getEntity().getLocation()),
            Optional.of(event.getBlock().getLocation()),
            Optional.of(event.getBlock().getType().getKey()),
            ENTITY));
  }

  /**
   * Protects player-owned entity and vehicle placement.
   *
   * @param event entity placement
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityPlace(EntityPlaceEvent event) {
    event.setCancelled(
        denied(
            Optional.ofNullable(event.getPlayer()),
            ProtectionAction.ENTITY_INTERACT,
            Optional.of(event.getBlock().getLocation()),
            Optional.of(event.getEntity().getLocation()),
            Optional.of(event.getEntity().getType().getKey()),
            ENTITY));
  }

  /**
   * Protects non-player teleport destinations.
   *
   * @param event entity teleport
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityTeleport(EntityTeleportEvent event) {
    event.setCancelled(
        denied(
            responsiblePlayer(event.getEntity()),
            ProtectionAction.TELEPORT,
            Optional.of(event.getFrom()),
            Optional.of(event.getTo()),
            Optional.of(event.getEntity().getType().getKey()),
            ENTITY));
  }

  /**
   * Protects every block in entity-created portals.
   *
   * @param event portal creation
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPortalCreate(PortalCreateEvent event) {
    Optional<Entity> creator = Optional.ofNullable(event.getEntity());
    for (BlockState state : event.getBlocks()) {
      if (denied(
          creator.flatMap(PaperMechanicalProtectionListener::responsiblePlayer),
          ProtectionAction.BLOCK_PLACE,
          creator.map(Entity::getLocation),
          Optional.of(state.getLocation()),
          Optional.of(state.getType().getKey()),
          PORTAL)) {
        event.setCancelled(true);
        return;
      }
    }
  }

  /**
   * Protects fire ignition at its source and destination.
   *
   * @param event ignition attempt
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockIgnite(BlockIgniteEvent event) {
    Optional<Player> player = Optional.ofNullable(event.getPlayer());
    Optional<Location> source =
        Optional.ofNullable(event.getIgnitingBlock())
            .map(Block::getLocation)
            .or(() -> Optional.ofNullable(event.getIgnitingEntity()).map(Entity::getLocation));
    event.setCancelled(
        denied(
            player,
            ProtectionAction.FIRE_SPREAD,
            source,
            Optional.of(event.getBlock().getLocation()),
            Optional.of(event.getBlock().getType().getKey()),
            FIRE));
  }

  /**
   * Protects blocks consumed by fire.
   *
   * @param event burn attempt
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockBurn(BlockBurnEvent event) {
    event.setCancelled(
        denied(
            Optional.empty(),
            ProtectionAction.FIRE_SPREAD,
            Optional.ofNullable(event.getIgnitingBlock()).map(Block::getLocation),
            Optional.of(event.getBlock().getLocation()),
            Optional.of(event.getBlock().getType().getKey()),
            FIRE));
  }

  /**
   * Protects lightning effects at their strike location.
   *
   * @param event lightning strike
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onLightning(LightningStrikeEvent event) {
    event.setCancelled(
        denied(
            Optional.empty(),
            ProtectionAction.FIRE_SPREAD,
            Optional.of(event.getLightning().getLocation()),
            Optional.empty(),
            Optional.empty(),
            FIRE));
  }

  /**
   * Protects natural block fading.
   *
   * @param event block fade
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockFade(BlockFadeEvent event) {
    event.setCancelled(blockStateDenied(event.getBlock(), event.getNewState(), Optional.empty()));
  }

  /**
   * Protects natural block growth.
   *
   * @param event block growth
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockGrow(BlockGrowEvent event) {
    event.setCancelled(blockStateDenied(event.getBlock(), event.getNewState(), Optional.empty()));
  }

  /**
   * Protects snow, ice, and other natural block formation.
   *
   * @param event block formation
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockForm(BlockFormEvent event) {
    event.setCancelled(blockStateDenied(event.getBlock(), event.getNewState(), Optional.empty()));
  }

  /**
   * Protects physics propagation between source and changed block.
   *
   * @param event block physics
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockPhysics(BlockPhysicsEvent event) {
    event.setCancelled(
        denied(
            Optional.empty(),
            ProtectionAction.BLOCK_PLACE,
            Optional.ofNullable(event.getSourceBlock()).map(Block::getLocation),
            Optional.of(event.getBlock().getLocation()),
            Optional.of(event.getChangedType().getKey()),
            ENVIRONMENT));
  }

  /**
   * Protects leaf decay.
   *
   * @param event leaf decay
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onLeavesDecay(LeavesDecayEvent event) {
    event.setCancelled(
        denied(
            Optional.empty(),
            ProtectionAction.BLOCK_BREAK,
            Optional.of(event.getBlock().getLocation()),
            Optional.empty(),
            Optional.of(event.getBlock().getType().getKey()),
            ENVIRONMENT));
  }

  /**
   * Protects every block changed by fertilization.
   *
   * @param event fertilization
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onFertilize(BlockFertilizeEvent event) {
    if (statesDenied(
        event.getBlocks(),
        Optional.ofNullable(event.getPlayer()),
        event.getBlock().getLocation())) {
      event.setCancelled(true);
    }
  }

  /**
   * Protects every block changed by tree and mushroom growth.
   *
   * @param event structure growth
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onStructureGrow(StructureGrowEvent event) {
    if (statesDenied(
        event.getBlocks(), Optional.ofNullable(event.getPlayer()), event.getLocation())) {
      event.setCancelled(true);
    }
  }

  /**
   * Protects every block removed by a sponge.
   *
   * @param event sponge absorption
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onSpongeAbsorb(SpongeAbsorbEvent event) {
    if (statesDenied(event.getBlocks(), Optional.empty(), event.getBlock().getLocation())) {
      event.setCancelled(true);
    }
  }

  /**
   * Protects TNT priming and chain reactions.
   *
   * @param event TNT prime attempt
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onTntPrime(TNTPrimeEvent event) {
    Optional<Location> source =
        Optional.ofNullable(event.getPrimingBlock())
            .map(Block::getLocation)
            .or(() -> Optional.ofNullable(event.getPrimingEntity()).map(Entity::getLocation));
    event.setCancelled(
        denied(
            Optional.ofNullable(event.getPrimingEntity())
                .flatMap(PaperMechanicalProtectionListener::responsiblePlayer),
            ProtectionAction.EXPLOSION_DAMAGE,
            source,
            Optional.of(event.getBlock().getLocation()),
            Optional.of(event.getBlock().getType().getKey()),
            MECHANICAL));
  }

  /**
   * Protects dispenser and dropper output across a block boundary.
   *
   * @param event dispense attempt
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockDispense(BlockDispenseEvent event) {
    Block source = event.getBlock();
    Optional<Location> destination =
        source.getBlockData() instanceof Directional directional
            ? Optional.of(source.getRelative(directional.getFacing()).getLocation())
            : Optional.empty();
    event.setCancelled(
        denied(
            Optional.empty(),
            ProtectionAction.ENTITY_INTERACT,
            Optional.of(source.getLocation()),
            destination,
            Optional.of(event.getItem().getType().getKey()),
            MECHANICAL));
  }

  /**
   * Protects hopper-style transfers between two block inventories.
   *
   * @param event inventory transfer
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInventoryMove(InventoryMoveItemEvent event) {
    Optional<Location> source = inventoryLocation(event.getSource());
    Optional<Location> destination = inventoryLocation(event.getDestination());
    if (source.isEmpty() && destination.isEmpty()) {
      return;
    }
    event.setCancelled(
        denied(
            Optional.empty(),
            ProtectionAction.CONTAINER_OPEN,
            source,
            destination,
            Optional.of(event.getItem().getType().getKey()),
            MECHANICAL));
  }

  /**
   * Protects inventory pickup of world items.
   *
   * @param event inventory pickup
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInventoryPickup(InventoryPickupItemEvent event) {
    Optional<Location> destination = inventoryLocation(event.getInventory());
    if (destination.isEmpty()) {
      return;
    }
    event.setCancelled(
        denied(
            Optional.empty(),
            ProtectionAction.CONTAINER_OPEN,
            Optional.of(event.getItem().getLocation()),
            destination,
            Optional.of(event.getItem().getItemStack().getType().getKey()),
            MECHANICAL));
  }

  /**
   * Protects projectile impact across island boundaries.
   *
   * @param event projectile impact
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onProjectileHit(ProjectileHitEvent event) {
    Optional<Location> destination =
        Optional.ofNullable(event.getHitBlock())
            .map(Block::getLocation)
            .or(() -> Optional.ofNullable(event.getHitEntity()).map(Entity::getLocation));
    if (destination.isEmpty()) {
      return;
    }
    event.setCancelled(
        denied(
            responsiblePlayer(event.getEntity()),
            ProtectionAction.ENTITY_INTERACT,
            projectileSource(event.getEntity()),
            destination,
            Optional.of(event.getEntity().getType().getKey()),
            PROJECTILE));
  }

  /**
   * Protects fishing-hook movement across island boundaries.
   *
   * @param event fishing interaction
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPlayerFish(PlayerFishEvent event) {
    event.setCancelled(
        denied(
            Optional.of(event.getPlayer()),
            ProtectionAction.ENTITY_INTERACT,
            Optional.of(event.getPlayer().getLocation()),
            Optional.of(event.getHook().getLocation()),
            Optional.of(event.getHook().getType().getKey()),
            PROJECTILE));
  }

  /**
   * Protects vehicle damage.
   *
   * @param event vehicle damage
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onVehicleDamage(VehicleDamageEvent event) {
    event.setCancelled(
        denied(
            Optional.ofNullable(event.getAttacker())
                .flatMap(PaperMechanicalProtectionListener::responsiblePlayer),
            ProtectionAction.ENTITY_DAMAGE,
            Optional.of(event.getVehicle().getLocation()),
            Optional.empty(),
            Optional.of(event.getVehicle().getType().getKey()),
            ENTITY));
  }

  /**
   * Protects vehicle destruction.
   *
   * @param event vehicle destroy
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onVehicleDestroy(VehicleDestroyEvent event) {
    event.setCancelled(
        denied(
            Optional.ofNullable(event.getAttacker())
                .flatMap(PaperMechanicalProtectionListener::responsiblePlayer),
            ProtectionAction.ENTITY_DAMAGE,
            Optional.of(event.getVehicle().getLocation()),
            Optional.empty(),
            Optional.of(event.getVehicle().getType().getKey()),
            ENTITY));
  }

  /**
   * Protects players entering vehicles.
   *
   * @param event vehicle entry
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onVehicleEnter(VehicleEnterEvent event) {
    event.setCancelled(
        denied(
            responsiblePlayer(event.getEntered()),
            ProtectionAction.ENTITY_INTERACT,
            Optional.of(event.getVehicle().getLocation()),
            Optional.empty(),
            Optional.of(event.getVehicle().getType().getKey()),
            ENTITY));
  }

  /**
   * Protects vehicle/entity collisions across cells and borders.
   *
   * @param event vehicle collision
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onVehicleCollision(VehicleEntityCollisionEvent event) {
    event.setCancelled(
        denied(
            responsiblePlayer(event.getEntity()),
            ProtectionAction.ENTITY_INTERACT,
            Optional.of(event.getVehicle().getLocation()),
            Optional.of(event.getEntity().getLocation()),
            Optional.of(event.getVehicle().getType().getKey()),
            ENTITY));
  }

  /**
   * Protects hanging-entity removal by a player or projectile.
   *
   * @param event hanging removal
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onHangingBreak(HangingBreakByEntityEvent event) {
    event.setCancelled(
        denied(
            Optional.ofNullable(event.getRemover())
                .flatMap(PaperMechanicalProtectionListener::responsiblePlayer),
            ProtectionAction.ENTITY_INTERACT,
            Optional.of(event.getEntity().getLocation()),
            Optional.empty(),
            Optional.of(event.getEntity().getType().getKey()),
            ENTITY));
  }

  /**
   * Protects hanging-entity placement.
   *
   * @param event hanging placement
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onHangingPlace(HangingPlaceEvent event) {
    event.setCancelled(
        denied(
            Optional.ofNullable(event.getPlayer()),
            ProtectionAction.ENTITY_INTERACT,
            Optional.of(event.getBlock().getLocation()),
            Optional.of(event.getEntity().getLocation()),
            Optional.of(event.getEntity().getType().getKey()),
            ENTITY));
  }

  private boolean blockStateDenied(Block current, BlockState replacement, Optional<Player> player) {
    ProtectionAction action =
        isAir(replacement.getType()) ? ProtectionAction.BLOCK_BREAK : ProtectionAction.BLOCK_PLACE;
    return denied(
        player,
        action,
        Optional.of(current.getLocation()),
        Optional.of(replacement.getLocation()),
        Optional.of(replacement.getType().getKey()),
        ENVIRONMENT);
  }

  private boolean statesDenied(List<BlockState> states, Optional<Player> player, Location source) {
    for (BlockState state : states) {
      if (denied(
          player,
          ProtectionAction.BLOCK_PLACE,
          Optional.of(source),
          Optional.of(state.getLocation()),
          Optional.of(state.getType().getKey()),
          ENVIRONMENT)) {
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

  private static Optional<Location> inventoryLocation(Inventory inventory) {
    return Optional.ofNullable(inventory.getLocation());
  }

  private static Optional<Location> projectileSource(Projectile projectile) {
    ProjectileSource shooter = projectile.getShooter();
    return shooter instanceof Entity entity
        ? Optional.of(entity.getLocation())
        : Optional.of(projectile.getLocation());
  }

  private static Optional<Player> responsiblePlayer(Entity entity) {
    if (entity instanceof Player player) {
      return Optional.of(player);
    }
    if (entity instanceof Projectile projectile
        && projectile.getShooter() instanceof Player player) {
      return Optional.of(player);
    }
    return Optional.empty();
  }

  private static boolean isAir(Material material) {
    return material == Material.AIR
        || material == Material.CAVE_AIR
        || material == Material.VOID_AIR;
  }
}
