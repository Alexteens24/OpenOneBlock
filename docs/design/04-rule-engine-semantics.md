# Rule Engine Execution Semantics

- Status: Accepted
- Specification version: 1

## Purpose

This specification defines deterministic rule compilation, evaluation, planning, conflict handling,
execution policy, delayed actions, extension contracts, reload behavior, and debugging. YAML syntax
examples are informative until the Rules v1 schema is introduced; these semantics are normative.

## Rule model

A rule has a namespaced ID, trigger type, scope, priority, condition tree, ordered actions, execution
policy, conflict declarations, and schema version. Rules never contain arbitrary file paths or Java
objects.

Supported scopes are `PLAYER`, `ISLAND`, `SERVER`, `SEASON`, and `SESSION`. A scope resolves to one
stable subject key for execution-state storage. If a trigger cannot provide the requested subject,
the compiler rejects the rule.

Rule evaluation is a pure decision phase:

```text
Trigger + immutable context snapshot + compiled registry
    -> matched atomic rule plans
    -> resolved operation plan
```

Actions do not execute while conditions are evaluated.

## Compilation and registry lifecycle

Reload builds an entirely new candidate registry:

1. Discover configured rule resources through the registry, never arbitrary paths from a rule.
2. Parse YAML with its declared schema version.
3. Validate IDs, trigger/scope compatibility, condition and action schemas, and value ranges.
4. Validate required economy, content, mob, structure, permission, and addon capabilities.
5. Compile conditions and actions into typed immutable nodes.
6. Resolve `run-rule` references and reject static cycles.
7. Build trigger and selector indexes.
8. Run static conflict, reachability, and execution-policy checks.
9. Atomically swap the registry only when there are no errors.

Warnings do not block activation; errors do. A failed reload leaves the previous registry instance and
version active. On first startup, an invalid registry enters admin-safe mode: diagnostics and repair
interfaces remain available, but rule-driven gameplay does not start.

Each registry has a unique version. An operation captures one registry version before evaluation and
uses it through planning. Reload never changes an in-flight plan.

## Indexing and deterministic order

The registry MUST NOT scan every rule for a hot trigger. It indexes first by trigger ID, then by
trigger-defined selectors such as phase, Magic Block profile, material, or content type. Selectors are
conservative: an index may return extra candidates but must never omit a potentially matching rule.

Candidate order is:

1. priority descending;
2. namespaced rule ID ascending by Unicode code-point order.

File name, YAML document order, map iteration order, registration timing, thread timing, and addon
load order MUST NOT affect the result.

## Context snapshot and conditions

All rules for one trigger read the same immutable snapshot. Counter changes include `before`, `after`,
and `delta`; Magic Block counters are incremented in the proposed event state before conditions run.

Condition nodes are `ALL`, `ANY`, `NOT`, or a typed leaf. They may read only values exposed by their
compiled context contract. A condition MUST NOT:

- mutate domain or provider state;
- query the database or load chunks;
- execute commands, schedule work, or send messages;
- depend on system time, global randomness, or mutable iteration order.

Time conditions read the event timestamp supplied by the snapshot. Economy, inventory, permission,
and capability-dependent values are captured before evaluation through bounded snapshot providers.
A required provider failure aborts the entire trigger operation before any rule plan is accepted; it
never silently substitutes zero, empty, or false.

## Deterministic randomness

Chance and random-choice nodes use algorithm `openoneblock:splitmix64-v1`. Its seed material is a
length-prefixed UTF-8 encoding of operation ID, optional Magic Block sequence, rule ID, and condition/
action path. SHA-256 hashes those bytes; the first eight digest bytes in big-endian order seed the
published SplitMix64 transition. A chance value uses the upper 53 output bits divided by `2^53`,
yielding `[0,1)`. Each node path receives an independent seed.

```text
state = state + 0x9E3779B97F4A7C15
z = state
z = (z xor (z >>> 30)) * 0xBF58476D1CE4E5B9
z = (z xor (z >>> 27)) * 0x94D049BB133111EB
output = z xor (z >>> 31)
```

All operations use unsigned 64-bit wraparound semantics.

Implementations MUST NOT use Java `hashCode`, collection order, `ThreadLocalRandom`, or a shared
`Random` instance. The canonical encoding and PRNG algorithm ID are stored with durable random plans.

Replay with the same registry version and canonical context MUST produce the same choices. Simulator
and debugger use the same algorithm as runtime. A future algorithm change requires a new algorithm
version and does not reinterpret already durable plans.

## Atomic rule plans

Each matched rule compiles its ordered actions into one atomic rule plan. Planning resolves values,
targets, capabilities, idempotency class, and conflict keys without executing effects.

If any required action cannot compile or plan, the whole rule plan is rejected. If any exclusive
action loses conflict resolution, the whole rule plan is rejected; its rewards, messages, counters,
and delayed actions do not survive independently. Best-effort describes execution failure after a
plan is accepted, not partial planning.

`random` and `choose` select a branch during planning. The selected branch remains part of the
durable concrete plan. `run-rule` inlines the referenced rule's action plan using the same snapshot
and operation; it does not create a new trigger or re-evaluate the referenced rule's conditions.

Static `run-rule` cycles are errors. Runtime nesting, including addon-produced references, has a hard
depth limit of 16; exceeding it rejects the originating atomic rule plan.

## Conflict resolution

Actions declare zero or more conflict keys. Examples include a Magic Block's next content, island
phase, border size, lifecycle lock, variable key, unique structure placement, and trigger default
behavior. Additive counters are mergeable only when their action type explicitly declares the
operation commutative.

Resolution considers all matched rule plans in deterministic order:

1. A `lock` claim prevents every other plan from writing that key.
2. For an exclusive key, the highest-priority contender wins.
3. All plans containing a losing exclusive action are rejected as a whole.
4. If multiple top contenders have equal priority, exactly one must declare a valid explicit override
   for that key; otherwise static configuration fails when provable, or runtime planning aborts the
   entire trigger operation.
5. An override cannot defeat a lock and cannot let a lower-priority plan defeat a higher-priority one.
6. After an accepted rule declares `stop-processing`, lower-priority candidates are not evaluated.

The debugger records contenders, keys, priorities, override/lock decisions, rejected atomic plans,
and final owners. There is no implicit "last action wins" behavior.

The `cancel` action claims the trigger's typed default-behavior key. It is only valid for triggers
whose contract declares cancellable default behavior. It does not roll back already resolved rule
plans or cancel the underlying Paper event directly.

## Execution policy

Execution policy state is addressed by rule ID, registry-compatible policy version, scope, subject,
and optional phase/season epoch.

- `once` permits one durable claim for its policy key.
- `maximum-runs` stores and atomically increments a bounded run count.
- `cooldown` stores the next eligible UTC instant.
- `per-player`, `per-island`, `per-season`, and similar options select the subject/epoch key.
- `reset-on-phase-change` includes the phase epoch in the key; it does not delete audit history.
- `session-only` state is in-memory and intentionally unavailable after restart.

Persistent claims are reserved in the same transaction as the triggering operation intent. A plan
that loses a claim is rejected before effects. Database uniqueness and conditional updates are the
authoritative concurrency guard.

## Action plan and failure classes

The resolved operation plan separates:

- authoritative state mutations;
- required world/entity mutations;
- rewards and external integrations;
- best-effort messages and audiovisual feedback;
- persistent or session delayed actions.

Every action type declares whether it is required or best effort and whether it is naturally,
detectably, provider, or non-idempotent. The Magic Block transaction specification controls durable
execution and reconciliation. An executor cannot downgrade its compiled failure class at runtime.

State mutations are validated as one delta before durable intent. World/integration executors receive
immutable payloads and idempotency keys, not mutable rule contexts.

## Delayed actions

`delay` is persistent unless the rule explicitly declares `session-only`. Planning stores a concrete
compiled sub-plan with:

- scheduled-action ID and parent operation/effect IDs;
- due UTC instant and target scope/subject IDs;
- resolved payload and action executor compatibility version;
- required capabilities and idempotency keys;
- cancellation and lifecycle revalidation policy.

When due, the scheduler creates a new operation in the target island lane or global execution lane.
It revalidates target existence, island lifecycle, capability compatibility, and cancellation state.
It does not re-evaluate the original conditions or capture current config as a replacement plan.

An overdue action executes once as soon as capacity permits. Missing/incompatible executors pause the
action and expose diagnostics; they do not drop or reinterpret it. Session-only actions are cancelled
on shutdown by definition.

Persistent scheduling uses one indexed due-time queue and shared async poll/wakeup mechanism, not one
repeating task per island. World work still dispatches to the owning region/entity scheduler.

## Extension contracts

Every trigger, condition, action, content, or progression extension must provide:

- a globally unique namespaced ID;
- configuration schema and documentation metadata;
- validator and immutable compiler output;
- declared context fields, capabilities, conflict keys, and failure/idempotency class;
- runtime executor compatibility version;
- deterministic debugger representation.

Extension registration occurs before candidate registry compilation. Removing an extension with
pending durable actions causes those actions to pause; it does not deserialize payloads into unknown
Java objects or discard them.

## Debugging and observability

Every evaluation trace includes operation, registry, trigger, rule, actor/subject, and island IDs
where applicable. It records candidate-index selection, condition results, deterministic random
choices, execution-policy claims, conflict decisions, final actions, and validation failures.

Secrets, command credentials, and full sensitive item/provider payloads must be redacted. Debugging
uses the same compiled nodes as runtime; it MUST NOT implement a second evaluator with different
semantics.

## Acceptance vectors

Implementations must cover at least:

1. Reordering files or YAML maps does not change candidate order or result.
2. Every rule reads the same post-counter-increment snapshot.
3. Runtime and simulator produce the same random choice for identical canonical context.
4. A rule losing one exclusive action contributes none of its other actions.
5. Equal-priority exclusive contenders without one override reject the operation.
6. A lock defeats all overrides and produces a traceable rejection.
7. A `once` race produces one durable claim and one accepted plan.
8. A failed reload retains the previous registry and in-flight registry version.
9. A static and a runtime `run-rule` cycle are both rejected at their defined boundary.
10. A persistent delayed action survives restart and does not re-evaluate old conditions.
11. Removing its executor pauses a delayed action without data loss.
12. A missing economy or custom-content capability is reported during compilation.
13. Condition providers cannot mutate state or perform database/world access.
14. `stop-processing` affects only lower-priority candidates after an accepted rule.
