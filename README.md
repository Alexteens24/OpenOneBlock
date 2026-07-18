# OpenOneBlock

> Build your own OneBlock experience.

OpenOneBlock is a gameplay platform for building configurable OneBlock experiences on Minecraft
servers. Its Java core protects domain invariants and data integrity, while OpenOneBlock Rules will
let server owners define progression, rewards, events, and Magic Block behavior without changing
the plugin source.

## Project status

OpenOneBlock is in its foundation phase. The repository currently defines module boundaries and
build tooling plus accepted core execution semantics; it does not yet produce an installable Paper
plugin.

## Design specifications

Implementation is governed by the accepted [core design specifications](docs/design/README.md):

1. [Island Domain and Lifecycle](docs/design/01-island-domain-lifecycle.md)
2. [Grid, Slot, and Border Invariants](docs/design/02-grid-slot-border.md)
3. [Magic Block Transaction Pipeline](docs/design/03-magic-block-transaction.md)
4. [Rule Engine Execution Semantics](docs/design/04-rule-engine-semantics.md)

Changes that conflict with these safety or execution semantics require design review before code is
changed.

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
