# OpenOneBlock Core Design Specifications

- Status: Accepted baseline
- Specification version: 1

These documents define the safety and execution semantics that OpenOneBlock implementations must
preserve. They are normative for the domain, persistence, scripting, protection, and Paper modules.
Code that conflicts with these specifications must change the specification through design review
before it changes behavior.

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHOULD**, **SHOULD NOT**, and **MAY** are to be
interpreted as described by [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and
[RFC 8174](https://www.rfc-editor.org/rfc/rfc8174) when they appear in uppercase.

## Specifications

1. [Island Domain and Lifecycle](01-island-domain-lifecycle.md)
2. [Grid, Slot, and Border Invariants](02-grid-slot-border.md)
3. [Magic Block Transaction Pipeline](03-magic-block-transaction.md)
4. [Rule Engine Execution Semantics](04-rule-engine-semantics.md)

## Shared terminology

- **Authoritative state**: normalized SQL state plus durable operations that have not yet been
  projected into normalized tables.
- **Island lane**: the sequential execution boundary for all mutations of one island.
- **Operation**: a uniquely identified mutation intent that can be recovered after a crash.
- **Snapshot**: an immutable, internally consistent view used by rules, APIs, analytics, and events.
- **Shard group**: the set of dimension worlds that share one logical island grid.
- **World effect**: a block, entity, inventory, teleport, or other Minecraft-state mutation.

## Global invariants

1. A slot MUST NOT be owned by two islands.
2. Gameplay MUST NOT mutate an island unless it is `ACTIVE`.
3. One Magic Block sequence MUST NOT create two logical operations or rewards.
4. Island mutations MUST be serialized by island ID.
5. World effects MUST execute on the scheduler that owns their location or entity.
6. The database is authoritative; caches and loaded aggregates are rebuildable projections.
7. Ambiguous non-idempotent effects MUST NOT be retried automatically.
8. A failed configuration reload MUST NOT replace the active rule registry.

## Identifier and time conventions

Runtime entities use typed identifiers rather than raw strings at module boundaries. Island, slot,
and operation identifiers use UUID v4. Configured content, rule, phase, shard group, dimension, and
extension identifiers use validated namespaced IDs. Versions, sequences, and slot ordinals are
non-negative signed 64-bit integers and MUST fail before overflow.

Persistent time uses UTC `Instant` semantics. Durations use non-negative millisecond precision.
Domain logic receives an injected clock; it MUST NOT read the system clock directly.
