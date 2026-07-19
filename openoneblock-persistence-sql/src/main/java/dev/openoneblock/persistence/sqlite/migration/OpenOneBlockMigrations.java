package dev.openoneblock.persistence.sqlite.migration;

import java.util.List;

/** Built-in SQLite schema migration registry. */
public final class OpenOneBlockMigrations {
  private OpenOneBlockMigrations() {}

  /**
   * Returns every migration in ascending version order.
   *
   * @return immutable migration list
   */
  public static List<SqlMigration> all() {
    return List.of(
        new SqlMigration(
            1,
            "foundation slot allocation",
            List.of(
                """
                CREATE TABLE shard_allocators (
                    shard_group_id TEXT PRIMARY KEY,
                    next_ordinal INTEGER NOT NULL CHECK (next_ordinal >= 0),
                    version INTEGER NOT NULL CHECK (version >= 0)
                )
                """,
                """
                CREATE TABLE slots (
                    slot_id TEXT PRIMARY KEY,
                    shard_group_id TEXT NOT NULL,
                    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
                    grid_x INTEGER NOT NULL,
                    grid_z INTEGER NOT NULL,
                    state TEXT NOT NULL CHECK (
                        state IN ('FREE', 'RESERVED', 'PREPARING', 'ACTIVE', 'CLEANING', 'QUARANTINED')
                    ),
                    owner_island_id TEXT,
                    ownership_role TEXT,
                    version INTEGER NOT NULL CHECK (version >= 0),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    UNIQUE (shard_group_id, ordinal),
                    UNIQUE (shard_group_id, grid_x, grid_z),
                    CHECK (
                        (state = 'FREE' AND owner_island_id IS NULL AND ownership_role IS NULL)
                        OR (
                            state <> 'FREE'
                            AND owner_island_id IS NOT NULL
                            AND ownership_role IN ('PRIMARY', 'MIGRATION_TARGET')
                        )
                    )
                )
                """,
                """
                CREATE INDEX slots_reusable_order
                ON slots (shard_group_id, state, ordinal)
                """,
                """
                CREATE UNIQUE INDEX slots_unique_primary_owner
                ON slots (owner_island_id)
                WHERE ownership_role = 'PRIMARY'
                """,
                """
                CREATE TABLE operations (
                    operation_id TEXT PRIMARY KEY,
                    island_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    state TEXT NOT NULL,
                    slot_id TEXT REFERENCES slots (slot_id),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """,
                """
                CREATE INDEX operations_island_kind
                ON operations (island_id, kind)
                """)),
        new SqlMigration(
            2,
            "island allocation and active membership",
            List.of(
                """
                CREATE TABLE islands (
                    island_id TEXT PRIMARY KEY,
                    owner_player_id TEXT NOT NULL,
                    lifecycle_state TEXT NOT NULL CHECK (
                        lifecycle_state IN (
                            'ALLOCATING', 'CREATING', 'ACTIVE', 'LOCKED', 'RESETTING',
                            'MIGRATING', 'DELETING', 'BROKEN', 'ARCHIVED'
                        )
                    ),
                    primary_slot_id TEXT REFERENCES slots (slot_id),
                    current_border_size INTEGER NOT NULL CHECK (current_border_size > 0),
                    maximum_border_size INTEGER NOT NULL CHECK (
                        maximum_border_size >= current_border_size
                    ),
                    version INTEGER NOT NULL CHECK (version >= 0),
                    pending_operation_id TEXT UNIQUE REFERENCES operations (operation_id),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    CHECK (
                        (lifecycle_state = 'ARCHIVED' AND primary_slot_id IS NULL)
                        OR (lifecycle_state <> 'ARCHIVED' AND primary_slot_id IS NOT NULL)
                    )
                )
                """,
                """
                CREATE TABLE island_memberships (
                    island_id TEXT NOT NULL REFERENCES islands (island_id),
                    player_id TEXT NOT NULL,
                    role_id TEXT NOT NULL,
                    active INTEGER NOT NULL CHECK (active IN (0, 1)),
                    owner INTEGER NOT NULL CHECK (owner IN (0, 1)),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (island_id, player_id),
                    CHECK (owner = 0 OR active = 1)
                )
                """,
                """
                CREATE UNIQUE INDEX island_memberships_one_active_island_per_player
                ON island_memberships (player_id)
                WHERE active = 1
                """,
                """
                CREATE UNIQUE INDEX island_memberships_one_active_owner_per_island
                ON island_memberships (island_id)
                WHERE active = 1 AND owner = 1
                """,
                """
                CREATE INDEX island_memberships_island_active
                ON island_memberships (island_id, active)
                """)),
        new SqlMigration(
            3,
            "verified shared world projection catalog",
            List.of(
                """
                CREATE TABLE world_projections (
                    shard_group_id TEXT NOT NULL,
                    dimension_id TEXT NOT NULL,
                    configured_world_name TEXT NOT NULL UNIQUE,
                    actual_world_id TEXT NOT NULL UNIQUE,
                    environment TEXT NOT NULL CHECK (
                        environment IN ('NORMAL', 'NETHER', 'THE_END')
                    ),
                    geometry_fingerprint TEXT NOT NULL CHECK (
                        length(geometry_fingerprint) = 64
                    ),
                    state TEXT NOT NULL CHECK (state IN ('VERIFIED', 'BLOCKED')),
                    version INTEGER NOT NULL CHECK (version >= 0),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (shard_group_id, dimension_id)
                )
                """,
                """
                CREATE TABLE world_projection_repairs (
                    operation_id TEXT PRIMARY KEY,
                    shard_group_id TEXT NOT NULL,
                    dimension_id TEXT NOT NULL,
                    expected_version INTEGER NOT NULL CHECK (expected_version >= 0),
                    adopted_world_name TEXT NOT NULL,
                    adopted_world_id TEXT NOT NULL,
                    adopted_environment TEXT NOT NULL,
                    adopted_geometry_fingerprint TEXT NOT NULL,
                    outcome_version INTEGER NOT NULL CHECK (outcome_version > expected_version),
                    outcome_created_at TEXT NOT NULL,
                    completed_at TEXT NOT NULL,
                    FOREIGN KEY (shard_group_id, dimension_id)
                        REFERENCES world_projections (shard_group_id, dimension_id)
                )
                """)),
        new SqlMigration(
            4,
            "durable world effect receipts",
            List.of(
                """
                CREATE TABLE world_effect_receipts (
                    operation_id TEXT NOT NULL REFERENCES operations (operation_id),
                    effect_index INTEGER NOT NULL CHECK (effect_index >= 0),
                    island_id TEXT NOT NULL REFERENCES islands (island_id),
                    effect_kind TEXT NOT NULL CHECK (
                        effect_kind IN (
                            'VERIFY_CLEAN_REGION', 'SET_VANILLA_BLOCK',
                            'VERIFY_SAFE_SPAWN', 'PLACE_STRUCTURE'
                        )
                    ),
                    safety TEXT NOT NULL CHECK (
                        safety IN (
                            'NATURALLY_IDEMPOTENT', 'DETECTABLY_IDEMPOTENT', 'NON_IDEMPOTENT'
                        )
                    ),
                    plan_descriptor TEXT NOT NULL,
                    fingerprint TEXT NOT NULL CHECK (length(fingerprint) = 64),
                    state TEXT NOT NULL CHECK (
                        state IN (
                            'NOT_STARTED', 'DISPATCHED', 'VERIFIED_SUCCESS',
                            'VERIFIED_FAILURE', 'AMBIGUOUS'
                        )
                    ),
                    dispatch_attempts INTEGER NOT NULL CHECK (dispatch_attempts >= 0),
                    diagnostic TEXT,
                    created_at TEXT NOT NULL,
                    dispatched_at TEXT,
                    completed_at TEXT,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (operation_id, effect_index),
                    CHECK (
                        (state = 'NOT_STARTED'
                            AND dispatch_attempts = 0
                            AND dispatched_at IS NULL
                            AND completed_at IS NULL)
                        OR (state = 'DISPATCHED'
                            AND dispatch_attempts > 0
                            AND dispatched_at IS NOT NULL
                            AND completed_at IS NULL)
                        OR (state IN ('VERIFIED_SUCCESS', 'VERIFIED_FAILURE', 'AMBIGUOUS')
                            AND dispatch_attempts > 0
                            AND dispatched_at IS NOT NULL
                            AND completed_at IS NOT NULL)
                    )
                )
                """,
                """
                CREATE INDEX world_effect_receipts_recovery
                ON world_effect_receipts (state, updated_at)
                """,
                """
                CREATE INDEX world_effect_receipts_island
                ON world_effect_receipts (island_id, operation_id, effect_index)
                """)),
        new SqlMigration(
            5,
            "creation outcomes spawn and initial progression",
            List.of(
                """
                ALTER TABLE operations ADD COLUMN request_fingerprint TEXT
                    CHECK (request_fingerprint IS NULL OR length(request_fingerprint) = 64)
                """,
                """
                ALTER TABLE operations ADD COLUMN outcome_state TEXT
                    CHECK (outcome_state IS NULL OR outcome_state IN ('SUCCEEDED', 'FAILED', 'AMBIGUOUS'))
                """,
                """
                ALTER TABLE operations ADD COLUMN outcome_payload TEXT
                """,
                """
                ALTER TABLE operations ADD COLUMN completed_at TEXT
                """,
                """
                ALTER TABLE islands ADD COLUMN lifecycle_lock_reason TEXT
                """,
                """
                CREATE TABLE island_creation_contexts (
                    operation_id TEXT PRIMARY KEY REFERENCES operations (operation_id),
                    primary_world_id TEXT NOT NULL,
                    profile_id TEXT NOT NULL,
                    phase_id TEXT NOT NULL,
                    starter_block_id TEXT NOT NULL,
                    magic_block_y INTEGER NOT NULL,
                    minimum_y INTEGER NOT NULL,
                    maximum_y_exclusive INTEGER NOT NULL,
                    CHECK (minimum_y < maximum_y_exclusive),
                    CHECK (
                        magic_block_y >= minimum_y
                        AND magic_block_y < maximum_y_exclusive - 1
                    )
                )
                """,
                """
                CREATE TABLE island_spawn_points (
                    island_id TEXT NOT NULL REFERENCES islands (island_id),
                    spawn_id TEXT NOT NULL,
                    world_id TEXT NOT NULL,
                    x REAL NOT NULL,
                    y REAL NOT NULL,
                    z REAL NOT NULL,
                    yaw REAL NOT NULL,
                    pitch REAL NOT NULL,
                    primary_spawn INTEGER NOT NULL CHECK (primary_spawn IN (0, 1)),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (island_id, spawn_id)
                )
                """,
                """
                CREATE UNIQUE INDEX island_spawn_points_one_primary
                ON island_spawn_points (island_id)
                WHERE primary_spawn = 1
                """,
                """
                CREATE TABLE island_progression (
                    island_id TEXT PRIMARY KEY REFERENCES islands (island_id),
                    current_phase_id TEXT NOT NULL,
                    version INTEGER NOT NULL CHECK (version >= 0),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """,
                """
                CREATE INDEX operations_recovery_state
                ON operations (kind, state, updated_at)
                """)),
        new SqlMigration(
            6,
            "magic blocks and sequence identity",
            List.of(
                """
                CREATE TABLE magic_blocks (
                    island_id TEXT NOT NULL REFERENCES islands (island_id),
                    magic_block_id TEXT NOT NULL,
                    world_id TEXT NOT NULL,
                    block_x INTEGER NOT NULL,
                    block_y INTEGER NOT NULL,
                    block_z INTEGER NOT NULL,
                    profile_id TEXT NOT NULL,
                    current_content_id TEXT,
                    state TEXT NOT NULL CHECK (
                        state IN ('READY', 'REGENERATING', 'COOLDOWN', 'LOCKED', 'BROKEN')
                    ),
                    sequence INTEGER NOT NULL CHECK (sequence >= 0),
                    last_persisted_sequence INTEGER NOT NULL CHECK (
                        last_persisted_sequence >= 0 AND last_persisted_sequence <= sequence
                    ),
                    cooldown_until TEXT,
                    version INTEGER NOT NULL CHECK (version >= 0),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (island_id, magic_block_id),
                    UNIQUE (world_id, block_x, block_y, block_z)
                )
                """,
                """
                CREATE INDEX magic_blocks_recovery_state
                ON magic_blocks (state, updated_at)
                """)),
        new SqlMigration(
            7,
            "normalized counters and typed variables",
            List.of(
                """
                CREATE TABLE counters (
                    scope_type TEXT NOT NULL CHECK (
                        scope_type IN ('PLAYER', 'ISLAND', 'SERVER', 'SEASON', 'SESSION')
                    ),
                    scope_id TEXT NOT NULL,
                    counter_id TEXT NOT NULL,
                    value INTEGER NOT NULL,
                    version INTEGER NOT NULL CHECK (version >= 0),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (scope_type, scope_id, counter_id)
                )
                """,
                """
                CREATE INDEX counters_scope_lookup
                ON counters (scope_type, scope_id)
                """,
                """
                CREATE TABLE typed_variables (
                    scope_type TEXT NOT NULL CHECK (
                        scope_type IN ('PLAYER', 'ISLAND', 'SERVER', 'SEASON', 'SESSION')
                    ),
                    scope_id TEXT NOT NULL,
                    variable_id TEXT NOT NULL,
                    value_type TEXT NOT NULL CHECK (
                        value_type IN (
                            'INTEGER', 'DECIMAL', 'BOOLEAN', 'STRING',
                            'TIMESTAMP', 'DURATION'
                        )
                    ),
                    integer_value INTEGER,
                    decimal_value TEXT,
                    boolean_value INTEGER CHECK (boolean_value IN (0, 1)),
                    string_value TEXT,
                    timestamp_value TEXT,
                    duration_millis INTEGER CHECK (
                        duration_millis IS NULL OR duration_millis >= 0
                    ),
                    version INTEGER NOT NULL CHECK (version >= 0),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (scope_type, scope_id, variable_id),
                    CHECK (
                        (value_type = 'INTEGER' AND integer_value IS NOT NULL
                            AND decimal_value IS NULL AND boolean_value IS NULL
                            AND string_value IS NULL AND timestamp_value IS NULL
                            AND duration_millis IS NULL)
                        OR (value_type = 'DECIMAL' AND integer_value IS NULL
                            AND decimal_value IS NOT NULL AND boolean_value IS NULL
                            AND string_value IS NULL AND timestamp_value IS NULL
                            AND duration_millis IS NULL)
                        OR (value_type = 'BOOLEAN' AND integer_value IS NULL
                            AND decimal_value IS NULL AND boolean_value IS NOT NULL
                            AND string_value IS NULL AND timestamp_value IS NULL
                            AND duration_millis IS NULL)
                        OR (value_type = 'STRING' AND integer_value IS NULL
                            AND decimal_value IS NULL AND boolean_value IS NULL
                            AND string_value IS NOT NULL AND timestamp_value IS NULL
                            AND duration_millis IS NULL)
                        OR (value_type = 'TIMESTAMP' AND integer_value IS NULL
                            AND decimal_value IS NULL AND boolean_value IS NULL
                            AND string_value IS NULL AND timestamp_value IS NOT NULL
                            AND duration_millis IS NULL)
                        OR (value_type = 'DURATION' AND integer_value IS NULL
                            AND decimal_value IS NULL AND boolean_value IS NULL
                            AND string_value IS NULL AND timestamp_value IS NULL
                            AND duration_millis IS NOT NULL)
                    )
                )
                """,
                """
                CREATE INDEX typed_variables_scope_lookup
                ON typed_variables (scope_type, scope_id)
                """)));
  }
}
