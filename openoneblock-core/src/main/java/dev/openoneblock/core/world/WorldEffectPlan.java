package dev.openoneblock.core.world;

import dev.openoneblock.api.id.IslandId;
import dev.openoneblock.api.id.NamespacedId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.grid.HorizontalBounds;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable, fingerprinted world effect understood by preparation providers. */
public sealed interface WorldEffectPlan
    permits WorldEffectPlan.VerifyCleanRegion,
        WorldEffectPlan.SetVanillaBlock,
        WorldEffectPlan.VerifySafeSpawn,
        WorldEffectPlan.PlaceStructure {
  /**
   * Returns the stable effect key.
   *
   * @return effect key
   */
  WorldEffectKey key();

  /**
   * Returns the island owning the target.
   *
   * @return island identity
   */
  IslandId islandId();

  /**
   * Returns the verified target world.
   *
   * @return world identity
   */
  WorldId worldId();

  /**
   * Returns the effect category.
   *
   * @return effect category
   */
  Kind kind();

  /**
   * Returns the recovery/idempotency classification.
   *
   * @return safety class
   */
  Safety safety();

  /**
   * Returns a canonical descriptor persisted with the receipt.
   *
   * @return canonical descriptor
   */
  String descriptor();

  /**
   * Returns the SHA-256 identity of the complete immutable effect intent.
   *
   * @return lowercase hexadecimal fingerprint
   */
  default String fingerprint() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(descriptor().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JVM does not provide SHA-256", exception);
    }
  }

  /** Effect categories used by validation, persistence, and diagnostics. */
  enum Kind {
    /** Read-only proof that a bounded region contains no unexpected blocks. */
    VERIFY_CLEAN_REGION(false),
    /** Exact Vanilla block state assignment. */
    SET_VANILLA_BLOCK(true),
    /** Read-only proof that a spawn has safe floor, feet, and head blocks. */
    VERIFY_SAFE_SPAWN(false),
    /** Structure-provider placement, implemented in a later WorldEdit bridge. */
    PLACE_STRUCTURE(true);

    private final boolean mutatesWorld;

    Kind(boolean mutatesWorld) {
      this.mutatesWorld = mutatesWorld;
    }

    /**
     * Returns whether dispatch can leave cleanup-relevant world residue.
     *
     * @return whether the effect mutates world state
     */
    public boolean mutatesWorld() {
      return mutatesWorld;
    }
  }

  /** Recovery behavior classification fixed when the plan is compiled. */
  enum Safety {
    /** Reapplying the exact target state is safe. */
    NATURALLY_IDEMPOTENT(true),
    /** A provider can inspect a durable marker to prove prior execution. */
    DETECTABLY_IDEMPOTENT(true),
    /** A crash after dispatch cannot be retried automatically. */
    NON_IDEMPOTENT(false);

    private final boolean automaticallyRecoverable;

    Safety(boolean automaticallyRecoverable) {
      this.automaticallyRecoverable = automaticallyRecoverable;
    }

    /**
     * Returns whether recovery may verify and safely resume this effect.
     *
     * @return whether automatic recovery is permitted
     */
    public boolean automaticallyRecoverable() {
      return automaticallyRecoverable;
    }
  }

  /** Rotation applied by a structure provider around the declared anchor. */
  enum Rotation {
    /** No rotation. */
    NONE,
    /** Ninety degrees clockwise. */
    CLOCKWISE_90,
    /** One hundred eighty degrees. */
    CLOCKWISE_180,
    /** Ninety degrees counterclockwise. */
    COUNTERCLOCKWISE_90
  }

  /** Optional clipboard mirror applied before rotation. */
  enum Mirror {
    /** No mirror. */
    NONE,
    /** Mirror across the local left-right axis. */
    LEFT_RIGHT,
    /** Mirror across the local front-back axis. */
    FRONT_BACK
  }

  /**
   * Complete bounded clean-region verification intent.
   *
   * @param key stable effect key
   * @param islandId owning island
   * @param worldId verified world
   * @param bounds horizontal verification bounds
   * @param minimumY inclusive minimum Y
   * @param maximumYExclusive exclusive maximum Y
   */
  record VerifyCleanRegion(
      WorldEffectKey key,
      IslandId islandId,
      WorldId worldId,
      HorizontalBounds bounds,
      int minimumY,
      int maximumYExclusive)
      implements WorldEffectPlan {
    /** Validates a non-empty vertical interval. */
    public VerifyCleanRegion {
      requireCommon(key, islandId, worldId);
      Objects.requireNonNull(bounds, "bounds");
      if (minimumY >= maximumYExclusive) {
        throw new IllegalArgumentException("clean verification height must not be empty");
      }
    }

    @Override
    public Kind kind() {
      return Kind.VERIFY_CLEAN_REGION;
    }

    @Override
    public Safety safety() {
      return Safety.NATURALLY_IDEMPOTENT;
    }

    @Override
    public String descriptor() {
      return String.join(
          "|",
          kind().name(),
          key.toString(),
          islandId.toString(),
          worldId.toString(),
          Integer.toString(bounds.minX()),
          Integer.toString(bounds.minZ()),
          Integer.toString(bounds.maxXExclusive()),
          Integer.toString(bounds.maxZExclusive()),
          Integer.toString(minimumY),
          Integer.toString(maximumYExclusive));
    }
  }

  /**
   * Exact Vanilla block assignment, naturally idempotent by target and block data.
   *
   * @param key stable effect key
   * @param islandId owning island
   * @param position exact target block
   * @param blockType canonical Vanilla block identity
   */
  record SetVanillaBlock(
      WorldEffectKey key, IslandId islandId, WorldBlockPosition position, NamespacedId blockType)
      implements WorldEffectPlan {
    /** Validates that only canonical Vanilla material identities enter this adapter. */
    public SetVanillaBlock {
      Objects.requireNonNull(position, "position");
      requireCommon(key, islandId, position.worldId());
      Objects.requireNonNull(blockType, "blockType");
      if (!blockType.namespace().equals("minecraft")) {
        throw new IllegalArgumentException("minimal block effects require minecraft namespace");
      }
    }

    @Override
    public WorldId worldId() {
      return position.worldId();
    }

    @Override
    public Kind kind() {
      return Kind.SET_VANILLA_BLOCK;
    }

    @Override
    public Safety safety() {
      return Safety.NATURALLY_IDEMPOTENT;
    }

    @Override
    public String descriptor() {
      return String.join(
          "|",
          kind().name(),
          key.toString(),
          islandId.toString(),
          worldId().toString(),
          Integer.toString(position.x()),
          Integer.toString(position.y()),
          Integer.toString(position.z()),
          blockType.toString());
    }
  }

  /**
   * Safe-spawn verification intent.
   *
   * @param key stable effect key
   * @param islandId owning island
   * @param spawn precise spawn target
   */
  record VerifySafeSpawn(WorldEffectKey key, IslandId islandId, WorldSpawnPosition spawn)
      implements WorldEffectPlan {
    /** Validates required identities. */
    public VerifySafeSpawn {
      Objects.requireNonNull(spawn, "spawn");
      requireCommon(key, islandId, spawn.worldId());
    }

    @Override
    public WorldId worldId() {
      return spawn.worldId();
    }

    @Override
    public Kind kind() {
      return Kind.VERIFY_SAFE_SPAWN;
    }

    @Override
    public Safety safety() {
      return Safety.NATURALLY_IDEMPOTENT;
    }

    @Override
    public String descriptor() {
      return String.join(
          "|",
          kind().name(),
          key.toString(),
          islandId.toString(),
          worldId().toString(),
          Double.toHexString(spawn.x()),
          Double.toHexString(spawn.y()),
          Double.toHexString(spawn.z()),
          Float.toHexString(spawn.yaw()),
          Float.toHexString(spawn.pitch()));
    }
  }

  /**
   * Structure placement remains a provider-facing port until the WorldEdit milestone.
   *
   * @param key stable effect key
   * @param islandId owning island
   * @param worldId verified world
   * @param structureId registered structure identity
   * @param anchor transform anchor
   * @param rotation clipboard rotation
   * @param mirror clipboard mirror
   * @param footprint complete transformed horizontal footprint
   * @param minimumY inclusive transformed minimum Y
   * @param maximumYExclusive exclusive transformed maximum Y
   */
  record PlaceStructure(
      WorldEffectKey key,
      IslandId islandId,
      WorldId worldId,
      NamespacedId structureId,
      WorldBlockPosition anchor,
      Rotation rotation,
      Mirror mirror,
      HorizontalBounds footprint,
      int minimumY,
      int maximumYExclusive)
      implements WorldEffectPlan {
    /** Validates the declared complete structure footprint. */
    public PlaceStructure {
      requireCommon(key, islandId, worldId);
      Objects.requireNonNull(structureId, "structureId");
      Objects.requireNonNull(anchor, "anchor");
      Objects.requireNonNull(rotation, "rotation");
      Objects.requireNonNull(mirror, "mirror");
      Objects.requireNonNull(footprint, "footprint");
      if (!anchor.worldId().equals(worldId)) {
        throw new IllegalArgumentException("structure anchor world does not match effect world");
      }
      if (minimumY >= maximumYExclusive) {
        throw new IllegalArgumentException("structure height must not be empty");
      }
    }

    @Override
    public Kind kind() {
      return Kind.PLACE_STRUCTURE;
    }

    @Override
    public Safety safety() {
      return Safety.DETECTABLY_IDEMPOTENT;
    }

    @Override
    public String descriptor() {
      return String.join(
          "|",
          kind().name(),
          key.toString(),
          islandId.toString(),
          worldId.toString(),
          structureId.toString(),
          Integer.toString(anchor.x()),
          Integer.toString(anchor.y()),
          Integer.toString(anchor.z()),
          rotation.name(),
          mirror.name(),
          Integer.toString(footprint.minX()),
          Integer.toString(footprint.minZ()),
          Integer.toString(footprint.maxXExclusive()),
          Integer.toString(footprint.maxZExclusive()),
          Integer.toString(minimumY),
          Integer.toString(maximumYExclusive));
    }
  }

  private static void requireCommon(WorldEffectKey key, IslandId islandId, WorldId worldId) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(islandId, "islandId");
    Objects.requireNonNull(worldId, "worldId");
  }
}
