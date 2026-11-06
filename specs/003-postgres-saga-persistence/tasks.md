---
description: "Task list for PostgreSQL Saga Persistence"
---

# Tasks: PostgreSQL Saga Persistence

**Input**: Design documents from `/specs/003-postgres-saga-persistence/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: Included. plan.md's Testing section names JUnit 5 + AssertJ, jqwik properties for the
forward-transition check and idempotent re-save, and a Testcontainers-backed integration suite covering
quickstart.md scenarios 1 to 15. Write each story's tests first and confirm they fail before implementing.
Testcontainers tests need a Docker-compatible container runtime (the team uses Podman; see T032).

**Organization**: Tasks are grouped by user story (spec.md: US1 P1, US2 P1, US3 P2, US4 P2).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 / US2 / US3 / US4, per spec.md
- All paths are relative to the repository root

## Path Conventions

- Core main: `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/spi/`
- Core test: `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/spi/`
- Postgres main: `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/`
- Postgres resources: `saga-forge-storage-postgres/src/main/resources/io/github/gkosharovdev/sagaforge/storage/postgres/`
- Postgres test: `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/`
- No new module. The storage module's runtime dependencies are `saga-forge-core`,
  `org.postgresql:postgresql:42.7.14` (bumped from 42.7.4), and `tools.jackson.core:jackson-databind:3.1.7`
  (research.md §9), both versions managed by Spring Boot 4.1.2. The root `pom.xml` and
  `saga-forge-core/pom.xml` are not modified (Constitution Principle I, plan.md Constraints).
- No existing `core.spi` type changes shape. `saga-forge-core` gains exactly `SagaLifecycleEntry` and
  `SagaLifecycleEventKind`.

---

## Phase 1: Setup

- [x] T001 Update `saga-forge-storage-postgres/pom.xml`:
  - add `tools.jackson.core:jackson-databind` at compile scope (no `<scope>`), pinned through a module-local
    `<jackson.version>3.1.7</jackson.version>` property (research.md §9). It is the module's only new non-test
    dependency;
  - bump `org.postgresql:postgresql` from `42.7.4` to `42.7.14` (the Spring Boot 4.1.2-managed version);
  - add test-scope `org.junit.jupiter:junit-jupiter`, `net.jqwik:jqwik`, and `org.assertj:assertj-core`
    (versions from the root `dependencyManagement`, so no `<version>`), plus
    `org.testcontainers:testcontainers`, `org.testcontainers:testcontainers-junit-jupiter`, and
    `org.testcontainers:testcontainers-postgresql`, pinned through one module-local
    `<testcontainers.version>2.0.5</testcontainers.version>` property (research.md §7; 2.0.5 matches Spring Boot
    4.1.2). Every test entry is `<scope>test</scope>`.
  Keep the `saga-forge-core` entry unchanged, and do not edit the root `pom.xml` or `saga-forge-core/pom.xml`.
  Verify with `mvn -B -pl saga-forge-storage-postgres -am test-compile`.
- [x] T002 Create `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/package-info.java`.
  Its Javadoc says the package holds the Postgres `StorageProvider` and `SagaContextStore` implementations, that the
  host applies `schema.sql`, and that the caller supplies the `javax.sql.DataSource` and its pooling. Also create the
  empty directories `saga-forge-storage-postgres/src/main/resources/io/github/gkosharovdev/sagaforge/storage/postgres/`
  and `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The schema, the JSON codec, the module's unchecked storage exception, and the shared Testcontainers
support that every story's code and tests depend on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [x] T003 [P] Create `saga-forge-storage-postgres/src/main/resources/io/github/gkosharovdev/sagaforge/storage/postgres/schema.sql`
  with the four tables (`saga_instance`, `saga_association`, `saga_context`, `saga_lifecycle_history`), their
  constraints, and `idx_saga_association_lookup`, exactly as in contracts/postgres-schema.md. Statements are
  separated by `;`. `values` is an SQL keyword: if Postgres rejects the unquoted `saga_context.values` column when
  T008 runs, quote it as `"values"` here and in every statement that uses it, and record the change in T033.
- [x] T004 [P] Create `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/UncheckedSqlException.java`:
  public, `extends RuntimeException`, constructor `(String message, SQLException cause)`. Javadoc: every driver
  failure from this module propagates as this type, per the `StorageProvider` and `SagaContextStore` Javadoc and
  contracts/postgres-schema.md "Failure modes".
- [x] T005 [P] Write `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/JsonCodecTest.java`
  against the research.md §9 value set (`String`, `Boolean`, `Integer`, `Long`, finite `Double`, `String`-keyed
  `Map`, `List`, nested), which narrows `SagaContext`'s "any non-structured value" scalar to what Jackson 3 can
  round-trip without type metadata (`.specify/memory/memory.md` G-003-07):
  - round-trips equal for `String`, `Boolean`, `Integer`, a `Long` outside the `int` range, a finite `Double`, a
    nested `Map<String, Object>`, and a `List<Object>`;
  - documented narrowing: a `Long` inside the `int` range decodes as `Integer` (Jackson's untyped default);
  - strings that need escaping (quote, backslash, control characters, non-ASCII) round-trip;
  - decoded maps and lists are unmodifiable at every nesting level;
  - deterministic key order: two equal maps built in different insertion orders encode to identical text;
  - rejected on encode with `IllegalArgumentException` naming the field and the value type: `Instant` (Jackson 3
    would write an ISO-8601 string that decodes as `String`), `BigDecimal` (decodes as `Double`), a record or POJO
    (decodes as `Map`), an enum, `NaN`/`Infinity`, a non-`String` map key, and a `null` value;
  - decoding malformed text throws `IllegalStateException` naming the field, with the `JacksonException` as cause.
- [x] T006 Implement package-private `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/JsonCodec.java`,
  a thin wrapper over one shared `tools.jackson.databind.json.JsonMapper` (research.md §9), with
  `static String encode(String field, Object value)`, `static Object decode(String field, String json)`,
  `static String encodeObject(String field, Map<String, ?> value)`, and
  `static Map<String, Object> decodeObject(String field, String json)`:
  - the mapper is a `static final` built once with `JsonMapper.builder()`, with no default typing and
    `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS` enabled;
  - `encode*` walks the value first and rejects anything outside the T005 value set with
    `IllegalArgumentException` naming the field (and nested key or index);
  - `decode*` reads untyped into `Map<String, Object>`, `List<Object>`, and scalars, and returns deep
    unmodifiable copies;
  - every `tools.jackson.core.JacksonException` is wrapped (`IllegalArgumentException` on encode,
    `IllegalStateException` on decode) with a message naming the field.
  It serves `saga_instance.state`, `saga_instance.steps`, `saga_context.values`, and
  `saga_lifecycle_history.touched_aggregates`; `steps` and `touched_aggregates` are first mapped to plain
  maps and lists by `SagaInstanceRows` (T010). Depends on: T005.
- [x] T007 [P] Create the test helper `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/PostgresTestSupport.java`:
  - one static `PostgreSQLContainer` (image `postgres:16-alpine`), started in a static initializer and shared by
    every test class. Do not use `@Testcontainers`/`@Container`: jqwik properties do not run JUnit Jupiter
    extensions;
  - applies `schema.sql` from the classpath once, after the container starts;
  - `newDataSource()` returns a new `org.postgresql.ds.PGSimpleDataSource` (from the existing driver) on each call,
    so a test can simulate a restart with a new provider on a new `DataSource`;
  - `reset()` runs `TRUNCATE saga_instance CASCADE` and drops every fault trigger;
  - `failOn(String table, String operation)` installs a `BEFORE <operation>` trigger on that table whose function
    raises an exception. This is a real database failure for quickstart scenarios 4 and 10, not a mock;
  - `queryRows(String sql, Object... params)` returns rows as `List<Map<String, Object>>` for direct SQL
    assertions (FR-012).
- [x] T008 Write `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/SchemaTest.java`:
  the schema applies cleanly; the two `CHECK` constraints reject a direct SQL insert of `status = 'COMPLETED'`
  without `outcome` and of `status = 'FAILED'` without `failure_step_name` (FR-011, defense in depth); deleting a
  `saga_instance` row with SQL cascades to `saga_association`, `saga_context`, and `saga_lifecycle_history` rows
  inserted with SQL. Depends on: T003, T007.

**Checkpoint**: `mvn -B -pl saga-forge-storage-postgres -am test` passes `JsonCodecTest` and `SchemaTest`.

---

## Phase 3: User Story 1 - Saga state survives a restart (Priority: P1) 🎯 MVP

**Goal**: `PostgresStorageProvider` saves, finds (by id and by association), and deletes `SagaInstance`s with
every field and invariant preserved (FR-001 to FR-004, FR-011, SC-001).

**Independent Test**: Save an instance with one completed step, open a new `PostgresStorageProvider` on a new
`DataSource` (simulated restart), call `findById`, and get an equal instance (spec US1, quickstart scenarios 1 to 3
and 13).

### Tests for User Story 1

- [x] T009 [P] [US1] Write `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/PostgresStorageProviderTest.java`
  with a nested `RoundTrip` class:
  - scenario 1: a `RUNNING` instance with `state` (scalars from the T005 value set, no `Long` inside the `int`
    range, and a nested map), two associations, one `COMPLETED`
    step with two `touchedAggregates`, and one `NOT_RUN` step is saved, then read by a new provider on
    `PostgresTestSupport.newDataSource()`, and `isEqualTo` the saved instance;
  - scenario 2: `findById` for an unknown id returns `Optional.empty()`;
  - scenario 3: a `COMPLETED` instance (with `outcome`) and `FAILED` instances (one `SagaFailure` with a `null`
    `message`, one with a message) round-trip equal;
  - saving the same `sagaId` again with changed `state`, `associations`, and `status` replaces the row and its
    association rows, so a removed association no longer matches;
  - `findByAssociation` returns only instances with that exact property and value (as in `HarnessStoresTest`:
    `s1` and `s4` match `orderId=o-1`, `s2` and `s3` do not); scenario 13: no match returns an empty list;
  - `delete(sagaId)` removes the instance and its association rows; deleting an unknown id is a no-op;
  - `save(null)`, `findById(null)`, `findByAssociation(null, v)`, `findByAssociation(p, null)`, and `delete(null)`
    throw `IllegalArgumentException`, and the constructor rejects a `null` `DataSource`.
  Build every `Instant` with `truncatedTo(ChronoUnit.MICROS)` (`timestamptz` precision). Depends on: T007.

### Implementation for User Story 1

- [x] T010 [P] [US1] Implement package-private `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/SagaInstanceRows.java`:
  - `steps` to and from JSON in the data-model.md shape
    (`{"<step>": {"status": "...", "touchedAggregates": [{"aggregateType": "...", "aggregateId": "..."}]}}`),
    mapped to plain maps and lists and then encoded by `JsonCodec`, with the `touchedAggregates` list mapping
    reusable by T019;
  - `state` to and from JSON through `JsonCodec`;
  - `SagaFailure` to and from the four `failure_*` columns;
  - `SagaInstance fromRow(ResultSet row, Map<String, String> associations)`;
  - `Instant` to and from `timestamptz` with `setObject(i, OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))`
    and `getObject(i, OffsetDateTime.class).toInstant()`.
  Depends on: T006.
- [x] T011 [US1] Implement `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/PostgresStorageProvider.java`
  (`implements StorageProvider`, constructor `PostgresStorageProvider(DataSource)`, null checks per the
  `StorageProvider` Javadoc):
  - **`save`**: one `Connection` per call with `autoCommit = false`; `INSERT INTO saga_instance ... ON CONFLICT
    (saga_id) DO UPDATE SET ...` with `?::jsonb` for `state` and `steps`; `DELETE FROM saga_association WHERE
    saga_id = ?`, then one batched insert per `associations` entry; commit. On any failure, roll back and rethrow,
    wrapping `SQLException` in `UncheckedSqlException`;
  - **`findById`**: the instance row plus its association rows, or `Optional.empty()`;
  - **`findByAssociation`**: `SELECT ... FROM saga_instance i JOIN saga_association a ON a.saga_id = i.saga_id
    WHERE a.association_property = ? AND a.association_value = ?` (served by `idx_saga_association_lookup`,
    SC-005), then each match's associations; ordered by `saga_id`; never `null`;
  - **`delete`**: `DELETE FROM saga_instance WHERE saga_id = ?`; the cascade removes associations, context, and
    history (data-model.md).
  History derivation comes in T019 and the forward-transition check in T025. Depends on: T004, T010.

**Checkpoint**: T009 passes; instance state survives a simulated restart.

---

## Phase 4: User Story 2 - Business context survives a failed step (Priority: P1)

**Goal**: `PostgresSagaContextStore` keeps `SagaContextRecord`s in `saga_context` through its own `Connection`, so
values survive the rollback of any `saga_instance` write (FR-005, FR-006, SC-002).

**Independent Test**: Save an instance and then its context record (instance first, research.md §8), make a
`PostgresStorageProvider.save` fail and roll back, and read the record unchanged (spec US2, quickstart scenarios
4 to 6 and 15).

### Tests for User Story 2

- [x] T012 [P] [US2] Write `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/PostgresSagaContextStoreTest.java`:
  - scenario 4: save an instance (through `PostgresStorageProvider`, because `saga_context.saga_id` references
    `saga_instance`) and a context record; call `PostgresTestSupport.failOn("saga_instance", "UPDATE")`; a `save()`
    of a changed instance throws; `find` returns the record unchanged and `findById` returns the pre-call instance
    (SC-002);
  - scenario 5: saving the same `sagaId` twice keeps only the latest `values` and `lastModifiedAt`;
  - scenario 6: `find` for a `sagaId` with no record returns `Optional.empty()`;
  - a structured value (nested `Map<String, Object>`) and scalars round-trip equal;
  - `delete(sagaId)` removes the record and keeps the instance; `PostgresStorageProvider.delete(sagaId)` also
    removes the record (scenario 14, context part);
  - `find(null)`, `save(null)`, and `delete(null)` throw `IllegalArgumentException`, and the constructor rejects a
    `null` `DataSource`;
  - scenario 15, write order (research.md §8): `save` of a record for a `sagaId` with no `saga_instance` row
    throws `UncheckedSqlException` (FK violation), and no `saga_context` row exists afterwards.
  Depends on: T007, T011.

### Implementation for User Story 2

- [x] T013 [P] [US2] Implement `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/PostgresSagaContextStore.java`
  (`implements SagaContextStore`, constructor `PostgresSagaContextStore(DataSource)`). Every `find`, `save`, and
  `delete` gets its own `Connection` from the `DataSource`, never accepts or reuses a caller's `Connection`, and
  commits on its own (research.md §6). `save` is `INSERT INTO saga_context ... ON CONFLICT (saga_id) DO UPDATE SET
  values = EXCLUDED.values, last_modified_at = EXCLUDED.last_modified_at`, with `values` encoded by `JsonCodec`.
  `SQLException` is wrapped in `UncheckedSqlException`. The class Javadoc states the write-order rule: the
  instance row must exist before the first `save` for a `sagaId`, otherwise `save` throws
  `UncheckedSqlException` (research.md §8). Depends on: T004, T006.

**Checkpoint**: T012 passes; T009 still passes.

---

## Phase 5: User Story 3 - Reconstructing how a saga got here (Priority: P2)

**Goal**: Every `save()` derives `saga_lifecycle_history` rows from the previous and incoming snapshots and inserts
them in the same transaction as the instance write, using the new storage-agnostic `SagaLifecycleEntry` and
`SagaLifecycleEventKind` core vocabulary (FR-007, FR-008, FR-009, FR-012, FR-013, SC-003).

**Independent Test**: Drive one instance through creation, two one-step saves, and a terminal save; read
`saga_lifecycle_history` with SQL and find `CREATED, STEP_COMPLETED(A), STEP_COMPLETED(B), COMPLETED` in
`sequence_number` order (spec US3, quickstart scenarios 7 to 9).

### Tests for User Story 3

- [x] T014 [P] [US3] Write `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/spi/SagaLifecycleEntryTest.java`
  per contracts/saga-lifecycle-entry.md:
  - a valid entry for each of the four kinds;
  - `IllegalArgumentException` for a `null` `sagaId`, `kind`, `occurredAt`, or `touchedAggregates`, and for a
    negative `sequenceNumber`;
  - `IllegalArgumentException` when `stepName` is present or absent inconsistently with `STEP_COMPLETED`,
    `outcomeStep` with `COMPLETED`, or `failure` with `FAILED`, and for non-empty `touchedAggregates` on any other
    kind;
  - `touchedAggregates` is an unmodifiable defensive copy.
- [x] T015 [P] [US3] Write `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/LifecycleHistoryDerivationTest.java`
  (plain JUnit, no database) for the data-model.md derivation algorithm:
  - no previous snapshot: `CREATED` at the given sequence number with `occurredAt == createdAt`, followed by a
    `COMPLETED` or `FAILED` entry when the first snapshot is already terminal;
  - one step going `NOT_RUN` to `COMPLETED`: one `STEP_COMPLETED` entry with that step's `touchedAggregates` and
    `occurredAt == lastModifiedAt`;
  - two steps completed in one diff: ordered alphabetically by step name (research.md §5 tiebreak);
  - a status change to `COMPLETED` gives an entry with `outcomeStep`, to `FAILED` an entry with `failure`;
    `CREATED` to `RUNNING` gives no entry;
  - identical previous and incoming snapshots give zero entries (FR-009);
  - sequence numbers start at the given value and are consecutive.
- [x] T016 [P] [US3] Write `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/LifecycleHistoryTest.java`
  (Testcontainers; reads `saga_lifecycle_history` only through `PostgresTestSupport.queryRows`, FR-012):
  - scenario 7: four saves give four rows `CREATED, STEP_COMPLETED(A), STEP_COMPLETED(B), COMPLETED` with
    `sequence_number` 0 to 3, each `occurred_at` equal to its snapshot's timestamp, and `outcome_step` on the last;
  - scenario 8: a save that leaves step C `NOT_RUN` gives no row for C;
  - scenario 9: the `FAILED` row's four `failure_*` columns equal `findById(sagaId).get().failure()`;
  - re-saving an unchanged instance adds no row;
  - a test named to document the limitation: one save that completes `zeta` and `alpha` together records `alpha`
    before `zeta`, a tiebreak and not a true-order guarantee (research.md §5);
  - scenario 14, history part: `PostgresStorageProvider.delete(sagaId)` removes the history rows.
  Depends on: T007, T011.

### Implementation for User Story 3

- [x] T017 [P] [US3] Create `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/spi/SagaLifecycleEventKind.java`
  (`CREATED`, `STEP_COMPLETED`, `COMPLETED`, `FAILED`) and
  `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/spi/SagaLifecycleEntry.java` (record
  `sagaId, sequenceNumber, kind, stepName, touchedAggregates, outcomeStep, failure, occurredAt`), with the
  compact-constructor validation of contracts/saga-lifecycle-entry.md and Javadoc that states the
  same-`save()` ordering limitation. JDK and existing core types only; no existing interface gains a method
  (Constitution Principle I, research.md §2). Depends on: T014.
- [x] T018 [US3] Implement package-private `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/LifecycleHistoryDerivation.java`
  with `static List<SagaLifecycleEntry> derive(SagaInstance previousOrNull, SagaInstance incoming, long
  nextSequenceNumber)`, implementing steps 2, 4, and 5 of the data-model.md derivation algorithm. Depends on: T015,
  T017.
- [x] T019 [US3] Extend `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/PostgresStorageProvider.java`
  `save` per data-model.md, all inside the existing transaction (FR-008, FR-013):
  1. `SELECT ... FROM saga_instance WHERE saga_id = ? FOR UPDATE`, rebuilding `previous` with `SagaInstanceRows`
     (or none);
  2. `SELECT COALESCE(MAX(sequence_number) + 1, 0) FROM saga_lifecycle_history WHERE saga_id = ?`;
  3. `LifecycleHistoryDerivation.derive(previous, incoming, next)`;
  4. upsert the instance and replace its associations (T011);
  5. batch-insert one `saga_lifecycle_history` row per entry, with `failure` flattened into the `failure_*`
     columns and `touched_aggregates` mapped by `SagaInstanceRows` and encoded by `JsonCodec`;
  6. commit.
  Depends on: T011, T018.

**Checkpoint**: T014, T015, and T016 pass; T009 and T012 still pass.

---

## Phase 6: User Story 4 - Partial failures don't corrupt state (Priority: P2)

**Goal**: A failed `save()` leaves nothing behind, and two diverging concurrent saves of one instance give one
winner and one `ConcurrentSagaUpdateException` (FR-008, FR-010, SC-004).

**Independent Test**: Inject a failure on the history insert and find the pre-call snapshot unchanged; run two
diverging concurrent saves and see exactly one succeed (spec US4, quickstart scenarios 10 to 12).

### Tests for User Story 4

- [x] T020 [P] [US4] Add a nested `Atomicity` class to `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/PostgresStorageProviderTest.java`
  (scenario 10). With `PostgresTestSupport.failOn("saga_lifecycle_history", "INSERT")`, a `save()` that completes a
  step throws, `findById` returns the pre-call snapshot, and no history row was added. A first `save()` of a new
  `sagaId` under the same fault leaves no `saga_instance` or `saga_association` row. This may already pass once T019
  lands, because T019 uses one transaction; it guards that guarantee. Depends on: T007, T019.
- [x] T021 [P] [US4] Write `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/ConcurrencyControlTest.java`:
  - stale sequential write: from the same `previous`, save "A completed", then save "B completed, A still
    `NOT_RUN`"; the second throws `ConcurrentSagaUpdateException`, and `findById` and the history reflect only the
    first write;
  - scenario 11: `@RepeatedTest(20)`, two threads released together by a `CyclicBarrier`, each with its own
    provider and `DataSource`, saving diverging snapshots. Exactly one returns and the other throws
    `ConcurrentSagaUpdateException`; the persisted instance equals the winner's snapshot and the history holds
    only the winner's entries (SC-004);
  - scenario 12: two identical concurrent snapshots both succeed, and the history gains exactly one
    `STEP_COMPLETED` row;
  - changing a terminal `COMPLETED` or `FAILED` status, or a completed step's `touchedAggregates`, is rejected;
  - two concurrent first saves of the same new `sagaId`: one wins and the other throws
    `ConcurrentSagaUpdateException` (T025).
  Depends on: T007, T019.
- [x] T022 [P] [US4] Write the jqwik property test `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/ForwardTransitionPropertyTest.java`
  (pure, no database) against `LifecycleHistoryDerivation`. Generators cover a fixed set of three or four step
  names, both `StepStatus` values, small `touchedAggregates` lists, and every `SagaStatus`, with `outcome` and
  `failure` set consistently:
  - **Forward transition**: `requireForwardTransition(previous, incoming)` accepts exactly when every previously
    `COMPLETED` step is still `COMPLETED` with equal `touchedAggregates` and a terminal previous status is
    unchanged; otherwise it throws `ConcurrentSagaUpdateException` (contracts/concurrency-control.md).
    **Validates: FR-010, SC-004**
  - **Idempotent re-save**: for any instance `x`, `requireForwardTransition(x, x)` accepts and `derive(x, x, n)`
    is empty. **Validates: FR-009, FR-010**
  Depends on: T018.

### Implementation for User Story 4

- [x] T023 [P] [US4] Create `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/ConcurrentSagaUpdateException.java`:
  public, unchecked, with a `sagaId()` accessor and a message naming the violated rule. It is never referenced
  from `saga-forge-core` (plan.md Complexity Tracking).
- [x] T024 [US4] Add `static void requireForwardTransition(SagaInstance previous, SagaInstance incoming)` to
  `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/LifecycleHistoryDerivation.java`,
  implementing "Forward transition" in contracts/concurrency-control.md and throwing
  `ConcurrentSagaUpdateException`. Depends on: T018, T023.
- [x] T025 [US4] Wire the check into `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/PostgresStorageProvider.java`
  `save`:
  - after the `FOR UPDATE` read and before any write, call `requireForwardTransition` when a previous row exists;
    on violation, roll back and rethrow `ConcurrentSagaUpdateException` unwrapped;
  - close the first-save race the row lock cannot cover (there is no row to lock yet): add
    `RETURNING (xmax = 0) AS inserted` to the upsert, and if no previous row was read but the upsert updated an
    existing row, roll back and throw `ConcurrentSagaUpdateException`.
  Depends on: T019, T024.

**Checkpoint**: T020, T021, and T022 pass; all earlier tests still pass.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Documentation sync (Constitution Principle V), the Principle III gate, dependency checks, and a full
verification run.

- [x] T026 [P] Update `AGENTS.md`:
  - **Current Status**: `saga-forge-storage-postgres` now implements `StorageProvider` and `SagaContextStore`
    (`PostgresStorageProvider`, `PostgresSagaContextStore`, DDL in `schema.sql`), and `saga-forge-core` gains
    `SagaLifecycleEntry`/`SagaLifecycleEventKind`. The "Not implemented" bullet keeps the engine, MySQL and
    MongoDB storage, both transports, and the starter.
  - **Saga Model** (next to the saga-scoped unit-of-work bullet): the decided write-order rule (research.md §8),
    which replaces the old "context rows require the instance row" gap: a `SagaContextRecord` is saved only after
    its `SagaInstance` row exists; the engine's unit of work persists the instance with
    `StorageProvider.save` before the first `SagaContextStore.save`, and a context save for an unknown `sagaId`
    fails with `UncheckedSqlException`.
  - **Module Map**: the Postgres line (JDBC plus Jackson 3 for JSONB).
  - **Known Gaps**: concurrency is a row lock plus a forward-transition check (no version field); steps completed
    in one `save()` are ordered by a name tiebreak; no history query API (FR-012); the host applies the schema
    (no migration tool); `DataSource` and pool wiring belong to the starter. Point to the
    `.specify/memory/memory.md` IDs rather than repeating their detail.
- [x] T027 [P] Update "Status at a glance" in `docs/architecture.md`: the SPI row (Postgres implementations of
  `StorageProvider` and `SagaContextStore` exist), the "Storage providers" row (Postgres implemented and tested;
  MySQL and MongoDB not), and a new row for `SagaLifecycleEntry`/`SagaLifecycleEventKind` (storage-agnostic
  lifecycle history, persisted by Postgres, no query API). Mention lifecycle history in the
  `saga-forge-storage-{postgres,mysql,mongodb}` paragraph of "Module responsibilities".
- [x] T028 [P] Update the `saga-forge-storage-postgres` / `-mysql` / `-mongodb` row of the Modules table in
  `README.md`: Postgres ships `StorageProvider` and `SagaContextStore`; MySQL and MongoDB are still placeholders.
  Leave "Getting started (target shape)" unchanged, because the starter does not exist yet.
- [x] T029 Close the Constitution Principle III gate. Re-read `.kiro/specs/saga-forge-framework/design.md` and
  `.kiro/specs/saga-context-lifecycle/design.md` against the delivered code and confirm that no correctness
  property or Error Handling row changes (plan.md: the new core types are additive, and the existing
  `SagaInstance` invariants are reused as-is). Record the outcome as one line under the III bullet of
  "Post-Phase 1 Constitution Re-Check" in `specs/003-postgres-saga-persistence/plan.md`. If any property is
  affected, stop and raise it rather than editing design.md silently. Depends on: T017, T025.
- [x] T030 [P] In `specs/003-postgres-saga-persistence/spec.md`, mark the Assumptions bullet about "an optimistic
  check (e.g., a version marker)" as "Superseded by research.md §3 (row lock plus forward-transition check)". In
  `specs/003-postgres-saga-persistence/quickstart.md`, replace "SC-? (atomicity)" in scenario 10 with
  "FR-008, FR-013".
- [x] T031 [P] Check Principles I and IV: `git diff` of `pom.xml` and `saga-forge-core/pom.xml` against the branch
  base shows no change; a search of `saga-forge-core/src/main/java` for `java.sql`, `javax.sql`, `org.postgresql`,
  `org.testcontainers`, `tools.jackson`, and `com.fasterxml` finds nothing; the `git diff` of
  `saga-forge-storage-postgres/pom.xml` shows exactly one new non-test dependency
  (`tools.jackson.core:jackson-databind` at `${jackson.version}` = `3.1.7`), the driver bump from `42.7.4` to
  `42.7.14`, and otherwise only test-scope additions.
- [x] T032 Unit tests only. By user decision, Testcontainers testing was given up: no container is started, and
  no test class that uses `PostgresTestSupport` is run (`SchemaTest`, `PostgresStorageProviderTest` with its nested
  `RoundTrip`/`Atomicity`, `PostgresSagaContextStoreTest`, `LifecycleHistoryTest`, `ConcurrencyControlTest`). They
  stay in the source tree, compiled but not run (`.specify/memory/memory.md` G-003-23, G-003-27). At the
  repository root (root `pom.xml`):
  1. `mvn -B clean test-compile` ends in `BUILD SUCCESS`, compiling every test source, the container classes
     included (`clean` because stale IDE classes break incremental builds, G-REPO-02);
  2. `mvn -B test -Dsurefire.failIfNoSpecifiedTests=false
     -Dtest='!SchemaTest,!PostgresStorageProviderTest*,!PostgresSagaContextStoreTest,!LifecycleHistoryTest,!ConcurrencyControlTest'`
     (surefire 3.2.5 exclusion syntax) ends in `BUILD SUCCESS` with zero failures: every `saga-forge-core` test,
     the pure Postgres classes `JsonCodecTest`, `LifecycleHistoryDerivationTest`, and
     `ForwardTransitionPropertyTest`, and the `saga-forge-example` tests. No "Tests run" line names a container
     class, and the log has no "testcontainers" or "Creating container";
  3. `podman ps` afterwards shows no postgres, testcontainers, or ryuk container.
- [x] T033 Walk scenarios 1 to 15 of `specs/003-postgres-saga-persistence/quickstart.md` against the delivered
  tests (each maps to a named test method). Update quickstart.md,
  `specs/003-postgres-saga-persistence/contracts/postgres-schema.md` (for example `values` quoting from T003 and
  the `UncheckedSqlException` name), and `specs/003-postgres-saga-persistence/contracts/concurrency-control.md`
  (the first-save race handling from T025) wherever they drifted.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: depends on Phase 1 and blocks every story. T005 then T006 is the codec chain;
  T003 and T007 then T008 is the schema check.
- **US1 (Phase 3)**: depends on Phase 2 only.
- **US2 (Phase 4)**: the store (T013) depends on Phase 2 only. Its test (T012) also needs T011, because
  `saga_context.saga_id` references `saga_instance`, so the test creates instances through
  `PostgresStorageProvider`.
- **US3 (Phase 5)**: depends on US1 (T011). It does not need US2.
- **US4 (Phase 6)**: depends on US3 (T018, T019), because the forward-transition check reads the same locked
  `previous` row and lives next to the derivation.
- **Polish (Phase 7)**: depends on all stories.

### Within Each User Story

- Tests first, and confirm they fail; then helpers; then the provider or store.
- US1: T009 and T010 in parallel; T010 then T011; T009 passes after T011.
- US2: T012 and T013 in parallel; T012 passes after T013.
- US3: T014 then T017; T015 and T017 then T018; T011 and T018 then T019; T016 passes after T019.
- US4: T023 then T024 then T025; T020 needs T019; T021 passes after T025; T022 passes after T024.

### Parallel Opportunities

- Phase 2: T003, T004, T005, and T007 together after T002.
- US1: T009 and T010 together.
- US2 and US3 can run in parallel once T011 lands (different files).
- US2: T012 and T013 together.
- US3: T014, T015, and T016 together; T017 alongside T015 and T016 once T014 exists.
- US4: T020, T021, T022, and T023 together.
- Polish: T026, T027, T028, T030, and T031 together.

## Parallel Example: User Story 3

```bash
Task: "Write SagaLifecycleEntryTest (T014)"
Task: "Write LifecycleHistoryDerivationTest (T015)"
Task: "Write LifecycleHistoryTest (T016)"
# then
Task: "Create SagaLifecycleEventKind and SagaLifecycleEntry in saga-forge-core (T017)"
# then
Task: "Implement LifecycleHistoryDerivation (T018)"
Task: "Extend PostgresStorageProvider.save with history (T019)"
```

## Parallel Example: User Story 4

```bash
Task: "Add the Atomicity tests to PostgresStorageProviderTest (T020)"
Task: "Write ConcurrencyControlTest (T021)"
Task: "Write ForwardTransitionPropertyTest (T022)"
Task: "Create ConcurrentSagaUpdateException (T023)"
```

## Implementation Strategy

### MVP Scope

US1 alone is the MVP: spec.md calls a working Postgres `StorageProvider` "the minimum viable slice". Stop after
Phase 3 and validate its checkpoint. US2 is also P1 and should land before the module is offered to an engine,
because without it `SagaContext` values have no durable store.

### Incremental Delivery

1. Phases 1 and 2: dependencies, schema, codec, and test support; the build is green.
2. US1: durable instance state (SC-001).
3. US2: durable, rollback-independent context (SC-002).
4. US3: lifecycle history in the same transaction (SC-003, with the documented tiebreak limitation).
5. US4: atomicity and concurrency hardening (SC-004).
6. Polish: docs synced, Principle III gate closed (T029), full build green (T032).

## Notes

- No production engine calls these classes yet; tests drive `save()` directly with evolving snapshots, as
  research.md §1 and §2 describe.
- `JsonCodec`, `SagaInstanceRows`, and `LifecycleHistoryDerivation` are package-private module internals, not a
  public SPI.
- `PostgresTestSupport` owns applying `schema.sql`; the module ships no migration tool (research.md §7).
- Deferred items and known limitations are tracked in `.specify/memory/memory.md`, not as tasks here:
  G-003-01 and G-003-02 (deferred by decision), G-003-03 to G-003-08, and G-ENG-01 (instance-first write order
  for the future engine; 002's harness writes context first).
- Commit after each task or logical group; stop at any checkpoint to validate.
