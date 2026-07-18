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
                """)));
  }
}
