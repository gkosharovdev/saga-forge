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
- **Status**: moot (004, T020 to T022, T033, T038, T039): no derivation exists any more. The engine's creation
  write hands `[CREATED]` over with every step `NOT_RUN`, and `LifecycleHistoryDerivation` is deleted.

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
- **Status**: closed by 004 (T020 to T022, T033, T038; verified by T048 and T059, `LifecycleHistoryTest` and
  `LifecycleHistoryPropertyTest`): the engine hands entries over in true completion order, storage numbers them in
  hand-over order, and the tiebreak is gone. The Postgres side is checked by the JDBC fake only (G-003-23).

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
- **Status**: open. Noticed at 004 planning (`specs/004-saga-engine/research.md` §16): the canonical sagas hit it.
  `OrderProcessingSaga` stores a `BigDecimal` (`orderAmount`) and `CreateBookingSaga` a `Long` (`amount`, read back as
  `Long`) in `SagaContext`, so both would fail on `PostgresSagaContextStore` (rejected on write / type mismatch on
  read). 004's tests use in-memory stores only; the starter's first Postgres run will meet it.

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
- **Status**: derivation half closed by 004 (T021, T022: `LifecycleHistoryDerivation` replaced by
  `ForwardTransition.requireForwardTransition`); the forward-transition half stays as decided at 003 T024.

### G-003-16: The step-name tiebreak is code-unit order, not locale collation
- **Description**: The G-003-04 tiebreak sorts with `String.compareTo` (UTF-16 code units, case-sensitive, so
  `Zeta` sorts before `alpha`). research.md §5 says "alphabetical" without fixing the comparator; another storage
  module needs the same comparator to give the same order.
- **Origin**: `saga-forge-storage-postgres/.../LifecycleHistoryDerivation.java` (T018); research.md §5.
- **Status**: closed by 004 together with G-003-04: there is no comparator any more.

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
- **Status**: open. Since 004 it also applies to `findByIdAndLock` (T074, Javadoc): after a wait, the associations
  read may predate the winner's commit; harmless under the engine, which never changes associations after creation.
  Container test `ConcurrencyControlTest.findByIdAndLockAfterWaitReadsWinnersCommit` added (T081), not yet run.

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
  equivalent real Postgres) with all of them passing. Added or changed by 004 and not yet run: T023 (tags and the
  write-contract update of `SchemaTest`, `PostgresStorageProviderTest` with `$RoundTrip` and `$Atomicity`,
  `PostgresSagaContextStoreTest`, `LifecycleHistoryTest`, `ConcurrencyControlTest`), T063
  (`JoinedTransactionContainerTest`: `joinedSaveCommitsWithClientWrite`, `joinedSaveRollsBackWithClientWrite`,
  `contextStoreWriteSurvivesJoinedRollback`, `withoutTransactionKeepsOwnTransaction`) and T081
  (`ConcurrencyControlTest`: `findByIdAndLockBlocksSecondTransactionUntilCommit`,
  `findByIdAndLockAfterWaitReadsWinnersCommit`, `findByIdAndLockDoesNotBlockContextStoreWrite`,
  `historyNumbersContinueUnderLock`). The engine adaptation is otherwise verified by the JDBC fake only
  (`JdbcTransactionBoundaryTest`, `PostgresStorageProviderJoinTest`, `PostgresStorageProviderLockTest`); the
  hand-off is in `specs/004-saga-engine/quickstart.md` "Container tests for the user to run" (T112).

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
- **Status**: closed by 004 (T001, T002): the container classes carry `@Tag("container")`, the module excludes the
  group by default (`${saga.excludedTestGroups}`), and `-Pcontainer-tests` runs them.

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
- **Status**: closed by 004 (T014 `SagaContextStore` Javadoc; T039 creation write in its own transaction before
  the first unit of work; verified by `SagaEngineHappyPathTest`, `FailurePositionTest`).

### G-ENG-02: Placement of `SagaLifecycleEntry` in `core.spi`
- **Description**: `SagaLifecycleEntry`/`SagaLifecycleEventKind` live in `saga-forge-core`'s `spi` package, but no
  SPI method accepts or returns them; only `saga-forge-storage-postgres` internals use them, and the derivation
  semantics (tiebreak, first-save rules) live in that module. Options: move them into the storage module; move
  the pure derivation into core next to them; or let the engine produce entries and pass them to storage. The
  user chose to leave them as is until the engine feature clarifies who produces lifecycle transitions.
- **Origin**: `specs/003-postgres-saga-persistence/research.md` §1, §2; user decision, 2026-11-05.
- **Status**: closed by 004 (T008 to T022): the entries stay in `core.spi`, are produced by the engine and handed
  over through `StorageProvider.save(instance, entries)`; `sequenceNumber` is removed; Postgres maps entries with the
  concrete `LifecycleEntryRows`.

### G-ENG-03: `PostgresStorageProvider.save` cannot join the host's transaction
- **Description**: `docs/architecture.md` "Truthful state and transaction boundaries" requires aggregate changes,
  step records, status and outcome to commit together in the host's local transaction. `PostgresStorageProvider`
  takes its own `Connection` from the `DataSource` and commits it itself, so an engine cannot enlist the instance
  write in the same transaction as the client's aggregate writes. The engine's transaction SPI must define how a
  storage provider obtains the ambient connection (e.g. a connection supplier bound by the engine or Spring's
  `DataSourceUtils`), and 003's provider must be adapted. `PostgresSagaContextStore` keeps its own connection by
  design (research.md §6).
- **Origin**: `saga-forge-storage-postgres/.../PostgresStorageProvider.java` `inTransaction`;
  `specs/002-transactional-step-sequence/research.md` §9 (transaction SPI deferred to the engine).
- **Status**: closed by 004 (T007, T054, T055): `TransactionBoundary`/`LocalTransaction` in core,
  `JdbcTransactionBoundary` and the joining `PostgresStorageProvider(DataSource, TransactionBoundary)` in the
  Postgres module, checked with the JDBC fake (T060 to T062). Database verification stays under G-003-23
  (`JoinedTransactionContainerTest`, not run).

### G-ENG-04: `EventDispatcher` and `SagaLifecycle` Javadoc give event routing to the transport
- **Description**: `EventDispatcher` says implementations "must route events to the correct saga instance based on
  association property correlation" and that a matching `@StartSaga` event "may" create an instance;
  `SagaLifecycle` says "`EventDispatcher` resolves which instance an incoming event correlates to". 002's
  contracts/saga-unit-of-work.md has the engine resolve instances through `StorageProvider.findByAssociation`, and
  004 gives routing to the engine behind an inbound entry point, leaving the dispatcher outbound only. The
  `StorageProvider` Javadoc's "the engine persists instance state on every state transition" also predates the
  per-unit-of-work commit.
- **Origin**: `saga-forge-core/.../spi/EventDispatcher.java`, `.../lifecycle/SagaLifecycle.java`,
  `.../spi/StorageProvider.java`; `specs/004-saga-engine/spec.md` FR-001, FR-003.
- **Status**: closed by 004 (T014, T015): `EventDispatcher` is documented outbound only, and the `SagaLifecycle`
  Javadoc gives instance resolution to the engine behind `EventReceiver`.

### G-ENG-05: A crash after the creation write strands a `CREATED` instance
- **Description**: The engine commits a new instance as `CREATED` before its first unit of work (004 FR-012,
  G-ENG-01). If the process stops before that unit of work finishes, the instance stays `CREATED` with no step run,
  and a redelivered start event is rejected as a duplicate (001 FR-006), so nothing ever resumes it. Needs a
  recovery rule (for example resuming a `CREATED` instance whose first unit of work never committed), which
  overlaps with the idempotency work of the Kafka feature.
- **Origin**: `specs/004-saga-engine/spec.md` FR-012, Edge Cases.
- **Status**: open (not addressed by 004). The 004 plan adds a consequence (`specs/004-saga-engine/research.md` §3,
  §6): an event for a non-start step that reaches a still-`CREATED` instance is reported `NOT_HANDLED` (so a
  non-start step never runs before the start step in the cross-process window), which means every later event for a
  stranded instance is `NOT_HANDLED` too.

### G-ENG-06: `SagaInstance.state` is never populated
- **Description**: `SagaInstance.state` is documented as the "serialized fields of the saga class itself", but the
  harness always writes `Map.of()` and 004 treats a saga's other fields as injected dependencies, creating a fresh
  saga object per unit of work, so the field stays empty. Either give it a use (with the serialization limits of
  G-003-07) or drop it.
- **Origin**: `saga-forge-core/.../spi/SagaInstance.java`; `HarnessSagaLifecycle.start`;
  `specs/004-saga-engine/spec.md` Assumptions "Saga objects".
- **Status**: open. Javadoc done in 004 (T015: `SagaInstance.state` "reserved, not populated"; the engine writes
  `Map.of()`); the use-or-drop decision remains.

### G-ENG-07: Events are lost if the process stops between commit and handoff
- **Description**: 004's default after-commit handoff is at-most-once: a committed unit of work's buffered
  events go to `EventDispatcher` right after the commit, and are lost if the process stops in between. The saga
  state stays committed and nothing redelivers them. 004 defines the handoff as an outbox-ready contract (which
  events, recording order, unit-of-work identity plus saga type and id, and the option to receive the events
  inside the unit of work's transaction) so that a transactional outbox can close the window without changing
  the engine.
- **Origin**: `specs/004-saga-engine/spec.md` FR-030, Edge Cases "Crash between commit and handoff",
  Assumptions "Delivery guarantee"; user decision, 2026-11-06.
- **Status**: open until the Kafka feature adds the transactional outbox (events captured in the unit of work's
  transaction and relayed at least once) behind the 004 handoff contract.

### G-ENG-08: Concurrent duplicate starts across processes are not prevented
- **Description**: 004 guarantees at most one active instance per saga type and association value within one
  process only. A start event handled after another process's creation write committed is rejected as a
  duplicate, but two start events with the same association value handled concurrently in different processes
  can each start an instance. 004 adds no storage-level uniqueness rule for it.
- **Origin**: `specs/004-saga-engine/spec.md` FR-027, Edge Cases "Duplicate start", Assumptions "Duplicate
  starts across processes"; user decision, 2026-11-06.
- **Status**: open until the Kafka feature's delivery and deduplication work addresses it.

### G-ENG-09: JDBC transaction support lives in the Postgres module
- **Description**: The 004 plan puts `JdbcTransactionBoundary` (a `TransactionBoundary` over a `DataSource`) and the
  joining logic in `saga-forge-storage-postgres`. The MySQL module, and a JDBC outbox in the Kafka feature, need the
  same code; either it is duplicated or extracted into a shared JDBC support module (which would also be the home for
  a shared `<T, R>` row mapper, research §4a).
- **Origin**: `specs/004-saga-engine/research.md` §2, §4a.
- **Status**: open (decide at the MySQL or Kafka feature).

### G-ENG-10: Cross-process window between rollback and the `FAILED` write
- **Description**: After a unit of work rolls back, its storage lock is released before the separate `FAILED` write
  locks the instance again. Another process can run a unit of work on the same instance in between. The `FAILED`
  write then marks an instance that has moved on, or finds it terminal and records nothing (logged as
  `saga.instance.failedNotRecorded`). The in-process lock closes the window within one engine only.
- **Origin**: `specs/004-saga-engine/research.md` §7; spec FR-011, FR-024.
- **Status**: open.

### G-ENG-11: Lock mode and hold time of the unit-of-work lock
- **Description**: The engine holds the storage lock (`findByIdAndLock`) for the whole unit of work, including handler
  calls to external systems, and defines no lock timeout (a host `lock_timeout` surfaces as a storage error raised to
  the transport). In Postgres the lock must be `FOR NO KEY UPDATE`: `PostgresSagaContextStore` writes on its own
  connection during the unit of work, and its `saga_context` FK check takes `FOR KEY SHARE`, which `FOR UPDATE` would
  block, hanging the unit-of-work thread on its own lock with no deadlock detection. Reasoned from the Postgres
  lock-conflict table; not run (G-003-23).
- **Origin**: `specs/004-saga-engine/research.md` §3, §15.
- **Status**: open. Implemented in 004 T074 (`FOR NO KEY UPDATE OF i` in `findByIdAndLock` and in `save`); the
  reasoning is not verified against Postgres. Container test `ConcurrencyControlTest.findByIdAndLockDoesNotBlockContextStoreWrite`
  added (T081), not yet run.

### G-ENG-12: Entry point called inside an active host transaction
- **Description**: Spec FR-007 covers calls from a handoff and from a running unit of work, not a call made while the
  host's own transaction is active on the thread (for example a synchronous transport invoked from a
  `@Transactional` method). The 004 `TransactionBoundary.begin()` never joins: it suspends the host transaction or
  throws, per implementation. The Spring starter and the in-memory transport must choose (for example deliver after
  the host commits).
- **Origin**: `specs/004-saga-engine/research.md` §2, §16; contracts/transaction-boundary.md.
- **Status**: open (starter feature).

### G-ENG-13: Two executable models of the 002 contract
- **Description**: The 004 plan keeps the 002 `SagaTestHarness` unchanged in `saga-forge-core` test sources and proves
  engine parity with differential tests through core's test-jar. Once the engine is the reference, the harness and its
  tests could be retired or reduced, and the 002 fixtures moved next to the engine tests.
- **Origin**: `specs/004-saga-engine/research.md` §8.
- **Status**: open.

### G-ENG-14: Test-double participants have no transaction isolation
- **Description**: `InMemoryTransactionBoundary` (engine test support) snapshots each registered `Snapshottable`
  participant at `begin` and restores the whole participant at `rollback`. Participants are shared fixture objects, so
  concurrent transactions see each other's uncommitted aggregate changes, and a rollback on one thread can undo a change
  another thread already committed. Only `TransactionalInMemoryStorageProvider` isolates (per-transaction buffers).
  Concurrency tests (US4, SC-003/SC-012) must not assert aggregate state across concurrently rolled-back units of work,
  or must give each instance its own participant.
- **Origin**: `saga-forge-engine/src/test/.../engine/support/InMemoryTransactionBoundary.java` (004 T024).
- **Status**: open (test-scaffolding limitation; documented in the class Javadoc).

### G-ENG-15: `SagaHandlingResult` enforces only part of the data-model rules
- **Description**: The record's compact constructor enforces the rules 004 T008 names (`sagaType`/`outcome` non-null,
  `outcomeStep` iff `COMPLETED`, `failure` iff `FAILED`, `handoffFailures` rules). It does not enforce the other
  data-model.md "SagaHandlingResult" rules: `sagaId` `null` for `UNMATCHED`/`DUPLICATE_START`, `started` only with a
  committed creation write, `unitOfWorkId` non-null iff a unit of work ran. The last cannot be decided from `outcome`
  alone (`NOT_HANDLED` has a unit of work after a conflict, none for a still-`CREATED` instance), so the engine is
  responsible for them and the US1/US4 tests must check them.
- **Origin**: `saga-forge-core/.../spi/SagaHandlingResult.java` (004 T008); `specs/004-saga-engine/data-model.md`.
- **Status**: open.

### G-ENG-16: Public "not API" types in `engine.internal`
- **Description**: `SagaEngine` lives in `engine` and the runtime in `engine.internal`, so the runtime cannot be
  package-private end to end. tasks.md and plan.md do not say how to cross the package boundary. US1 makes exactly
  four internal types public, each documented "Not API": `EngineRuntime` (composition root: `assemble`, `receive`,
  `commandGateway`, `eventPublisher`, `sagaTypes`, `needsHandlerInstances`), `EngineSettings` (parameter object of
  the builder's plug-ins), `TimeAndIdentity` (clock and id suppliers) and `Diagnostics` (constructor and the two
  startup methods). Everything else is package-private. US1 also adds two package-private files tasks.md does not
  list: `UnitOfWork` (the interface both units of work implement, so the gateway can report failures) and
  `LocalTransactions` (rollback-if-still-active on failure paths). A JPMS `module-info` (exporting only `engine`)
  would hide the public internals.
- **Origin**: 004 T042; `saga-forge-engine/src/main/java/.../engine/internal/`.
- **Status**: open (decide when the starter or a `module-info` is added; T094 `StartupChecks` will need the same
  treatment if `SagaEngine` calls it directly).

### G-ENG-17: US1 transitional behavior closed by later 004 phases
- **Description**: After US1 the engine still: rethrows any unit-of-work failure from `receive` after rolling back,
  with no `FAILED` write (T051–T053); lets a storage or creation-write failure stop the fan-out instead of raising
  `EventHandlingException` (T053); takes no in-process instance or duplicate-start lock (T071–T073); treats a
  `SagaConcurrencyConflictException` like any failure (T073); calls the handoff after commit whatever its
  `timing()` (an `IN_TRANSACTION` handoff therefore runs outside the transaction) and lets an `AFTER_COMMIT` handoff
  failure propagate from `receive`/`send` after the commit (T084, T085); has no reentrancy guard (T083, T086); and
  runs no engine startup checks (T094, T095).
- **Origin**: 004 T036, T038, T039, T042.
- **Status**: closed by 004: the failure path by T052/T053, locks and the conflict path by T072/T073, handoff
  timings and reentrancy by T084 to T086, and the engine startup checks by T094/T095.

### G-ENG-18: Checked exceptions from client code are wrapped by the engine
- **Description**: `Reflection.invoke`/`construct` rethrow a client `RuntimeException` or `Error` unchanged but wrap
  a checked exception once in `java.lang.reflect.UndeclaredThrowableException` (no sneaky throw in production code).
  The 002 harness rethrows the checked exception itself, so for a step or handler that throws a checked exception
  the engine's `SagaFailure.causeType` (once US2 records it) is `UndeclaredThrowableException` where the harness
  reports the checked type. No spec rule decides which one `SagaFailure` should name.
- **Origin**: 004 T029; `saga-forge-core/src/test/.../execution/harness/HarnessReflection.java`.
- **Status**: open (differential tests must avoid checked exceptions, or US2 decides to unwrap for `SagaFailure`).

### G-ENG-19: Model members are discovered on the declaring class only
- **Description**: The engine discovers `@SagaStep`, `@CommandHandler`, `@AggregateLoader` and `@AggregateSaver`
  members with `getDeclaredMethods`/`getDeclaredConstructors`, as `ReflectiveAnnotationScanner` validates them; the
  002 harness walks superclasses. An annotated member inherited from a superclass is neither validated nor run.
  Fields (`@SagaId`, `SagaContext`, `CommandGateway`, `@AggregateIdentifier`, association properties) are still
  looked up through the class hierarchy.
- **Origin**: 004 T029, T030, T034, T035.
- **Status**: open (no spec rule on inherited saga or handler members).

### G-ENG-20: Interpretations made by the US1 runtime
- **Description**: Choices where the contracts are silent or loose: (a) a `SagaObjectProvider` result is accepted if
  it is an instance of the saga class (subclass proxies pass), while contracts/host-plugins.md says "exactly
  `sagaType`"; (b) handler, loader and saver hosts are asked from `HandlerInstanceProvider` on every use, as the
  harness does, not memoized after the first use; (c) `saga.startup.refused` logs the count line, then one line per
  error as `saga.startup.refused error=<text>`; (d) a `registerSagaType` failure refuses the engine at the first
  rejected saga type, without trying the rest; (e) an unresolvable handler parameter is named `#<1-based position>
  <SimpleType>`, reading engine-startup.md's `#2 Clock` as 1-based; (f) `SagaEngineStartupException`'s message is a
  header line, then one error per line.
- **Origin**: 004 T031, T035, T038, T041, T042.
- **Status**: open (confirm or change in the US1 tests or US6).

### G-ENG-21: `SagaObjectProvider.supports` is asked per unit of work, not at engine start
- **Description**: The US1 unit of work called `SagaObjectProvider.newInstance` for every saga type, whether or not
  `supports` accepted it, contrary to the SPI Javadoc. Fixed in 004 T052: a unit of work now uses the provider only if
  `supports(type)` is true, else the no-argument constructor. The SPI says `supports` is called once at engine start;
  until US6 (T095) resolves the provider per saga type at `start()`, it is asked at the start of every unit of work.
  `supports` is side-effect free by contract, so the only cost is the repeated call.
- **Origin**: 004 T038 (found while writing T056's `FailureSagaObjects`).
- **Status**: resolved in 004 T094/T095. `start()` asks `supports` once per saga type and refuses a type the
  registered provider does not support (contracts/engine-startup.md); a unit of work then calls `newInstance`
  without asking again. The per-unit-of-work `supports` filter of T052 was removed. With a provider registered,
  there is no fallback to the no-argument constructor.

### G-ENG-22: `identicalConcurrentSaves` no longer checks history de-duplication
- **Description**: 003's `ConcurrencyControlTest.identicalConcurrentSaves` checked that two identical concurrent
  saves left one set of history rows, because storage derived history from snapshots and an unchanged snapshot
  derived nothing. Since 004 storage records exactly the entries it is handed; the test now hands none (T023), so it
  checks the snapshot outcome only. Two identical saves that each hand the same entries would store them twice:
  storage does not de-duplicate, it relies on the engine's lock (`findByIdAndLock`) to keep a second unit of work
  from producing the same entries.
- **Origin**: 004 T023.
- **Status**: open (accepted: the engine never hands the same entries twice; a non-engine caller could).

### G-ENG-23: The 002 harness's `InMemoryStorageProvider.findByIdAndLock` does not lock
- **Description**: The harness double in `saga-forge-core` test sources implements `findByIdAndLock` as `findById`:
  it neither requires an active transaction nor locks. The harness is single-threaded and runs no engine, so nothing
  depends on it; the engine's concurrency tests use `TransactionalInMemoryStorageProvider`, which locks. A future test
  that runs the engine over the harness double would silently lose serialization.
- **Origin**: 004 T018.
- **Status**: open (documented in its Javadoc; not used by any engine test).

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
- **Status**: open (the user's call). 004 T103 appended Properties 21 to 28 to `saga-forge-framework/design.md`,
  each naming its test class, with a note that none of `saga-context-lifecycle/design.md`'s Properties 1 to 12 applies
  to the engine as written; that document and the stale `SagaInstance` section are unchanged.

## Governance

### G-GOV-01: "Zero-dependency core" is worded inconsistently
- **Description**: Constitution Principle I forbids runtime dependencies on Spring, a specific datastore or a
  specific transport; AGENTS.md's Module Map says core has "zero dependencies" outright, and 002/003 plans
  treated it as zero third-party runtime dependencies. Whether a stack-neutral library (e.g. annotation-only
  JSpecify) is allowed in core is therefore undecided.
- **Origin**: `.specify/memory/constitution.md` Principle I; `AGENTS.md` Module Map.
- **Decision (user, 2026-11-06)**: focused, stack-neutral dependencies are welcome and will be adopted when a
  feature needs them; each lands in the module that needs it, or in core only as annotation-only / test-scope.
  Acknowledged candidates: JSpecify (nullability), SLF4J (logging), ArchUnit (module-boundary tests, test
  scope), the JTA API (`jakarta.transaction-api`, transaction contract); later also Micrometer Observation,
  Failsafe/Resilience4j (behind framework-owned retry attributes, Principle II), an outbox library (Kafka
  feature) and a jMolecules adapter. Feature 004 (engine) stays dependency-free.
- **Status**: open until Principle I and the AGENTS.md Module Map are reworded to match (constitution
  amendment, at the first feature that adds one of these). The 004 plan adds a `saga-forge-engine` module
  (`specs/004-saga-engine/research.md` §1), held by plan to Principle I's constraints (no Spring, datastore or
  transport types; no third-party dependency). The amendment should name it, since SLF4J and the JTA API would land
  there rather than in core.

## Step retry and command idempotency (005)

### G-005-01: No long or scheduled backoff
- **Description**: Retries wait in-process within the handling call, with the total backoff bounded by a
  framework-wide maximum (`specs/005-step-retry` FR-014, FR-014a). Waiting minutes or hours between attempts
  needs a durable scheduler, which does not exist; such policies are rejected at startup.
- **Origin**: `specs/005-step-retry/spec.md` FR-014a; user decision, 2026-11-09.
- **Status**: open (deferred; overlaps the "no waiting timeouts" deadline gap).

### G-005-02: In-process retry blocks the caller and holds the instance
- **Description**: While a retry waits, the instance's unit-of-work lock is held and the caller (for Kafka, the
  consumer thread) is blocked, so other events for that instance wait and a long total backoff can trip
  transport-side timeouts.
- **Origin**: `specs/005-step-retry/spec.md` FR-014, Assumptions.
- **Status**: open (bounded by FR-014a; revisit with the Kafka transport).

### G-005-03: Outside systems are idempotent only if handlers pass the identity on
- **Description**: The framework guarantees a stable command identity and never re-handles a command whose
  handling committed, but a handler calling an outside system must pass the identity as that system's
  idempotency key; the framework cannot enforce it.
- **Origin**: `specs/005-step-retry/spec.md` FR-003, Assumptions.
- **Status**: open (documentation and example responsibility).

### G-005-04: Triggering commands are not deduplicated before a saga instance exists
- **Description**: 005 covers commands dispatched from saga steps and duplicate events for completed steps.
  Deduplicating the command or request that leads to a saga's start (two concurrent requests for the same
  booking) is not covered.
- **Origin**: `specs/005-step-retry/spec.md` FR-005, FR-021; `docs/architecture.md` "Idempotent step execution".
- **Status**: open (deferred).

### G-005-05: A crash during the first attempt is not counted
- **Description**: The attempt count is written only after a failed attempt claims the next one
  (`specs/005-step-retry` research §9). If the process stops during the first attempt of a fresh event, nothing
  is recorded, so a redelivery starts again at attempt 1; that one attempt is not counted against `maxAttempts`.
  Recording every first attempt would add a write to every unit of work.
- **Origin**: `specs/005-step-retry/research.md` §9; SC-005.
- **Status**: open (accepted trade-off).

### G-005-06: A retry of a start unit of work cannot resume after a crash
- **Description**: A retry claimed for the unit of work opened by a saga's start event (`pendingRetry.triggerStep` is
  the `@StartSaga` step) is resumed only within the same handling call. If the process stops while waiting, the
  redelivered start event finds the existing, still active instance and is reported `DUPLICATE_START`, so the claimed
  attempt never runs and the instance stays `CREATED`/`RUNNING` with its `pendingRetry` (like G-ENG-05). Retries of
  units of work opened by non-start events resume as designed.
- **Origin**: `specs/005-step-retry` research §9; found while writing `IdempotencyFaultInjectionTest` (T056).
- **Status**: open (decide with G-ENG-05 whether a redelivered start event may resume an active instance's claimed
  retry).

### G-005-07: No test for an event waiting on the instance during a retry
- **Description**: The attempt loop holds the in-process instance lock across attempts and waits, so a second event
  for the same instance waits until the loop ends (004 FR-025 holds). This follows from the structure (one lease per
  handling call) but no concurrency test asserts it; tasks.md T046 listed one.
- **Origin**: `specs/005-step-retry/tasks.md` T046.
- **Status**: open.

## Compensation (006)

### G-006-01: Retry exhaustion is a contract without a retry design
- **Description**: `specs/006-saga-compensation` FR-006a makes exhausted retries a final failure that starts
  compensation, but step retry is not designed; until it is, a step has one attempt and an unmarked failure ends
  `FAILED`. The retry design must reuse 005's failure classification (transient / final / final-by-exhaustion)
  and change `.kiro/specs/saga-forge-framework/` before adding attributes to `@SagaStep`.
- **Origin**: `specs/006-saga-compensation/spec.md` FR-006a, FR-006b; `docs/architecture.md` "Per-step retry
  policy with failure classification".
- **Status**: closed 2026-11-09 by `specs/005-step-retry`: retry with failure classification is implemented
  (`FailureClassification`, `SagaFailure.classification`/`attempts`), and 006 FR-006 to FR-006b reference it.

### G-006-02: A compensation does not wait for confirmation of the undo
- **Description**: A compensation is complete when its unit of work commits (FR-013). A refund refused by the
  provider after the command was accepted is not seen by the saga, which still ends "compensated".
- **Origin**: `specs/006-saga-compensation/spec.md` FR-013 (user decision, 2026-11-09).
- **Status**: open (deferred).

### G-006-03: No operator path out of "requires intervention"
- **Description**: A saga whose compensation failed ends "requires intervention"; resuming compensation or
  marking it resolved by hand is out of scope (FR-021).
- **Origin**: `specs/006-saga-compensation/spec.md` FR-016, FR-021.
- **Status**: open (deferred).

### G-006-04: No way to mark a step irreversible
- **Description**: A committed step without `@SagaCompensation` is treated as "nothing to compensate". A step
  whose effect cannot be undone cannot force "requires intervention".
- **Origin**: `specs/006-saga-compensation/spec.md` FR-007, Assumptions.
- **Status**: open (possible later extension).

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
