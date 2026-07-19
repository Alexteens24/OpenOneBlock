package dev.openoneblock.api.island;

/** Stable configurable permission identities for island roles and gameplay policies. */
public enum IslandPermission {
  /** Break normal island blocks and invoke eligible Magic Block breaks. */
  BLOCK_BREAK,
  /** Place island blocks. */
  BLOCK_PLACE,
  /** Open and modify containers. */
  CONTAINER_OPEN,
  /** Interact with island entities and vehicles. */
  ENTITY_INTERACT,
  /** Damage passive island entities. */
  DAMAGE_ANIMALS,
  /** Damage hostile island entities. */
  DAMAGE_MONSTERS,
  /** Use redstone controls. */
  REDSTONE_USE,
  /** Fill or empty buckets. */
  USE_BUCKET,
  /** Teleport into the island through protected destinations. */
  TELEPORT,
  /** Invite another player. */
  INVITE_MEMBER,
  /** Remove another non-owner member. */
  KICK_MEMBER,
  /** Change the caller's island home. */
  SET_HOME,
  /** Change mutable island settings. */
  CHANGE_SETTINGS,
  /** Purchase or apply island upgrades. */
  UPGRADE_ISLAND,
  /** Request an island reset. */
  RESET_ISLAND
}
