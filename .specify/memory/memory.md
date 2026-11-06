# Saga Forge Gap Register

A running register of known gaps, deferred decisions and limitations noticed during work, each to be
addressed later. Agents add an entry whenever they notice one, instead of silently fixing or ignoring it
(AGENTS.md "Agent Working Rules For This Repo"). Durable project rules stay in `AGENTS.md`; entries here
point to them rather than repeating them.

Entry format: `### <ID>: <title>` with **Description**, **Origin** (file/section) and **Status**
(`open`, or `closed` with the closing change). IDs are `G-<feature or area>-NN` and are never reused.

## 003-postgres-saga-persistence

### G-003-01: First-save lifecycle history is incomplete
- **Description**: On an instance's first `save()`, steps already `COMPLETED` get no `STEP_COMPLETED`
  history entry, and `CREATED` is emitted even when the first `status` is `RUNNING`.
- **Origin**: `specs/003-postgres-saga-persistence/data-model.md` "Derivation algorithm" step 2;
  `contracts/saga-lifecycle-entry.md`.
- **Status**: open (explicitly deferred by the user, 2026-11-05).

### G-003-02: Stale writers differing only in `state`/`associations` are not detected
- **Description**: The forward-transition check only looks at completed steps and terminal status, so two
  stale writers whose snapshots differ only in `state` or `associations` both pass and the last write wins.
  FR-010's "diverging attempts" reads broader than this.
- **Origin**: `specs/003-postgres-saga-persistence/contracts/concurrency-control.md` "Forward transition";
  spec.md FR-010.
- **Status**: open (explicitly deferred by the user, 2026-11-05).

### G-003-03: First-save race rejects identical concurrent first saves
- **Description**: With no row to lock on the first save, the `RETURNING (xmax = 0)` check rejects the second
  of two concurrent first saves even when both snapshots are identical, unlike the idempotent re-save that
  quickstart.md scenario 12 accepts for an existing instance.
- **Origin**: `specs/003-postgres-saga-persistence/tasks.md` T021, T025; quickstart.md scenario 12.
- **Status**: open. Implemented as specified by T025 (`PostgresStorageProvider.save`); `ConcurrencyControlTest`
  covers only diverging concurrent first saves, not the identical case.

### G-003-04: Same-save multi-step ordering is a tiebreak
- **Description**: Steps newly completed in one `save()` get `STEP_COMPLETED` entries in alphabetical step-name
  order, not true execution order; `SagaInstance.steps()` carries no ordering signal. SC-003 is only fully met
  for one step per save.
- **Origin**: `specs/003-postgres-saga-persistence/research.md` §5.
- **Status**: open.

### G-003-05: `timestamptz` precision is microseconds
- **Description**: `timestamptz` stores microseconds while `Instant` holds nanoseconds, so SC-001's
  "identical after restart" only holds at microsecond precision. Tests truncate to `MICROS`; the provider does
  not.
- **Origin**: `specs/003-postgres-saga-persistence/tasks.md` T009; spec.md SC-001.
- **Status**: open.

### G-003-06: SC-005 has no runnable check
- **Description**: SC-005 ("not the dominant cost of a saga step") is addressed only structurally (indexed
  association lookup); no test or benchmark measures it.
- **Origin**: `specs/003-postgres-saga-persistence/spec.md` SC-005; plan.md Performance Goals.
- **Status**: open.

### G-003-07: JSON type fidelity for `Object`-typed values
- **Description**: Without polymorphic typing, Jackson cannot restore Java types in `SagaInstance.state` and
  `SagaContextRecord.values`: a `Long` in the `int` range reads back as `Integer`, and `Instant`,
  `BigDecimal`, records, enums and POJOs are rejected on write. That is narrower than `SagaContext`'s "any
  non-structured value" scalar.
- **Origin**: `specs/003-postgres-saga-persistence/research.md` §9; tasks.md T005.
- **Status**: open.

### G-003-08: `values` column name is an SQL keyword
- **Description**: `saga_context.values` uses an SQL keyword as a column name; quote it as `"values"` in the
  DDL and every statement if Postgres rejects it unquoted.
- **Origin**: `specs/003-postgres-saga-persistence/contracts/postgres-schema.md`; tasks.md T003.
- **Status**: closed (T013). Postgres 16 accepts it unquoted in the DDL, an `INSERT` column list and a
  `SELECT` (`SchemaTest`, T008), and in the `ON CONFLICT (saga_id) DO UPDATE SET values = EXCLUDED.values`
  upsert of `PostgresSagaContextStore` (`PostgresSagaContextStoreTest` scenario 5 takes the conflict path).
  `VALUES` is a non-reserved (column-name) keyword in Postgres, so no quoting is needed.

### G-003-09: JSONB cannot store U+0000
- **Description**: Postgres `jsonb` rejects the `\u0000` escape in strings and keys. `JsonCodec` accepts any
  `String`, so a `state`/`values` string or key containing U+0000 is not rejected with
  `IllegalArgumentException` before SQL runs (contracts/postgres-schema.md "Failure modes"); it would fail at
  the database as `UncheckedSqlException`. Not covered by a test.
- **Origin**: `saga-forge-storage-postgres/.../JsonCodec.java`; research.md §9 value set.
- **Status**: open.

### G-003-10: `JsonCodec` has no cycle detection
- **Description**: A self-referential `Map`/`List` in `state` or `values` makes `JsonCodec.encode*` recurse
  until `StackOverflowError` instead of failing with `IllegalArgumentException` naming the field.
- **Origin**: `saga-forge-storage-postgres/.../JsonCodec.java` (T006).
- **Status**: open.

### G-003-11: `testcontainers-junit-jupiter` declared but unused
- **Description**: T001 adds `org.testcontainers:testcontainers-junit-jupiter` (test scope), but T007 forbids
  `@Testcontainers`/`@Container` (jqwik does not run Jupiter extensions), so nothing uses it.
- **Origin**: `saga-forge-storage-postgres/pom.xml`; tasks.md T001, T007.
- **Status**: open.

### G-003-12: `SagaInstance` accepts null identity, status and timestamps
- **Description**: The `SagaInstance` compact constructor does not reject a `null` `sagaId`, `sagaType`,
  `status`, `createdAt` or `lastModifiedAt`. `PostgresStorageProvider.save` rejects them with
  `IllegalArgumentException` naming the field before any SQL runs (otherwise a `null` `status` would surface
  as a `NullPointerException` and a `null` `sagaId` as an `UncheckedSqlException`). Other `StorageProvider`s
  may differ; the core record or the `StorageProvider` Javadoc could make this uniform.
- **Origin**: `saga-forge-core/.../spi/SagaInstance.java`; `saga-forge-storage-postgres/.../SagaInstanceRows.java`
  (T010).
- **Status**: open.

### G-003-13: Re-save can change `sagaType` and `createdAt`
- **Description**: The `save` upsert overwrites every column, including `saga_type` and `created_at`, so a
  re-save of an existing `sagaId` with a different saga type or creation time is accepted. The planned
  forward-transition check (contracts/concurrency-control.md, T024/T025) covers completed steps and terminal
  status only, not these identity fields.
- **Origin**: `saga-forge-storage-postgres/.../PostgresStorageProvider.java` (T011);
  `specs/003-postgres-saga-persistence/contracts/concurrency-control.md`.
- **Status**: open. Confirmed by T024/T025: `requireForwardTransition` does not check `sagaType` or `createdAt`,
  so the gap stands.

### G-003-14: `findByAssociation` order depends on the database collation
- **Description**: `StorageProvider.findByAssociation` defines no order. The Postgres provider orders by
  `saga_id` under the database's default collation, so the order is deterministic per database but can differ
  between databases with different collations (and from the in-memory harness's insertion order).
- **Origin**: `saga-forge-storage-postgres/.../PostgresStorageProvider.java` (T011);
  `saga-forge-core/.../spi/StorageProvider.java`.
- **Status**: open.

### G-003-15: Derivation treats a step missing from the previous snapshot as `NOT_RUN`
- **Description**: data-model.md step 4 only names `NOT_RUN → COMPLETED`. `LifecycleHistoryDerivation` also
  emits `STEP_COMPLETED` for a step that is `COMPLETED` in `incoming` but has no entry in `previous.steps()`
  (decision taken at T018 so a completion is not lost). A step present in `previous` but missing from
  `incoming` produces no entry; whether that is a forward-transition violation is for T024/T025.
- **Origin**: `saga-forge-storage-postgres/.../LifecycleHistoryDerivation.java` (T018); data-model.md
  "Derivation algorithm" step 4.
- **Status**: open (derivation half). The forward-transition half was decided at T024: a step `COMPLETED` in
  `previous` but missing from `incoming` is a violation (`ConcurrentSagaUpdateException`); a `NOT_RUN` step
  missing from `incoming` is accepted. contracts/concurrency-control.md does not state this yet (T033).

### G-003-16: The step-name tiebreak is code-unit order, not locale collation
- **Description**: The G-003-04 tiebreak sorts with `String.compareTo` (UTF-16 code units, case-sensitive, so
  `Zeta` sorts before `alpha`). research.md §5 says "alphabetical" without fixing the comparator; another storage
  module needs the same comparator to give the same order.
- **Origin**: `saga-forge-storage-postgres/.../LifecycleHistoryDerivation.java` (T018); research.md §5.
- **Status**: open.

### G-003-17: Concurrent first saves fail with `UncheckedSqlException` until T025
- **Description**: Two concurrent first saves of one new `sagaId` both find no row to lock and both derive
  `CREATED` at `sequence_number` 0. The second upsert waits for the first, then updates; its history insert then
  violates the `(saga_id, sequence_number)` PK and the save fails as `UncheckedSqlException` (no data is
  corrupted). T025's `RETURNING (xmax = 0)` check is meant to turn this into `ConcurrentSagaUpdateException`.
- **Origin**: `saga-forge-storage-postgres/.../PostgresStorageProvider.java` (T019); tasks.md T021, T025.
- **Status**: closed (T025). When no previous row was read but the upsert's `RETURNING (xmax = 0) AS inserted`
  reports an update, `save` throws `ConcurrentSagaUpdateException` before the history insert and the transaction
  rolls back. Not yet run against a database: T032 must see `ConcurrencyControlTest.concurrentFirstSaves` pass
  (see G-003-22).

### G-003-18: Non-terminal status changes are not recorded in the history
- **Description**: `SagaLifecycleEventKind` has no kind for `RUNNING` or `COMPENSATING`, so a status change to
  either (e.g. `CREATED → RUNNING`, `RUNNING → COMPENSATING`) adds no history row. FR-007 lists only creation,
  step completion and terminal outcomes; compensation semantics belong to a separate spec.
- **Origin**: `saga-forge-core/.../spi/SagaLifecycleEventKind.java` (T017); data-model.md "Derivation
  algorithm" step 5.
- **Status**: open.

### G-003-19: A terminal instance's `outcome`/`failure` can change on re-save
- **Description**: `requireForwardTransition` keeps a terminal status fixed but does not compare `outcome` or
  `failure`, so a `COMPLETED` instance re-saved with a different `outcome` (or a `FAILED` one with a different
  `failure`) is accepted. contracts/concurrency-control.md claims they "can't change" because `SagaInstance`'s
  constructor ties them to the status, but the constructor only ties their presence, not their value. The
  derivation adds no history row for it either (status unchanged).
- **Origin**: `saga-forge-storage-postgres/.../LifecycleHistoryDerivation.java` (T024);
  contracts/concurrency-control.md "Forward transition".
- **Status**: open. The contract's wording should be corrected at T033.

### G-003-20: Non-terminal status order is not enforced
- **Description**: The forward-transition check accepts any non-terminal status change, including regressions
  such as `RUNNING → CREATED` or `COMPENSATING → RUNNING`. The contract only lists forward moves as allowed and
  does not forbid backward ones.
- **Origin**: `saga-forge-storage-postgres/.../LifecycleHistoryDerivation.java` (T024);
  contracts/concurrency-control.md "Forward transition".
- **Status**: open.

### G-003-21: The locked read's associations may be from before the winner's commit
- **Description**: `LOCK_PREVIOUS` reads associations through a scalar subquery on `saga_association` and locks
  only the `saga_instance` row (`FOR UPDATE OF i`). Under `READ COMMITTED`, a save that waited on the lock gets
  the winner's new instance row (EvalPlanQual re-check), but the subquery is evaluated against the statement's
  original snapshot, so `previous.associations()` can be the pre-winner set. Harmless today, because neither
  `requireForwardTransition` nor `derive` reads associations. It must be fixed (e.g. re-read associations in a
  second statement after the lock) before any check uses them, such as a fix for G-003-02. Reasoned from
  Postgres semantics, not observed.
- **Origin**: `saga-forge-storage-postgres/.../PostgresStorageProvider.java` `LOCK_PREVIOUS` (T019, T025).
- **Status**: open.

### G-003-22: First-save race detection relies on `xmax = 0`, an implementation detail
- **Description**: T025 detects a lost concurrent first save with `RETURNING (xmax = 0) AS inserted` on the
  `INSERT ... ON CONFLICT DO UPDATE`. That the updated row version carries a non-zero `xmax` (the upserting
  transaction's lock, carried onto the new version) is Postgres behavior widely relied on but not documented;
  a future Postgres version could change it. Not verified in this phase (no container run).
- **Origin**: `saga-forge-storage-postgres/.../PostgresStorageProvider.java` `UPSERT_INSTANCE` (T025).
- **Status**: open. T032 must verify `ConcurrencyControlTest.concurrentFirstSaves` (20 repetitions) passes on
  `postgres:16-alpine`; a failure there showing `UncheckedSqlException` (history PK clash) means the flag read
  `true` on the update path.

### G-003-23: The container-backed suites have not been run against a database since US3
- **Description**: Every test class that uses `PostgresTestSupport` (`SchemaTest`, `PostgresStorageProviderTest`
  with its nested `RoundTrip` and `Atomicity`, `PostgresSagaContextStoreTest`, `LifecycleHistoryTest`,
  `ConcurrencyControlTest`) compiles but has not run against Postgres since US3. The US4 tests
  (`Atomicity`, T020; `ConcurrencyControlTest`, T021) never ran at all, and the US1 to US3 suites were not re-run
  after T024/T025 changed `PostgresStorageProvider.save`. The user then gave up on Testcontainers testing: T032
  ran unit tests only, excluding these classes on the command line (G-003-27). The expected outcomes are
  reasoned from Postgres `READ COMMITTED` semantics: a second `save` blocks on `SELECT ... FOR UPDATE OF i`,
  then reads the winner's committed row and fails the forward-transition check (scenario 11) or passes it with
  no new history (scenario 12); a losing concurrent first save waits on the `saga_id` unique index, takes the
  `DO UPDATE` path and is rejected (G-003-22). Unverified as a result:
  - the `RETURNING (xmax = 0)` first-save race detection (G-003-22), and therefore the closure of G-003-17
    (`ConcurrencyControlTest.concurrentFirstSaves`);
  - that Postgres accepts `FOR UPDATE OF i` with the `jsonb_object_agg` scalar subquery in the select list, and
    what that subquery reads after a lock wait (G-003-21);
  - atomicity of a failed `save()` (`PostgresStorageProviderTest$Atomicity`, scenario 10);
  - stability of the concurrency suite: the three `@RepeatedTest(20)` methods with no lock-wait timeout or
    flakiness (scenarios 11 and 12);
  - the US1 to US3 suites against the post-T025 `save`.
- **Origin**: `specs/003-postgres-saga-persistence/tasks.md` T020, T021, T025, T032; user decision at T032 to
  give up on Testcontainers testing.
- **Status**: open. Closing it needs a run of the container-backed classes on `postgres:16-alpine` (or an
  equivalent real Postgres) with all of them passing.

### G-003-24: No query API over lifecycle history
- **Description**: `saga_lifecycle_history` is write-only from the SPI's point of view: no `StorageProvider`
  method returns `SagaLifecycleEntry` values, so history is readable only by inspecting the data store directly
  (FR-012). A programmatic query API belongs to a future observability feature.
- **Origin**: `specs/003-postgres-saga-persistence/spec.md` FR-012; plan.md "Carried forward".
- **Status**: open (deferred by design).

### G-003-25: The host applies the schema; no migration tool
- **Description**: `saga-forge-storage-postgres` ships `schema.sql` as a classpath resource but neither creates
  nor migrates the schema; the host applies it with whatever schema management it already uses. A future schema
  change has no shipped migration path.
- **Origin**: `specs/003-postgres-saga-persistence/research.md` §7; `saga-forge-storage-postgres/.../package-info.java`.
- **Status**: open (deferred by design).

### G-003-26: `DataSource` and pool wiring belong to the starter
- **Description**: `PostgresStorageProvider` and `PostgresSagaContextStore` take a caller-supplied `DataSource`
  and create or configure no connection pool. Detecting the datastore and wiring a `DataSource`/pool is the
  Spring Boot starter's job, which does not exist yet.
- **Origin**: `specs/003-postgres-saga-persistence/research.md` §7; plan.md "Carried forward".
- **Status**: open (deferred to the starter feature).

### G-003-27: A plain `mvn test` still runs the container suites
- **Description**: Nothing separates the container-backed test classes (G-003-23) from the unit tests: there is
  no JUnit tag, Maven profile, surefire exclusion in `saga-forge-storage-postgres/pom.xml`, or naming
  convention such as `*IT` with failsafe. A plain `mvn test` at the root therefore runs them and would start a
  Postgres container (or fail without a container runtime). The unit-only run depends on a command-line
  exclusion: `-Dsurefire.failIfNoSpecifiedTests=false
  -Dtest='!SchemaTest,!PostgresStorageProviderTest*,!PostgresSagaContextStoreTest,!LifecycleHistoryTest,!ConcurrencyControlTest'`
  (surefire 3.2.5), which must be kept in step with any new container-backed class by hand.
- **Origin**: `saga-forge-storage-postgres/pom.xml`; `specs/003-postgres-saga-persistence/tasks.md` T032.
- **Status**: open.

## Engine (future feature)

### G-ENG-01: Instance-first write order
- **Description**: The engine's unit of work must persist (and commit) the saga instance with
  `StorageProvider.save` before the first `SagaContextStore.save` for that `sagaId`; Postgres enforces it with
  the `saga_context` FK. 002's `SagaTestHarness` does the opposite: `HarnessSagaLifecycle.start` saves the
  instance inside the first unit of work (restored on rollback) while `HarnessSagaContext` writes context
  through to `InMemorySagaContextStore` immediately. The `SagaContextStore` Javadoc does not state the rule yet.
- **Origin**: `specs/003-postgres-saga-persistence/research.md` §8;
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/execution/harness/HarnessUnitOfWork.java`,
  `HarnessSagaContext.java`.
- **Status**: open.

### G-ENG-02: Placement of `SagaLifecycleEntry` in `core.spi`
- **Description**: `SagaLifecycleEntry`/`SagaLifecycleEventKind` live in `saga-forge-core`'s `spi` package, but no
  SPI method accepts or returns them; only `saga-forge-storage-postgres` internals use them, and the derivation
  semantics (tiebreak, first-save rules) live in that module. Options: move them into the storage module; move
  the pure derivation into core next to them; or let the engine produce entries and pass them to storage. The
  user chose to leave them as is until the engine feature clarifies who produces lifecycle transitions.
- **Origin**: `specs/003-postgres-saga-persistence/research.md` §1, §2; user decision, 2026-11-05.
- **Status**: open (deferred to the engine feature).

## Dependency alignment with Spring Boot 4.1.2 (future starter feature)

Noted only; nothing is bumped until the starter feature decides.

### G-DEP-01: JUnit 5 vs Boot-managed JUnit 6
- **Description**: Root `junit.version` is `5.11.4`; Spring Boot 4 manages JUnit 6.
- **Origin**: root `pom.xml` `<junit.version>`.
- **Status**: open.

### G-DEP-02: mysql-connector-j version
- **Description**: `mysql-connector-j` is `9.1.0`; Boot 4.1.2 manages `9.7.0`.
- **Origin**: `saga-forge-storage-mysql/pom.xml`.
- **Status**: open.

### G-DEP-03: mongodb-driver-sync version
- **Description**: `mongodb-driver-sync` is `5.3.1`; Boot 4.1.2 manages `5.8.1`.
- **Origin**: `saga-forge-storage-mongodb/pom.xml`.
- **Status**: open.

### G-DEP-04: kafka-clients version
- **Description**: `kafka-clients` is `3.9.0`; align with the Boot 4.1.2-managed version (not checked here).
- **Origin**: `saga-forge-transport-kafka/pom.xml`.
- **Status**: open.

## 002-transactional-step-sequence

### G-002-01: `@ReadModel` documented but not implemented
- **Description**: `docs/architecture.md` "Status at a glance" lists `@ReadModel` in the implemented annotation
  model, but `saga-forge-core` has no `ReadModel.java`. AGENTS.md's Module Map correctly omits it.
- **Origin**: `docs/architecture.md` "Status at a glance" (annotation model row).
- **Status**: closed (003 T027). Verified no `ReadModel.java` exists anywhere in the repository and removed
  `@ReadModel` from the annotation-model row. Other `@ReadModel` mentions in architecture.md remain (G-002-04).

### G-002-04: architecture.md still presents `@ReadModel` as an existing annotation
- **Description**: With no `ReadModel.java` in `saga-forge-core`, the "jMolecules consideration" section says
  Saga Forge's own markers "(`@DomainEvent`, `@Command`, `@ReadModel`) already play a similar role" and that core
  "should keep its own `@DomainEvent`/`@Command`/`@ReadModel` annotations", and the Vocabulary entry for
  Projection says read-model types "are marked `@ReadModel`". The "downstream policies" mentions read as target
  design and are fine. Either add the annotation through a spec or reword these as future.
- **Origin**: `docs/architecture.md` "jMolecules consideration", "Vocabulary" (Projection); noticed at 003 T027.
- **Status**: open.

### G-002-02: README core row misses the 002 contracts
- **Description**: The `saga-forge-core` row of README.md's Modules table lists only the annotations, SPI
  (`StorageProvider`, `EventDispatcher`), `SagaContext`/`SagaLifecycle` and the scanner, not the 002 contracts
  listed in AGENTS.md "Current Status".
- **Origin**: `README.md` Modules table.
- **Status**: closed (003 T028). The core row now lists the scanner's `validateAll` → `ValidationReport`,
  `CommandGateway`, `DomainEvents`/`EventPublisher` and the SPI additions (`SagaContextStore`,
  `AggregateResolver`, `HandlerInstanceProvider`, `SagaInstance` step records, and 003's `SagaLifecycleEntry`).

### G-002-03: 002 verification tasks marked complete without running
- **Description**: 002 tasks T041 to T044 (root `mvn -B test`, SC-005 search, pom diff, quickstart walk) were
  marked complete by user decision without being executed; the 002 tests are to be run later. The superseded
  banners on `docs/Saga_*.md` (part of T042) are present.
- **Origin**: `specs/002-transactional-step-sequence/tasks.md` T041 to T044.
- **Status**: open.

### G-002-05: `.kiro/specs/*/design.md` lag the current core contracts
- **Description**: `.kiro/specs/saga-forge-framework/design.md` still shows the pre-002 `SagaInstance` (no
  `steps`/`StepRecord`, `outcome`, `failure`) and no `SagaContextStore`; `.kiro/specs/saga-context-lifecycle/design.md`
  describes a `ThreadLocal`-based `SagaLifecycle.setContext`/`registerProperty` and `SagaContext.getProperty` API with
  progress-state records in `SagaInstance.state`, none of which exists in `saga-forge-core` (which has
  `getState`/`putState`/`updateStateAttribute` and `SagaContextRecord`). AGENTS.md calls these docs "accurate". Not
  caused by 003; 003 changes none of their properties or Error Handling rows (T029).
- **Origin**: `.kiro/specs/saga-forge-framework/design.md` "SagaInstance"; `.kiro/specs/saga-context-lifecycle/design.md`
  "Components and Interfaces", "Progress State Persistence Model"; noticed at 003 T029.
- **Status**: open. AGENTS.md says to leave these specs as-is; decide whether to annotate them or correct AGENTS.md's
  "accurate" claim.

## Repository hygiene

### G-REPO-01: Eclipse files untracked and not ignored
- **Description**: Eclipse files show up untracked in `git status --short`: `.project` and `.settings/` at the
  root, and `.project`, `.classpath` and `.settings/` in every module. `.gitignore` does not list them.
- **Origin**: `.gitignore`.
- **Status**: open.

### G-REPO-02: IDE compiler output in Maven `target/` breaks incremental `mvn test`
- **Description**: The Eclipse `.classpath` files point the IDE's compiler (ECJ) at Maven's `target/classes`
  and `target/test-classes`. ECJ rejects `ValidationReportTest.java:151` (a generic `containsExactly` call
  javac accepts) and writes "Unresolved compilation problem" class files that are newer than the sources, so
  Maven skips recompiling and `mvn -pl saga-forge-storage-postgres -am test` failed with 3 errors in
  `ValidationReportTest$ValidateAll` until `mvn clean` was run.
- **Origin**: `saga-forge-*/.classpath`; `saga-forge-core/src/test/.../scanner/ValidationReportTest.java`.
- **Status**: open.
