# Phase 0 Research: PostgreSQL Saga Persistence

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Each section settles one design question the spec left to planning. Format: Decision, Rationale,
Alternatives considered. §1 and §3 explicitly revisit assumptions spec.md marked provisional
("since no explicit mechanism was specified"); they are not re-litigating the Clarifications already
resolved and recorded in spec.md.

## 1. How `save()` satisfies the unit-of-work atomicity contract without a core SPI

**Decision**: `StorageProvider.save(SagaInstance)` remains the only write entry point this feature uses.
`PostgresStorageProvider.save(instance)` opens one JDBC transaction, does everything FR-008/FR-009/FR-013
require inside it (instance-row upsert + derived lifecycle-history inserts, §2 below), and commits or rolls
back that one transaction. There is no new `UnitOfWork`/`TransactionBoundary` type anywhere, in core or in
this module; "the unit of work" from `specs/002-transactional-step-sequence/contracts/saga-unit-of-work.md`
stays exactly what it already is — a behavioral contract the future engine must honor, proven only by
`SagaTestHarness`. This feature's own transaction is a plain JDBC transaction scoped to one `save()` call,
not a new abstraction layered on top of JDBC.

**Rationale**: Matches spec.md's "Revision 2026-11-05" walk-back and `specs/002-.../research.md` §9
("Committing to a transaction SPI before the engine exists risks designing it twice"). A real engine
integration will call `save()` once per committed unit of work with the fully-updated snapshot (consistent
with how `HarnessUnitOfWork.run()` only ever calls `with.storage().save(...)` once, on the rollback path, and
otherwise lets `HarnessSagaLifecycle` hold the in-memory state that a real `StorageProvider` would instead
persist) — so a single-call, single-transaction `save()` is sufficient to fulfil FR-008/FR-009 without this
feature inventing an engine-shaped abstraction.

**Alternatives considered**: A `save(SagaInstance, List<SagaLifecycleEntry>)` overload that lets a future
caller pass pre-computed history entries explicitly (rejected: changes the existing SPI's method shape,
which spec.md's Assumptions rule out, and front-runs a decision — how the engine assembles a list of
transitions per unit of work — that belongs to the engine feature, not this one). A Postgres-module-internal
`UnitOfWork` helper type wrapping `Connection` (rejected: adds a name and a concept with no caller outside
this module; the module's own `save()`/`find()` methods can open and close a `Connection` directly without
naming the pattern).

## 2. Deriving lifecycle-history entries by diffing, not by a new write method

**Decision**: FR-007's "new storage-agnostic vocabulary in `saga-forge-core`" is exactly two new types —
`SagaLifecycleEntry` (record) and `SagaLifecycleEventKind` (enum) — and nothing else. No core interface
gains a method that accepts or returns either type. `PostgresStorageProvider.save(incoming)` derives the
entries itself: inside the transaction from §1, it reads the row currently persisted for `incoming.sagaId()`
(or finds none, on the first save), diffs it against `incoming`, and inserts one `saga_lifecycle_history` row
per transition the diff reveals (data-model.md "Derivation algorithm"). FR-012's "inspectable via direct
data-store access, no programmatic query API" is satisfied the same way: nothing in core or in this module
exposes a `find`/`query` operation over `SagaLifecycleEntry` — only direct SQL against
`saga_lifecycle_history` (contracts/postgres-schema.md).

**Rationale**: Spec.md's User Story 3 Independent Test says to "drive a saga instance through creation, two
step completions, and a terminal outcome; then query the persisted history" — it describes driving the
instance (i.e., calling `save()` repeatedly with evolving snapshots, the only available write surface today)
and reading the database directly, not calling some new recording API. Deriving history from successive
`save()` calls means zero new public methods anywhere, which is the narrowest change that satisfies FR-007:
the two new types are genuinely storage-agnostic (any future backend diffs its own previous/incoming
snapshots the same way and gets the same shape out), while the *mechanism* that produces them stays private
to each storage module, exactly as Constitution Principle IV prefers (no new public surface unless the spec
requires it, and FR-012 explicitly says it does not here).

**Alternatives considered**: A new core SPI, e.g. `SagaLifecycleHistoryStore.record(SagaLifecycleEntry)`
(rejected: nothing in this feature's scope would call it except this module's own `save()`, so making it
public buys nothing now and pre-commits a shape the future engine feature might want to own differently —
same "don't design it twice" reasoning as §1). Having the (not-yet-existing) engine compute entries itself
and pass them to storage (rejected: no engine exists in this feature to do that computation; deferring it
there is exactly the point of the walk-back already recorded in spec.md).

## 3. Concurrency control: row lock + monotonic validation, not a version marker

**Decision**: Supersedes spec.md's Assumptions bullet ("assumed to use an optimistic check, e.g. a version
marker ... since no explicit mechanism was specified"). `PostgresStorageProvider.save(incoming)` takes a
`SELECT ... FOR UPDATE` row lock on `saga_instance` for `incoming.sagaId()` before reading the previously
persisted snapshot, then validates that `incoming` is a strictly forward transition from it (every previously
`COMPLETED` step stays `COMPLETED` with the same `touchedAggregates`; a previously terminal `status`
(`COMPLETED`/`FAILED`) never changes). A validation failure throws a new, unchecked,
`saga-forge-storage-postgres`-scoped `ConcurrentSagaUpdateException` and rolls back the transaction, leaving
the persisted row exactly as the winning writer left it (contracts/concurrency-control.md).

**Rationale**: `SagaInstance` has no version/revision field and this feature's own Assumptions rule out
changing its shape, so a textbook optimistic-CAS ("write succeeds only if the row's version still matches
what the caller last read") is not implementable: `save(SagaInstance)` is handed only the new full snapshot,
never "the version the caller believes is current." A row lock plus a monotonicity check needs no extra
field and no SPI change: it rejects exactly the cases FR-010/SC-004 care about (two processes racing to
persist conflicting updates to the same instance) while legitimate sequential callers — including a single
caller retrying after its own prior successful save — are unaffected, because their next snapshot is always
a forward extension of what is already persisted.

**Alternatives considered**: An optimistic counter stored only inside the Postgres row, compared against a
value threaded through some side channel back to the caller between `findById` and `save` (rejected: no such
channel exists in the `StorageProvider` contract today; inventing one changes the SPI shape, which spec.md
rules out). Relying on Postgres's own `xmin` system column the same way (rejected: same problem — detecting
a conflict this way still requires the caller to echo back the `xmin` it last observed, which `save()`'s
single-argument signature has no place for). Last-write-wins with no detection (rejected: explicitly what
FR-010 and SC-004 forbid).

## 4. Schema shape: normalize associations, flatten failure, JSONB for the rest

**Decision**: Four tables (full DDL in contracts/postgres-schema.md):
- `saga_instance` — one row per `SagaInstance`; `state` and `steps` as `JSONB`; `outcome` and the four
  `SagaFailure` fields as plain nullable columns (not nested JSON); PK `saga_id`.
- `saga_association` — one row per `associations` entry (`saga_id`, `association_property`,
  `association_value`), FK to `saga_instance` with `ON DELETE CASCADE`, composite B-tree index on
  `(association_property, association_value)`.
- `saga_context` — one row per `SagaContextRecord` (`saga_id` PK/FK `ON DELETE CASCADE`, `values JSONB`,
  `last_modified_at`).
- `saga_lifecycle_history` — one row per `SagaLifecycleEntry` (`saga_id` FK `ON DELETE CASCADE`,
  `sequence_number`, `kind`, nullable `step_name`/`outcome_step`/failure columns/`touched_aggregates JSONB`,
  `occurred_at`).

**Rationale**: `findByAssociation(associationProperty, associationValue)` (FR-003) is the lookup an engine
calls on every incoming event to resolve the target instance, so SC-005 ("not the dominant cost of executing
a step") pushes toward an indexed equality lookup rather than a JSONB containment query — a normalized table
with a plain composite index is the more predictable, more easily `EXPLAIN`-able choice. `state` and `steps`
have no fixed shape across saga types (`state` is "serialized fields of the saga class itself"; `steps`'s
key set depends on which `@SagaStep` methods the saga type declares), so JSONB avoids inventing a schema the
framework cannot know in advance — consistent with the existing Javadoc's "serializing value types is the
storage module's concern" for `SagaInstance.state()`. `SagaFailure` has an exactly fixed, small shape
(`stepName`, `causeType`, `message`, `failedAt`), so flattening it is ordinary relational modeling, not a
missed opportunity to generalize. `ON DELETE CASCADE` fulfils spec.md's Assumption that deleting an instance
also removes its context and history — enforced by Postgres itself, so it holds regardless of which Java
class (`PostgresStorageProvider` or `PostgresSagaContextStore`) is asked to delete what, with no cross-class
coupling inside the module.

**Alternatives considered**: A single `associations` `JSONB` column queried with `@>` containment (rejected:
works, but a GIN index on a containment query is harder to reason about and tune than a B-tree equality
index for what is always an exact-match lookup on one property/value pair). Normalizing `steps` into its own
table (rejected: `touchedAggregates` nesting and the per-saga-type variable key set make this needlessly
complex for data this feature never queries by individual step from SQL — only full-instance reads and the
diff in §2 touch it, both happy to deserialize one JSON blob).

## 5. Known limitation: step ordering within a single `save()` call

**Decision**: When the diff in §2 finds more than one step newly `COMPLETED` in the same `save()` call (an
engine may legitimately run several in-process-triggered steps of one saga in a single unit of work, per
`contracts/saga-unit-of-work.md` step 2), the resulting `STEP_COMPLETED` entries are assigned consecutive
`sequence_number`s in ascending alphabetical order of step name — a deterministic, reproducible tiebreak,
**not** a reconstruction of the true order those steps actually ran in.

**Rationale, and why this is a real limitation, not a design preference**: `SagaInstance.steps` is a
`Map<String, StepRecord>`, copied with `Map.copyOf` in its compact constructor; `Map.copyOf` does not
preserve insertion order, and even if it did, `SagaInstance` does not currently carry any per-step
ordering/timestamp signal. Given only "the previous persisted snapshot" and "the incoming snapshot," there is
no information left in the `StorageProvider.save(SagaInstance)` call from which the true in-process run order
of two steps completed in the same unit of work could be recovered — not a gap this module can close without
either a new field on `StepRecord`/`SagaInstance` or a new parameter on `save()`, both of which are shape
changes spec.md's Assumptions place out of this feature's scope. In the common path exercised by this
feature's own tests (one triggering event per unit of work, matching User Story 3's Independent Test), this
limitation never manifests — each `save()` call advances at most one step. It is surfaced here, in writing,
rather than silently assumed away, because SC-003 ("zero out-of-order entries") reads as a stronger guarantee
than this feature can deliver for the multi-step-per-commit case; closing that gap for real is left to
whichever future engine feature decides how `save()` is actually invoked per unit of work.

**Alternatives considered**: Deriving order from `Map` iteration order as returned by `Map.copyOf` (rejected:
explicitly unspecified by the JDK, so "deterministic" would be false — a HashMap-derived order can differ
between JVM versions/runs for the same logical input, which is worse than an honestly-documented, stable
alphabetical tiebreak). Blocking this feature on a `StepRecord`/`SagaInstance` shape change to add ordering
(rejected: out of this feature's stated scope per spec.md Assumptions; also not this feature's call to make
unilaterally, since those types are shared, already-stable core vocabulary used by every future storage
backend and by 002's harness).

## 6. `SagaContextStore`'s separate transaction

**Decision**: `PostgresSagaContextStore.save(record)`/`find`/`delete` use their own JDBC `Connection`
(obtained from the same injected `DataSource` as `PostgresStorageProvider`, but never the same `Connection`
instance), each call committing (or rolling back on failure) independently of any `saga_instance` write in
flight elsewhere.

**Rationale**: This is not a new decision so much as a direct, literal implementation of the existing
`SagaContextStore` Javadoc ("a `save` MUST NOT join the saga-scoped unit of work's transaction... Postgres
and MySQL modules store records in their own table and write them in a separate transaction") and FR-005.
Stated here only to make the concrete mechanism (separate `Connection`, not a nested/savepoint transaction on
the same connection) explicit for data-model.md and the test plan.

**Alternatives considered**: A `SAVEPOINT` within the same connection/transaction as the instance write
(rejected: a rollback to before the savepoint still participates in the same outer transaction and the same
connection-level failure modes — e.g., the whole connection becoming unusable after certain Postgres errors —
which does not meet "survives that unit of work's rollback" as robustly as full connection independence).

## 7. Dependencies: `DataSource`, no pool, no migration tool, Jackson 3 for JSONB, Testcontainers for tests only

**Decision**: `PostgresStorageProvider`/`PostgresSagaContextStore` take a `javax.sql.DataSource` (a JDK
interface already available via `java.sql`, no new dependency) in their constructors; connection pooling is
whatever `DataSource` the caller supplies (the Spring Boot starter feature's job later — see AGENTS.md's
module map). No Flyway/Liquibase dependency is added; the schema (contracts/postgres-schema.md) ships as a
literal DDL script the host applies however it already manages schema, consistent with spec.md's Edge Cases
("schema evolution ... addressed by future migration tooling"). JSONB columns are encoded with
`tools.jackson.core:jackson-databind` `3.1.7`, compile scope in this module only (§9). The Postgres driver is
bumped from `42.7.4` to `42.7.14`, the version Spring Boot 4.1.2 manages. Tests add `testcontainers` +
`testcontainers-junit-jupiter` + `testcontainers-postgresql` (new to the repo) as `saga-forge-storage-postgres`
test-scope-only dependencies (Testcontainers `2.0.5`, which matches Spring Boot 4.1.2); `jqwik` and
`assertj-core` are reused from the root `dependencyManagement` exactly as `saga-forge-core` already uses them.

**Rationale**: Keeps the module's runtime dependency footprint to `saga-forge-core`, the Postgres JDBC
driver, and `jackson-databind` (§9), with both third-party versions aligned to what Spring Boot 4.1.2
manages, so the future starter brings no conflicting versions. All of it stays in this module; the root
`pom.xml` and `saga-forge-core` are untouched (Constitution Principle I), and no new storage or transport
technology is introduced (Principle IV). Testcontainers is the de facto standard way to integration-test a
JDBC module against a real engine without a shared, hand-maintained test database, and was confirmed absent
anywhere else in the repo (greenfield, no conflicting convention to reconcile). It needs a Docker-compatible
container runtime (the team uses Podman).

**Alternatives considered**: Embedded/in-process Postgres (e.g. `pg-embedded`) (rejected: another
new dependency with weaker fidelity to real Postgres locking/JSONB behavior, which §3's row-lock mechanism
specifically needs to exercise faithfully). Requiring a real, externally-provisioned Postgres for CI
(rejected: no CI workflow exists yet in this repo to provision one, and it would make local test runs depend
on out-of-repo setup).

## 8. Write order: instance row before context row (FK kept)

**Decision**: Keep `saga_context.saga_id REFERENCES saga_instance (saga_id) ON DELETE CASCADE`. A
`SagaContextRecord` may only be saved once its `SagaInstance` row exists: the caller (the future engine's
unit of work) MUST persist the instance with `StorageProvider.save` before the first
`SagaContextStore.save` for that `sagaId`. Because `PostgresSagaContextStore` writes on its own
`Connection` (§6), "exists" means committed; `PostgresStorageProvider.save` commits before it returns
(§1), so a context save after `save()` returns always sees the row. A context save for a `sagaId` with no
`saga_instance` row fails with the FK violation, surfaced as the module's `UncheckedSqlException`. That is
the documented, expected failure mode, not something the store works around.

**Rationale**: Referential integrity (no context row can exist for an unknown `sagaId`) and the cascade
(`StorageProvider.delete(sagaId)` removes the context with no coupling between `PostgresStorageProvider`
and `saga_context`, §4, spec.md's orphan-cleanup Assumption). Instance-first costs nothing for a caller that
starts a saga through `SagaLifecycle.start`, which creates the instance before any step runs.

Engine follow-up, not a 003 task: 002's `SagaTestHarness` saves the new instance inside the first unit of
work (`HarnessSagaLifecycle.start`, restored on rollback) while `HarnessSagaContext` writes through to
`InMemorySagaContextStore` immediately. Against Postgres that context write would hit an uncommitted
instance row and fail. The engine must use instance-first ordering (`.specify/memory/memory.md` G-ENG-01).

**Alternatives considered**: Dropping the FK (rejected: allows orphaned context rows and loses the cascade,
so `delete(sagaId)` would either leave orphans or need to know about `saga_context`). A
`DEFERRABLE INITIALLY DEFERRED` FK (rejected: the context write is a separate transaction, §6, so deferring
the check to its commit does not make the uncommitted instance row visible).

## 9. JSON encoding: Jackson 3 (`tools.jackson`)

**Decision**: `saga_instance.state`, `saga_instance.steps`, `saga_context.values`, and
`saga_lifecycle_history.touched_aggregates` are encoded and decoded by a thin package-private `JsonCodec`
over one shared `JsonMapper` built once with the immutable `JsonMapper.builder()`:
- no default typing; decoding is untyped, to `Map<String, Object>`, `List<Object>`, and scalars, returned
  unmodifiable;
- `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS` enabled, so equal maps always encode to identical text and
  idempotent re-save comparison is stable;
- the accepted value set is `String`, `Boolean`, `Integer`, `Long`, finite `Double`, `Map` with `String`
  keys, and `List`, nested. Before encoding, the codec rejects (`IllegalArgumentException` naming the field)
  `null` values, non-`String` keys, `NaN`/`Infinity`, and every other type, including `Instant`,
  `BigDecimal`, records, enums, and POJOs. Without type metadata Jackson would decode those as a different
  type (`Instant` as an ISO-8601 `String`, `BigDecimal` as `Double`, a POJO as a `Map`), so the round-trip
  would not be equal and `SagaContext.getState(name, type)` would miss. Integral numbers decode as `Integer`
  when they fit, else `Long`, so a `Long` in the `int` range comes back as `Integer`
  (`.specify/memory/memory.md` G-003-07);
- Jackson 3 exceptions are unchecked (`tools.jackson.core.JacksonException`); the codec wraps them in an
  exception that names the field or column (`IllegalArgumentException` when encoding,
  `IllegalStateException` when decoding stored text).

Dependency: `tools.jackson.core:jackson-databind`, pinned to exactly `3.1.7` through a module-local
`<jackson.version>` property, compile scope in `saga-forge-storage-postgres` only. `saga-forge-core` stays
zero-dependency (Constitution Principle I) and the root `pom.xml` is not modified.

**Rationale**: Spring Boot 4.1.2 manages Jackson 3.1.7, so the future starter adds no second version.
Jackson 3 needs Java 17+ (the project is on 21), handles `java.time` natively (no jsr310 module), and its
unchecked exceptions fit the "storage failures propagate as unchecked exceptions" SPI Javadoc. Rejecting
non-round-trippable types at write time beats silently storing a value that reads back as another type.

**Alternatives considered**: A hand-written JSON writer/parser (rejected: escaping and number edge cases to
maintain for no gain; the zero-dependency rule applies to `saga-forge-core`, not to storage modules).
Jackson 2.x, `com.fasterxml.jackson` (rejected: Boot 4 defaults to Jackson 3, so 2.x would put two Jackson
lines on a host's classpath). Polymorphic default typing to keep Java types (rejected: security; reading
class names from stored data is a known deserialization-gadget vector, and it ties rows to Java class names).
