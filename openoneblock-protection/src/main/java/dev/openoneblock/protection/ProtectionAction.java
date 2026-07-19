package dev.openoneblock.protection;

/** Canonical actions understood by the native island protection engine. */
public enum ProtectionAction {
  /** Break a normal block. */
  BLOCK_BREAK,
  /** Place a block. */
  BLOCK_PLACE,
  /** Open or modify a container. */
  CONTAINER_OPEN,
  /** Interact with an entity. */
  ENTITY_INTERACT,
  /** Damage an entity. */
  ENTITY_DAMAGE,
  /** Use a redstone control. */
  REDSTONE_USE,
  /** Fill a bucket from the world. */
  BUCKET_FILL,
  /** Empty a bucket into the world. */
  BUCKET_EMPTY,
  /** Move blocks with a piston. */
  PISTON_MOVE,
  /** Flow fluid between blocks. */
  FLUID_FLOW,
  /** Mutate blocks through an explosion. */
  EXPLOSION_DAMAGE,
  /** Spread or create fire. */
  FIRE_SPREAD,
  /** Teleport an actor. */
  TELEPORT,
  /** Break a registered Magic Block through its dedicated pipeline. */
  MAGIC_BLOCK_BREAK
}
