# OpenOneBlock

> Build your own OneBlock experience.

OpenOneBlock is a gameplay platform for building configurable OneBlock experiences on Minecraft
servers. Its Java core protects domain invariants and data integrity, while OpenOneBlock Rules will
let server owners define progression, rewards, events, and Magic Block behavior without changing
the plugin source.

## Project status

OpenOneBlock is in its foundation phase. The repository currently defines module boundaries and
build tooling only; it does not yet produce an installable Paper plugin.

## Modules

| Module | Responsibility |
| --- | --- |
| `openoneblock-api` | Public immutable views, events, and addon contracts |
| `openoneblock-core` | Island, grid, slot, border, Magic Block, and progression domain |
| `openoneblock-paper` | Paper composition root, listeners, commands, and platform adapters |
| `openoneblock-scripting` | Rule parsing, compilation, planning, and execution state |
| `openoneblock-protection` | Native island and cross-boundary protection policies |
| `openoneblock-persistence-sql` | SQL repositories, migrations, write-behind, and recovery |
| `openoneblock-structures-worldedit` | WorldEdit-compatible structure operations |
| `openoneblock-integrations` | Optional economy, content, mob, and placeholder bridges |
| `openoneblock-admin-tools` | Validation, inspection, simulation, diagnostics, and repair |

## Requirements

- JDK 21
- Git

The target server baseline is Paper 1.21.11 on Java 21.

## Build

```bash
./gradlew build
```

Run all formatting checks with:

```bash
./gradlew spotlessCheck
```

OpenOneBlock is licensed under GPL-3.0-only. See `LICENSE` for details.
