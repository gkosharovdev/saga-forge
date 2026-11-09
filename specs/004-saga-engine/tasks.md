---
description: "Task list for the Saga Engine Runtime"
---

# Tasks: Saga Engine Runtime

**Input**: Design documents from `/specs/004-saga-engine/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Confirmed decisions (plan.md Summary, user, 2026-11-06)**: new module `saga-forge-engine`; storage assigns
lifecycle sequence numbers and `SagaLifecycleEntry.sequenceNumber` is removed; `StorageProvider.findByIdAndLock`
at the start of every unit of work, `FOR NO KEY UPDATE` in Postgres; a write-time conflict is reported
`NOT_HANDLED` and not retried.

**Tests**: Required (plan.md Testing, quickstart.md scenarios 1 to 24). **Not TDD**: every task writes production
code first; its unit tests come in the same task or a later task of the same phase. No task in the middle of a
phase runs tests (compiling is allowed). Each phase ends with exactly one checkpoint task that runs the unit suite
of the modules the phase touched. Testcontainers tests are never run by the implementer; container-backed classes
are edited and compiled only, and handed to the user at T112.

**Organization**: by user story (spec.md: US1 P1, US2 P1, US3 P2, US4 P2, US5 P2, US6 P3).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 to US6, per spec.md; Setup, Foundational and Polish tasks carry no story label
- All paths are relative to the repository root `saga-forge/`

## Path Conventions

- Core main: `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/` (`spi/`, `lifecycle/`,
  `event/`, `command/`)
- Core test: `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/` (`spi/`,
  `execution/harness/`, `execution/fixtures/inprocess/`, `execution/fixtures/booking/`)
- Engine main: `saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/` (public types) and
  `.../engine/internal/` (package-private runtime)
- Engine test: `saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/` (`support/` test doubles,
  `fixtures/` test sagas, `internal/` tests of package-private classes)
- Postgres main: `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/`
- Postgres test: `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/`
- Example test: `saga-forge-example/src/test/java/io/github/gkosharovdev/sagaforge/example/`
- Maven: `/opt/homebrew/bin/mvn` (3.9.9, JDK 21). Plugin versions are pinned to the Maven 3.9.9 defaults:
  `maven-jar-plugin` `3.4.1`, `maven-surefire-plugin` `3.2.5`.
- **Compile check** (allowed in any task, runs no test):
  `/opt/homebrew/bin/mvn -B -pl <modules> -am -DskipTests clean verify`. `verify` packages the internal test-jars
  inside the reactor (quickstart.md Prerequisites); `clean` avoids stale IDE classes (G-REPO-02).
- **Container classes** (never run here): `SchemaTest`, `PostgresStorageProviderTest` (with `@Nested` `RoundTrip`,
  `Atomicity`), `PostgresSagaContextStoreTest`, `LifecycleHistoryTest`, `ConcurrencyControlTest`, and the new
  `JoinedTransactionContainerTest` (T063). Any class that touches `PostgresTestSupport` starts a container in its
  static initializer: unit tests MUST NOT reference it (a `{@link}` in Javadoc is harmless).
- **Container guard** (part of every checkpoint): the Maven output has no `Tests run:` line naming a container
  class and no line containing `testcontainers`, `Creating container` or `ryuk`. If one appears, stop and report.

## Shared files

Phases run one at a time in the order below. These files are created by one phase and extended by later ones;
every other file has exactly one owning phase (its "Files owned by this phase" line).

| File | Created by | Extended by |
|---|---|---|
| `saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/SagaEngine.java` | US1 (T042) | US6 (T095) |
| `saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/internal/EventRouter.java` | US1 (T039) | US2 (T053), US4 (T072), US5 (T086) |
| `saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/internal/SagaUnitOfWork.java` | US1 (T038) | US2 (T052), US4 (T073), US5 (T084) |
| `saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/internal/NonSagaUnitOfWork.java` | US1 (T036) | US5 (T085) |
| `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/PostgresStorageProvider.java` | Foundational (T022) | US2 (T055), US4 (T074) |
| `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/ConcurrencyControlTest.java` | Setup (T002, tag) | Foundational (T023), US4 (T081) |
| `SchemaTest.java`, `PostgresStorageProviderTest.java`, `PostgresSagaContextStoreTest.java`, `LifecycleHistoryTest.java` (Postgres test) | Setup (T002, tag) | Foundational (T023) |
| `.specify/memory/memory.md` | Polish (T108) | any phase may append a new `G-ENG-NN` entry for a gap it finds (append-only) |

If a test in a later phase exposes a defect in a file owned by an earlier phase, fix it there and name the fix in
that task's report.

---

## Phase 1: Setup (container fence, G-003-27)

**Purpose**: Make a plain `mvn test`/`verify` unit-only before anything else changes, so every later checkpoint is
safe by default (research.md §14).

**Files owned by this phase**: `saga-forge-storage-postgres/pom.xml`; the `@Tag` line of the five container classes
(shared, see "Shared files").

- [x] T001 [P] Update `saga-forge-storage-postgres/pom.xml`: add `<saga.excludedTestGroups>container</saga.excludedTestGroups>`
  to `<properties>`; add `<build><plugins>` with `org.apache.maven.plugins:maven-surefire-plugin` version `3.2.5` and
  `<configuration><excludedGroups>${saga.excludedTestGroups}</excludedGroups></configuration>`; add a
  `<profiles>` entry `<id>container-tests</id>` whose `<properties>` set `saga.excludedTestGroups` to empty
  (`<saga.excludedTestGroups></saga.excludedTestGroups>`), so `-Pcontainer-tests` runs the container classes.
  Plugin and profile configuration only: no `<dependency>` is added, removed or changed (SC-008). Do not touch the
  root `pom.xml` here.
- [x] T002 [P] Add `@org.junit.jupiter.api.Tag("container")` (import `org.junit.jupiter.api.Tag`) at class level to
  `SchemaTest.java`, `PostgresStorageProviderTest.java` (its `@Nested` `RoundTrip` and `Atomicity` inherit it),
  `PostgresSagaContextStoreTest.java`, `LifecycleHistoryTest.java` and `ConcurrencyControlTest.java` in
  `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/`. Add one sentence to
  the class Javadoc of `PostgresTestSupport.java` in the same directory: every class that uses it must carry
  `@Tag("container")`, and it runs only with `-Pcontainer-tests`. Change nothing else in these files.
- [x] T003 Checkpoint (Phase 1). First, without running anything, confirm: `grep -l '@Tag("container")'` lists exactly
  the five classes of T002, and `saga-forge-storage-postgres/pom.xml` contains `excludedGroups` and the
  `container-tests` profile. Then run, still with the explicit 003 exclusion as a second fence:
  `/opt/homebrew/bin/mvn -B -pl saga-forge-storage-postgres -am clean verify -Dsurefire.failIfNoSpecifiedTests=false -Dtest='!SchemaTest,!PostgresStorageProviderTest*,!PostgresSagaContextStoreTest,!LifecycleHistoryTest,!ConcurrencyControlTest'`.
  Requires `BUILD SUCCESS`, zero failures, and the container guard (Path Conventions). From Phase 2 on the tag alone
  fences the container classes.

**Checkpoint**: a default build no longer selects any container class.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The new module skeleton, core's test-jar, every new and changed `core.spi` contract, the
`SagaLifecycleEntry`/`StorageProvider` ripple through core tests, the harness and the Postgres module (compile-level,
own-transaction mode only), and the engine test doubles. Every story depends on this phase.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

**Files owned by this phase**: root `pom.xml`; `saga-forge-core/pom.xml`; `saga-forge-engine/pom.xml`;
`saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/package-info.java`,
`.../engine/internal/package-info.java`; every file under
`saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/` touched below; core tests
`spi/HandlingReportTest.java`, `spi/HandedOffEventsTest.java`, `spi/SagaLifecycleEntryTest.java`,
`execution/harness/InMemoryStorageProvider.java`, `execution/harness/HarnessStoresTest.java`; Postgres
`ForwardTransition.java` (renamed), `LifecycleEntryRows.java`, `ConcurrentSagaUpdateException.java`,
`ForwardTransitionPropertyTest.java`, `LifecycleEntryRowsTest.java`, the deleted `LifecycleHistoryDerivationTest.java`;
engine `support/InMemoryTransactionBoundary.java`, `support/TransactionalInMemoryStorageProvider.java`,
`support/CapturingEventHandoff.java`, `support/RecordingLogger.java`, `support/TestTransport.java`,
`support/ConcurrentSagaContextStore.java`, `support/TestDoublesTest.java`. Shared: `PostgresStorageProvider.java`
(T022) and the five container classes (T023).

### Module skeleton and test-jar

- [x] T004 Add `<module>saga-forge-engine</module>` to the root `pom.xml` `<modules>`, directly after
  `<module>saga-forge-core</module>`. Change nothing else in the root POM.
- [x] T005 [P] Create `saga-forge-engine/pom.xml`: parent `io.github.gkosharovdev:saga-forge:0.1.0-SNAPSHOT`,
  `artifactId` `saga-forge-engine`, name "Saga Forge Engine", description "Saga runtime: drives saga instances over
  the core SPI contracts". Dependencies: `io.github.gkosharovdev:saga-forge-core:${project.version}` (compile, the
  only non-test dependency, FR-037); test scope `org.junit.jupiter:junit-jupiter`, `net.jqwik:jqwik`,
  `org.assertj:assertj-core` (versions from the root `dependencyManagement`), and
  `io.github.gkosharovdev:saga-forge-core:${project.version}` with `<type>test-jar</type>` (harness, fixtures,
  `InMemorySagaContextStore`, `RecordingEventDispatcher`, `MapHandlerInstanceProvider`). Build:
  `maven-jar-plugin` `3.4.1` with an execution running goal `test-jar` (the example module uses the engine's test
  doubles). Create `saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/package-info.java`
  (public engine API: `SagaEngine`, `StartupReport`, `SagaEngineStartupException`, `DispatchingEventHandoff`; depends
  on the JDK and `saga-forge-core` only; no Spring, JDBC, datastore or transport type, Constitution Principle I) and
  `.../engine/internal/package-info.java` (package-private runtime; not API).
- [x] T006 [P] Update `saga-forge-core/pom.xml`: add `<build><plugins>` with `maven-jar-plugin` `3.4.1` and an
  execution running goal `test-jar`, so the 002 harness and fixtures are published as `saga-forge-core:test-jar`
  (research.md §8). No dependency changes.

### New core SPI types (`saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/spi/`)

- [x] T007 [P] Create `TransactionBoundary.java` (`LocalTransaction begin()`, `Optional<LocalTransaction> current()`,
  `default boolean isActive()`) and `LocalTransaction.java` (`commit()`, `rollback()`, `setRollbackOnly()`,
  `isRollbackOnly()`, `<R> Optional<R> resource(Class<R> type)`) in `core/spi/`, with the Javadoc of
  contracts/transaction-boundary.md: thread-bound; `begin()` never joins (suspend or `IllegalStateException`, per
  implementation); ended-transaction and null-argument rules; commit on a rollback-only transaction rolls back and
  throws `IllegalStateException`; the JTA/Spring mapping table as an HTML table; joining rules for storage providers,
  aggregate access, `SagaContextStore` (never joins) and an outbox handoff (FR-020 to FR-022, G-ENG-03).
- [x] T008 [P] Create in `core/spi/`: `EventReceiver.java` (`HandlingReport receive(Object domainEvent)`, Javadoc =
  contracts/event-receiver.md pre-conditions, effect, failures, reentrancy); `HandlingOutcome.java` (`WAITING`,
  `COMPLETED`, `FAILED`, `DUPLICATE_START`, `UNMATCHED`, `UNEXPECTED`, `NOT_HANDLED`, each value documented per
  data-model.md "HandlingOutcome"); `SagaHandlingResult.java` (record `sagaType, sagaId, outcome, started,
  outcomeStep, failure, unitOfWorkId, handoffFailures`; compact constructor enforces the data-model.md rules:
  `sagaType`/`outcome` non-null, `outcomeStep` non-null iff `COMPLETED`, `failure` non-null iff `FAILED`,
  `handoffFailures` non-null, no `null` element, non-empty only for `WAITING`/`COMPLETED`, stored with
  `List.copyOf`; `IllegalArgumentException` otherwise); `HandlingReport.java` (record `event, results`, `event`
  non-null, `results` copied, `boolean ignored()` iff `results` is empty); `EventHandlingException.java` (unchecked;
  `EventHandlingException(String message, HandlingReport report, List<Throwable> causes)`, first cause as `getCause()`,
  the rest as suppressed; `HandlingReport report()`).
- [x] T009 [P] Create `EventHandoff.java` (nested `enum Timing { AFTER_COMMIT, IN_TRANSACTION }`,
  `default Timing timing()` returning `AFTER_COMMIT`, `void handOff(HandedOffEvents batch)`) and
  `HandedOffEvents.java` (record `unitOfWorkId, sagaType, sagaId, List<Object> events`; `IllegalArgumentException` for
  a `null` `unitOfWorkId`/`events`/element, an empty `events`, or exactly one of `sagaType`/`sagaId` present;
  `events` stored with `List.copyOf`) in `core/spi/`, with the Javadoc of contracts/event-handoff.md: which events,
  order, identity, both timings and their failure handling, the outbox note (FR-029 to FR-032, G-ENG-07).
- [x] T010 [P] Create `core/spi/SagaConcurrencyConflictException.java`: public, non-final, unchecked;
  `SagaConcurrencyConflictException(String sagaId, String message)`, `String sagaId()`; Javadoc per
  contracts/storage-provider.md: thrown by a storage write that detects a concurrent change; the engine reports
  `NOT_HANDLED`, never `FAILED` (FR-026).
- [x] T011 [P] Create `core/spi/HandlerParameterResolver.java` (`boolean supports(Executable handler, Parameter
  parameter)`, `Object resolve(Executable handler, Parameter parameter)`, both `java.lang.reflect`) and
  `core/spi/SagaObjectProvider.java` (`boolean supports(Class<?> sagaType)`, `Object newInstance(Class<?> sagaType)`),
  with the Javadoc of contracts/host-plugins.md (host-only, never client code; when each method is called; the
  engine overwrites the `@SagaId`, `SagaContext` and `CommandGateway` fields; other fields are dependencies, never
  persisted; null arguments → `IllegalArgumentException`).

### Changed core contracts and Javadoc corrections

- [x] T012 Change `core/spi/StorageProvider.java` per contracts/storage-provider.md: add abstract
  `void save(SagaInstance instance, List<SagaLifecycleEntry> newEntries)` (pre-conditions, effect, storage-assigned
  positions, `IllegalArgumentException` for a `null` argument/element or an entry whose `sagaId` differs from
  `instance.sagaId()`, `SagaConcurrencyConflictException` on a detected concurrent change); turn
  `save(SagaInstance)` into `default void save(SagaInstance instance) { save(instance, List.of()); }` (records no
  history); add abstract `Optional<SagaInstance> findByIdAndLock(String sagaId)` (requires a joined active transaction,
  else `IllegalStateException` before touching the store; exclusive per-`sagaId` lock until that transaction ends;
  missing instance → empty, nothing locked; must not block the `SagaContextStore`'s writes). Rewrite the class
  Javadoc (research.md §12, G-ENG-04): replace "persists instance state on every state transition" with the three
  engine writes (creation write, unit-of-work write, `FAILED` write); joining the active `TransactionBoundary`
  transaction when built with one, own transaction otherwise; no derivation. Keep the existing null-safety list and
  extend it to the new methods. `findById`, `findByAssociation`, `delete` unchanged.
- [x] T013 Change `core/spi/SagaLifecycleEntry.java`: remove the `sequenceNumber` component and its negative check;
  components become `sagaId, kind, stepName, touchedAggregates, outcomeStep, failure, occurredAt`; present-iff and
  null rules unchanged. Rewrite its Javadoc (G-ENG-02, research.md §4): produced by the engine and handed to
  `StorageProvider.save(instance, entries)` with the write it belongs to; persisted order = hand-over order, the
  position is assigned by storage; no tiebreak and no first-save limitation; `occurredAt` = the clock instant of the
  transition; still no query API (G-003-24). Rewrite the Javadoc of `core/spi/SagaLifecycleEventKind.java` the same
  way (`CREATED` = the creation write, `STEP_COMPLETED` = one completed step, `COMPLETED` = an `@EndSaga` outcome,
  `FAILED` = the `FAILED` write; no kind for `RUNNING`, G-003-18). Values unchanged.
- [x] T014 [P] Correct the event-routing Javadoc (G-ENG-04, research.md §12): in `core/spi/EventDispatcher.java`
  the dispatcher becomes outbound only (`dispatch` hands an event to the transport for delivery to `EventReceiver`s;
  routing and correlation belong to the engine; `registerSagaType` is called once per saga type at engine start,
  before the first event, and tells the transport which saga types it consumes; after-commit delivery goes through
  `EventHandoff`, whose default calls `dispatch`); remove "Implementations must route events to the correct saga
  instance" and "a new saga instance may be created". In `core/lifecycle/SagaLifecycle.java` replace "`EventDispatcher`
  resolves which instance an incoming event correlates to" with: the engine resolves instances through
  `StorageProvider.findByAssociation` behind `EventReceiver`, and persists returned snapshots with the creation,
  unit-of-work and `FAILED` writes. Javadoc only; no signature change.
- [x] T015 [P] Correct the remaining Javadoc (research.md §12), no signature change: `core/spi/SagaInstance.java`
  `state` becomes "reserved; not populated by the engine; values that must survive between steps belong in
  `SagaContext`" (G-ENG-06); `core/spi/SagaContextStore.java` gains "a record is saved only after its instance's
  creation write committed; never joins the unit of work's transaction" (G-ENG-01);
  `core/spi/DomainEventRecorder.java`, `core/event/EventPublisher.java` and `core/command/CommandGateway.java` replace
  after-commit delivery "for `EventDispatcher.dispatch`" with "through the after-commit handoff (`EventHandoff`; by
  default `EventDispatcher.dispatch`)"; `core/spi/HandlerInstanceProvider.java` widens from service-hosted handlers
  to also the classes hosting `@AggregateLoader`/`@AggregateSaver` methods, looked up on first use.

### Core test ripple

- [x] T016 [P] Create `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/spi/HandlingReportTest.java`
  (each `SagaHandlingResult` rule of T008 accepted and rejected; `handoffFailures` copied and unmodifiable;
  `HandlingReport.ignored()` iff no results; `EventHandlingException` exposes the report, the first cause and the
  others as suppressed) and `.../core/spi/HandedOffEventsTest.java` (valid saga-scoped and non-saga batches; every
  rejection of T009; `events` defensive copy, unmodifiable). JUnit 5 + AssertJ, style of `SagaLifecycleEntryTest`.
- [x] T017 Update `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/spi/SagaLifecycleEntryTest.java`
  to the component list of T013: drop the `sequenceNumber` argument everywhere, delete
  `negativeSequenceRejected` and the `sequenceNumber()` assertion in `stepCompleted`; every other case unchanged.
- [x] T018 Update `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/execution/harness/InMemoryStorageProvider.java`
  to the new SPI: replace the `save(SagaInstance)` override with `save(SagaInstance, List<SagaLifecycleEntry>)`
  (validates per T012; stores the instance; appends the entries to a per-`sagaId` list exposed as
  `List<SagaLifecycleEntry> history(String sagaId)`, included in `snapshot()`/`restore()`/`copy()`), and add
  `findByIdAndLock(String)` returning `findById` (Javadoc: the harness is single-threaded and has no transactions, so
  it neither locks nor requires one; the engine never uses this class). The harness itself, `PersistedState` and
  every 002 test stay unchanged. Add a nested class to `.../execution/harness/HarnessStoresTest.java` covering:
  entries appended in order across two saves, `save(instance)` adds none, mismatched `sagaId` and `null` element
  rejected, `findByIdAndLock` equals `findById`, history restored with a snapshot.

### Postgres ripple, own-transaction mode (compile-level; joining is US2, locking is US4)

- [x] T019 Rename `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/LifecycleHistoryDerivation.java`
  to `ForwardTransition.java` with `git mv` (class `ForwardTransition`, package-private, final). Keep
  `requireForwardTransition(previous, incoming)` and the private helpers it uses, unchanged in behavior; delete
  `derive(...)` and every helper only it used (research.md §4; G-003-04, G-003-16 and the derivation half of G-003-15
  go away). Class Javadoc: the forward-transition rule is defense in depth against a caller that bypasses the engine;
  history is never derived. In `.../postgres/ForwardTransitionPropertyTest.java` replace every
  `LifecycleHistoryDerivation` reference with `ForwardTransition`, rename
  `reSavingTheSameSnapshotIsAcceptedAndDerivesNothing` to `reSavingTheSameSnapshotIsAccepted`, and drop its `derive`
  assertion and the `next` parameter. Delete `.../postgres/LifecycleHistoryDerivationTest.java` (it tests only
  `derive`).
- [x] T020 [P] Create `.../storage/postgres/LifecycleEntryRows.java` (package-private, final; research.md §4a): move
  `INSERT_HISTORY` here from `PostgresStorageProvider`; `static List<Numbered> number(List<SagaLifecycleEntry>
  entries, long firstSequenceNumber)` (pure; `record Numbered(long sequenceNumber, SagaLifecycleEntry entry)`,
  consecutive from `firstSequenceNumber`, list order kept); `static void insertAll(Connection connection,
  List<SagaLifecycleEntry> entries, long firstSequenceNumber)` (no-op for an empty list; one batch; binds the eleven
  columns exactly as today's `insertHistory`, through `SagaInstanceRows.encodeTouchedAggregates`, `bindFailure` and
  `setInstant`). Create `.../postgres/LifecycleEntryRowsTest.java` (pure, no `PostgresTestSupport`): numbering starts
  at the given value, is consecutive and keeps list order (including step names that sort the other way); an empty
  list yields nothing; `INSERT_HISTORY` names the eleven columns in bind order.
- [x] T021 [P] Make `.../storage/postgres/ConcurrentSagaUpdateException.java` extend
  `io.github.gkosharovdev.sagaforge.core.spi.SagaConcurrencyConflictException` (T010): pass `sagaId` and the existing
  message to `super`, remove its own `sagaId` field and accessor (inherited), keep the message format and
  `serialVersionUID`. Javadoc: the engine treats it as `NOT_HANDLED` (FR-026).
- [x] T022 Adapt `.../storage/postgres/PostgresStorageProvider.java` to the T012 contract, own-transaction mode only:
  implement `save(SagaInstance, List<SagaLifecycleEntry>)` (argument checks of T012 before any SQL, then
  `requireStorable`; in one transaction: `lockPrevious`, `ForwardTransition.requireForwardTransition`,
  `nextSequenceNumber`, upsert, first-save race check, `replaceAssociations`,
  `LifecycleEntryRows.insertAll(connection, entries, next)`); remove the `save(SagaInstance)` override (the
  interface default now records no history) and the old `insertHistory`; implement `findByIdAndLock(String)` as
  `IllegalArgumentException` for `null`, else `IllegalStateException` ("built without a TransactionBoundary; cannot
  lock"), which is the contract's final behavior for the `DataSource`-only constructor (contracts/storage-provider.md).
  Rewrite the class and `save` Javadoc: persists the entries handed over, numbered `MAX + 1` consecutively in list
  order, never derives. `LOCK_PREVIOUS` stays `FOR UPDATE OF i` until T074.
- [x] T023 Update the container classes to the new write contract, **edit and compile only, never run**
  (`.../postgres/` test directory): in `LifecycleHistoryTest.java` hand the entries explicitly to every save
  (`fullLifecycleInOrder`: `[CREATED]`, `[STEP_COMPLETED A]`, `[STEP_COMPLETED B]`, `[COMPLETED]`, rows 0 to 3 in that
  order; `failedRowMatchesInstanceFailure`: a `[FAILED]` entry carrying `instance.failure()`; `notRunStepHasNoRow`
  and `deleteRemovesHistory` likewise), rename `unchangedResaveAddsNothing` to `saveWithoutEntriesAddsNoRow`, replace
  `sameSaveStepsUseAlphabeticalTiebreakNotTrueOrder` with `oneSaveKeepsHandOverOrder` (entries `zeta` then `alpha` in
  one save → rows `zeta`, `alpha`; G-003-04, G-003-16, SC-010), and add `entriesAppendAfterExistingHistory` (a second
  save's entries are numbered after the first's). In `PostgresStorageProviderTest.java` extend `RoundTrip.nullRules`
  with `save(instance, null)`, a list holding `null`, and an entry with another `sagaId` (all
  `IllegalArgumentException`, nothing written), and make both `Atomicity` tests hand entries so the
  `saga_lifecycle_history` `INSERT` fault trigger still fires. In `ConcurrencyControlTest.java` pass explicit entries
  wherever a test expected derived rows, and make `identicalConcurrentSaves` hand no entries (both saves succeed, no
  history row is added). `PostgresSagaContextStoreTest.java` and `SchemaTest.java` need no change beyond T002. Compile
  check: `/opt/homebrew/bin/mvn -B -pl saga-forge-storage-postgres -am -DskipTests clean verify`.

### Engine test doubles (`saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/support/`)

All are test-only scaffolding (Javadoc says so; never move to `src/main`, Constitution Principle IV) and thread-safe
unless stated.

- [x] T024 Create `support/InMemoryTransactionBoundary.java` implementing `TransactionBoundary`: thread-bound
  `LocalTransaction`s; `begin()` throws `IllegalStateException` if one is active on the thread; the contract's
  ended-transaction and rollback-only rules (commit on rollback-only rolls back and throws). Each transaction exposes
  `resource(InMemoryTransactionBoundary.Scope.class)`, a per-transaction `Scope` with `onCommit(Runnable)`,
  `onRollback(Runnable)` and `onEnd(Runnable)` hooks, which the storage double and `CapturingEventHandoff` use to join
  (the same `resource(Class)` path Postgres uses with `Connection`). Participants:
  `participant(Snapshottable)` (core test-jar `execution/fixtures/inprocess/Snapshottable`) snapshotted at `begin` and
  restored at `rollback`, standing in for the host's transaction over client storage. Records `begin`/`commit`/
  `rollback` counts and per-thread history; `failNextCommit(RuntimeException)` injects a commit failure (the
  transaction is rolled back, then the exception thrown).
- [x] T025 Create `support/TransactionalInMemoryStorageProvider.java` implementing `StorageProvider` over an
  `InMemoryTransactionBoundary` (constructor argument): inside an active transaction every write is buffered in its
  `Scope` and applied at commit, discarded at rollback, so other threads see committed state only (US1-2); reads in a
  transaction see its own buffered writes. Outside a transaction writes apply immediately (own-transaction mode).
  `save(instance, entries)` per T012, entries numbered by the store at apply time, consecutive after the instance's
  committed history (`List<StoredEntry> history(String sagaId)`, `record StoredEntry(long sequenceNumber,
  SagaLifecycleEntry entry)`). `findByIdAndLock`: `IllegalStateException` with no active transaction; a fair per-`sagaId`
  lock owned by the transaction, re-entrant within it, released by the `Scope`'s `onEnd`; two engines sharing one
  instance of this class model two processes (US3, US4). `findByAssociation` in insertion order. Fault injection:
  `failNextSave(RuntimeException)`, `conflictOnNextSave(String sagaId)` (throws `SagaConcurrencyConflictException`
  from that save), `failNextFindByIdAndLock(RuntimeException)`; `committed()` returns a committed-only view for
  observers; `saveCalls()` counts writes.
- [x] T026 [P] Create in `support/`: `CapturingEventHandoff.java` (implements `EventHandoff`; timing set in the
  constructor; records every batch it receives; with `IN_TRANSACTION` it keeps a batch only when the receiving
  transaction commits, through `InMemoryTransactionBoundary.Scope`; `received()`, `committed()`,
  `failNext(RuntimeException)`; optional delegate for loop-back); `RecordingLogger.java` (`System.Logger` recording
  level, message, throwable; parses the leading event name and `key=value` pairs of contracts/diagnostics.md;
  `records()`, `byEvent(String)`, `isLoggable` true for every level); `TestTransport.java` (implements
  `EventDispatcher` only: `registerSagaType` and `dispatch` are recorded; `connect(EventReceiver)`;
  `deliver(Object event)` calls `receive` and records the `HandlingReport`; with `loopBack(true)` each dispatched event
  is delivered back into the receiver synchronously from inside `dispatch`; `reports()`, `dispatched()`,
  `registeredSagaTypes()`; it uses nothing of the engine but `EventReceiver`, SC-007); `ConcurrentSagaContextStore.java`
  (`SagaContextStore` over a `ConcurrentHashMap`, for the concurrency tests; core's `InMemorySagaContextStore` is not
  thread-safe).
- [x] T027 Create `support/TestDoublesTest.java` (JUnit 5 + AssertJ): boundary begin/commit/rollback/rollback-only and
  second-`begin` rules, participant snapshot/restore, commit-failure injection; storage double write visibility
  before and after commit, discard on rollback, entry numbering across two committed writes, `findByIdAndLock`
  without a transaction, a second thread's `findByIdAndLock` blocking until the first transaction ends (with a
  timeout), conflict and save-failure injection; `CapturingEventHandoff` keeping `IN_TRANSACTION` batches only on
  commit; `RecordingLogger` parsing `saga.uow.committed sagaType=X sagaId=Y`.
- [x] T028 Checkpoint (Phase 2). Run
  `/opt/homebrew/bin/mvn -B -pl saga-forge-core,saga-forge-engine,saga-forge-storage-postgres -am clean verify`.
  Requires `BUILD SUCCESS`, zero failures (core including every 002 harness test unchanged, `HarnessStoresTest`,
  `SagaLifecycleEntryTest`, the new record tests; engine `TestDoublesTest`; Postgres `JsonCodecTest`,
  `ForwardTransitionPropertyTest`, `LifecycleEntryRowsTest`), the container classes compiled but not run, and the
  container guard.

**Checkpoint**: contracts, ripple and doubles are in place; user stories can start.

---

## Phase 3: User Story 1 - An event starts a saga and runs it to an outcome in one atomic unit of work (Priority: P1) 🎯 MVP

**Goal**: `SagaEngine` routes an inbound event, commits a `CREATED` instance in its own write (FR-012), runs the
start step and every in-process step in recording order in one host transaction, commits at the first `@EndSaga`
step or a wait point, records lifecycle entries in true order (FR-033), and hands the buffered events off after
commit through `DispatchingEventHandoff` (FR-001 to FR-004, FR-006, FR-008 to FR-010, FR-012 to FR-019, FR-029).
Failures roll back here but are only marked `FAILED` in US2; locks come in US4; `IN_TRANSACTION` handoff, handoff
failures and reentrancy in US5; engine startup checks in US6.

**Independent Test**: assemble the engine over `InMemoryTransactionBoundary`, `TransactionalInMemoryStorageProvider`,
`InMemorySagaContextStore` and `RecordingEventDispatcher`; `receive` the start event of `ThreeStepSaga` and check the
committed instance, aggregates, context values, lifecycle history and after-commit dispatches; run the same sequence
on the 002 `SagaTestHarness` and compare (quickstart.md scenarios 2, 3, 4, 17, 21).

**Files owned by this phase** (engine main `saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/`):
`SagaEngine.java` (shared, see "Shared files"), `StartupReport.java`, `SagaEngineStartupException.java`,
`DispatchingEventHandoff.java`, `internal/Reflection.java`, `internal/SagaModel.java`, `internal/Diagnostics.java`,
`internal/EngineSagaContext.java`, `internal/EngineSagaLifecycle.java`, `internal/AggregateAccess.java`,
`internal/EngineCommandGateway.java`, `internal/NonSagaUnitOfWork.java` (shared), `internal/EngineEventPublisher.java`,
`internal/SagaUnitOfWork.java` (shared), `internal/EventRouter.java` (shared); engine test
`saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/`: `internal/EngineSagaContextTest.java`,
`internal/EngineSagaLifecycleTest.java`, `internal/EngineCommandGatewayTest.java`, `DispatchingEventHandoffTest.java`,
`StartupReportTest.java`, `support/DifferentialRun.java`, `ThreeStepSagaDifferentialTest.java`,
`SagaEngineHappyPathTest.java`, `LifecycleHistoryTest.java`, `OutsideUnitOfWorkTest.java`.

### Implementation for User Story 1 (`.../engine/internal/` unless noted)

- [x] T029 [P] [US1] Create `saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/internal/Reflection.java`
  (production counterpart of the harness's `HarnessReflection`): read a named property from an object (record
  accessor, getter, field; `Optional`), read the `@AggregateIdentifier` value of an event or command, set a field
  (accessible), find the `@SagaId` field and the `SagaContext`- and `CommandGateway`-typed fields, instantiate through
  a no-argument constructor of any visibility, and invoke a method or constructor rethrowing an
  `InvocationTargetException`'s cause unchanged (`RuntimeException`/`Error` as is, a checked cause wrapped once).
- [x] T030 [P] [US1] Create `.../engine/internal/SagaModel.java`: built once per registered `@Saga` class at `start()`
  from the already-validated class; holds the saga type, its `Step`s keyed by exact event class (`record Step(String
  name, Method method, Class<?> eventType, String associationProperty, boolean start, boolean end)`), the start step,
  and the three injectable fields. `Optional<Step> stepFor(Class<?>)`, `List<String> stepNames()`.
- [x] T031 [P] [US1] Create `.../engine/internal/Diagnostics.java` over a `System.Logger`: one method per row of
  contracts/diagnostics.md (all fourteen event names, levels and keys), message = event name then `key=value` pairs
  with unknown keys omitted, `Supplier<String>` messages below `INFO`, throwables attached where the table says so.
  Later phases only call these methods (FR-038).
- [x] T032 [P] [US1] Create `.../engine/internal/EngineSagaContext.java` implementing `core.lifecycle.SagaContext`
  for one `sagaId` over any `SagaContextStore` and the engine `Clock`: starts from the stored `SagaContextRecord` (or
  empty), the value semantics of feature 001/002 (names non-blank, `null` rejected, type-safe `getState` with
  `SagaStateTypeMismatchException`, `updateStateAttribute` on structured values only with
  `NonStructuredStateValueException`), and write-through: `contextStore.save(new SagaContextRecord(...))` after every
  `putState`/`updateStateAttribute`, outside the unit of work's transaction (FR-013). Port the rules from
  `saga-forge-core/src/test/.../execution/harness/HarnessSagaContext.java`; never share an instance between sagaIds.
- [x] T033 [P] [US1] Create `.../engine/internal/EngineSagaLifecycle.java` implementing `core.lifecycle.SagaLifecycle`
  over a per-write working copy instead of storage writes: `start(...)` checks `StorageProvider.findByAssociation` for
  an active instance of the saga type (→ `DuplicateSagaInstanceException`, 001 FR-006), builds the `CREATED` instance
  (fresh id from the saga-id supplier, every step `NOT_RUN`, associations `{property: value}`, `state` `Map.of()`,
  G-ENG-06) and a `CREATED` entry (`occurredAt = createdAt`), without persisting; `recordStepCompleted`, `advance`,
  `complete(sagaId, outcomeStep)` and `fail` apply the 001/002 transition rules (terminal instances rejected with
  `InvalidSagaTransitionException`) to the working copy and append `STEP_COMPLETED`, nothing, `COMPLETED`, `FAILED`
  entries respectively (data-model.md "Lifecycle entry production"); `load(SagaInstance)` seeds the working copy from
  a locked read; `drain(sagaId)` returns the instance and its pending entries for one write and clears them.
- [x] T034 [US1] Create `.../engine/internal/AggregateAccess.java`: discovers `@AggregateLoader`/`@AggregateSaver`
  methods on the registered classes, keyed by aggregate type; host instances from `HandlerInstanceProvider` on first
  use (FR-016, contracts/host-plugins.md); ordered `AggregateResolver`s, the first whose `supports` is true serves
  both load and save. `Optional<Object> load(Class<?> type, Object id)`: client loader, else resolver, else empty;
  `save(Object aggregate)`: client saver, else resolver, else nothing (left to the client's own unit of work).
- [x] T035 [US1] Create `.../engine/internal/EngineCommandGateway.java` implementing `core.command.CommandGateway`
  per `specs/002-transactional-step-sequence/contracts/command-gateway.md` and contracts/contract-deltas.md: a routing
  table built at `start()` (command type → its single `@CommandHandler`: service method, `@Aggregate` instance
  method, or `@Aggregate` constructor/static factory); `send` validates the command (`null`, not `@Command` →
  `IllegalArgumentException`), then, if an engine unit of work is bound on this thread (`DomainEventScope.current()`
  is a `SagaUnitOfWork` or `NonSagaUnitOfWork`), runs the handler synchronously in it: service instance from
  `HandlerInstanceProvider`; instance-method target loaded through `AggregateAccess` by the command's
  `@AggregateIdentifier` (absent → `AggregateNotFoundException`), handler invoked, aggregate saved; creation handler
  invoked and the new aggregate saved. The `@Command` argument is supplied by the engine; every other parameter by the
  first `HandlerParameterResolver` that `supports` it (none → `IllegalStateException` naming handler and parameter;
  US6 rejects this at startup) (FR-019). No handler → `NoCommandHandlerException`. Any failure (handler, loader, saver,
  resolver, provider, parameter resolver) calls `setRollbackOnly()` on the current `LocalTransaction` and reports the
  exception to the bound unit of work (`failed(Throwable)`, the first one is kept, so a failure a step catches is still
  known) before it leaves `send`, unchanged (002 FR-012). With no engine unit of work on the thread (including a thread a
  handler started), `send` runs the handler in a new `NonSagaUnitOfWork` (FR-016).
- [x] T036 [US1] Create `.../engine/internal/NonSagaUnitOfWork.java` (implements `DomainEventRecorder`): fresh
  `unitOfWorkId`; `begin()`; bind itself with `DomainEventScope.runWith` around the handler call; record every event
  (validated `@DomainEvent`); commit; then hand every recorded event, in recording order, to the configured
  `EventHandoff` as one `HandedOffEvents(uowId, null, null, events)` if non-empty (FR-016, FR-029). Provides
  `failed(Throwable)` for the gateway. On failure, or a rollback-only transaction at commit time: roll back, discard
  the events, rethrow the original (or first recorded) exception from `send`. (`IN_TRANSACTION` timing and handoff failures:
  T085.)
- [x] T037 [US1] Create `.../engine/internal/EngineEventPublisher.java` implementing `core.event.EventPublisher`:
  inside an engine unit of work equivalent to `DomainEvents.record`; outside one, validate the event (`null`, not
  `@DomainEvent` → `IllegalArgumentException`) and call `EventDispatcher.dispatch` immediately, without looping it back
  into the engine (FR-018, contracts/event-handoff.md last section).
- [x] T038 [US1] Create `.../engine/internal/SagaUnitOfWork.java` (implements `DomainEventRecorder`) following
  data-model.md "Saga-scoped unit of work, step by step" rows 2 to 6 and 8 (row 7, the `IN_TRANSACTION` handoff, is
  T084; rows 0 and 1, the locks, are T072) and contracts/contract-deltas.md: fresh
  `unitOfWorkId`; `begin()`; `findByIdAndLock(sagaId)` as the first storage call (missing → `UNMATCHED`, terminal →
  `UNEXPECTED`, `CREATED` for a non-start step → `NOT_HANDLED`, each after rolling back, with the matching
  `Diagnostics` call); load the context record into an `EngineSagaContext`; create a fresh saga object
  (`SagaObjectProvider.newInstance`, else the no-argument constructor) and set the `@SagaId`, `SagaContext` and
  `CommandGateway` fields (FR-009); bind itself with `DomainEventScope.runWith` and run the triggered step, then the
  queued events in recording order. `record(event)`: adds `AggregateReference` (`@DomainEvent.aggregate`,
  `@AggregateIdentifier` value) to the running step's touched aggregates; queues it if it triggers a non-start step
  of this saga type with this instance's association value, else buffers it (FR-008, FR-017). After each step:
  `recordStepCompleted`; at the first `@EndSaga` step `complete(sagaId, step)` and stop (remaining queued events move
  to the buffer); at an empty queue `advance`. Then `save(instance, entries)` from `EngineSagaLifecycle.drain`,
  `commit()`, log `saga.uow.committed`, and hand the buffered events to `EventHandoff` as one
  `HandedOffEvents(uowId, sagaType, sagaId, events)` if non-empty, after the commit. Returns a `SagaHandlingResult`
  (`WAITING` or `COMPLETED` with `outcomeStep`). Provide `failed(Throwable)` for the gateway (T035) and track the
  name of the step running or last run. On any failure in this phase's code: roll back, discard queue and buffer,
  rethrow (T052 replaces this with the `FAILED` write).
- [x] T039 [US1] Create `.../engine/internal/EventRouter.java` implementing the `receive` algorithm of
  contracts/event-receiver.md: validate first (FR-006: `null`, not `@DomainEvent`, or a missing/empty association
  value for any reacting step of any saga type → `IllegalArgumentException` naming event type, saga type, step and
  property, before anything runs); no reacting saga type → `HandlingReport` with no results, log `saga.event.ignored`.
  For each saga type in registration order: start step → `EngineSagaLifecycle.start` (`DuplicateSagaInstanceException`
  → `DUPLICATE_START`, log `saga.event.duplicateStart`), then the creation write in its own transaction (`begin`,
  `save(instance, [CREATED])`, `commit`, FR-012, FR-024, G-ENG-01), then a `SagaUnitOfWork` (`started = true`);
  non-start step → `findByAssociation(property, value)` filtered to the saga type; one `SagaUnitOfWork` per active
  instance in returned order, each committed or rolled back independently; none → `UNMATCHED`
  (`saga.event.unmatched`); only terminal ones → one `UNEXPECTED` result for the most recently returned
  (`saga.event.unexpected`) (FR-002 to FR-004). Collect one `SagaHandlingResult` per affected saga type/instance.
- [x] T040 [P] [US1] Create public `saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/DispatchingEventHandoff.java`
  implementing `EventHandoff` (`AFTER_COMMIT`): constructor `(EventDispatcher)`; `handOff` calls `dispatch` for each
  event in order; a failing `dispatch` does not stop the rest; at the end the first failure is thrown with the others
  suppressed. Javadoc: at most once, lost if the process stops before or during the call (FR-030, G-ENG-07).
- [x] T041 [P] [US1] Create public `.../engine/StartupReport.java` (record `ValidationReport validation, List<String>
  engineErrors`; unmodifiable copies, no `null`s; `isValid()`; `allErrors()` = `validation.allErrors()` then
  `engineErrors`) and `.../engine/SagaEngineStartupException.java` (unchecked; `report()`; message lists
  `allErrors()` one per line), per contracts/engine-startup.md.
- [x] T042 [US1] Create public `.../engine/SagaEngine.java` implementing `EventReceiver`, with the builder of
  contracts/engine-startup.md: `register(Class<?>...)`, `storage`, `contextStore`, `transactions`, `dispatcher`
  (required), `handlerInstances` (required when a registered class hosts a service handler, loader or saver),
  repeatable ordered `aggregateResolver` and `parameterResolver`, `sagaObjects`, `handoff` (default
  `DispatchingEventHandoff` over the dispatcher), `clock` (default system UTC), `sagaIds`, `unitOfWorkIds` (default
  random UUIDs), `logger` (default `System.getLogger("io.github.gkosharovdev.sagaforge.engine")`). `build()` throws
  `IllegalArgumentException`/`IllegalStateException` for missing or `null` required inputs only; the handoff's
  `timing()` is read once here. States `BUILT` → `STARTED` | `REFUSED` (data-model.md). `start()` (this phase):
  `ReflectiveAnnotationScanner.validateAll(registered)`; any error → `REFUSED`, log `saga.startup.refused`, throw
  `SagaEngineStartupException(new StartupReport(report, List.of()))`; else build the `SagaModel`s and the command
  routes, call `EventDispatcher.registerSagaType` for every saga class in registration order (a failure refuses the
  engine with the engine-startup.md message), become `STARTED`, log `saga.startup.started` (FR-034, FR-036; US6 adds
  the engine checks in T095). `start()` twice → `IllegalStateException`. `receive` delegates to `EventRouter`;
  `receive`, `commandGateway().send` and `eventPublisher().publish` throw `IllegalStateException` unless `STARTED`.
  Accessors `commandGateway()`, `eventPublisher()`, `isStarted()`.

### Unit tests for User Story 1 (`saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/`)

- [x] T043 [P] [US1] Create `internal/EngineSagaContextTest.java` (names, `null` rejection, type-safe reads and
  mismatch, structured update, write-through after every call, isolation between two sagaIds) and
  `internal/EngineSagaLifecycleTest.java` (each transition and its entry, `advance` adds none, terminal rejection,
  duplicate detection through the store, `drain` returns entries in transition order and clears them).
- [x] T044 [P] [US1] Create `DispatchingEventHandoffTest.java` (order, every event attempted after a failure, first
  failure thrown with the rest suppressed) and `StartupReportTest.java` (`isValid`, `allErrors` order, copies,
  exception message).
- [x] T045 [US1] Create `internal/EngineCommandGatewayTest.java` over the core test-jar booking fixtures
  (`PaymentService` service handler, `Inventory` via `InventoryRepository` loader/saver, `Booking` creation handler
  saved by `BookingRepository`) inside a bound unit of work: routing, `NoCommandHandlerException`,
  `AggregateNotFoundException`, first supporting `AggregateResolver` wins for load and save, a non-command parameter
  supplied by a `HandlerParameterResolver` (a handler class declared in the test), rollback-only set before a handler
  failure leaves `send`.
- [x] T046 [US1] Create `support/DifferentialRun.java` (runs one event sequence on a 002 `SagaTestHarness` and on a
  `SagaEngine` built over this phase's doubles with the same fixed `Clock`, the same deterministic saga-id supplier
  and separate fixture instances; exposes both sides' instances, step records, outcome, failure, context values,
  aggregate/service state and dispatched events for comparison) and `ThreeStepSagaDifferentialTest.java`: the 002
  User Story 1 happy-path scenarios for `ThreeStepSaga` give equal results on both (SC-001, SC-009; quickstart.md
  scenario 3).
- [x] T047 [US1] Create `SagaEngineHappyPathTest.java`: `ThreeStepSaga` → one instance `COMPLETED` with outcome
  `onShipmentScheduled`, every step `COMPLETED` with its touched aggregates, service effects committed (US1-1); an
  observer thread reading `TransactionalInMemoryStorageProvider.committed()` from inside a step sees no step record,
  status or outcome of the running unit of work (US1-2, quickstart 2); a later step reads the `SagaContext` value an
  earlier step wrote, and an incompatible type fails (US1-3); steps run in event order despite declaration order,
  via `ThreeStepSagaProbe` (US1-6, quickstart 4); `CreateBookingSaga`'s first unit of work loads and saves `Inventory`
  through `InventoryRepository` (US1-5) and hands `PaymentInitiatedEvent` to the dispatcher exactly once, after
  commit (US1-4).
- [x] T048 [US1] Create `LifecycleHistoryTest.java` (engine): `ThreeStepSaga` → history `CREATED`, `STEP_COMPLETED`
  for `onOrderPlaced`, `onItemsReserved`, `onInvoiceIssued`, `onShipmentScheduled` in that order (which is not
  alphabetical), then `COMPLETED(onShipmentScheduled)`; `CreateBookingSaga`'s first unit of work → `CREATED` plus two
  `STEP_COMPLETED`, no status entry for `RUNNING`; storage numbers are consecutive from 0 (US1-8, SC-010,
  quickstart 17 committed part).
- [x] T049 [US1] Create `OutsideUnitOfWorkTest.java`: `eventPublisher().publish(new OrderPlaced(...))` outside a unit
  of work reaches `RecordingEventDispatcher` immediately and starts nothing by itself; delivering it back through
  `receive` starts a new instance (US1-7); `commandGateway().send(new ReserveItems(...))` outside a unit of work
  commits `ReservationService`'s change and hands its `ItemsReserved` to the handoff with `sagaType`/`sagaId` `null`;
  with `ReservationService.failNext()` armed (registered as an `InMemoryTransactionBoundary` participant) it rolls
  back the change, hands nothing off and rethrows (FR-016, FR-018, quickstart 21).
- [x] T050 [US1] Checkpoint (US1). Run `/opt/homebrew/bin/mvn -B -pl saga-forge-engine -am clean verify`. Requires
  `BUILD SUCCESS`, zero failures in core and engine, and the container guard.

**Checkpoint**: MVP increment 1. The happy path runs on the engine and matches the harness.

---

## Phase 4: User Story 2 - A failure rolls everything back and leaves the saga FAILED (Priority: P1)

**Goal**: any failure inside a unit of work (step, handler, loader, saver, resolver, handler-instance provider,
parameter resolver, instance write, commit, or a failure a step caught) rolls the host transaction back in full,
discards the buffered events, and records `FAILED` with a `SagaFailure` in a separate locked write; `SagaContext`
values survive (FR-010, FR-011, FR-024, FR-005 error path). The Postgres provider joins the engine-controlled
transaction (FR-022, FR-023, G-ENG-03).

**Independent Test**: run the failure fixture saga on the engine with a failure injected at every position; each
leaves zero aggregate changes, zero `COMPLETED` steps of that unit of work, zero handed-off events, a `FAILED`
instance naming the step, and the earlier context values (quickstart.md scenario 5, SC-002); Postgres joining is
checked with the JDBC fake (quickstart.md scenario 23, joined/not-joined part).

**Files owned by this phase**: engine main `.../engine/internal/FailedWrite.java`; shared edits of
`internal/SagaUnitOfWork.java` (T052) and `internal/EventRouter.java` (T053); engine test
`saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/fixtures/failure/*`,
`FailurePositionTest.java`, `FailureDifferentialTest.java`, `LifecycleHistoryPropertyTest.java`; Postgres main
`JdbcTransactionBoundary.java`, `package-info.java`, shared edit of `PostgresStorageProvider.java` (T055); Postgres test
`JdbcFake.java`, `JdbcTransactionBoundaryTest.java`, `PostgresStorageProviderJoinTest.java`,
`JoinedTransactionContainerTest.java` (container, compile only).

### Implementation for User Story 2

- [x] T051 [P] [US2] Create `saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/internal/FailedWrite.java`
  (data-model.md row 9, research.md §7): in its own transaction `begin()`, `findByIdAndLock(sagaId)`, then if the
  stored instance is terminal (or missing) write nothing, roll back, log `saga.instance.failedNotRecorded` and report
  `FAILED` with the failure and the instance unchanged; else `EngineSagaLifecycle.load` + `fail(sagaId, failure)`,
  `save(failed, [FAILED])`, `commit()`, log `saga.instance.failed`. It always updates the committed creation record,
  never creates one. A failure of this write propagates to the caller (no outcome recorded, FR-005). G-ENG-10 window
  documented in the Javadoc.
- [x] T052 [US2] Extend `.../engine/internal/SagaUnitOfWork.java` (shared) with the failure path of data-model.md
  row 9: any exception from loading the context record, creating the saga object, a step, a handler (reported through
  `failed(Throwable)` even if the step caught it), `save` or `commit`, and a rollback-only transaction found before
  `save`, ends the run: roll back (if still active), discard queue, buffer and pending entries, build
  `SagaFailure(stepName = the step running or last run, causeType = cause class name, message, failedAt =
  clock.instant())`, log `saga.uow.rolledBack` (with the throwable), run `FailedWrite`, return a `FAILED`
  `SagaHandlingResult` with that failure (`started` as passed in). No further step runs. A failure of `FailedWrite`
  propagates. Remove the T038 rethrow.
- [x] T053 [US2] Extend `.../engine/internal/EventRouter.java` (shared) with the FR-005 error path (contracts/
  event-receiver.md "Failure"): when for an instance no outcome could be recorded (storage unavailable in
  `findByAssociation`/`findByIdAndLock`, creation write failed, `FailedWrite` failed), record the cause, continue with
  the remaining saga types and instances, then throw `EventHandlingException` carrying the partial `HandlingReport`
  and all causes; log `saga.event.notHandled`. A creation-write failure creates nothing and has no `SagaHandlingResult`
  of its own.
- [x] T054 [P] [US2] Create public `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/JdbcTransactionBoundary.java`
  implementing `TransactionBoundary` over a `DataSource` per contracts/transaction-boundary.md "JdbcTransactionBoundary":
  `begin()` throws `IllegalStateException` if a transaction is bound on the thread, else takes a connection, remembers
  and sets `autoCommit = false`, binds it; `commit()`/`rollback()` end it, restore `autoCommit`, close and unbind (a
  failure wrapped in `UncheckedSqlException` after unbinding and closing); commit on rollback-only rolls back and
  throws `IllegalStateException`; `resource(Connection.class)` returns the bound connection, any other type empty.
  Javadoc: never suspends; the pool needs two connections per concurrent unit of work (transaction + context store).
  Mention it in `.../storage/postgres/package-info.java`. JDBC only, no dependency (FR-023, FR-037).
- [x] T055 [US2] Extend `.../storage/postgres/PostgresStorageProvider.java` (shared) with joining (FR-022, FR-023,
  contracts/storage-provider.md table): add constructor `(DataSource, TransactionBoundary)` (both non-null); for
  `save(instance, entries)`, `findById`, `findByAssociation` and `delete`, when the boundary has an active transaction
  use `current().flatMap(tx -> tx.resource(Connection.class))` (absent → `IllegalStateException` naming
  `java.sql.Connection`), never commit, roll back, change `autoCommit` on or close it, and on `SQLException` call
  `setRollbackOnly()` before throwing `UncheckedSqlException`; with no active transaction, or with the
  `DataSource`-only constructor, keep 003's own-transaction behavior exactly. `findByIdAndLock` stays as T022 until
  T074. `PostgresSagaContextStore` is not changed (own connection, FR-022).

### Unit tests for User Story 2

- [x] T056 [P] [US2] Create the failure fixture in `saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/fixtures/failure/`
  (SC-002 positions; `ThreeStepSaga` has service handlers only): `FailureSaga` (`@StartSaga onParcelRequested`
  writes context `weight` and sends `ChargeFee`; `onFeeCharged` writes context `feeId` and sends `ReserveSlot`;
  `@EndSaga onSlotReserved` sends `PrintLabel`; all `associationProperty = "parcelId"`); service `FeeService`
  (`@CommandHandler handle(ChargeFee, FeeSchedule)`, the `FeeSchedule` parameter supplied by `FeeScheduleResolver`, a
  `HandlerParameterResolver`) recording `FeeCharged`; `@Aggregate Slot` (instance-method handler, loaded/saved by
  `SlotRepository`'s `@AggregateLoader`/`@AggregateSaver`) recording `SlotReserved`; `@Aggregate Label`
  (instance-method handler, loaded/saved through `LabelResolver`, an `AggregateResolver`) recording `LabelPrinted`,
  which no step reacts to (buffered); events `ParcelRequested`, `FeeCharged`, `SlotReserved`, `LabelPrinted`,
  commands `ChargeFee`, `ReserveSlot`, `PrintLabel`, aggregates for every `@DomainEvent(aggregate = ...)`;
  `FaultPlan` with `enum FaultPoint { START_STEP, FEE_STEP, END_STEP, FEE_HANDLER, SLOT_HANDLER, LABEL_HANDLER,
  SLOT_LOADER, SLOT_SAVER, RESOLVER_LOAD, RESOLVER_SAVE, HANDLER_INSTANCE_PROVIDER, PARAMETER_RESOLVER }` and a
  `catchInFeeStep` flag (`onFeeCharged` catches the `SLOT_HANDLER` failure); `FailureSagaObjects` (a
  `SagaObjectProvider` injecting the `FaultPlan`); `FailingHandlerInstances` (wraps `MapHandlerInstanceProvider`,
  throws at `HANDLER_INSTANCE_PROVIDER`). `FeeService`, `SlotRepository` and `LabelResolver` are `Snapshottable` and
  expose their committed state.
- [x] T057 [US2] Create `saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/FailurePositionTest.java`
  (`@ParameterizedTest` over every `FaultPoint`, plus the instance write via
  `TransactionalInMemoryStorageProvider.failNextSave` and the commit via `InMemoryTransactionBoundary.failNextCommit`,
  so at least 14 positions): zero aggregate changes in `FeeService`, `SlotRepository`, `LabelResolver`; no step of the
  unit of work `COMPLETED`; nothing handed off; instance `FAILED` with `SagaFailure.stepName` = the step that ran the
  failing code (the end step for write and commit) and the cause type; `weight` (and `feeId` for failures after
  `onFeeCharged` wrote it) still readable; result `FAILED`, `started = true`; history `[CREATED, FAILED]` (US2-1,
  US2-3, US2-4, SC-002, quickstart 5). Also: `catchInFeeStep` → still rolled back and `FAILED` (US2-2); a `FeeCharged`
  delivered for the `FAILED` instance → `UNEXPECTED`, no effect (US2-5); a failing `FAILED` write
  (`failNextFindByIdAndLock` armed from inside the failing step, so it hits the `FAILED` write's lock, not the unit
  of work's) → `EventHandlingException` with the partial report, instance as last committed; a failing creation write → `EventHandlingException`, no instance (FR-005).
- [x] T058 [US2] Create `.../engine/FailureDifferentialTest.java` with `support/DifferentialRun`: the 002 failure
  scenarios on `ThreeStepSaga` (each of `ReservationService`, `InvoiceService`, `ShipmentService` armed with
  `failNext()`) and on `CreateBookingSaga`'s first unit of work (the booking fixtures' `FaultSwitch`) give equal status,
  step records, failure step and cause type, aggregate state, context values and dispatched events on harness and
  engine (SC-001, SC-009).
- [x] T059 [US2] Create `.../engine/LifecycleHistoryPropertyTest.java` (jqwik, `@Property(tries = 200)`, Property 27,
  Javadoc `**Validates: Requirements FR-033, SC-010**`): for any `Optional<FaultPoint>` on `FailureSaga`, the stored
  history is `CREATED` then either `STEP_COMPLETED(onParcelRequested)`, `STEP_COMPLETED(onFeeCharged)`,
  `STEP_COMPLETED(onSlotReserved)`, `COMPLETED(onSlotReserved)` or `FAILED`; numbers consecutive from 0; no entry of
  the rolled-back unit of work.
- [x] T060 [P] [US2] Create `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/JdbcFake.java`
  (JDK only, `java.lang.reflect.Proxy` over `DataSource`, `Connection`, `PreparedStatement`, `ResultSet`; research.md
  §15): records per connection the SQL text, bound parameters per batch row, and `setAutoCommit`/`commit`/`rollback`/
  `close` calls; returns scripted rows chosen by SQL prefix (instance rows with an `associations` JSON column, the
  `NEXT_SEQUENCE_NUMBER` value, the upsert's `inserted` flag); can throw a `SQLException` for a matching statement.
  Never references `PostgresTestSupport`.
- [x] T061 [US2] Create `.../postgres/JdbcTransactionBoundaryTest.java` over `JdbcFake`: `begin` sets `autoCommit =
  false` and binds; `current()`/`resource(Connection.class)` return it, other types empty; `commit`/`rollback` end,
  restore `autoCommit`, close; a second `begin` on the thread throws; commit on rollback-only rolls back and throws;
  ended-transaction calls throw; a failing commit is wrapped and the connection still closed.
- [x] T062 [US2] Create `.../postgres/PostgresStorageProviderJoinTest.java` over `JdbcFake`: joined (inside a
  `JdbcTransactionBoundary` transaction) `save(instance, entries)`, `findById`, `findByAssociation` and `delete` use
  the bound connection and never commit, roll back, change `autoCommit` or close it; history rows are inserted in the
  given order, numbered from the scripted `MAX + 1`; a `SQLException` surfaces as `UncheckedSqlException`, marks the
  transaction rollback-only and leaves the connection open; a forward-transition violation surfaces as
  `ConcurrentSagaUpdateException`, which `isInstanceOf(SagaConcurrencyConflictException.class)`; a missing
  `Connection` resource → `IllegalStateException`. Not joined: 003's own-connection, `autoCommit=false`, commit,
  restore, close sequence unchanged; argument checks of T012 before any SQL. `PostgresSagaContextStore.save` takes its
  own connection while a transaction is bound (FR-022).
- [x] T063 [P] [US2] Create `.../postgres/JoinedTransactionContainerTest.java`, `@Tag("container")`, using
  `PostgresTestSupport` and `JdbcTransactionBoundary` (**edit and compile only, never run**; G-ENG-03, FR-022, FR-023,
  G-003-23): `joinedSaveCommitsWithClientWrite` (inside one transaction a client `INSERT` on the bound connection into
  a test table created in the test, plus `save(instance, entries)`, both visible after commit),
  `joinedSaveRollsBackWithClientWrite` (both absent after rollback, no history row), `contextStoreWriteSurvivesJoinedRollback`
  (instance committed first, then a `PostgresSagaContextStore.save` during a joined transaction that rolls back is still
  readable), `withoutTransactionKeepsOwnTransaction` (the boundary-built provider outside a transaction commits on its
  own). Compile check: `/opt/homebrew/bin/mvn -B -pl saga-forge-storage-postgres -am -DskipTests clean verify`.
- [x] T064 [US2] Checkpoint (US2). Run
  `/opt/homebrew/bin/mvn -B -pl saga-forge-engine,saga-forge-storage-postgres -am clean verify`. Requires
  `BUILD SUCCESS`, zero failures, and the container guard (`JoinedTransactionContainerTest` compiled, not run).

**Checkpoint**: MVP complete (US1 + US2): truthful commit and rollback on the engine; Postgres can join.

---

## Phase 5: User Story 3 - A waiting saga resumes on a later event routed by association (Priority: P2)

**Goal**: prove that a unit of work committed at a wait point resumes on a later event, in another engine
instance that shares only the stores, through association routing and with no memory carried over (FR-003,
FR-004, FR-014). Non-start routing was built in US1 (T039); this phase adds the fan-out fixture and the resume,
routing and differential tests. No new production file.

**Independent Test**: run `CreateBookingSaga` on engine A until it waits; build engine B over the same
`TransactionalInMemoryStorageProvider`, context store and client repositories and nothing else; deliver *payment
confirmed* (and, separately, *payment declined*) to B and compare with an uninterrupted run (quickstart.md
scenarios 6, 7, 8; SC-004).

**Files owned by this phase**: engine test `saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/fixtures/fanout/*`,
`ResumeAcrossEnginesTest.java`, `CreateBookingSagaDifferentialTest.java`, `RoutingOutcomesTest.java`,
`FanOutTest.java`.

### Tests for User Story 3 (`saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/`)

- [x] T065 [P] [US3] Create the fan-out fixture in `fixtures/fanout/`: `@Aggregate Case` (`@AggregateIdentifier
  caseId`); events `CaseOpened`, `CaseNoted`, `CaseEscalated`, `CaseClosed` (each `@DomainEvent(aggregate =
  Case.class)` with `caseId`); `@Command AddNote(caseId, Mode mode)` with `enum Mode { NOTE, CLOSE_THEN_ESCALATE }`;
  service `NoteService` (`@CommandHandler handle(AddNote)`: `NOTE` records `CaseNoted`; `CLOSE_THEN_ESCALATE` records
  `CaseClosed` then `CaseEscalated`; can be armed to fail); `AuditSaga` (`@StartSaga onCaseOpened` sends `AddNote`
  with the mode carried by the event; `onCaseEscalated`; `@EndSaga onCaseClosed`); `ReviewSaga` (`@StartSaga
  onCaseNoted`; `@EndSaga onCaseClosed`, which throws when `ReviewSaga.failOnClose` is set). All steps
  `associationProperty = "caseId"`. US5 reuses it (events queued at `@EndSaga`, events triggering another saga
  type's start step).
- [x] T066 [P] [US3] Create `ResumeAcrossEnginesTest.java`: engine A, first unit of work of `CreateBookingSaga` →
  `RUNNING`, `onBookingRequested` and `onAvailabilityClaimed` `COMPLETED`, others `NOT_RUN` (US3-1); engine B (fresh
  `SagaEngine`, same stores and repositories only) receives `PaymentConfirmedEvent` → `onPaymentConfirmed` and
  `onBookingConfirmed` in one new unit of work, `COMPLETED` with outcome `onBookingConfirmed`, `onPaymentDeclined`
  `NOT_RUN` (US3-2); a separate run with `PaymentDeclinedEvent` → `onPaymentDeclined` reads the claim id written in
  the first unit of work, outcome `onPaymentDeclined` (US3-3); a second unit of work failing on B keeps the first's
  effects committed and ends `FAILED` (US3-4, quickstart 8); in every case the end state equals an uninterrupted run
  on one engine (SC-004, quickstart 7).
- [x] T067 [P] [US3] Create `CreateBookingSagaDifferentialTest.java` with `support/DifferentialRun`: both outcomes
  of `CreateBookingSaga` (the 002 User Story 2 and 3 scenarios, including resume from persisted state) give equal
  status, step records, outcome, aggregate state, context values and dispatched events on harness and engine (SC-001,
  SC-009).
- [x] T068 [US3] Create `RoutingOutcomesTest.java`: a non-start event with no correlated instance → `UNMATCHED`, no
  effect, no exception (US3-5); an event correlated only to `COMPLETED` instances → one `UNEXPECTED` result for the
  most recently returned instance; an event for a non-start step reaching an instance still `CREATED` (seeded directly
  in the storage double) → `NOT_HANDLED`, nothing run (research.md §6, G-ENG-05); an event no saga reacts to →
  `ignored()` (FR-004, quickstart 6); `receive(null)`, a non-`@DomainEvent` object and an event with an empty
  association value → `IllegalArgumentException`, nothing ran (FR-006, quickstart 20).
- [x] T069 [US3] Create `FanOutTest.java` over the fan-out fixture: `CaseClosed` for a case with an active
  `AuditSaga` and an active `ReviewSaga` instance runs one unit of work per saga type, each committed independently;
  with `ReviewSaga.failOnClose` set, `ReviewSaga` ends `FAILED` while `AuditSaga` ends `COMPLETED` (spec Edge Cases
  "Event reacted to by several saga types"); results are in saga registration order.
- [x] T070 [US3] Checkpoint (US3). Run `/opt/homebrew/bin/mvn -B -pl saga-forge-engine -am clean verify`. Requires
  `BUILD SUCCESS`, zero failures, and the container guard.

**Checkpoint**: waiting sagas resume across engine instances.

---

## Phase 6: User Story 4 - Concurrent events for one instance are processed one after the other (Priority: P2)

**Goal**: at most one unit of work per instance at a time, within one process (per-`sagaId` lock) and across
processes (`findByIdAndLock`, `FOR NO KEY UPDATE` in Postgres); in-process duplicate-start guarantee; a write-time
conflict is `NOT_HANDLED`, never `FAILED`; different instances never wait for each other (FR-025 to FR-028,
research.md §3).

**Independent Test**: two engine instances over shared doubles with row-lock semantics; two events for one waiting
instance delivered concurrently 100 times never overlap, lose no step record and never end `FAILED`
(quickstart.md scenarios 9 to 13, SC-003, SC-012).

**Files owned by this phase**: engine main `.../engine/internal/InstanceLocks.java`; shared edits of
`internal/EventRouter.java` (T072) and `internal/SagaUnitOfWork.java` (T073); engine test
`saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/fixtures/concurrency/*`,
`internal/InstanceLocksTest.java`, `ConcurrentUnitsOfWorkTest.java`, `ConcurrencyConflictTest.java`,
`ConcurrentDuplicateStartTest.java`; Postgres shared edit of `PostgresStorageProvider.java` (T074); Postgres test
`PostgresStorageProviderLockTest.java`, shared edit of `ConcurrencyControlTest.java` (T081, container, compile only).

### Implementation for User Story 4

- [x] T071 [P] [US4] Create `saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/internal/InstanceLocks.java`:
  keyed fair `ReentrantLock`s in a reference-counted `ConcurrentHashMap`, an entry removed when its last holder or
  waiter leaves; `Lease acquire(Object key)` blocks until held, `Lease.close()` releases (idempotent). Used with two
  key kinds: `sagaId` (instance lock) and `(sagaType, associationProperty, associationValue)` (duplicate-start lock).
- [x] T072 [US4] Extend `.../engine/internal/EventRouter.java` (shared) with the lock order of data-model.md "Locks"
  (duplicate-start → instance → storage): a start step takes the duplicate-start lease, runs the duplicate check and
  `EngineSagaLifecycle.start`, takes the new `sagaId`'s instance lease, runs the creation write, then releases the
  duplicate-start lease (also on failure) and runs the unit of work holding the instance lease (FR-027); a non-start
  step takes each correlated instance's lease in turn before its unit of work (never two at once, so no engine-level
  deadlock). The lease is passed into `SagaUnitOfWork`, which releases it (T073).
- [x] T073 [US4] Extend `.../engine/internal/SagaUnitOfWork.java` (shared): release the instance lease after
  `commit()` and before the `AFTER_COMMIT` handoff, after `FailedWrite` on failure, and after rollback for
  `UNMATCHED`/`UNEXPECTED`/`NOT_HANDLED`; a `SagaConcurrencyConflictException` from `save` or `commit` rolls back,
  discards queue, buffer and entries, writes no `FAILED`, logs `saga.uow.conflict`, and returns `NOT_HANDLED` with the
  `unitOfWorkId` (FR-026, data-model.md row 10). No in-process retry.
- [x] T074 [US4] Extend `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/PostgresStorageProvider.java`
  (shared): `findByIdAndLock` for the boundary-built provider with an active transaction runs `FIND_BY_ID + " FOR NO
  KEY UPDATE OF i"` on the bound connection and maps the row as `findById` does (`IllegalArgumentException` for
  `null`; `IllegalStateException` with no active joined transaction, before any SQL); change `LOCK_PREVIOUS` from
  `FOR UPDATE OF i` to `FOR NO KEY UPDATE OF i` (its upsert never updates `saga_id`, so it is a no-key update). Javadoc:
  why `NO KEY` (the context store's `saga_context` FK check takes `FOR KEY SHARE` on its own connection, G-ENG-11),
  the lock is held until the transaction ends, re-entrant within it, and the locked read's associations may predate a
  winner's commit (G-003-21; harmless because the engine never changes associations after creation).

### Unit tests for User Story 4

- [x] T075 [P] [US4] Create the concurrency fixture in `saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/fixtures/concurrency/`:
  `@Aggregate Ticket` (`@AggregateIdentifier ticketId`); events `TicketOpened`, `SignalA`, `SignalB`, `TicketClosed`;
  `SignalSaga` (`@StartSaga onTicketOpened` waits; `onSignalA` writes context `a` and reports the `b` it saw;
  `onSignalB` writes `b` and reports the `a` it saw; `@EndSaga onTicketClosed`; all `associationProperty =
  "ticketId"`, no commands); thread-safe `StepProbe` (per-`sagaId` active count and maximum overlap, entry/exit order,
  what each step saw, an optional `CountDownLatch` to hold a step, a configurable dwell time to widen the race).
- [x] T076 [P] [US4] Create `saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/internal/InstanceLocksTest.java`:
  same key serializes two threads, different keys do not wait, entries are removed when unused, `close()` is
  idempotent.
- [x] T077 [US4] Create `.../engine/ConcurrentUnitsOfWorkTest.java` (Property 28; Javadoc `**Validates: Requirements
  FR-025, FR-026, SC-003**`): `@RepeatedTest(100)` two engines over one `TransactionalInMemoryStorageProvider` and one
  `ConcurrentSagaContextStore`, `SignalA` and `SignalB` for one waiting instance released by a barrier on two threads
  → `StepProbe` max overlap 1, both steps `COMPLETED`, both `STEP_COMPLETED` entries stored, the step that ran second
  saw the first's context value, no `FAILED` (US4-1, US4-3, quickstart 9); the same with one engine and two threads;
  first unit of work completes the saga (`TicketClosed`), the second event → `UNEXPECTED` (US4-2, quickstart 10); two
  instances, one held in a step by the latch, the other commits within a timeout (US4-5, FR-028, quickstart 12).
- [x] T078 [US4] Create `.../engine/ConcurrencyConflictTest.java`: `conflictOnNextSave` → result `NOT_HANDLED` with a
  `unitOfWorkId`, rolled back, no `FAILED` write (`saveCalls`), no entry recorded, instance as before, context values
  written by the step kept, `saga.uow.conflict` logged via `RecordingLogger`; redelivering the event then succeeds
  (US4-4, FR-026, quickstart 11).
- [x] T079 [US4] Create `.../engine/ConcurrentDuplicateStartTest.java` (Property 25; Javadoc `**Validates:
  Requirements FR-027, SC-012**`): `@RepeatedTest(100)`, one engine, eight threads released by a barrier each
  receiving `TicketOpened` with the same `ticketId` → exactly one result `started = true`, seven `DUPLICATE_START`
  with `sagaId` `null`, one instance stored (quickstart 13).
- [x] T080 [US4] Create `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/PostgresStorageProviderLockTest.java`
  over `JdbcFake`: joined `findByIdAndLock` issues one statement ending in `FOR NO KEY UPDATE OF i` on the bound
  connection and maps the scripted row, or returns empty; without an active transaction, and for the
  `DataSource`-only constructor, `IllegalStateException` before any SQL; `null` → `IllegalArgumentException`; the
  `save` lock statement contains `FOR NO KEY UPDATE OF i` and no plain `FOR UPDATE`.
- [x] T081 [US4] Add to `.../postgres/ConcurrencyControlTest.java` (container, **edit and compile only, never run**),
  each with a lock-wait timeout: `findByIdAndLockBlocksSecondTransactionUntilCommit` (FR-025, G-ENG-11),
  `findByIdAndLockAfterWaitReadsWinnersCommit` (status and steps are the winner's; asserts and documents what the
  associations read after the wait, G-003-21), `findByIdAndLockDoesNotBlockContextStoreWrite` (a
  `PostgresSagaContextStore.save` on its own connection completes while the lock is held, G-ENG-11, research.md §3),
  `historyNumbersContinueUnderLock` (two serialized joined writes number their entries consecutively, FR-033). Use
  `JdbcTransactionBoundary` and the boundary-built provider. Compile check:
  `/opt/homebrew/bin/mvn -B -pl saga-forge-storage-postgres -am -DskipTests clean verify`.
- [x] T082 [US4] Checkpoint (US4). Run
  `/opt/homebrew/bin/mvn -B -pl saga-forge-engine,saga-forge-storage-postgres -am clean verify`. Requires
  `BUILD SUCCESS`, zero failures (the 100-repetition tests included), and the container guard.

**Checkpoint**: units of work of one instance serialize; conflicts are redeliverable.

---

## Phase 7: User Story 5 - Transports plug into the engine at two points with a defined dispatch guarantee (Priority: P2)

**Goal**: the inbound entry point reports every outcome; the after-commit handoff meets the outbox-ready obligations
in both timings (`AFTER_COMMIT` default, `IN_TRANSACTION`); handoff failures after commit are reported, not
`FAILED`; reentrant calls from a running unit of work are rejected, calls from an `AFTER_COMMIT` handoff run as new
units of work (FR-005, FR-007, FR-029 to FR-032).

**Independent Test**: a test transport that only calls `EventReceiver.receive` and only receives dispatches runs
`CreateBookingSaga` and the fan-out sagas end to end, looping dispatched events back from inside the handoff; a
capturing in-transaction handoff gets exactly each committed unit of work's events (quickstart.md scenarios 14, 15,
16, 19; SC-005, SC-007 engine half, SC-011).

**Files owned by this phase**: engine main `.../engine/internal/ReentrancyGuard.java`; shared edits of
`internal/SagaUnitOfWork.java` (T084), `internal/NonSagaUnitOfWork.java` (T085), `internal/EventRouter.java` (T086);
engine test `saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/TestTransportEndToEndTest.java`,
`HandoffObligationsTest.java`, `InTransactionHandoffTest.java`, `HandoffObligationsPropertyTest.java`,
`ReentrancyTest.java`, `HandlingReportOutcomesTest.java`.

### Implementation for User Story 5

- [x] T083 [P] [US5] Create `saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/internal/ReentrancyGuard.java`:
  a thread-bound "engine unit of work running" marker (`enter()`, `exit()`, `isRunning()`), set from `begin()` until
  the unit of work's commit or rollback has finished, and `requireNotRunning()` which reports the failure to the bound
  unit of work (`failed(Throwable)`, so a step that catches it still fails) and throws `IllegalStateException`
  (FR-007, research.md §6).
- [x] T084 [US5] Extend `.../engine/internal/SagaUnitOfWork.java` (shared): mark the thread with `ReentrancyGuard`
  from `begin()` and clear it after commit/rollback, before the `AFTER_COMMIT` handoff; with an `IN_TRANSACTION`
  handoff, call `handOff` after `save(instance, entries)` and before `commit()`, inside the transaction, only for a
  non-empty batch, and treat its failure as a unit-of-work failure (T052 path, `SagaFailure.stepName` = the last step
  run); with `AFTER_COMMIT`, catch a `RuntimeException` from `handOff`, put it and its suppressed exceptions into
  `SagaHandlingResult.handoffFailures`, log `saga.handoff.failed`, and leave the committed instance untouched (FR-031).
- [x] T085 [US5] Extend `.../engine/internal/NonSagaUnitOfWork.java` (shared) the same way: `ReentrancyGuard` marker;
  `IN_TRANSACTION` handoff before `commit()` whose failure rolls back and is rethrown from `send`; an `AFTER_COMMIT`
  failure is logged (`saga.handoff.failed` without `sagaType`/`sagaId`) and not thrown (contracts/event-handoff.md
  "Failure handling").
- [x] T086 [US5] Extend `.../engine/internal/EventRouter.java` (shared): `receive` calls
  `ReentrancyGuard.requireNotRunning()` before validation, so a call from a step, a handler or an `IN_TRANSACTION`
  handoff throws `IllegalStateException` and fails that unit of work, while a call from inside an `AFTER_COMMIT`
  handoff (marker cleared, instance lease released) is handled as a new unit of work (FR-007, US5-5).

### Unit tests for User Story 5 (`saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/`)

- [x] T087 [US5] Create `TestTransportEndToEndTest.java` (quickstart 14, US5-1, US5-5, SC-007 engine half): the
  engine built with `TestTransport` as `EventDispatcher` and default handoff, `loopBack(true)`; `CreateBookingSaga`
  driven only through `transport.deliver(...)` reaches both outcomes; `AuditSaga`'s handed-off `CaseNoted` is looped
  back from inside `dispatch` and starts `ReviewSaga` as a new unit of work after `AuditSaga`'s committed (checked from
  inside `ReviewSaga`'s step through `TransactionalInMemoryStorageProvider.committed()`); every report matches
  contracts/event-receiver.md. The test touches only `EventReceiver` and the transport, never engine internals.
- [x] T088 [US5] Create `HandoffObligationsTest.java` with `CapturingEventHandoff` (`AFTER_COMMIT`): a rolled-back unit
  of work (`FailureSaga` with a fault) hands off nothing (US5-2, SC-005); `AuditSaga` with `CLOSE_THEN_ESCALATE` hands
  off `CaseEscalated`, still queued when `onCaseClosed` ended the unit of work, and `AuditSaga` with `NOTE` hands off
  `CaseNoted` (it triggers `ReviewSaga`'s start step, so it is never run in-process), in recording order, after
  commit, each once (US5-3); a handoff that throws after commit leaves the
  instance committed and not `FAILED`, lists the exception in `handoffFailures` and logs `saga.handoff.failed` (US5-4,
  FR-031); `DispatchingEventHandoff` over a dispatcher failing on the middle event still dispatches the rest and lists
  the failure.
- [x] T089 [US5] Create `InTransactionHandoffTest.java` with `CapturingEventHandoff` (`IN_TRANSACTION`) (US5-7,
  SC-011, quickstart 16): per committed unit of work the batch arrives before `commit()`, inside the unit of work's
  transaction (`TransactionBoundary.current()` is it), with `unitOfWorkId`, `sagaType` and `sagaId`, in recording
  order, and is retained after commit; with `failNextCommit` nothing is retained; a throwing handoff ends the saga
  `FAILED` naming the last step; a non-saga `send` with a throwing handoff rolls back and rethrows. Same builder as
  the default case except `.handoff(...)` (no engine change between timings).
- [x] T090 [US5] Create `HandoffObligationsPropertyTest.java` (jqwik `@Property(tries = 100)`, Property 26; Javadoc
  `**Validates: Requirements FR-029, FR-030, SC-005, SC-011**`): for any generated sequence of runs (`FailureSaga` with
  an optional `FaultPoint`, `AuditSaga` with either mode, a non-saga `send`) under either timing, each committed unit
  of work's batch holds exactly its buffered events, once, in recording order, with a `unitOfWorkId` no other batch
  has, and `sagaType`/`sagaId` present iff saga-scoped; rolled-back units of work leave nothing.
- [x] T091 [US5] Create `ReentrancyTest.java` (FR-007, quickstart 19): `receive` from inside a step, from inside a
  handler and from inside an `IN_TRANSACTION` handoff → `IllegalStateException`, that unit of work `FAILED`; from
  inside an `AFTER_COMMIT` handoff → accepted, runs after the first unit of work finished.
- [x] T092 [US5] Create `HandlingReportOutcomesTest.java`: one case per row of contracts/event-receiver.md "Outcomes
  per instance" (`WAITING`, `COMPLETED`, `FAILED` started and not started, `DUPLICATE_START`, `UNMATCHED`,
  `UNEXPECTED`, `NOT_HANDLED` for `CREATED` and for a conflict, ignored), asserting `outcome`, `started`, `sagaId`
  nullness, `outcomeStep`, `failure`, `unitOfWorkId` presence per data-model.md; a `FAILED` saga is returned
  normally, not thrown (US5-1, US5-6).
- [x] T093 [US5] Checkpoint (US5). Run `/opt/homebrew/bin/mvn -B -pl saga-forge-engine -am clean verify`. Requires
  `BUILD SUCCESS`, zero failures, and the container guard.

**Checkpoint**: both plug-in points are defined and verified.

---

## Phase 8: User Story 6 - Startup validation fails fast (Priority: P3)

**Goal**: `start()` reports every `validateAll` error and every host-level problem (aggregate without loader or
resolver, unresolvable handler parameter, uninstantiable saga, dispatcher rejecting a saga type) in one
`StartupReport`; a refused engine accepts no event (FR-034 to FR-036, Properties 21 to 24).

**Independent Test**: start the engine with each kind of error alone and all together; one
`SagaEngineStartupException` lists every error, `receive` then throws `IllegalStateException`, and on success every
saga type is registered with the dispatcher before the first event (quickstart.md scenario 18, SC-006).

**Files owned by this phase**: engine main `.../engine/internal/StartupChecks.java`; shared edit of
`SagaEngine.java` (T095); engine test
`saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/fixtures/startup/*`,
`StartupValidationTest.java`, `StartupChecksPropertyTest.java`.

### Implementation for User Story 6

- [x] T094 [P] [US6] Create `saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/internal/StartupChecks.java`
  (contracts/engine-startup.md "Engine checks and messages", exact message texts and placeholders): every
  `@Aggregate` with an instance-method `@CommandHandler` has a registered `@AggregateLoader` or a registered
  `AggregateResolver` whose `supports` is true (Property 21); every `@CommandHandler` parameter other than its
  `@Command` one is supported by at least one `HandlerParameterResolver`, none registered → every such parameter is an
  error, `{param}` as `#<position> <SimpleType>` (Property 22); every `@Saga` class is supported by the registered
  `SagaObjectProvider`, or without one declares a no-argument constructor of any visibility (Property 23). Returns
  `List<String>` of all errors in one pass; calls only `supports` methods (no saga object, bean or handler instance is
  created; handler/loader/saver host instances are not checked, spec Assumptions "Handler instances").
- [x] T095 [US6] Extend `saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/SagaEngine.java`
  (shared) `start()`: run `validateAll` and `StartupChecks` together; any error from either → `REFUSED` (terminal),
  log `saga.startup.refused` plus one `ERROR` line per error, throw `SagaEngineStartupException(new
  StartupReport(validation, engineErrors))`; only when both are clean, call `registerSagaType` for every saga class in
  registration order (a failure adds `EventDispatcher rejected @Saga class {class}: {message}` and refuses), then
  `STARTED`. A refused engine rejects `receive`, `send` and `publish` with `IllegalStateException`, processing nothing
  (FR-036, US6-5).

### Unit tests for User Story 6

- [x] T096 [P] [US6] Create the startup fixtures in `saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/fixtures/startup/`:
  `UnloadableAggregate` (`@Aggregate` with an instance-method `@CommandHandler`, no loader anywhere) with its command
  and event; `ServiceWithExtraParameter` (`@CommandHandler handle(StartupCommand, java.time.Clock)`);
  `SagaWithoutNoArgConstructor` (valid `@Saga` whose only constructor takes an argument); an invalid model reuses a
  core test-jar scanner fixture (e.g. `core.scanner.fixtures.NoSagaIdSaga`); a `RejectingDispatcher` whose
  `registerSagaType` throws for one class.
- [x] T097 [US6] Create `.../engine/StartupValidationTest.java` (quickstart 18): each error alone (US6-1 to US6-3, plus
  the provider-unsupported and dispatcher-rejection messages) and all together (US6-4) → one
  `SagaEngineStartupException` whose `report().allErrors()` lists every expected message once, `validateAll` errors
  first; a resolver whose `supports` is true for `UnloadableAggregate` clears that error; afterwards `receive`,
  `commandGateway().send` and `eventPublisher().publish` throw `IllegalStateException` and the stores are untouched
  (US6-5, SC-006); on success `RecordingEventDispatcher.registeredSagaTypes()` lists every saga in registration order
  before the first `receive` (FR-036); `start()` twice throws.
- [x] T098 [US6] Create `.../engine/StartupChecksPropertyTest.java` (jqwik `@Property(tries = 100)`, Properties 21 to
  24; Javadoc `**Validates: Requirements FR-019, FR-034, FR-035, FR-036, SC-006**`): for any subset of {unloadable
  aggregate with or without a supporting resolver, extra-parameter handler with or without a supporting parameter
  resolver, saga without no-arg constructor with or without a supporting provider, an invalid saga} added to a valid
  base model (`ThreeStepSaga` and its services), the report holds an error for exactly the unsupported members, every
  one in a single report, and the refused engine accepts zero events.
- [x] T099 [US6] Checkpoint (US6). Run `/opt/homebrew/bin/mvn -B -pl saga-forge-engine -am clean verify`. Requires
  `BUILD SUCCESS`, zero failures, and the container guard.

**Checkpoint**: every user story is implemented and unit-tested.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: the canonical example on the engine, diagnostics coverage, the Principle III gate, doc sync (FR-040,
Principle V), the gap register, the dependency check (FR-037, SC-008), the quickstart walk, and the container hand-off.

**Files owned by this phase**: `saga-forge-example/pom.xml`; `saga-forge-example/src/test/java/io/github/gkosharovdev/sagaforge/example/OrderProcessingSagaEngineTest.java`;
`saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/DiagnosticsTest.java`;
`.kiro/specs/saga-forge-framework/design.md`; `specs/001-core-lifecycle-context/contracts/saga-lifecycle.md`,
`specs/001-core-lifecycle-context/contracts/saga-context.md`, `specs/002-transactional-step-sequence/contracts/saga-unit-of-work.md`,
`.../002-transactional-step-sequence/contracts/command-gateway.md`, `.../event-publisher.md`, `.../saga-context-store.md`,
`.../saga-lifecycle.md`, `specs/003-postgres-saga-persistence/contracts/saga-lifecycle-entry.md`,
`.../concurrency-control.md`, `.../postgres-schema.md`; `README.md`; `docs/architecture.md`; `AGENTS.md`;
`.specify/memory/memory.md` (planned updates); `specs/004-saga-engine/quickstart.md`.

- [x] T100 [P] Update `saga-forge-example/pom.xml`: add test-scope dependencies
  `io.github.gkosharovdev:saga-forge-engine:${project.version}`, the same with `<type>test-jar</type>` (engine test
  doubles and `TestTransport`), and `io.github.gkosharovdev:saga-forge-core:${project.version}` with
  `<type>test-jar</type>` (`InMemorySagaContextStore`, `MapHandlerInstanceProvider`, `Snapshottable`). Main sources of
  the example stay unchanged (SC-009).
- [x] T101 Create `saga-forge-example/src/test/java/io/github/gkosharovdev/sagaforge/example/OrderProcessingSagaEngineTest.java`
  (FR-040, quickstart 1, SC-007): the engine registers `OrderProcessingSaga`, `Order`, `OrderRepository` and
  `PaymentService` (instances through `MapHandlerInstanceProvider`), over `InMemoryTransactionBoundary`,
  `TransactionalInMemoryStorageProvider`, `InMemorySagaContextStore` and `TestTransport` as dispatcher. Seed the
  `Order` in `OrderRepository`; `transport.deliver(new OrderCreatedEvent(...))` → report `COMPLETED`, `started = true`,
  outcome `completeOrder`; `handleOrderCreated`, `processPayment`, `completeOrder` `COMPLETED` with their touched
  aggregates; the `Order` change saved through the `@AggregateSaver`; history `CREATED`, three `STEP_COMPLETED` in run
  order, `COMPLETED` (US1-1, US1-5, US1-8). A second case without a seeded `Order` → `AggregateNotFoundException` in
  `processPayment`'s unit of work → `FAILED` naming `processPayment`, `orderAmount` still readable from the context
  store. A third publishes `OrderCreatedEvent` through `engine.eventPublisher()` with `loopBack(true)` and sees the
  saga start (US1-7). `OrderRepository` is a plain map, so aggregate rollback is not asserted here (research.md §14).
- [x] T102 [P] Create `saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/DiagnosticsTest.java`
  (FR-038, quickstart 22): with `RecordingLogger` passed to `builder().logger(...)`, provoke each event name of
  contracts/diagnostics.md (refused and started startup, ignored, unmatched, unexpected, duplicate start, committed,
  rolled back plus `saga.instance.failed`, `saga.instance.failedNotRecorded`, conflict, `saga.uow.notStarted`,
  `saga.handoff.failed`, `saga.event.notHandled`) and assert the level and that `event`, `sagaType`, `sagaId` and `uow`
  are present wherever the table lists them; the throwable is attached where required.
- [x] T103 Principle III gate: append Properties 21 to 28 of research.md §13 to `.kiro/specs/saga-forge-framework/design.md`
  after Property 20, in that document's format (`### Property N: title`, "*For any* ..." statement,
  `**Validates: Feature spec specs/004-saga-engine FR-...**`), each also naming its test class (21 to 24
  `StartupChecksPropertyTest`, 25 `ConcurrentDuplicateStartTest`, 26 `HandoffObligationsPropertyTest`, 27
  `LifecycleHistoryPropertyTest`, 28 `ConcurrentUnitsOfWorkTest`). Next to them add a short note: none of
  `.kiro/specs/saga-context-lifecycle/design.md` Properties 1 to 12 applies to the engine as written (they describe
  `getProperty`/`registerProperty`/`setContext`, which do not exist; G-002-05), its "Engine-Level Error Handling" rule
  is honored by `DomainEventScope.runWith` restoring the previous binding, and that document is left unchanged
  (AGENTS.md). Properties 1 to 20 are not changed. Confirm against the delivered tests that each property holds.
- [x] T104 Apply contracts/contract-deltas.md to the base contracts, as a dated "004 delta" section at the end of each
  file (or an inline correction where the delta replaces a sentence), quoting the delta in Saga Forge vocabulary:
  `specs/002-transactional-step-sequence/contracts/saga-unit-of-work.md` (begin/commit/rollback steps made concrete,
  invariants), `.../command-gateway.md` (parameter resolution, several resolvers, non-saga unit of work, loader/saver
  hosts), `.../event-publisher.md` (handoff instead of direct dispatch), `.../saga-context-store.md` (after the
  creation write), `.../saga-lifecycle.md` and `specs/001-core-lifecycle-context/contracts/saga-lifecycle.md` (engine
  resolves instances; three writes; entries), `specs/001-core-lifecycle-context/contracts/saga-context.md`
  (`SagaContextRecord` restated), `specs/003-postgres-saga-persistence/contracts/saga-lifecycle-entry.md` (producer is
  the engine, `sequenceNumber` removed, limitations closed), `.../concurrency-control.md` (`FOR NO KEY UPDATE`,
  `findByIdAndLock`, inserted not derived, superclass), `.../postgres-schema.md` (numbering from hand-over order,
  rollback-only in joined mode). Also add the `EventDispatcher` outbound-only note where `saga-lifecycle.md` relates it.
- [x] T105 [P] Update `README.md` (FR-040, Principle V): "Status" says the engine runtime exists in
  `saga-forge-engine` (event routing, saga-scoped unit of work, transaction boundary, one unit of work per instance,
  outbox-ready handoff, startup validation), verified by unit tests over in-memory doubles; the Postgres provider joins
  the engine transaction; still missing: Spring Boot starter, transports, compensation, retry, idempotency. "Modules"
  table gains a `saga-forge-engine` row (role: runtime over the core SPI; depends on `saga-forge-core`) and the
  `saga-forge-example` row's dependencies mention the engine (test scope). Keep the canonical example unchanged.
- [x] T106 [P] Update `docs/architecture.md`: "Status at a glance" gains an engine row (implemented) and corrects any
  row the engine changes; "Module responsibilities" gains `saga-forge-engine` and replaces "storage modules record
  history by diffing" with "storage modules persist the lifecycle entries the engine hands over, numbered in hand-over
  order"; the "Why orchestration..." sentence "None of these has a runtime implementation yet" becomes accurate (the
  engine drives them; Postgres implements storage and context store); "Transaction boundary relative to the
  triggering dispatch" says it is implemented by 004 (`TransactionBoundary`/`LocalTransaction`, storage joins via
  `resource(Class)`, `FAILED` written separately), with the remaining gaps (G-ENG-05, G-ENG-07, G-ENG-08, G-ENG-10,
  G-ENG-12) named as limitations. No implementation prescription for a consumer (Principle IV).
- [x] T107 [P] Update `AGENTS.md`: "Current Status" (engine implemented and unit-tested in `saga-forge-engine`;
  `saga-forge-storage-postgres` implemented by 003 and adapted by 004; Spring starter and transports still
  placeholders; container suites fenced by `@Tag("container")`, run with `-Pcontainer-tests`); "Module Map" gains
  `saga-forge-engine` (runtime; JDK + core only; host-facing builder; contracts it needs live in `core.spi`);
  "Known Gaps" replaces the items the engine closes ("no engine populates the `SagaContext`/`CommandGateway` fields",
  "Deferred to the engine feature: the transaction SPI ... cross-process one-unit-of-work ... non-command parameters
  ... startup check") with what remains (G-ENG-05 to G-ENG-13, compensation, retry, `@StartSaga` as handler).
- [x] T108 Update `.specify/memory/memory.md` with what this feature delivered, each with the closing task IDs:
  close G-003-04, G-003-16 and G-003-27; close G-ENG-01, G-ENG-02, G-ENG-03 (database verification still under
  G-003-23) and G-ENG-04; mark G-003-01 moot (no derivation exists any more) and the derivation half of G-003-15
  closed; G-ENG-06: Javadoc done, use-or-drop decision still open; G-003-21: now also applies to `findByIdAndLock`,
  container test `findByIdAndLockAfterWaitReadsWinnersCommit` added (not run); G-003-23: list the container classes
  and methods added or changed by this feature (T023, T063, T081) as not yet run; G-ENG-11: container test added (not
  run); G-002-05: Properties 21 to 28 appended (T103). Add new `G-ENG-NN` entries for anything found during
  implementation, at least: `identicalConcurrentSaves` now hands no entries because storage no longer de-duplicates
  history (T023); the harness `InMemoryStorageProvider.findByIdAndLock` does not lock (T018). Never reuse an ID.
- [x] T109 Dependency check (FR-037, SC-008, quickstart 24): run `git diff master -- pom.xml '*/pom.xml'` and confirm
  the only changes are the root `<module>` entry (T004), core's `maven-jar-plugin` test-jar (T006), the new
  `saga-forge-engine/pom.xml` (T005), Postgres surefire/profile (T001) and the example's test-scope internal artifacts
  (T100); no `<dependency>` outside `io.github.gkosharovdev` was added or changed in any module. Then compare resolved
  trees: `git worktree add /tmp/saga-forge-master master`, run
  `/opt/homebrew/bin/mvn -B -q -DskipTests clean verify dependency:tree -DoutputFile=deps.txt` in both trees (runs no
  test), diff each module's third-party lines (`grep -v io.github.gkosharovdev`), expect no new artifact, then
  `git worktree remove /tmp/saga-forge-master` and delete the `deps.txt` files. Record the result in this task's report.
- [x] T110 Walk quickstart.md scenarios 1 to 24 against the delivered tests: add a "Test" column (or line) naming the
  class and method that covers each scenario in `specs/004-saga-engine/quickstart.md`, fix any command that changed,
  drop the note about the 003 `-Dtest` exclusion now that the tag is in place, and list any scenario left uncovered in
  this task's report.
- [x] T111 Checkpoint (Polish). Run the full unit suite at the root: `/opt/homebrew/bin/mvn -B clean verify`. Requires
  `BUILD SUCCESS` and zero failures in every module (core, engine, Postgres unit tests, example, placeholders), and the
  container guard. This is the last task of the feature that runs tests.
- [x] T112 Container hand-off (runs nothing). Write a section "Container tests for the user to run" at the end of
  `specs/004-saga-engine/quickstart.md` and report the same list to the user. For every container class and method
  this feature affected or added (check the names against the code), give what it verifies and the FR/SC and
  memory.md IDs it covers, at least:
  - `SchemaTest` (all methods; tag only, schema unchanged; G-003-23);
  - `PostgresStorageProviderTest$RoundTrip` (all methods; `nullRules` extended for `save(instance, entries)`;
    FR-023, G-003-23) and `$Atomicity` (`failedHistoryInsertKeepsPreCallSnapshot`, `failedFirstSaveLeavesNothing`
    with handed entries; FR-023, G-003-23);
  - `PostgresSagaContextStoreTest` (all methods; tag only; FR-022, G-ENG-01);
  - `LifecycleHistoryTest` (`fullLifecycleInOrder`, `notRunStepHasNoRow`, `failedRowMatchesInstanceFailure`,
    `saveWithoutEntriesAddsNoRow`, `oneSaveKeepsHandOverOrder`, `entriesAppendAfterExistingHistory`,
    `deleteRemovesHistory`; FR-033, SC-010, G-003-04, G-003-16, G-ENG-02);
  - `ConcurrencyControlTest` (existing methods incl. `concurrentFirstSaves` and `identicalConcurrentSaves`: G-003-22,
    G-003-17, G-003-23; new `findByIdAndLockBlocksSecondTransactionUntilCommit`, `findByIdAndLockAfterWaitReadsWinnersCommit`,
    `findByIdAndLockDoesNotBlockContextStoreWrite`, `historyNumbersContinueUnderLock`: FR-025, FR-033, G-ENG-11,
    G-003-21);
  - `JoinedTransactionContainerTest` (four methods of T063; FR-022, FR-023, G-ENG-03, G-003-23).
  Commands: `/opt/homebrew/bin/mvn -B -pl saga-forge-storage-postgres -am clean verify -Pcontainer-tests` (all
  Postgres tests, unit and container), or only the container classes with
  `-Pcontainer-tests -Dsurefire.failIfNoSpecifiedTests=false -Dtest='SchemaTest,PostgresStorageProviderTest*,PostgresSagaContextStoreTest,LifecycleHistoryTest,ConcurrencyControlTest,JoinedTransactionContainerTest'`.
  Podman notes from `specs/003-postgres-saga-persistence/quickstart.md`: `export DOCKER_HOST=unix://<socket>` with the
  path from `podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}'`, and
  `TESTCONTAINERS_RYUK_DISABLED=true` if Ryuk fails under rootless Podman; image `postgres:16-alpine`. State that
  closing G-003-23, G-003-22, G-003-21 and G-ENG-11 needs this run to pass, and that the implementer did not run it.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: none. Must finish first so every later checkpoint is fenced from the container classes.
- **Foundational (Phase 2)**: after Setup. Blocks every story.
- **US1 (Phase 3, P1)**: after Foundational. Creates the engine runtime every later story extends.
- **US2 (Phase 4, P1)**: after US1 (extends `SagaUnitOfWork` and `EventRouter`; its Postgres half, T054, T055, T060 to
  T063, depends only on Foundational).
- **US3 (Phase 5, P2)**: after US2 (failure cases in T066 need the `FAILED` path). Adds no production file.
- **US4 (Phase 6, P2)**: after US3 in this plan's order (shared `EventRouter`/`SagaUnitOfWork`); technically needs
  only US2. Its Postgres half (T074, T080, T081) needs US2's T055.
- **US5 (Phase 7, P2)**: after US4 (the instance lease must be released before the `AFTER_COMMIT` handoff that T084
  reorders; T087 to T088 reuse the fan-out fixture of US3).
- **US6 (Phase 8, P3)**: after US1 functionally (only `SagaEngine.start` is shared); scheduled after US5 so the
  shared-file edits stay sequential.
- **Polish (Phase 9)**: after every story. T101 needs US1, US2 and US5 (`TestTransport` loop-back); T103 needs the
  property tests of US2, US4, US5, US6; T111 runs everything; T112 is last and runs nothing.

### Within each phase

- Production code first, then its unit tests (same task or a later task of the phase); never "confirm the test fails".
- Doubles and fixtures before the tests that use them (T024 → T025 → T027; T056 → T057 to T059; T065 → T069;
  T075 → T077 to T079; T096 → T097, T098).
- A shared file is edited by one task at a time, in task-ID order.
- The checkpoint is the only task that runs tests; every other task may run the compile check only.

### User story dependencies

```text
Setup → Foundational → US1 → US2 → US3 → US4 → US5 → US6 → Polish
                         └──────── MVP (US1 + US2) ───────┘
```

## Parallel Opportunities

- Setup: T001, T002.
- Foundational: T005, T006 (after T004); T007 to T011 (five independent `core.spi` files); T014, T015 (Javadoc,
  disjoint files); T016 after T008 to T010; T020, T021 alongside T019; T026 alongside T024/T025.
- US1: T029 to T033 (independent internal classes); T040, T041 alongside T034 to T039; T043, T044.
- US2: T051, T054, T056, T060, T063 (disjoint files; the engine and Postgres halves can proceed side by side).
- US3: T065, T066, T067.
- US4: T071, T075, T076.
- US5: T083.
- US6: T094, T096.
- Polish: T100, T102, T105, T106, T107.

### Parallel example: Foundational core contracts

```text
Task: "T007 Create TransactionBoundary.java and LocalTransaction.java in saga-forge-core/.../core/spi/"
Task: "T008 Create EventReceiver, HandlingOutcome, SagaHandlingResult, HandlingReport, EventHandlingException"
Task: "T009 Create EventHandoff.java and HandedOffEvents.java"
Task: "T010 Create SagaConcurrencyConflictException.java"
Task: "T011 Create HandlerParameterResolver.java and SagaObjectProvider.java"
```

### Parallel example: User Story 1

```text
Task: "T029 Create internal/Reflection.java"
Task: "T030 Create internal/SagaModel.java"
Task: "T031 Create internal/Diagnostics.java"
Task: "T032 Create internal/EngineSagaContext.java"
Task: "T033 Create internal/EngineSagaLifecycle.java"
```

### Parallel example: User Story 2

```text
Task: "T051 Create internal/FailedWrite.java"                      (engine)
Task: "T054 Create JdbcTransactionBoundary.java"                    (Postgres)
Task: "T056 Create fixtures/failure/*"                              (engine test)
Task: "T060 Create JdbcFake.java"                                   (Postgres test)
```

### Parallel example: User Story 4

```text
Task: "T071 Create internal/InstanceLocks.java"
Task: "T075 Create fixtures/concurrency/*"
Task: "T076 Create internal/InstanceLocksTest.java"
```

### Parallel example: Polish

```text
Task: "T105 Update README.md"
Task: "T106 Update docs/architecture.md"
Task: "T107 Update AGENTS.md"
```

## Implementation Strategy

### MVP first (US1 + US2, both P1)

1. Phase 1 Setup → T003 green (container fence in place).
2. Phase 2 Foundational → T028 green.
3. Phase 3 US1 → T050 green: the happy path runs on the engine and matches the harness.
4. Phase 4 US2 → T064 green: failures roll back in full and leave `FAILED`; Postgres can join the transaction.
5. **Stop and validate**: the MVP runs sagas with truthful commit and rollback when events for one instance arrive
   one at a time; concurrency safety (US4) and the final transport contract (US5) come next.

### Incremental delivery

1. US3 → T070: waiting sagas resume across engine instances (no new production code).
2. US4 → T082: concurrency safety within and across processes.
3. US5 → T093: transport plug-in points final; the Kafka feature can build on them.
4. US6 → T099: fail-fast startup.
5. Polish → T111 green, then T112 hands the container run to the user.

## Notes

- **The user's test rules** (mandatory, override the template's TDD guidance):
  1. Not TDD: production code first, with its unit tests in the same task or a later task of the same phase. No
     "confirm the test fails before implementing".
  2. Tests run only at the end of a phase: each phase ends with exactly one checkpoint task (T003, T028, T050, T064,
     T070, T082, T093, T099, T111) that runs the unit suite of the modules it touched and requires `BUILD SUCCESS`,
     with `clean` (G-REPO-02). No other task runs tests; compiling is allowed. In Polish the checkpoint T111 is the last
     test-running task and is followed only by the hand-off T112, which runs nothing.
  3. Testcontainers tests are never run. Container classes (Path Conventions) are edited and compiled only (T002, T023,
     T063, T081). T001/T002 fence them by tag from Phase 1 on; T003 additionally uses the 003 `-Dtest` exclusion.
  4. T112 writes the container hand-off into quickstart.md and reports it; the user runs it.
- Property-based tests (jqwik or `@RepeatedTest(100)`): T059 (Property 27), T077 (Property 28), T079 (Property 25),
  T090 (Property 26), T098 (Properties 21 to 24). Each carries a `**Validates: Requirements ...**` line.
- No third-party dependency anywhere (FR-037); only plugin configuration and internal test-jars change in POMs.
- Engine main code imports no JDBC, Spring or transport type; test doubles stay in `src/test` (Principles I, IV).
- No commits are part of this task list; the user decides when to commit.
- Record any new gap in `.specify/memory/memory.md` as a new `G-ENG-NN` entry rather than fixing it silently.
