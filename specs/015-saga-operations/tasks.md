---

description: "Task list for 015-saga-operations"
---

# Tasks: Saga Operations: Query, Repair and Recovery

**Input**: Design documents from `/specs/015-saga-operations/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: included. Every story in the spec has an independent test. The contracts number their cases (O, R, RC, CP,
MR, ST, SW in `operations-api.md`; Q, F in `storage-query-spi.md`; E in `starter-operations.md`). AGENTS.md and the
constitution ask for a test with every new abstraction, written with or before its first caller: contract first for the
`StorageProvider` query, and the operations tests with `EngineSagaOperations`.

**Organization**:
- **Setup**: a green baseline and the design rules.
- **Foundational**: core values and SPI changes, the storage query on all three storages, the new instance fields, and
  the `EngineSagaOperations` skeleton. Nothing compiles across modules without it, because the `StorageProvider` change
  breaks every implementation.
- **The stories, in spec order**:
  - US1: query and view.
  - US2: resume compensation.
  - US3: recovery (stranded start, sweeper).
  - US4: operator compensate and mark resolved.
  - US5: HTTP and dashboards (starter endpoint, gauge, health).
- **Last**: telemetry that spans the stories, the fixture, container acceptance, docs and the register, then
  verification.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: the user story from spec.md (US1 to US5)

## Path Conventions

Inside a named module, two placeholders stand for long package paths:
- `…` means `src/main/java/io/github/gkosharovdev/sagaforge`.
- `…test` means `src/test/java/io/github/gkosharovdev/sagaforge`.

Other paths are relative to the repository root. Short prefixes:

| Prefix | Path |
|---|---|
| `ops/` | `saga-forge-core/…/core/operations/` |
| `spi/` | `saga-forge-core/…/core/spi/` |
| `rt/` | `saga-forge-runtime/…/runtime/` |
| `internal/` | `rt/internal/` |
| `rt-test/` | `saga-forge-runtime/…test/runtime/` |
| `postgres/` | `saga-forge-storage-postgres/…/storage/postgres/` |
| `pg-resources/` | `saga-forge-storage-postgres/src/main/resources/io/github/gkosharovdev/sagaforge/storage/postgres/` |
| `pg-test/` | `saga-forge-storage-postgres/…test/storage/postgres/` |
| `spring/` | `saga-forge-spring-boot-starter/…/spring/` |
| `fixture/` | `saga-forge-test/…/test/` |

---

## Phase 1: Setup

**Purpose**: a green baseline and the design rules in front of the implementer.

- [X] T001 On branch `015-saga-operations`, run `mvn -q install` from the repository root and confirm it is green before any code change (the spec, plan and register edits on the branch are docs only)
- [X] T002 Load the `effective-java`, `clean-code-java` and `solid-design-review` skills (AGENTS.md). Re-read plan.md "Design-rule application" and "Abstraction familiarity rating", and research.md R1–R15. Keep the names in data-model.md unless a test shows a better shape; if it does, update plan.md and data-model.md in the same change

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: the operations contracts and values, the new instance and history fields, the storage query on every
storage under one contract, and the wiring point `SagaRuntime.operations()`.

**⚠️ CRITICAL**: no user story work starts before this phase is complete.

### Core values (data-model.md "Core")

- [X] T003 [P] Create package `ops/` with `package-info.java`, plus the records `OperatorRequest` (with `recovery(reason)` and `RECOVERY_OPERATOR = "saga-forge-recovery"`), `SagaResolution` (with `of(OperatorRequest, Instant)`), `PageCursor` (with `token()`/`fromToken(String)`, base64url `epochSecond.nano:sagaId`), `SagaInstanceQuery` (`all()` plus withers, limit 1..500, default 50, copied sets, `modifiedAfter` before `modifiedBefore`), `SagaInstancePage`, `InstanceCounts` (`EnumMap` of every status, `get(SagaStatus)`) and `RecoveryReport` (`none()`). Follow data-model.md validation exactly, and state the canonical-constructor exceptions in a sentence, not with `@throws`
- [X] T004 [P] Create `ops/RepairResult.java` (sealed: `Applied(SagaInstance)`, `NotApplied(RepairRejection, SagaStatus)`), `ops/RepairRejection.java` (`NOT_FOUND`, `WRONG_STATUS`, `ALREADY_RESOLVED`) and `ops/SagaStartAbandonedException.java`
- [X] T005 Create `ops/SagaOperations.java` with the ten methods of data-model.md "SagaOperations". Its Javadoc covers thread safety, that queries take no lock, that repairs are synchronous, and that context values are reachable only through `context(sagaId)`
- [X] T006 [P] Write `saga-forge-core/…test/core/operations/OperationsRecordsTest.java`: validation and copies of every T003/T004 record; the `PageCursor` token round trip and malformed tokens; the query limit bounds and `modifiedAfter`/`modifiedBefore` order; a blank operator or reason, and lengths over 200/2,000

### Changed core types

- [X] T007 Extend `spi/SagaInstance.java` with `SagaResolution resolution` (nullable) and `int compensationRound` (≥ 0). Keep the old constructors (defaulting to `null`/0) and add `withResolution`/`withCompensationRound`. Update `saga-forge-core/…test/core/spi/` record tests
- [X] T008 Extend `spi/SagaLifecycleEntry.java` with a nullable `OperatorRequest operator` (keep the old constructor), and `spi/SagaLifecycleEventKind.java` with `COMPENSATION_RESUMED`, `RESOLVED` and `START_RECOVERED`. Update their tests
- [X] T009 Add `OPERATOR_REQUEST` to `saga-forge-core/…/core/compensation/CompensationTriggerKind.java`. Extend `CompensationTrigger.java` with `OperatorRequest operatorRequest`, present iff the kind is `OPERATOR_REQUEST`; for that kind `failure` is optional and `eventType`/`commandType`/`deadlineName` are null. Add the factory `byOperator(OperatorRequest, String stepName, SagaFailure failure, Instant at)`, keep the old constructors, and add the tests for the new rule (research R5)
- [X] T010 Extend `saga-forge-core/…/core/outcome/OutcomeTrigger.java` with `operatorRequest` under the same rule and map it from the compensation trigger. Make the outcome factories in `SagaCompleted`/`SagaFailed`/`SagaCompensated` and `Values.eventId` take `compensationRound`: `sagaId:STATUS` in round 0, else `sagaId:STATUS:r<round>` (research R11). Update the outcome tests and keep the existing round-0 ids byte-for-byte
- [X] T011 Add `Duration instanceCountInterval` (default 30 s, > 0) to `saga-forge-core/…/core/telemetry/SagaTelemetrySettings.java`, keeping the old factory, and update its test
- [X] T012 Add the abstract methods `find(SagaInstanceQuery)`, `countByStatus(Set<String>)` and `history(String)` to `spi/StorageProvider.java`, with Javadoc for order, exclusive bounds, empty results and "every status key present" (contracts/storage-query-spi.md)

### Storage contract first (contracts/storage-query-spi.md)

- [X] T013 Write the abstract `rt-test/support/StorageProviderQueryContract.java` with cases Q1–Q10, plus a jqwik property for Q7: random saves interleaved with paging, where every unchanged instance is seen exactly once. Export it in the runtime test-jar (the jar already exists). Implementations supply a fresh store, a "save in a transaction" helper and a clock
- [X] T014 Implement the query, counts and history in `rt-test/support/TransactionalInMemoryStorageProvider.java`. Rename its `history(String) → List<StoredEntry>` helper to `storedHistory` and update every caller under `saga-forge-runtime/src/test`. Add the forward checks F1–F8 to its committed-state check. Add `rt-test/support/TransactionalInMemoryStorageProviderQueryTest.java` extending the contract
- [X] T015 [P] Implement the query, counts and history (and F1–F8 if it checks transitions) in `fixture/FixtureStorage.java`, and add `saga-forge-test/…test/test/FixtureStorageQueryTest.java` extending the contract (the module already uses the runtime test-jar; add it with `test` scope if not)

### Postgres (data-model.md "Storage: Postgres", research R3, R12)

- [X] T016 Add `resolution JSONB`, `compensation_round INT NOT NULL DEFAULT 0`, history `operator TEXT`/`operator_reason TEXT` and the index `saga_instance_status_modified (status, last_modified_at, saga_id)` to `pg-resources/schema.sql`. Write `pg-resources/schema-upgrade-015.sql` (idempotent `IF NOT EXISTS`). Add the test resource `saga-forge-storage-postgres/src/test/resources/io/github/gkosharovdev/sagaforge/storage/postgres/schema-before-015.sql` and `pg-test/SchemaUpgrade015Test.java` (`@Tag("container")`, modelled on `SchemaUpgrade013Test`)
- [X] T017 Read and write `resolution` and `compensation_round` in `postgres/SagaInstanceRows.java`. Write the history operator columns in `postgres/LifecycleEntryRows.java`. Add `operatorRequest` to the trigger JSON in `postgres/JsonCodec.java` (absent in old rows means null)
- [X] T018 Implement `find` (keyset SQL with `status = ANY(?)`, `saga_type = ANY(?)`, exclusive bounds, `(last_modified_at, saga_id) > (?, ?)`, `LIMIT limit + 1`), `countByStatus` (one `GROUP BY status, resolution IS NULL`) and `history` (`ORDER BY sequence_number`) in `postgres/PostgresStorageProvider.java`. Reads join the runtime's transaction when one is active, else run in a read-only transaction of their own
- [X] T019 Extend `postgres/ForwardTransition.java` with the R12 rules (F1–F8) and their Javadoc, and add the cases to `pg-test/ForwardTransitionTest.java`
- [X] T020 Add `pg-test/PostgresStorageProviderQueryContainerTest.java` (`@Tag("container")`) extending `StorageProviderQueryContract`, and run it with `-Pcontainer-tests`

### Runtime wiring

- [X] T021 Make `internal/EngineSagaLifecycle.java` carry `resolution` and `compensationRound` through every write, and pass the round to the outcome factories (T010) wherever a terminal write builds its outcome event. The existing runtime tests stay green
- [X] T022 Create `internal/EngineSagaOperations.java` (package-private, implements `SagaOperations`, holds `Collaborators`, the shared `InstanceLocks`, saga models by type and `CompensationRun`). Implement the query methods (`find`, `findByAssociation`, `list`, `count`, `history`, `context`) over `StorageProvider`/`SagaContextStore` in a read-only transaction through the `TransactionBoundary`; each repair method returns `NotApplied(NOT_FOUND)` until its story. Wire it in `internal/EngineRuntime.java` with the same `InstanceLocks` that `EventRouter` and `CommandFailureHandler` use, and add `SagaRuntime.operations()` in `rt/SagaRuntime.java` (`IllegalStateException` before `start()`, like the other accessors)

**Checkpoint**: everything compiles, and the storage contract is green on three storages. The operations API answers
queries.

---

## Phase 3: User Story 1 — Find saga instances and see what happened (Priority: P1) 🎯 MVP

**Goal**: look up by id or association, list with filters and paging, count, and read history, with no context values
in views.

**Independent Test**: drive sagas into several statuses through the runtime and check O1–O7 against the stored state.

- [X] T023 [P] [US1] Write `rt-test/internal/EngineSagaOperationsQueryTest.java` with cases O1–O7 (contracts/operations-api.md) over `TransactionalInMemoryStorageProvider`, real sagas from the runtime test models, and a check that a query holds no instance lock (O6: a query finishes while another thread holds the instance's in-process lock and a unit of work is open)
- [X] T024 [US1] Make O1–O7 pass in `internal/EngineSagaOperations.java`. `context(sagaId)` returns an unmodifiable copy and an empty map for an unknown id; `count` computes `failedUnresolved`/`compensationFailedUnresolved` from `countByStatus`
- [X] T025 [P] [US1] Add a public-API test, `rt-test/SagaRuntimeOperationsTest.java`: `SagaRuntime.operations()` before start throws, and after start returns a working instance

**Checkpoint**: US1 is shippable alone (read-only operations).

---

## Phase 4: User Story 2 — Get a COMPENSATION_FAILED saga moving again (Priority: P1)

**Goal**: an operator resume that re-runs failed compensations and the remaining ones, recorded in history, with a
round-suffixed outcome event.

**Independent Test**: RC1–RC7 and R1–R6 with a compensation that fails once and then succeeds.

- [X] T026 [P] [US2] Write `rt-test/internal/ResumeCompensationTest.java` with cases RC1–RC7 and R2–R5 for resume. RC4 uses an outbox command failure over the runtime's test transport (012 setup) so that a compensation moves `COMPENSATED → FAILED` first
- [X] T027 [US2] Add `resumeCompensation(sagaId, request)` to `internal/EngineSagaLifecycle.java` (research R4). From `COMPENSATION_FAILED`: `COMPENSATING`, every `FAILED` step compensation back to `NOT_ATTEMPTED`, the instance failure cleared, round + 1. From `COMPENSATING`: only `lastModifiedAt`. Both append one `COMPENSATION_RESUMED` entry with the operator
- [X] T028 [US2] Create `internal/ResumeCompensation.java` (`UnitOfWork.Work<SagaInstance, RepairRejection>`) with the factory `byOperator(request)` and its admission: unresolved `COMPENSATION_FAILED`/`COMPENSATING`, else `NOT_FOUND`/`ALREADY_RESOLVED`/`WRONG_STATUS`
- [X] T029 [US2] Implement `resumeCompensation` in `internal/EngineSagaOperations.java`: validate the request, then, under `InstanceLocks`, run `ResumeCompensation` on `UnitOfWork.forInstance`, then `CompensationRun.run(...)`. Release each committed outbox after the lock, as `CommandFailureHandler` does. Return `Applied(instance as it ended)`, or `NotApplied`
- [X] T030 [P] [US2] Write `rt-test/internal/RepairConcurrencyTest.java` (R6): a resume racing a delivered event for the same instance, and two resumes racing each other. Every compensation runs at most once (count invocations on the saga's compensation methods)
- [X] T031 [US2] Add `ResumeCompensation` to the allowed works in `rt-test/internal/UnitOfWorkBoundaryTest.java` only if the ArchUnit rule names works explicitly; otherwise confirm it passes unchanged

**Checkpoint**: `COMPENSATION_FAILED` has a supported way out (closes G-006-03 in code).

---

## Phase 5: User Story 3 — Stalled and stranded instances recover on their own (Priority: P1)

**Goal**: a redelivered start adopts a stranded `CREATED` instance, and a sweeper resumes stalled compensations and
handles abandoned starts according to the policy.

**Independent Test**: ST1–ST3 and SW1–SW10 over the in-memory doubles. ST4 and SW5 are repeated over Postgres in Phase 9.

### Stranded start (research R10)

- [X] T032 [P] [US3] Write `rt-test/internal/StrandedStartTest.java` with cases ST1–ST3. Strand an instance by failing the first unit of work's transaction commit after the creation write (fault-injecting `InMemoryTransactionBoundary`), then deliver the start event again
- [X] T033 [US3] Add `recordStartRecovered(sagaId)` (a `START_RECOVERED` entry) to `internal/EngineSagaLifecycle.java`. In `internal/EventRouter.java`, on `DuplicateSagaInstanceException`, look up the active instance of (saga type, property, value); if it is `CREATED` with no completed step (`InstanceChecks`), take its instance lock and run the start step on it with `started = false`, appending `START_RECOVERED` in that unit of work. Otherwise report `DUPLICATE_START` as today. Keep the lock order duplicate-start → instance → storage and update the class Javadoc
- [X] T034 [US3] Add the `saga.start.recovered` log event to `internal/Diagnostics.java`, and the `saga_forge.start.recovered` span attribute on `saga_forge.handle` to `internal/SagaInstrumentation.java`

### Recovery sweeper (research R8, R9)

- [X] T035 [P] [US3] Create `rt/RecoverySettings.java` (public record: defaults, `enabledWithDefaults()`, withers, validation per data-model.md) and `rt/AbandonedStartPolicy.java` (`REPORT`, `FAIL`). Add `Builder.recovery(RecoverySettings)` in `rt/SagaRuntime.java` and record tests in `rt-test/RecoverySettingsTest.java`
- [X] T036 [P] [US3] Write `rt-test/internal/RecoverySweeperTest.java` with cases SW1–SW10 over a `MutableClock` and a manual `ScheduledExecutorService` (or direct `sweep()` calls); SW5 uses two runtimes over one `TransactionalInMemoryStorageProvider`
- [X] T037 [US3] Add the factory `ResumeCompensation.stalled(stallAge, now)` to `internal/ResumeCompensation.java`: it admits only `COMPENSATING` with `lastModifiedAt` before `now − stallAge`, and the operator is `OperatorRequest.recovery("stalled compensation resumed after " + stallAge)`
- [X] T038 [US3] Add `failAbandonedStart(sagaId, failure)` to `internal/EngineSagaLifecycle.java`. Create `internal/FailAbandonedStart.java` (admission: `CREATED`, no completed step, older than the abandonment age; writes `FAILED` with a `SagaFailure` of `SagaStartAbandonedException`, `UNCLASSIFIED`, attempts 1, so the terminal write emits `SagaFailed`)
- [X] T039 [US3] Create `internal/AbandonedStartReports.java` (a `ConcurrentHashMap<String, Instant>`: report at most once per abandonment age, forget the instance once it is no longer `CREATED`)
- [X] T040 [US3] Create `internal/RecoverySweeper.java`. `sweep()` runs the two batched queries of research R8 and handles each candidate through `EngineSagaOperations` (stalled resume, report or fail), counting per kind, logging and continuing after a per-instance error, and returning a `RecoveryReport`. `start()` schedules it at `interval` on a daemon thread `saga-forge-recovery` taken from an injectable `ScheduledExecutorService` supplier. `stop()` is idempotent, stops scheduling and waits for a running sweep
- [X] T041 [US3] Implement `recover()` in `internal/EngineSagaOperations.java` by delegating to the sweeper. Without recovery settings, use the defaults for ages and batch size so that a host can call `recover()` with scheduling off
- [X] T042 [US3] Add `SagaRuntime.stop()` in `rt/SagaRuntime.java` (idempotent; stops the sweeper, after which `receive` and repairs throw `IllegalStateException`). Start the sweeper from `start()` when `RecoverySettings.enabled`. Validate the settings at startup with the other startup checks (`internal/StartupChecks.java`)
- [X] T043 [US3] Add the log events `saga.recovery.compensationResumed`, `saga.recovery.startAbandoned`, `saga.recovery.startFailed` and `saga.recovery.failed` to `internal/Diagnostics.java`, and the counter `saga_forge.recovery{kind}` to `internal/SagaInstrumentation.java`

**Checkpoint**: stranded starts and stalled compensations recover without an operator (closes G-ENG-05, G-013-05 and
G-006-05 in code; addresses G-012-04, G-017-07).

---

## Phase 6: User Story 4 — Compensate or close a saga by operator decision (Priority: P2)

**Goal**: an operator compensates a `RUNNING` or `FAILED` instance with an operator trigger, and marks a `FAILED` or
`COMPENSATION_FAILED` instance resolved with no further effect.

**Independent Test**: CP1–CP6 and MR1–MR4.

- [X] T044 [P] [US4] Write `rt-test/internal/OperatorCompensateTest.java` with cases CP1–CP6. CP3 uses a saga with a deadline over the runtime's scheduling test outbox (017 setup). CP5 uses a compensation method that takes `CompensationTrigger`
- [X] T045 [P] [US4] Write `rt-test/internal/MarkResolvedTest.java` with cases MR1–MR4, plus R4 for resume and compensate on a resolved instance
- [X] T046 [US4] Add `StartCompensation.byOperator(trigger)` to `internal/StartCompensation.java`, with its own admission (unresolved `RUNNING`/`FAILED`, and no `NOTHING_COMMITTED` skip), mapping to `RepairRejection`. Build the trigger in `EngineSagaOperations` with `CompensationTrigger.byOperator`, using the most recently completed step (or the start step) and the recorded failure of a `FAILED` instance. Confirm that `EngineSagaLifecycle.startCompensation` closes deadlines and clears a pending retry on leaving `RUNNING` (017 `replace`), and that a `FAILED` instance's compensation start passes the in-memory forward checks
- [X] T047 [US4] Implement `compensate` in `internal/EngineSagaOperations.java` (under the lock: `StartCompensation.byOperator`, then `CompensationRun`, with outboxes released after the lock)
- [X] T048 [US4] Add `resolve(sagaId, request)` to `internal/EngineSagaLifecycle.java` (sets `SagaResolution`, appends `RESOLVED`, no status change, no outcome event). Create `internal/MarkResolved.java` with its admission, and implement `markResolved` in `internal/EngineSagaOperations.java`
- [X] T049 [US4] Make every admission (`ResumeCompensation`, `StartCompensation.byOperator`, `MarkResolved`) return `ALREADY_RESOLVED` for a resolved instance, and add the case to each test

**Checkpoint**: all three repair actions work through the API.

---

## Phase 7: User Story 5 — Operate sagas over HTTP and dashboards (Priority: P2)

**Goal**: the Actuator `sagas` endpoint (reads by default, writes through Boot access control and a `Principal`), the
recovery properties, the instances gauge and the health details.

**Independent Test**: E1–E7 plus a gauge check.

### Runtime gauge and operation telemetry (research R13)

- [X] T050 [P] [US5] Write `rt-test/internal/OperationsTelemetryTest.java` with the OpenTelemetry SDK testing exporter: the `saga_forge.operation` span with action and result for each repair; the `saga_forge.operations` counter; and the `saga_forge.instances` gauge, whose series per (status, resolved) refresh at most once per `instanceCountInterval` (count `countByStatus` calls through a spying storage)
- [X] T051 [US5] Implement the operation span and counter around each repair in `internal/EngineSagaOperations.java`. Implement the observable gauge with cached counts in `internal/SagaInstrumentation.java`, and the `saga.operation.applied`/`saga.operation.notApplied` log events in `internal/Diagnostics.java`

### Starter (contracts/starter-operations.md)

- [X] T052 [US5] Add `recovery.*` (enabled default **true**, interval, stall-age, batch-size, abandonment-age, abandoned-start-policy), `operations.expose-context-values` (false) and `telemetry.instance-count-interval` to `spring/SagaForgeProperties.java` and `saga-forge-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json`. Pass them to the builder in `spring/runtime/SagaRuntimeConfiguration.java`. Add a `FailureAnalyzer` for invalid recovery settings and register it in `META-INF/spring.factories` (or wherever the existing analyzers are registered)
- [X] T053 [US5] Make `spring/runtime/SagaRuntimeLifecycle.java` `stop()` call `SagaRuntime.stop()`, and add the case to its test
- [X] T054 [P] [US5] Write `saga-forge-spring-boot-starter/…test/spring/actuator/SagasEndpointTest.java` (`ApplicationContextRunner`, invoking the endpoint bean) with cases E1, E4, E5 and E6: no write without access; no `context` key unless exposed; endpoint present without a transport bean. Also cover the list parameters (repeated `status`, a bad `limit` → 400, a cursor round trip)
- [X] T055 [US5] Create `spring/actuator/SagasEndpoint.java` (`@Endpoint(id = "sagas", defaultAccess = Access.READ_ONLY)`). Read: list (returns instances without `state`, `next` token, `counts`) and view (`@Selector sagaId` → instance and history, plus context when exposed, 404 when unknown). Write: `@WriteOperation` repair with selectors `sagaId` and `action` (`resume-compensation`, `compensate`, `resolve`), body `reason`, and a `Principal` parameter: 401 when there is none, 400 for a blank reason or an unknown action, 409 for `NotApplied`. Register it in `spring/actuator/SagaForgeActuatorConfiguration.java` under `@ConditionalOnAvailableEndpoint` and `@ConditionalOnBean(SagaRuntime.class)`, independent of `InMemoryTransport` (split the configuration so that the transport-bound `sagaforge` beans keep their condition)
- [X] T056 [US5] Add the details `instances.compensationFailed` and `instances.failedUnresolved` to `spring/actuator/SagaForgeHealthIndicator.java` without changing the status (E7). When there is no transport, register a runtime-only health contribution, or document why not, in the starter README
- [X] T057 [US5] Add the new properties and the `sagas` endpoint (exposure, `management.endpoint.sagas.access=unrestricted`, the security recommendation) to `saga-forge-spring-boot-starter/README.md`, and keep `ReadmePropertiesTest` green
- [X] T058 [US5] Add `saga-forge-example-spring/…test/.../SagasEndpointWebContainerTest.java` (`@Tag("container")`) with Spring Security in test scope and two users, covering E2 and E3 over real HTTP. Set the example's test properties to expose `sagas`

**Checkpoint**: operators can use every action over HTTP, and dashboards get the per-status gauge (closes G-008-02 in
code).

---

## Phase 8: Test fixture (research R15, FR-038)

- [X] T059 Add `SagaFixture.operations()` and `SagaFixture.Builder.recovery(RecoverySettings)` in `fixture/SagaFixture.java`. When recovery is enabled, `whenTimeElapses`/`whenTimeReaches`/`givenTimeElapses` run `operations().recover()` after advancing the clock
- [X] T060 Add `resolution()` and `history()` to `fixture/InstanceView.java`, and `expectResolved()`, `expectNotResolved()` and `expectHistory(SagaLifecycleEventKind...)` (a subsequence check with a clear `SagaExpectationError` message) to `fixture/Then.java`
- [X] T061 [P] Write `saga-forge-test/…test/test/OperationsFixtureTest.java`: resume, compensate and resolve through the fixture; a stall recovered by `whenTimeElapses(6 min)`; an abandoned start under both policies. Extend `FixtureParityTest` with one resume scenario compared against a hand-assembled runtime
- [X] T062 [P] Add `saga-forge-example/…test/.../OrderProcessingSagaOperationsFixtureTest.java` (quickstart §2): a compensation that fails once, is resumed and ends `COMPENSATED`; and stall recovery
- [X] T063 Update `saga-forge-test/README.md` with the operations and recovery parts of the fixture

---

## Phase 9: Container acceptance (SC-003, SC-004, SC-005)

- [X] T064 Write `pg-test/OperationsAcceptanceTest.java` (`@Tag("container")`, two runtime + in-memory transport pairs over one Postgres with separate pools, as in 013's `MultiInstanceAcceptanceTest`), covering:
  - ST4: a crash after the creation write; the relay redelivers and the saga completes with exactly one instance.
  - SW5: both runtimes run `recover()` concurrently over 500 stalled instances; each compensation runs once (SC-004).
  - A listing of 10,000 instances at 500 per page sees each one exactly once (SC-005).
  - 1,000 sagas with crashes injected after the creation write and after a compensation commit all reach a terminal status within stall age plus two sweep intervals (SC-003, with short ages).
- [X] T065 Run `mvn -Pcontainer-tests -pl saga-forge-storage-postgres,saga-forge-spring-boot-starter,saga-forge-example-spring -am verify` and fix what fails

---

## Phase 10: Docs and register

- [X] T066 [P] Add an "Operations and recovery" section to `docs/architecture.md`: the operations API, repair admissions, operator transitions, recovery safety by lock (not leadership), the stranded-start adoption, outcome ids per round, and the `sagas` endpoint security model
- [X] T067 [P] Write the guide pages in `docs/guide/`: a new `operations.md`, or an extension of the existing operations page (finding instances, the three repairs, recovery settings, the abandoned-start policy and why `report` is the default, the Actuator endpoint and its access). Every snippet must be copied from compiling code and name its source file (the fixture tests and the Spring example). Add `mkdocs.yml` navigation if the page is new. List G-015-01 to G-015-07 in `docs/guide/limitations.md`
- [X] T068 [P] Update `docs/agents/saga-forge/SKILL.md` and `docs/agents/AGENTS-snippet.md` (operations API, recovery, properties, endpoint), then run `docs/guide/build-llms-full.sh`
- [X] T069 [P] Add the properties of FR-041 to `.kiro/specs/saga-forge-framework/design.md`: operator transitions; at most one run per compensation per round; an adopted start never creates a second instance; Property 38 revised to "one outcome event per terminal write, with an id unique per instance". Each property names its contract cases
- [X] T070 Update `.specify/memory/memory.md`:
  - Close G-006-03, G-006-05, G-008-02, G-ENG-05 and G-013-05.
  - Update G-012-04 and G-017-07 (recovered by the sweeper), and G-003-24 (history is now readable through `StorageProvider.history`).
  - Add G-015-02 to G-015-07 from research.md, keeping G-015-01.
  - Mark adoption-backlog item 5 done.
- [X] T071 Update `.specify/memory/roadmap.md` (015 done, 015b next) and the "Current Status", "Known Gaps" and "Module Map" (runtime public surface: `RecoverySettings`, `AbandonedStartPolicy`, `operations()`, `stop()`) sections of `AGENTS.md`

---

## Phase 11: Verification

- [X] T072 Run `mvn spotless:apply`, then `mvn -Pcontainer-tests verify` (whole build, container suites included), and record the test count and date in AGENTS.md "Current Status"
- [X] T073 Run `mvn -P release -Dgpg.skip -DskipTests install` (Javadoc must pass: no `<h3>`, no `@throws` on record comments). Check that `docs/guide/build-llms-full.sh` leaves no diff, and mark spec.md "Status: Implemented (date)"

---

## Dependencies & Execution Order

- **Setup (T001–T002)** comes first.
- **Foundational (T003–T022)** blocks every story:
  - T003–T006 can run in parallel. T007–T012 depend on T003/T004.
  - The contract T013 comes before T014/T015/T020.
  - Postgres T016–T019 come before T020.
  - T021 and T022 depend on T007–T012.
- **US1 (T023–T025)** needs only the foundation. It is the MVP.
- **US2 (T026–T031)** needs the foundation. It is independent of US1, but shares `EngineSagaOperations` (sequence the edits to that file).
- **US3 (T032–T043)**:
  - The stranded-start part (T032–T034) needs only the foundation.
  - The sweeper part needs US2's `ResumeCompensation` (T028), because T037 adds its stalled factory.
- **US4 (T044–T049)** needs the foundation. T049 touches US2's and US3's admissions, so it runs after them.
- **US5 (T050–T058)** needs US1 for the reads, and US2 and US4 for the write actions behind the endpoint. T052/T053 need US3's settings.
- **Fixture (T059–T063)** needs US2, US3 and US4.
- **Acceptance (T064–T065)** needs US2, US3, US5 and Postgres.
- **Docs (T066–T071)** can start once the APIs settle, after US4. **Verification (T072–T073)** comes last.

### Parallel opportunities

- Foundational: T003, T004 and T006 together; T015 alongside T016–T019 (different modules).
- Tests written before their implementation within each story: T023/T025, T026/T030, T032/T035/T036, T044/T045,
  T050/T054.
- Docs T066–T069 together.

### Parallel example: User Story 3

```text
Task: "T032 [US3] Write StrandedStartTest ST1–ST3 in rt-test/internal/StrandedStartTest.java"
Task: "T035 [US3] Create RecoverySettings and AbandonedStartPolicy in rt/"
Task: "T036 [US3] Write RecoverySweeperTest SW1–SW10 in rt-test/internal/RecoverySweeperTest.java"
```

## Implementation Strategy

1. **MVP**: Setup, then Foundational, then US1. Read-only operations are useful on their own (support, dashboards) and
   carry no risk.
2. **Then US2**: the dead end `COMPENSATION_FAILED` gets a way out.
3. **Then US3**: silent strands and stalls recover. With US2, all three P1 stories are in.
4. **Then US4 and US5**: operator decisions, HTTP and the gauge.
5. **Finally**: the fixture, container acceptance, docs and the register, then verification. Commit per phase, run
   `mvn spotless:apply` before each commit, and run each phase's tests before moving on.

## Implementation notes (deviations, 2026-11-26)

- T014/T015: Q7 is a seeded `Random` loop over five seeds, not jqwik (`saga-forge-test` has no jqwik). The in-memory
  storage doubles do not repeat the Postgres F1–F8 forward checks; `ForwardTransitionOperationsTest` covers them.
- T018: `SchemaUpgrade015Test` chains the released upgrade scripts instead of a `schema-before-015.sql` file.
- T022/T024: queries read storage directly, without a read-only transaction (no lock, no write; O6 holds).
- T023–T050: the runtime cases live in `SagaRepairTest`, `SagaRecoveryTest`, `ParcelRepairTest` and
  `OperationsConcurrencyAndTelemetryTest` in the runtime's root test package (they use the public API), and
  `OutsideUnitOfWorkTest` checks `operations()`/`stop()` before start. Not covered by a runtime test: ST2 (racing
  redelivery, guarded by the re-read under the instance lock), CP3 (deadline closed on operator compensation, which
  goes through the same `EngineSagaLifecycle.replace` as 017), SW5 (two runtimes; covered on Postgres by `OperationsAcceptanceTest` with 40 instances, not 500), SW10 and the gauge refresh cache.
- T046: the operator compensation is its own work, `CompensateByOperator`, not a factory on `StartCompensation`.
- T042: settings are validated by the `RecoverySettings` constructor, so `StartupChecks` gains no check.
- T052: invalid `saga-forge.recovery.*` values fail through `SagaForgeProperties.validate()` and the existing
  `SagaForgeConfigurationFailureAnalyzer`, not a new analyzer; the metadata comes from the configuration processor.
- T054: `SagasEndpointTest` invokes the endpoint bean over `UnwindFixture` from the runtime test-jar. T058's E2 uses
  `resolve` on a `FAILED` order (the example has no compensation that fails); test-only web, security and actuator
  dependencies, and `src/test/resources/config/application.properties` sets a random port.
- T059–T062: the fixture adds `whenOperator(...)` (a repair with expectations) and an `operations()` that delivers
  what a repair caused. `OperationsFixtureTest` covers resume, compensate, resolve and recovery on time; a stall or an
  abandoned start cannot be produced through the fixture (it has no crash injection), so those stay with the runtime
  tests, and `FixtureParityTest` gains no resume scenario. The example's fixture test covers the resumed refund only.
