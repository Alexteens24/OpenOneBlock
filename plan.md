# OpenOneBlock Development Plan

> Build your own OneBlock experience.

- Last updated: 2026-07-19
- Planning status: active
- Current phase: Foundation
- Target platform: Paper 1.21.11, Java 21, Folia-compatible architecture
- Source of truth for semantics: `docs/design/`

This file is the implementation backlog for OpenOneBlock. It translates the accepted design
specifications and product plan into ordered, testable development milestones. When implementation
and this plan disagree, the accepted specifications in `docs/design/` take precedence until a design
review updates them.

## Status legend

- `[x]` Implemented and covered by tests.
- `[~]` Partially implemented; not yet usable end-to-end.
- `[ ]` Not implemented.
- `P0` Required for the first usable alpha.
- `P1` Required for the public MVP.
- `P2` Production hardening or extension work.
- `P3` Explicitly deferred.

## Non-negotiable engineering rules

Every milestone must preserve these rules:

1. The SQL database is authoritative; caches and loaded aggregates are rebuildable projections.
2. One slot must never be owned by two islands.
3. A slot must not return to `FREE` before cleanup is verified.
4. Gameplay must not mutate an island unless it is `ACTIVE`.
5. Every mutation of one island must pass through its sequential island lane.
6. Every critical command must carry an operation ID and expected aggregate version.
7. Replaying an operation must return its stored outcome instead of repeating rewards or world work.
8. World and entity mutations must run on their Paper/Folia ownership scheduler.
9. Event hot paths must not query SQL, scan islands, load chunks, or inspect block metadata.
10. Rule conditions are read-only and every rule in one event reads the same immutable snapshot.
11. Addons receive immutable views and mutate state only through application services.
12. Failed config or rule reloads must leave the current valid runtime registry active.
13. Ambiguous non-idempotent effects must not be retried automatically.
14. Database failures must not silently lose critical rewards, ownership, or lifecycle mutations.

## Current implementation snapshot

The repository is a Gradle multi-module project and currently passes 177 automated tests. It produces
an installable Paper foundation JAR, reaches `READY` only after verified recovery, and does not yet
register `/oneblock` commands or gameplay listeners.

### Completed foundation work

- [x] Multi-module Gradle layout and Java 21 toolchain.
- [x] Formatting, reproducible archives, Javadoc, JUnit, and GitHub Actions build.
- [x] Four accepted design specifications:
  - island domain and lifecycle;
  - grid, slot, and border invariants;
  - Magic Block transaction semantics;
  - rule engine execution semantics.
- [x] Typed IDs for islands, operations, players, shard groups, dimensions, and worlds.
- [x] Namespaced ID validation.
- [x] Fixed grid geometry and half-open bounds.
- [x] Signed boundary-safe location-to-grid arithmetic.
- [x] Deterministic square-spiral slot coordinates.
- [x] Island and slot lifecycle transition policies.
- [x] Generic mutation preconditions and optimistic version contracts.
- [x] Bounded transient sequential island execution lanes.
- [x] O(1) in-memory slot locator with fail-closed conflict state.
- [x] World UUID to shard/dimension projection registry.
- [x] O(1) world location to island slot resolver.
- [x] SQLite connection factory using WAL-compatible configuration.
- [x] Checksummed, atomic SQL migrations.
- [x] `BEGIN IMMEDIATE` write transactions with bounded `SQLITE_BUSY` retry.
- [x] Concurrent, idempotent SQLite slot allocation.
- [x] Lowest-ordinal reusable slot selection.
- [x] SQLite schema for slots, operations, islands, and active memberships.
- [x] Atomic island identity, owner membership, operation, and slot creation.
- [x] Durable creation transitions:
  - `ALLOCATING + RESERVED`;
  - `CREATING + PREPARING`;
  - `ACTIVE + ACTIVE`.
- [x] Optimistic island and slot version checks.
- [x] Startup query for pending island creations.
- [x] Startup SQLite snapshot loader for all non-`FREE` slots.
- [x] Atomic construction of a fresh runtime locator projection.
- [x] Paper/Folia-neutral scheduler contracts.
- [x] Paper global, region, async, and entity scheduler adapters.
- [x] Void chunk generator with all Vanilla generation phases disabled.
- [x] Shared void-world specification, validation, creation, and verification adapters.
- [x] Reproducible Shadow JAR with Paper metadata, SQLite runtime, and artifact-content tests.
- [x] Paper `JavaPlugin` composition root with a thread-safe, fail-closed runtime lifecycle gate.
- [x] Versioned commented foundation configuration with safe YAML parsing, typed immutable snapshots,
  strict unknown-field/cross-field validation, deterministic fingerprints, and atomic publication.
- [x] Config migration framework with original backups, adjacent-version validation, atomic writes,
  idempotency, and explicit backup restore.
- [x] SQLite world projection catalog with authoritative UUID/environment/config identity, complete
  restart drift diagnostics, verified runtime registry construction, and idempotent optimistic admin
  adoption.
- [x] Asynchronous Paper startup composition that installs and validates config, creates bounded
  executors, migrates SQLite, provisions worlds on the global scheduler, verifies persisted identity,
  rebuilds slot projections, runs a fail-closed recovery gate, and atomically reaches `READY`.
- [x] Bounded graceful shutdown with lane draining, scheduler cancellation, executor teardown, and
  tested startup rollback.
- [x] Shared reference-counted island runtime lifecycle with minimal immutable headers, deterministic
  chunk coverage, bounded complete-set ticket acquisition, partial rollback, loaded-chunk metrics,
  and no per-island repeating tasks.
- [x] Paper plugin-ticket adapter with owning-region load/verification/mutation, overlapping lease
  reference counts, timeout/late-completion cleanup, and disable-safe emergency release.
- [x] Crash-recoverable world preparation contracts with immutable fingerprinted plans, explicit
  idempotency classes, durable-before-dispatch coordination, terminal evidence, cleanup requirements,
  and fail-closed structure-provider isolation.
- [x] SQLite migration V4 and journal for ordered world-effect receipts across restart, including
  intent-conflict detection and database-enforced evidence-state invariants.
- [x] Minimal Paper starter preparation using region-owned exact Vanilla block mutation, thread-safe
  chunk snapshots for asynchronous clean-cell verification, and safe-spawn verification.
- [x] Atomic creation activation projections for primary spawn, initial progression, and the first
  sequence-zero Magic Block, guarded by verified world-effect evidence.
- [x] Complete idempotent `CreateIslandService` success path with bounded island-lane admission,
  reference-counted chunk tickets, durable creation context, exact restart replay, and atomic
  activation.
- [x] Startup recovery now replays persisted `ALLOCATING` and `CREATING` intents from their original
  world/profile/phase/starter/build-height context before publishing `READY`.
- [x] Creation failure policy with durable pre-dispatch abort, `BROKEN/CLEANING` intent, verified
  native block/entity cleanup, safe locator removal, slot reuse after clean evidence, and mandatory
  quarantine after failed or ambiguous cleanup.
- [x] Normalized V7 counter and typed-variable persistence with constrained scopes/types, initial
  island break counters, optimistic versions, and database-enforced value-shape invariants.
- [x] Post-activation delivery that teleports an online owner only for interactive creation, publishes
  an immutable event for interactive and recovered activation, never repeats delivery on operation
  replay, and cannot roll back an already committed `ACTIVE` island.
- [x] Unit and integration tests for concurrency, rollback, restart, idempotency, projection conflicts,
  signed boundaries, scheduler routing, entity retirement, and void-world configuration.

### Partially implemented areas

- [x] Island creation service: success, recovery, cleanup, quarantine, stored replay, initial
  projections, owner teleport, and immutable event publication are complete.
- [x] Crash recovery for the current creation pipeline resumes allocation, preparation, and cleanup;
  it activates only from verified effects, releases only after verified clean evidence, and otherwise
  keeps the slot quarantined.
- [~] Folia support: scheduler adapters exist, but every future listener and integration still needs an
  ownership audit before `folia-supported: true` is safe.
- [~] Island aggregate: creation header, owner membership, primary spawn, initial progression, first
  Magic Block, normalized counters, and typed-variable storage are persisted; broader team roles,
  upgrades, and aggregate mutation services remain.

## Global definition of done

A feature is not complete until all applicable items are satisfied:

- Domain invariants are encoded independently from Bukkit and SQL APIs.
- Application mutation enters the island lane.
- SQL mutation is atomic and uses optimistic expected versions where applicable.
- Critical operations are idempotent by operation ID.
- World/entity work uses the correct ownership scheduler.
- Runtime projections update only after database commit.
- Failure behavior is explicit: retry, rollback, cleanup, quarantine, broken, or manual repair.
- Restart/recovery behavior is covered by a file-database smoke test.
- Concurrent behavior is covered by a race test where relevant.
- Public API exposes immutable data only.
- Javadoc and package boundaries are complete.
- `./gradlew spotlessCheck build --no-daemon` passes locally.
- GitHub Actions passes on the pushed `main` commit.

# Ordered implementation roadmap

## Milestone 1 — Installable Paper composition root (`P0`)

Goal: produce one plugin JAR that can start, validate configuration, initialize infrastructure, and
shut down safely without exposing gameplay early.

### Packaging

- [x] Add the Shadow plugin and build one distributable Paper JAR.
- [x] Include the SQLite JDBC driver in the distributable artifact.
- [x] Add `paper-plugin.yml` or `plugin.yml` with:
  - plugin name `OpenOneBlock`;
  - namespace `openoneblock`;
  - main class;
  - Java/API version;
  - optional dependencies declared without hard linkage;
  - commands only after command services exist.
- [x] Keep `folia-supported` disabled until the complete event and integration audit passes.
- [x] Add artifact-name and version metadata.
- [x] Add a build test that inspects the final JAR for required classes and metadata.

### Plugin lifecycle

- [x] Add the `JavaPlugin` composition root.
- [x] Define explicit startup states:
  - `BOOTSTRAPPING`;
  - `RECOVERING`;
  - `READY`;
  - `DEGRADED`;
  - `SHUTTING_DOWN`;
  - `STOPPED`.
- [~] Reject commands and gameplay events until state is `READY`; the lifecycle gate is implemented,
  while command and listener adapters do not exist yet.
- [x] Create bounded executors for SQL and non-Minecraft computation.
- [x] Initialize schema migrations before repositories.
- [x] Provision configured worlds through the global scheduler.
- [x] Load slot locator snapshot before listeners.
- [~] Run crash recovery before enabling commands or gameplay; the startup gate blocks unfinished
  creations until the recovery decision engine is implemented.
- [~] Register listeners and commands last; startup ordering is enforced, but no listener or command
  service exists yet.
- [~] On shutdown:
  - stop accepting new lane work;
  - drain accepted lanes with a timeout;
  - flush critical persistence work once the write-behind queue exists;
  - cancel plugin-owned scheduler tasks;
  - release chunk tickets once the runtime ticket manager exists;
  - close executors and database resources.
- [x] Add startup rollback so a partial enable does not leave listeners or executors active.

### Acceptance tests

- [x] Valid empty data directory reaches `READY`.
- [x] Invalid config prevents world and repository activation.
- [x] Migration failure prevents listener registration.
- [x] Shutdown while lanes are active drains or reports timed-out operations.
- [x] Re-enable/restart does not duplicate listeners, worlds, or runtime projections.

## Milestone 2 — Versioned configuration system (`P0`)

Goal: reject unsafe configuration before worlds or gameplay start.

### Configuration files

- [x] Create default `config.yml`.
- [x] Create `worlds.yml`.
- [x] Create `islands.yml`.
- [x] Create `roles.yml`.
- [x] Create `messages.yml`.
- [x] Create directories for `phases/`, `rules/`, `structures/`, `loot/`, `profiles/`, and
  `integrations/`.
- [x] Require `schema-version` in every managed file.
- [x] Preserve comments and generate example files where practical.

### Loader and validation

- [x] Build typed immutable config records.
- [x] Reject unknown fields instead of silently ignoring them.
- [x] Report file, path, expected type, actual value, and remediation for each error.
- [~] Validate all namespaced IDs and cross-file references; ID syntax and role references are
  validated, while phase/profile existence waits for their registries.
- [x] Validate shard grid geometry before world provisioning.
- [x] Validate world-name uniqueness and shard/dimension uniqueness.
- [x] Validate build heights against target worlds after provisioning.
- [x] Validate role inheritance without cycles.
- [x] Add a config migration framework.
- [x] Write backups before modifying old config files.
- [x] Make reload parse and validate into a candidate snapshot before atomic publication.

### Initial configuration model

- [x] Database type, file, busy timeout, retry policy, and queue limits.
- [x] Shard groups and dimension world names.
- [x] Cell size, initial border, maximum border, and safety gap.
- [x] World seeds and environments.
- [x] Allowed build-height range.
- [x] Creation/reset/delete policies.
- [x] Starter Magic Block material and regeneration delay.
- [x] Default phase/profile IDs.
- [x] Message locale and formatting policy.
- [x] Debug, audit, and metrics options.

### Acceptance tests

- [x] Default config parses and round-trips.
- [x] Invalid geometry fails before server world access.
- [x] Duplicate shard/dimension world mapping is rejected.
- [x] Unknown and obsolete fields produce validation errors.
- [x] Failed reload preserves the active config snapshot.
- [x] Config migrations are idempotent and recoverable from backup.

## Milestone 3 — Persisted world projection catalog (`P0`)

Goal: detect database/world identity drift instead of trusting names after restart.

- [x] Add SQL migration for `world_projections`:
  - shard group ID;
  - dimension ID;
  - configured world name;
  - actual world UUID;
  - environment;
  - geometry/config fingerprint;
  - state and version;
  - created/updated timestamps.
- [x] Persist the world UUID after first successful provisioning.
- [x] On restart, compare loaded world UUID and environment with persisted identity.
- [x] Fail closed if a configured name points to a replacement or copied world.
- [x] Require an explicit admin repair/adopt operation for identity changes.
- [x] Build `WorldProjectionRegistry` from verified persisted projections.
- [x] Ensure every enabled dimension in a shard resolves the same logical slot grid.
- [x] Add diagnostics for missing worlds, duplicate UUIDs, and geometry fingerprint drift.

### Acceptance tests

- [x] First catalog startup records all configured projections atomically.
- [x] Normal restart reuses the same UUIDs.
- [x] Replacing a world folder is detected and blocks gameplay.
- [x] Two dimensions with one UUID are rejected.
- [x] Config geometry drift requires an explicit migration plan.

## Milestone 4 — Chunk tickets and island runtime lifecycle (`P0`)

Goal: world operations can temporarily keep the required chunks active without permanent offline
cost.

### Runtime model

- [x] Implement island runtime states:
  - `UNLOADED`;
  - `PREPARING`;
  - `ACTIVE`;
  - `IDLE`;
  - `UNLOADING`.
- [x] Separate durable island state from transient runtime state.
- [x] Keep only minimal immutable headers for offline islands.
- [x] Add reference-counted activity reasons:
  - online player;
  - world preparation;
  - cleanup;
  - Magic Block regeneration;
  - scheduled world action;
  - admin inspection requiring chunks.
- [x] Add one shared runtime manager, not one repeating task per island.

### Chunk ownership

- [x] Calculate affected chunks from reserved/full-cell bounds.
- [x] Acquire Paper plugin chunk tickets on the owning region.
- [x] Verify every requested chunk load result.
- [x] Release all tickets on success, rollback, cancellation, timeout, and shutdown.
- [x] Never load a chunk to perform location lookup or read island metadata.
- [x] Add timeouts and operation-level diagnostics.
- [x] Track loaded island chunks as a metric.

### Acceptance tests

- [x] Offline island retains no tickets or repeating tasks.
- [x] Two activity reasons share tickets and release only after both complete.
- [x] Failed chunk preparation releases already-acquired tickets.
- [x] Shutdown releases all plugin tickets.
- [x] World effects are dispatched to every owning region without cross-thread mutable state.

## Milestone 5 — Island world-preparation ports (`P0`)

Goal: define the crash-recoverable application boundary for clear, starter content, spawn, and Magic
Block setup before depending on WorldEdit.

- [x] Define immutable world-operation plans.
- [x] Define `IslandWorldPreparation` port.
- [x] Define `IslandCleanup` port.
- [x] Define verification results and durable effect receipts.
- [x] Record effect IDs before dispatching world work.
- [x] Make safe effects idempotent by operation/effect ID.
- [x] Distinguish:
  - not started;
  - dispatched;
  - verified success;
  - verified failure;
  - ambiguous outcome.
- [x] Do not automatically retry ambiguous container, entity, command, or reward effects.
- [x] Add a minimal starter implementation that can:
  - verify a clean cell;
  - place a configured Vanilla Magic Block;
  - establish a safe spawn location;
  - verify both locations remain inside the reserved region.
- [x] Keep structure placement behind a port for later WorldEdit implementation.

### Acceptance tests

- [x] Preparation only runs while island/slot are `CREATING/PREPARING`.
- [x] Every block mutation executes on its owning region.
- [x] Partial multi-region failure returns durable cleanup requirements.
- [x] Replaying verified effects does not place duplicates.
- [x] Spawn and Magic Block outside the reserved region are rejected.

## Milestone 6 — Island creation application service (`P0`)

Goal: complete `/ob create` semantics from membership validation through activation.

- [x] Add `CreateIslandService` running inside the island lane after identity creation.
- [x] Accept caller-supplied clock, island ID, operation ID, owner ID, and selected profile.
- [x] Atomically allocate island, owner membership, slot, and durable operation.
- [x] Transition to `CREATING/PREPARING` before the first world effect.
- [x] Acquire required chunk tickets.
- [x] Clear residue when policy requires it.
- [x] Apply starter content through the preparation port.
- [x] Create the first Magic Block record and world block.
- [x] Persist spawn point, phase, initial counters, and an empty typed-variable set atomically.
- [x] Verify border, spawn, Magic Block, and slot ownership.
- [x] Transition island and slot to `ACTIVE` atomically.
- [x] Publish locator state only after commit.
- [x] Teleport the online owner only after interactive activation.
- [x] Publish immutable `IslandCreatedEvent` after interactive or recovered activation.
- [x] Return stored outcome on duplicate operation ID.

### Failure policy

- [x] Failure before world work archives the island and releases the `RESERVED` slot atomically.
- [x] Failure after world work starts transitions into cleanup.
- [x] Verified cleanup may archive/release.
- [x] Failed or ambiguous cleanup marks island `BROKEN` and slot `QUARANTINED`.
- [x] Never infer `ACTIVE` from blocks being present.

### Acceptance tests

- [x] Two simultaneous creates for one player yield one active membership and no leaked slot.
- [x] Duplicate operation replay returns the same island and skips world work and post-activation
  delivery.
- [x] Restart at every currently durable creation phase resumes the correct next step.
- [x] Paste/place failure cannot activate the island.
- [x] Cleanup uncertainty quarantines the slot.

## Milestone 7 — Commands and minimal player workflow (`P0`)

Goal: make the Foundation usable on a test Paper server.

### Command platform

- [ ] Register `/oneblock` and `/ob` through the supported Paper command lifecycle.
- [ ] Centralize permission nodes and command error mapping.
- [ ] Make command responses asynchronous without blocking the server thread.
- [ ] Add locale-ready message keys instead of hard-coded listener text.
- [ ] Add operation IDs to every mutation command.

### Commands

- [ ] `/ob create`.
- [ ] `/ob home`.
- [ ] `/ob delete` with explicit confirmation token.
- [ ] `/ob reset` with explicit confirmation token.
- [ ] `/ob info`.
- [ ] `/ob help`.
- [ ] Basic admin inspect command.

### Teleport safety

- [ ] Resolve destination through persisted island slot, never Vanilla dimension scaling.
- [ ] Prepare destination chunk through region scheduler.
- [ ] Teleport players through their entity scheduler or supported async teleport API.
- [ ] Verify destination inside current border and safe build height.
- [ ] Provide deterministic fallback when stored home is invalid.

### Acceptance tests

- [ ] Commands reject before plugin `READY`.
- [ ] Console/player sender requirements are explicit.
- [ ] `/ob home` performs no SQL on the region thread.
- [ ] Teleport failure leaves island state unchanged.
- [ ] Confirmation tokens expire and cannot target another island/version.

## Milestone 8 — Native protection engine (`P0`)

Goal: protect every shared-world interaction without WorldGuard and without SQL in event handlers.

### Core query model

- [ ] Add immutable `ProtectionQuery`:
  - actor;
  - action;
  - source location;
  - destination location;
  - target;
  - cause;
  - context snapshot.
- [ ] Add `ALLOW`, `DENY`, and `PASS` decisions with namespaced reason IDs.
- [ ] Add protection policy registry and deterministic priority.
- [ ] Add current-border geometry to runtime island snapshot.
- [ ] Add admin bypass as an explicit final policy, not scattered checks.
- [ ] Add temporary script policies with bounded lifetime.

### Required evaluation order

- [ ] Managed world lookup.
- [ ] Slot/island lookup.
- [ ] Lifecycle gate.
- [ ] Current border check.
- [ ] Membership lookup.
- [ ] Role permission lookup.
- [ ] Source/destination cross-boundary check.
- [ ] Magic Block policy.
- [ ] Temporary script policy.
- [ ] Admin bypass.

### Paper event coverage

- [ ] Block break/place.
- [ ] Bucket fill/empty.
- [ ] Container and redstone interaction.
- [ ] Armor stands, item frames, vehicles, and entity interaction.
- [ ] Entity damage and mob griefing.
- [ ] Ender pearl, chorus fruit, portals, and teleport destinations.
- [ ] Pistons with every moved source/destination block.
- [ ] Fluid flow.
- [ ] Explosions and TNT chains.
- [ ] Fire spread and lightning.
- [ ] Falling blocks and entity block changes.
- [ ] Growth, fade, form, spread, and physics events.
- [ ] Hoppers, dispensers, projectiles, fishing hooks, and vehicles.
- [ ] Cross-island transfer denial for all source/destination mechanics.

### Acceptance tests

- [ ] Event lookup remains O(1) with 100,000 synthetic islands.
- [ ] No event adapter invokes repository or chunk-load APIs.
- [ ] Every non-`ACTIVE` lifecycle state denies gameplay mutation.
- [ ] Current-border boundaries are exact for odd and even sizes.
- [ ] Piston/fluid/explosion tests cover neighboring cells and safety gaps.
- [ ] Conflicted locator cells fail closed.

## Milestone 9 — Team, roles, and memberships (`P1`)

Goal: support configurable island collaboration without hard-coded listener roles.

- [ ] Add durable roles and permissions config registry.
- [ ] Add owner, co-owner, moderator, member, trusted, visitor, and banned defaults.
- [ ] Separate active membership from visitor/trust/ban access records.
- [ ] Add member repository and immutable `MemberView`.
- [ ] Enforce one active island membership per player in every SQL backend.
- [ ] Implement invite, accept, decline, kick, leave, ban, unban, promote, and demote services.
- [ ] Implement atomic ownership transfer.
- [ ] Increment island version for every accepted membership mutation.
- [ ] Route all membership mutations through the island lane.
- [ ] Publish immutable membership events after commit.
- [ ] Add configurable team size and invite expiry.

### Acceptance tests

- [ ] Concurrent invites/accepts cannot create two active memberships.
- [ ] Owner cannot leave without transfer or deletion policy.
- [ ] Transfer updates owner/member rows and version atomically.
- [ ] Role config reload cannot remove permissions into an ambiguous state.
- [ ] No role bypasses lifecycle protection.

## Milestone 10 — Delete, reset, cleanup, and quarantine (`P0`)

Goal: safely release or rebuild cells without ever exposing residue to another island.

### Deletion

- [ ] Revoke gameplay admission first.
- [ ] Transition island to `DELETING` and slot to `CLEANING` atomically.
- [ ] Cancel pending island world work and delayed actions.
- [ ] Remove plugin-created entities and block entities.
- [ ] Clear configured world regions across every dimension projection.
- [ ] Release chunk/plugin tickets.
- [ ] Remove locator ownership only after authoritative transition.
- [ ] Verify no island, migration, operation, or scheduled action references the slot.
- [ ] Archive island and deactivate memberships only after verified cleanup.
- [ ] Transition slot to `FREE` only in the final verified transaction.

### Reset

- [ ] Keep island ID, members, and logical slot.
- [ ] Define resettable and retained data policy.
- [ ] Transition island to `RESETTING` and slot to `PREPARING`.
- [ ] Clear and reapply starter content.
- [ ] Reinitialize phase, counters, variables, upgrades, and Magic Blocks according to policy.
- [ ] Require full activation verification before returning to `ACTIVE`.

### Quarantine and repair

- [ ] Cleanup timeout/failure transitions slot to `QUARANTINED`.
- [ ] Quarantined slots are excluded from allocation.
- [ ] Add explicit cleanup retry operation.
- [ ] Prevent direct `QUARANTINED -> FREE`.
- [ ] Repair enters `LOCKED`, never directly `ACTIVE`.

### Acceptance tests

- [ ] Crash at every cleanup phase resumes without premature reuse.
- [ ] Failed entity cleanup quarantines the slot.
- [ ] Archived island has no primary slot and no active memberships.
- [ ] Reset failure results in `BROKEN`, not `ACTIVE`.
- [ ] Reusable slot selection ignores quarantined and cleaning slots.

## Milestone 11 — Crash recovery and durable operations (`P0`)

Goal: startup can deterministically resume, rollback, clean, or require manual repair.

- [ ] Expand operations schema with:
  - operation kind;
  - durable phase;
  - expected island/slot versions;
  - request fingerprint;
  - outcome/result payload;
  - last verified effect;
  - retry/ambiguity classification;
  - error code and diagnostic context.
- [ ] Add normalized operation-effect receipts.
- [ ] Add startup scanner for every non-terminal operation.
- [ ] Add recovery handlers for create, reset, delete, migration, repair, and critical rewards.
- [ ] Add bounded recovery concurrency across islands while preserving one lane per island.
- [ ] Mark unprovable state `BROKEN` instead of guessing.
- [ ] Persist recovery audit entries.
- [ ] Add admin operation list/show/retry/abort commands.

### Acceptance tests

- [ ] Process termination after each durable phase produces the specified recovery result.
- [ ] Repeated startup recovery is idempotent.
- [ ] Missing world or changed UUID blocks recovery safely.
- [ ] Ambiguous non-idempotent effect requires manual reconciliation.
- [ ] Optimistic conflict unloads the runtime aggregate and prevents gameplay.

## Milestone 12 — Audit log and write strategy (`P0`)

Goal: frequent state remains efficient while critical mutations are durable and traceable.

- [ ] Add normalized audit log table.
- [ ] Include operation ID, island ID, sequence, rule ID, player ID, type, timestamp, and outcome.
- [ ] Add dirty tracking for non-critical runtime state.
- [ ] Add bounded write-behind queue.
- [ ] Batch compatible counter and variable updates.
- [ ] Add queue backpressure and health state.
- [ ] Flush critical mutations immediately:
  - creation/deletion/reset;
  - owner transfer;
  - border/phase transitions;
  - valuable rewards;
  - slot state;
  - persistent `once` rule state.
- [ ] Never acknowledge a critical reward before its durable commit policy is satisfied.
- [ ] Add last persisted sequence and version tracking.

### Acceptance tests

- [ ] High-frequency counters do not issue one SQL write per event.
- [ ] Queue saturation applies backpressure instead of dropping writes.
- [ ] Critical writes bypass normal batching.
- [ ] Restart reconciles runtime sequence with last persisted sequence.

## Milestone 13 — Magic Block engine (`P0`)

Goal: implement the safe high-frequency OneBlock loop with duplicate-sequence protection.

### Domain and persistence

- [ ] Add `MagicBlockId` and immutable `MagicBlockView`.
- [ ] Add multiple named Magic Blocks per island.
- [ ] Persist location, profile, current content, state, sequence, cooldown, and lock state.
- [ ] Enforce unique Magic Block IDs and locations within an island.
- [ ] Validate all locations inside the reserved region.
- [ ] Add operation/sequence uniqueness constraints.

### Break pipeline

- [ ] Resolve location through O(1) locator.
- [ ] Resolve Magic Block through per-island location index.
- [ ] Require island `ACTIVE` and protection `ALLOW`.
- [ ] Enter the island lane.
- [ ] Verify current content and expected sequence.
- [ ] Increment sequence before condition evaluation.
- [ ] Update system counters with `before`, `after`, and `delta`.
- [ ] Build immutable rule context snapshot.
- [ ] Select next content from the phase engine.
- [ ] Build and validate an action plan.
- [ ] Commit state before exposing critical rewards.
- [ ] Execute region-owned regeneration and effects.
- [ ] Publish immutable API event after completion.

### Content types

- [ ] Vanilla block.
- [ ] Empty/delayed regeneration.
- [ ] Chest/loot content.
- [ ] Vanilla mob.
- [ ] Structure content.
- [ ] Command event.
- [ ] Custom block/item/mob extension ports.
- [ ] Script-defined content registration.

### Acceptance tests

- [ ] One sequence cannot issue two rewards under duplicate event delivery.
- [ ] Two simultaneous breaks serialize and produce consecutive sequences.
- [ ] Invalid world content locks or repairs instead of silently advancing.
- [ ] Regeneration runs on the owning region.
- [ ] Offline islands retain no regeneration repeating task.

## Milestone 14 — Counters and typed variables (`P0`)

Goal: support efficient progression and rule snapshots without an unindexed JSON state blob.

- [ ] Add optimized system counters:
  - island total breaks;
  - island phase breaks;
  - material generated counts;
  - player Magic Block breaks;
  - server total breaks.
- [ ] Add typed custom variables:
  - integer;
  - decimal;
  - boolean;
  - string;
  - timestamp;
  - duration.
- [ ] Define scope keys for player, island, server, season, and session.
- [ ] Reject arbitrary Java object values.
- [ ] Include `before`, `after`, and `delta` in every counter event.
- [ ] Apply counter mutations before evaluating event conditions.
- [ ] Ensure every rule in one event sees the same snapshot.
- [ ] Add overflow, precision, and type-conversion policies.

## Milestone 15 — Phase and weighted content engine (`P0`)

Goal: provide efficient default progression without implementing weighted selection as generic scripts.

- [ ] Add versioned phase config format.
- [ ] Add phase registry with atomic reload.
- [ ] Add weighted content pools and deterministic testable RNG injection.
- [ ] Add start/completion requirements.
- [ ] Add milestones, transitions, and fallback behavior.
- [ ] Add branching phase graph validation.
- [ ] Reject missing content providers at compile time.
- [ ] Detect unreachable phases and transition cycles requiring explicit loops.
- [ ] Persist current phase and phase-local counters.
- [ ] Publish phase enter/leave events through immutable views.

### Acceptance tests

- [ ] Weighted distribution simulator matches expected tolerance.
- [ ] Same seeded context produces deterministic selection.
- [ ] Invalid reload keeps the active phase registry.
- [ ] Phase transition is critical and crash recoverable.

## Milestone 16 — OpenOneBlock Rules v1 (`P0`)

Goal: compile structured YAML rules into indexed, deterministic action plans.

### Parsing and compilation

- [ ] Define rule schema version and namespaced IDs.
- [ ] Parse trigger, scope, priority, conditions, actions, and execution policy.
- [ ] Validate extension capabilities during compilation.
- [ ] Compile structured `ALL`, `ANY`, and `NOT` condition trees.
- [ ] Compile basic comparison, counter, variable, phase, role, permission, inventory, money,
  cooldown, time, member-count, material, and content-type conditions.
- [ ] Build trigger indexes so an event never scans every rule.
- [ ] Add static checks for unreachable branches, invalid references, recursive `run-rule`, and action
  conflicts.
- [ ] Atomically swap only a fully valid compiled registry.

### Planning and conflict resolution

- [ ] Conditions read snapshots only.
- [ ] Actions produce plans instead of mutating immediately.
- [ ] Separate state, world, reward, message, delayed, and integration effects.
- [ ] Add explicit priority, lock, override, stop-processing, and conflict-warning semantics.
- [ ] Reject ambiguous “last rule wins” behavior.
- [ ] Add deterministic random/choose planning with injected RNG.
- [ ] Add maximum action and recursion budgets.

### Execution policy

- [ ] `once`.
- [ ] `maximum-runs`.
- [ ] cooldown.
- [ ] per-player, per-island, per-season.
- [ ] reset-on-phase-change.
- [ ] persistent versus session-only.
- [ ] Durable execution state for critical policies.

### Basic actions

- [ ] Give/take item and money.
- [ ] Run command with explicit sender and allowlist policy.
- [ ] Send message, title, sound, and broadcasts.
- [ ] Spawn entity.
- [ ] Set next content.
- [ ] Change phase.
- [ ] Add counter/set variable.
- [ ] Upgrade border.
- [ ] Start cooldown.
- [ ] Delay, random, choose, cancel, lock, and unlock.
- [ ] Run another rule with recursion protection.

### Acceptance tests

- [ ] Indexed dispatch evaluates only candidate rules.
- [ ] All rules in one trigger read the same snapshot.
- [ ] Conflicting `set-next-content` plans resolve explicitly.
- [ ] Persistent `once` survives restart and duplicate trigger delivery.
- [ ] Invalid reload preserves the active registry.

## Milestone 17 — Delayed and scheduled actions (`P1`)

Goal: delayed gameplay survives restart without one repeating task per island.

- [ ] Add durable scheduled actions table.
- [ ] Store due time, scope, island/sequence/rule IDs, payload, state, and retry policy.
- [ ] Add one shared due-action scheduler with bounded batches.
- [ ] Dispatch world/entity actions through ownership schedulers at execution time.
- [ ] Cancel or revalidate actions when island lifecycle/version changes.
- [ ] Separate persistent and session-only delays.
- [ ] Ensure offline islands do not retain chunks until an action is actually due.
- [ ] Recover claimed-but-unfinished actions after crash.

## Milestone 18 — WorldEdit/FAWE structure system (`P0` for public MVP)

Goal: safely paste starter and rule structures through a bounded registry.

- [ ] Add optional WorldEdit dependency bridge.
- [ ] Detect FAWE through compatible WorldEdit APIs without hard dependency.
- [ ] Add namespaced structure registry.
- [ ] Restrict files to configured structure roots; never accept arbitrary rule paths.
- [ ] Load and cache `.schem` metadata.
- [ ] Support rotation and mirror transforms.
- [ ] Validate transformed bounding boxes before paste.
- [ ] Enforce current-border, reserved-region, and full-cell policies.
- [ ] Enforce block-count and X/Y/Z limits.
- [ ] Configure entity and container overwrite policies.
- [ ] Lock island during paste/clear.
- [ ] Produce operation receipts and verification results.
- [ ] Add starter paste, safe reset, and rule `place-structure` action.

### Acceptance tests

- [ ] No transformed schematic can touch an adjacent full cell.
- [ ] Oversized or out-of-height structures fail before world mutation.
- [ ] Failed paste never marks island `ACTIVE`.
- [ ] FAWE absence falls back or disables capability with clear validation output.

## Milestone 19 — Border upgrades and visualization (`P1`)

- [ ] Persist current and maximum border sizes with optimistic versions.
- [ ] Add configurable upgrade paths and costs.
- [ ] Add upgrade requirements extension point.
- [ ] Execute upgrade through island lane and critical transaction.
- [ ] Integrate economy reservation/commit semantics.
- [ ] Update protection immediately after commit.
- [ ] Add non-persistent visualization that does not keep chunks loaded.
- [ ] Add border upgrade trigger and event.
- [ ] Prevent any upgrade beyond reserved region or grid safety gap.

## Milestone 20 — Public API and addon SDK (`P1`)

Goal: extensions add behavior without mutating internal aggregates.

- [ ] Add immutable views:
  - `IslandView`;
  - `MemberView`;
  - `BorderView`;
  - `ProgressionView`;
  - `CounterView`;
  - `MagicBlockView`.
- [ ] Add application service API for border, phase, counter, variable, and scheduled mutations.
- [ ] Add public events with post-commit semantics.
- [ ] Add extension registration lifecycle.
- [ ] Support trigger, condition, action, content, protection, progression, and integration extensions.
- [ ] Require namespaced ID, config schema, validator, compiler, executor, and documentation metadata.
- [ ] Freeze registries at the correct plugin lifecycle phase.
- [ ] Add API compatibility tests and semantic version policy.
- [ ] Publish API artifact separately from the server plugin.

## Milestone 21 — Integrations (`P1`)

### Bridge contracts

- [ ] Economy bridge.
- [ ] Custom block bridge.
- [ ] Custom item bridge.
- [ ] Mob bridge.
- [ ] Placeholder bridge.
- [ ] Quest bridge.
- [ ] Permission bridge.
- [ ] Structure bridge.

### Providers

- [ ] OpenEco.
- [ ] Vault.
- [ ] PlaceholderAPI.
- [ ] ItemsAdder.
- [ ] Oraxen.
- [ ] Nexo.
- [ ] CraftEngine.
- [ ] MythicMobs.

### Capability rules

- [ ] Discover optional plugins without classloading absent APIs.
- [ ] Validate required capability while compiling phases/rules.
- [ ] Disable invalid rule/phase entries with clear diagnostics before gameplay.
- [ ] Define provider priority and explicit config selection.
- [ ] Handle provider disable/reload without corrupting island operations.
- [ ] Treat external non-idempotent operations as critical effects with receipts.

## Milestone 22 — Admin tools (`P1`)

- [ ] `/ob admin inspect`.
- [ ] `/ob admin validate`.
- [ ] `/ob admin lock` and `unlock`.
- [ ] `/ob admin cleanup`.
- [ ] `/ob admin repair`.
- [ ] `/ob admin simulate`.
- [ ] `/ob admin rule test` and `trace`.
- [ ] `/ob admin operation list/show/retry/abort`.
- [ ] `/ob admin diagnostics`.
- [ ] Show island ID, owner, lifecycle, shard, grid, slot state, borders, sequences, chunk tickets,
  pending operations, dirty state, and last flush.
- [ ] Add structured rule trace explaining trigger, each condition, conflict resolution, and planned
  actions.
- [ ] Make every destructive admin command require explicit island ID/version confirmation.

## Milestone 23 — Observability (`P1`)

- [ ] Structured logs with operation, island, sequence, rule, and player IDs.
- [ ] Metrics for active islands and loaded island chunks.
- [ ] Magic Block operations per second and processing latency.
- [ ] Rule evaluations, matches, conflicts, and failures.
- [ ] SQL queue depth, latency, retries, and failures.
- [ ] Paste/cleanup duration and cleanup failures.
- [ ] Protection denial counts by reason.
- [ ] Scheduler dispatch latency and region/entity retirement failures.
- [ ] Startup recovery counts and unresolved broken islands.
- [ ] Redact secrets and command-sensitive values from logs.
- [ ] Add health summary used by diagnostics command.

## Milestone 24 — MySQL/MariaDB backend (`P2`)

- [ ] Extract SQL dialect-independent repository tests.
- [ ] Add connection pool with bounded sizing and health checks.
- [ ] Implement schema migrations for MySQL/MariaDB.
- [ ] Use indexed locking reads without `SKIP LOCKED` for deterministic allocation.
- [ ] Preserve SQLite and MySQL operation/idempotency semantics.
- [ ] Add backend-specific uniqueness and partial-index equivalents.
- [ ] Add Testcontainers integration tests.
- [ ] Add SQLite-to-MySQL migration/export tooling.
- [ ] Keep Redis optional and never authoritative.

## Milestone 25 — Production hardening (`P2`)

- [ ] Complete Folia ownership audit and enable metadata only after all checks pass.
- [ ] Add long-duration soak tests.
- [ ] Add 100k/1M island synthetic locator and repository benchmarks.
- [ ] Stress concurrent creates, breaks, upgrades, resets, and deletes.
- [ ] Fault-inject SQL busy, disconnect, disk-full, and executor rejection.
- [ ] Fault-inject chunk load, paste, teleport, and external integration failures.
- [ ] Add migration compatibility matrix.
- [ ] Add config migration compatibility fixtures.
- [ ] Add backup/export/import and repair tools.
- [ ] Add dependency and license checks.
- [ ] Add release signing, changelog, and reproducible artifact verification.
- [ ] Document supported Paper/Folia and integration versions.

# Module-specific backlog

## `openoneblock-api`

- [ ] Immutable island/member/border/progression/counter/Magic Block views.
- [ ] Public post-commit events.
- [ ] Extension registration contracts.
- [ ] Application service interfaces safe for addons.
- [ ] API version and compatibility annotations.

## `openoneblock-core`

- [ ] Full island aggregate and snapshot/delta model.
- [ ] Membership and role domain.
- [x] Runtime/chunk lifecycle manager.
- [x] World preparation plans, effect semantics, and minimal starter coordinator.
- [ ] Creation/reset/delete/migration coordinators.
- [ ] Magic Block and content engine.
- [ ] Counters, variables, upgrades, phases, and requirements.
- [ ] Slot cleanup/removal publication semantics.
- [ ] Border upgrade domain and invariants.

## `openoneblock-paper`

- [x] Installable composition root and plugin metadata.
- [x] Typed config bootstrap.
- [x] World provisioning at startup.
- [x] Chunk ticket adapter.
- [x] Minimal region-owned Vanilla starter preparation adapter.
- [ ] Commands and messages.
- [ ] Teleport and entity adapters.
- [ ] Gameplay listeners using protection/application services only.
- [ ] Live server smoke-test harness.

## `openoneblock-scripting`

- [ ] Rule schema, parser, validator, compiler, indexes, planner, executor, and debugger.
- [ ] Built-in triggers, conditions, actions, policies, and execution state.
- [ ] Atomic registry reload and trace output.

## `openoneblock-protection`

- [ ] Query/decision model and policy pipeline.
- [ ] Membership/role/current-border policies.
- [ ] Cross-island source/destination policies.
- [ ] Magic Block and script policies.
- [ ] Paper event adapters and exhaustive mechanical/environmental coverage.

## `openoneblock-persistence-sql`

- [x] World projection catalog.
- [x] Durable world-effect receipt journal.
- [ ] Full island/member/Magic Block repositories.
- [ ] Counters and typed variables.
- [ ] Rule execution state and scheduled actions.
- [ ] Audit logs.
- [ ] Write-behind queue and critical flush API.
- [ ] Cleanup, recovery, and migration repositories.
- [ ] MySQL/MariaDB dialect.

## `openoneblock-structures-worldedit`

- [ ] Optional WorldEdit/FAWE detection.
- [ ] Structure registry and metadata cache.
- [ ] Bounding/transform validation.
- [ ] Paste, clear, verification, and operation receipts.

## `openoneblock-integrations`

- [ ] Capability registry and provider lifecycle.
- [ ] Economy, placeholders, custom blocks/items/mobs, quests, and permissions.
- [ ] Compile-time rule/phase capability validation.

## `openoneblock-admin-tools`

- [ ] Inspectors, validators, diagnostics, repair, operation tooling, simulator, and rule trace.
- [ ] Safe output pagination and export formats.

# Planned SQL migrations

Migration numbering must remain append-only and checksummed.

- [x] V1: shard allocators, slots, and operations.
- [x] V2: islands and active memberships.
- [x] V3: persisted world projections and geometry fingerprints.
- [x] V4: durable world-effect plans, dispatch evidence, outcomes, and recovery indexes.
- [x] V5: operation request fingerprints/outcomes, durable creation replay context, island spawn
  points, initial progression, and lifecycle lock metadata.
- [x] V6: Magic Blocks and sequence uniqueness.
- [x] V7: normalized counters and typed variables.
- [ ] V8: phases, upgrades, and progression state.
- [ ] V9: rule execution policies and cooldown state.
- [ ] V10: durable scheduled actions.
- [ ] V11: audit logs and retention metadata.
- [ ] V12: structure/paste/cleanup operation receipts.
- [ ] Later migrations: team invites/access records, backup references, season state, and backend
  compatibility metadata.

# Test and verification strategy

## Unit tests

- Pure geometry, transition policies, condition trees, conflict resolution, and serializers.
- No Bukkit or SQL dependency for domain rules.
- Inject clocks and RNG; do not depend on wall-clock sleeps for correctness.

## SQLite integration tests

- Use real temporary file databases, WAL, restart, and concurrent executors.
- Verify rollback by querying every affected table.
- Verify idempotency by operation ID and sequence.
- Verify migration checksum drift and append-only behavior.
- Test busy retry at connection initialization and transaction acquisition.

## Paper adapter tests

- Verify correct global/region/entity/async scheduler routing.
- Verify no world mutation executes before ownership dispatch.
- Verify void-world creator options and existing-world fail-closed checks.
- Latest manual smoke: Paper 1.21.11 build 132 on Java 21, an existing foundation database migrated
  from V6 through V7, restart reused the persisted world projection, reached `READY`, and bounded
  shutdown completed without plugin errors.
- Automate the live Paper test-server smoke test before the public alpha release.

## Property and stress tests

- Random grid/border coordinate round trips.
- Spiral bijection across complete rings and overflow limits.
- Concurrent slot allocation and membership races.
- Duplicate Magic Block delivery and reward idempotency.
- Large indexed rule registries and location indexes.
- Repeated startup recovery from every durable phase fixture.

## Required CI gates

- Formatting.
- Java compilation with all warnings reviewed.
- Unit/integration tests.
- Javadoc.
- Final plugin JAR inspection.
- Optional live Paper smoke test.
- Dependency/license/security scan before releases.

# Performance budgets

These budgets should become executable benchmarks or metrics thresholds:

- Location-to-island lookup: O(1), no allocation-heavy scans, no SQL.
- Protection event lookup: O(1) island resolution plus bounded policy chain.
- Magic Block dispatch: indexed candidate rules only.
- Offline island: no chunk ticket, hologram chunk load, repeating task, or full aggregate retention.
- SQL writes: no synchronous write per normal block break.
- Island lane: bounded queue and no thread held while awaiting SQL or region work.
- Scheduled actions: one shared scheduler, not one task per island.
- Startup projection: minimal slot entries only, no chunks or island aggregates.
- Structure operations: bounded blocks/chunks and explicit timeout.

# Security and abuse controls

- [ ] Validate all configured file paths against owned plugin directories.
- [ ] Never allow rules to choose arbitrary structure paths.
- [ ] Define command allowlists/denylists and execution sender policy.
- [ ] Escape or use structured placeholders for player-controlled message values.
- [ ] Bound rule recursion, actions, random choices, and delayed scheduling.
- [ ] Bound YAML size, nesting depth, and collection counts.
- [ ] Bound database queues, executor queues, and per-island lane queues.
- [ ] Rate-limit expensive player and admin commands.
- [ ] Require explicit confirmations for delete, reset, repair, purge, and adoption.
- [ ] Redact database credentials and sensitive command values.
- [ ] Validate addon namespaced IDs and reject duplicate registrations.
- [ ] Fail closed when capabilities disappear during runtime.

# Explicitly deferred (`P3`)

Do not implement these before the public MVP foundation is stable:

- Web editor/OpenOneBlock Studio.
- Redis and cross-server network coordination.
- Marketplace.
- Full JavaScript scripting.
- Auction or shop framework.
- Custom quest framework.
- Custom boss framework.
- Complete season framework.
- Cross-server island transfer.
- Advanced analytics warehouse.

# Immediate next commit queue

The next implementation work should follow this order unless a discovered invariant requires a
design change:

1. `[x] build(paper): produce installable plugin artifact`
   - Shadow JAR;
   - plugin metadata;
   - JAR-content test.
2. `[x] feat(config): add typed versioned foundation configuration`
   - `config.yml` and `worlds.yml`;
   - strict validation;
   - default resources.
3. `[x] feat(persistence): persist verified world projections`
   - migration V3;
   - UUID/config fingerprint verification;
   - restart tests.
4. `[~] feat(paper): add startup composition and readiness gate`
   - migrate DB;
   - provision worlds;
   - rebuild locator;
   - run recovery;
   - safe shutdown.
5. `[x] feat(core): add chunk activity and preparation contracts`
   - reference-counted reasons;
   - ticket ownership;
   - region-dispatched preparation.
6. `[ ] feat(core): coordinate crash-safe island creation`
   - durable stages;
   - minimal starter block/spawn;
   - cleanup/quarantine policy.
7. `[ ] feat(paper): add create and home commands`
   - readiness/permission checks;
   - entity-safe teleport;
   - end-to-end live smoke test.
8. `[ ]` Begin native protection only after the end-to-end create/home path is stable.

# Public alpha exit criteria

The first public alpha is ready only when all of the following are true:

- Installable JAR starts on a clean Paper server.
- Shared overworld is created as a verified void world.
- SQLite migrations and startup recovery complete before gameplay.
- Players can create, visit home, reset, and delete islands.
- Slot cleanup/quarantine prevents residue reuse.
- Native protection covers required player, mechanical, and environmental events.
- Magic Block sequence processing is duplicate-safe.
- Counters and weighted phases work.
- Rules v1 supports basic indexed triggers, structured conditions, actions, priority, once, cooldown,
  conflicts, and atomic reload.
- Starter WorldEdit schematic paste is bounded and verified.
- Basic owner/member behavior works.
- Admin inspect, validate, diagnostics, and operation inspection exist.
- Crash/restart smoke tests pass for creation, Magic Block sequence, reward, reset, and deletion.
- Offline islands retain no unreasonable chunks or repeating tasks.
- Full CI and a live Paper smoke test pass on the release artifact.
