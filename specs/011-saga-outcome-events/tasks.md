---

description: "Task list for 011-saga-outcome-events"
---

# Tasks: Saga Outcome Events

**Input**: Design documents from `/specs/011-saga-outcome-events/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: included. The spec gives an independent test for every story, the quickstart names the suites, and the
repository adds tests with every change (AGENTS.md). The unit-of-work refactoring is proven by the existing suites
staying unchanged (quickstart G1 to G4), so it adds only the boundary test.

**Organization**: setup, then the foundational phase:
- the unit-of-work refactoring, which changes no behaviour;
- the outcome-event core types and the lifecycle hook, which every outcome story needs.

The user stories follow. US4 (the rename, P2) comes **before** US1 to US3, because the outcome event of a failed
compensation names the renamed status (`eventId = sagaId:COMPENSATION_FAILED`, research §3). Doing it first avoids
writing and then rewriting the old name.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: the user story from spec.md (US1 to US5)

## Path Conventions

`…` stands for `src/main/java/io/github/gkosharovdev/sagaforge` and `…test` for
`src/test/java/io/github/gkosharovdev/sagaforge` inside the named module. Other paths are relative to the repository root.
`internal/` under the runtime means `saga-forge-runtime/…/runtime/internal/`.

---

## Phase 1: Setup

**Purpose**: the test dependency and a green baseline before the refactoring.

- [X] T001 Add `com.tngtech.archunit:archunit-junit5` (the current 1.x release on Maven Central) to `<dependencyManagement>` in `pom.xml` and as a `test`-scoped dependency in `saga-forge-runtime/pom.xml`; confirm `mvn -q -pl saga-forge-runtime -am test-compile` passes
- [X] T002 Record the baseline: run `mvn verify -Pcontainer-tests` on the branch before any source change and note the test count per module in `specs/011-saga-outcome-events/quickstart.md` under "Phase gate" (G1 compares against it)

---

## Phase 2: Foundational

### 2a. One unit of work (behaviour-preserving refactoring, research §1, contracts/unit-of-work.md)

**Purpose**: a single `UnitOfWork` class owns every runtime transaction. No test outside `UnitOfWorkBoundaryTest`
changes in this sub-phase.

- [X] T003 Write `UnitOfWorkBoundaryTest` in `saga-forge-runtime/…test/runtime/internal/UnitOfWorkBoundaryTest.java`: ArchUnit rule U1. Only `internal.UnitOfWork` may call `TransactionBoundary.begin`, `StorageProvider.findByIdAndLock`, `StorageProvider.save`, `Outbox.store` and `Outbox.committed` in `saga-forge-runtime` main classes; `EventPublisher` outside a unit of work keeps its direct `EventDispatcher.dispatch`. It fails until T013.
- [X] T004 Create `internal/WorkContext.java`, with the members contracts/unit-of-work.md lists:
  - the loaded `EngineSagaLifecycle`;
  - `newSagaObject()`, which injects `@SagaId`, `SagaContext` and `CommandGateway`, logic moved from `SagaUnitOfWork.runSteps` and `CompensationUnitOfWork`;
  - `running(String methodName)`, which scopes `CommandIds`;
  - `attempt()`;
  - `reportFailure(Throwable)`.
- [X] T005 Rewrite `internal/UnitOfWork.java` from an interface into the final class of contracts/unit-of-work.md:
  - factories `forInstance(settings, saga, sagaId, correlation)` and `standalone(settings, correlation)`;
  - nested `Work<T>` (`admit`, `perform`, `recorded`, default `recorded` → `false`);
  - sealed `Result<T>`: `Committed(value, afterRelease)`, `NotRun(reason)`, `Failed(running, cause)`, `Conflicted(unitOfWorkId)`;
  - `run(Work)` implementing steps 1 to 10. Batch building, `IN_TRANSACTION`/`AFTER_COMMIT` timing, `committed`, rollback-only check, discard and conflict detection are moved from `SagaUnitOfWork` (`commit`, `storeInTransaction`, `storeAfterCommit`, `buffered`, `discard`, `conflicted`) and `NonSagaUnitOfWork`.

  It keeps implementing `DomainEventRecorder` with `failed`, `nextCommandId`, `alreadyHandled`, `commandHandled`, `recordOutboxCommand` and static `bound()`, exactly as the interface did.
- [X] T006 [P] Create the work `internal/RunSteps.java`. It holds what `SagaUnitOfWork` decided:
  - `admit`: `lockInstance`'s rules (unmatched, compensating, duplicate, unexpected, not started, resume a claimed retry), returned as `NotRun` reasons that `AttemptLoop` maps to the same `AttemptResult`s as today;
  - `perform`: `runSteps`/`runQueued`/`runStep`, the `@EndSaga` and `@StartCompensation` stops, `advance`, `clearPendingRetry`;
  - `recorded`: queue events that trigger a step of this instance.

  Keep the step span (`SagaInstrumentation.STEP`) and the diagnostic calls verbatim.
- [X] T007 [P] Create the work `internal/RunCompensation.java` from `CompensationUnitOfWork`:
  - `admit`: a `COMPENSATING` instance with a `NOT_ATTEMPTED` step, else `NotRun(status)`;
  - `perform`: the next compensation, with the trigger parameter if it declares one, then `recordCompensated`;
  - recorded events are never run in process.

  Keep the compensation span and diagnostics.
- [X] T008 [P] Create the work `internal/HandleCommand.java` from `NonSagaUnitOfWork`: runs one handler `Runnable` in standalone scope; failure and rethrow rules of `NonSagaUnitOfWork` move to its caller in `internal/EngineCommandGateway.java` and the command receiver in `internal/EngineRuntime.java`
- [X] T009 [P] Create the lifecycle-only works `internal/RecordFailure.java` (from `FailedWrite`: missing or terminal instance → `NotRun`, else `fail`), `internal/ClaimAttempt.java` (from `AttemptWrite`), `internal/StartCompensation.java` (from `CompensationStartWrite`, including the "no completed step → `RecordFailure`" hand-over decided by its caller) and `internal/RecordCompensationFailure.java` (from `CompensationFailedWrite`, including its `FailureClassifier` call)
- [X] T010 Switch `internal/AttemptLoop.java` to `UnitOfWork.forInstance(...).run(new RunSteps(...))`, `RecordFailure`, `ClaimAttempt` and `StartCompensation`, mapping `Result` to the existing `AttemptResult`/`SagaHandlingResult` values, diagnostics (`saga.instance.failed`, `saga.instance.failedNotRecorded`) and in-process lock release order unchanged
- [X] T011 Switch `internal/CompensationRun.java` to `RunCompensation` and `RecordCompensationFailure` through `UnitOfWork`, keeping its `Summary` and the after-release outbox list in commit order
- [X] T012 Switch `internal/EngineCommandGateway.java` and the outbox-command receiver in `internal/EngineRuntime.java` to `UnitOfWork.standalone(...).run(new HandleCommand(...))`; nested `send` inside a bound unit of work keeps using `UnitOfWork.bound()`
- [X] T013 Delete `internal/SagaUnitOfWork.java`, `internal/CompensationUnitOfWork.java`, `internal/NonSagaUnitOfWork.java`, `internal/FailedWrite.java`, `internal/AttemptWrite.java`, `internal/CompensationStartWrite.java`, `internal/CompensationFailedWrite.java`; move their class Javadoc content to the works and to `UnitOfWork`; fix references in `internal/package-info.java` and other Javadoc
- [X] T014 Phase gate. All four must hold, and the commit holds only Phase 2a:
  - G1: `mvn verify -Pcontainer-tests` passes with T002's test counts plus `UnitOfWorkBoundaryTest`, and no existing test file modified (`git diff --stat -- '*/src/test/**'` shows only the new test);
  - G2: `FixtureParityTest` and the differential tests pass;
  - G3: `UnitOfWorkBoundaryTest` passes;
  - G4: the `rg` check in `specs/011-saga-outcome-events/quickstart.md` finds none of the removed classes.

### 2b. Outcome-event types and production (research §2 to §5, data-model.md)

**Purpose**: what US1, US2, US3 and US5 share.

- [X] T015 [P] Create `saga-forge-core/…/core/outcome/FailurePhase.java`, `OutcomeTrigger.java` (with `of(CompensationTrigger)`), `SagaOutcome.java` (sealed), `SagaCompleted.java`, `SagaFailed.java`, `SagaCompensated.java` and `package-info.java`. Each record validates in its canonical constructor as in data-model.md, its Javadoc has no `<h3>` and no `@throws` on the record comment (release build rule), and the package Javadoc says outcome events are framework events, not domain events.
- [X] T016 [P] Write `saga-forge-core/…test/core/outcome/OutcomeTypesTest.java`: the validation rules of data-model.md, including `SagaFailed` with a phase and trigger that do not match, `OutcomeTrigger.of` dropping the failure message, and unmodifiable `associations`.
- [X] T017 [P] Write `ReflectiveAnnotationScannerTest` cases in `saga-forge-core/…test/core/scanner/ReflectiveAnnotationScannerTest.java`: a `@SagaStep` taking `SagaFailed` or `SagaCompleted` is rejected with the existing non-`@DomainEvent` parameter message (FR-017, Q12).
- [X] T018 Add `Optional<SagaOutcome> outcome` to `PendingWrite` in `internal/EngineSagaLifecycle.java`. Build it in `complete`, `fail`, `endCompensatedIfDone` and `recordCompensationFailed` from the working copy, following the "Mapping from the instance" table in data-model.md with `eventId = sagaId + ":" + status`. Build it only when `EngineSettings.outcomeEvents()` is on, and clear it in `drain`.
- [X] T019 Add `outcomeEvents` (default `true`) to `internal/EngineSettings.java` and `SagaRuntime.Builder.outcomeEvents(boolean)` in `saga-forge-runtime/…/runtime/SagaRuntime.java`, with Javadoc.
- [X] T020 In `internal/UnitOfWork.java` step 8, append the drained write's outcome as the last element of the batch's `events` and of `recordedEvents`, so the batch is non-empty whenever an outcome exists (O2, O4, O5).
- [X] T021 In `internal/EventRouter.java` `receive`, return an empty `HandlingReport` for a `SagaOutcome` before `requireDomainEvent` (C1); document it in `saga-forge-core/…/core/spi/EventReceiver.java`.
- [X] T022 [P] Write `saga-forge-runtime/…test/runtime/internal/EngineSagaLifecycleTest.java` cases: each of the four terminal transitions produces the expected outcome; `advance`, `recordStepCompleted`, `startCompensation` with compensations left, and `recordCompensated` with steps left produce none; with outcome events off, none at all.

**Checkpoint**: the runtime produces outcome events and every existing suite still passes (outcome events are extra
batch elements; tests asserting exact batch contents are updated in the story phases).

---

## Phase 3: User Story 4 - A failed compensation is recorded as a fact (Priority: P2, done first)

**Goal**: `REQUIRES_INTERVENTION` becomes `COMPENSATION_FAILED` everywhere (FR-019 to FR-022, research §6).

**Independent Test**: a saga whose compensation throws ends `COMPENSATION_FAILED` with handling outcome
`COMPENSATION_FAILED` and one terminal `COMPENSATION_FAILED` history entry. A 0.1.0 database upgraded with
`schema-upgrade-011.sql` holds no `REQUIRES_INTERVENTION` instance.

### Tests for User Story 4

- [X] T023 [P] [US4] Update `saga-forge-runtime/…test/runtime/CompensationFailureTest.java` and `…test/runtime/LifecycleHistoryTest.java` to expect `COMPENSATION_FAILED` status and outcome, and exactly one terminal `COMPENSATION_FAILED` history entry (Q6, Q13)
- [X] T024 [P] [US4] Update `saga-forge-core/…test/core/spi/CompensationVocabularyTest.java`, `saga-forge-test/…test/test/FailureAndCompensationTest.java`, `saga-forge-storage-postgres/…test/storage/postgres/CompensationPersistenceTest.java`, `ForwardTransitionPropertyTest.java` and `LifecycleHistoryTest.java` (same package) for the renamed status and the removed kind
- [X] T025 [P] [US4] Write `saga-forge-storage-postgres/…test/storage/postgres/SchemaUpgrade011Test.java` (`@Tag("container")`):
  - set up a 006-era database with one `REQUIRES_INTERVENTION` instance and its two history rows, then apply `schema-upgrade-011.sql` twice;
  - assert the status became `COMPENSATION_FAILED` with every other column equal;
  - assert the `REQUIRES_INTERVENTION` history row is gone and the `COMPENSATION_FAILED` row kept;
  - assert the second run changed nothing (Q14).

### Implementation for User Story 4

- [X] T026 [US4] Rename `REQUIRES_INTERVENTION` to `COMPENSATION_FAILED` in `saga-forge-core/…/core/spi/SagaStatus.java` and `HandlingOutcome.java`; remove `REQUIRES_INTERVENTION` from `SagaLifecycleEventKind.java` and make the `COMPENSATION_FAILED` Javadoc say it is the terminal entry; update validation and Javadoc in `SagaInstance.java` and `SagaHandlingResult.java`
- [X] T027 [US4] Update `internal/EngineSagaLifecycle.java` (`recordCompensationFailed` sets `COMPENSATION_FAILED` and writes one `COMPENSATION_FAILED` entry; `isTerminal`), `internal/CompensationRun.java`, `internal/RecordCompensationFailure.java` and `internal/EventRouter.java` (telemetry outcome value `compensation_failed`)
- [X] T028 [P] [US4] Update `saga-forge-storage-postgres/…/storage/postgres/ForwardTransition.java` and the `trigger_iff_compensation` constraint in `saga-forge-storage-postgres/src/main/resources/io/github/gkosharovdev/sagaforge/storage/postgres/schema.sql`
- [X] T029 [P] [US4] Create `saga-forge-storage-postgres/src/main/resources/io/github/gkosharovdev/sagaforge/storage/postgres/schema-upgrade-011.sql` per research §6: drop and re-add `trigger_iff_compensation`, update the statuses, delete the `REQUIRES_INTERVENTION` history rows. Give it the header style of `schema-upgrade-006.sql` and make it idempotent.
- [X] T030 [P] [US4] Update the `InstanceView` Javadoc in `saga-forge-test/…/test/InstanceView.java`
- [X] T031 [US4] Rename the status in docs: `README.md`, `docs/architecture.md`, `docs/use-cases.md`, `docs/guide/model-reference.md`, `docs/guide/operations.md`, `docs/guide/recipes.md`, `docs/guide/limitations.md`, `specs/010-otel-observability/contracts/telemetry-reference.md` (a note that 011 renamed the attribute value), `AGENTS.md`, and Properties 27 and 30 to 34 in `.kiro/specs/saga-forge-framework/design.md`. Then run `docs/guide/build-llms-full.sh`.
- [X] T032 [US4] Run the Q15 search in `specs/011-saga-outcome-events/quickstart.md`. "Intervention" may remain only in historical specs 001 to 010, `.specify/memory/` and this feature's spec and research.

**Checkpoint**: US4 complete; `mvn verify -Pcontainer-tests` green.

---

## Phase 4: User Story 1 - React to a failed saga without polling (Priority: P1) 🎯 MVP

**Goal**: every `FAILED` write hands one `SagaFailed(STEP)` to the outbox in its own transaction, including a failure
before any unit of work committed (FR-004, FR-009, FR-010).

**Independent Test**: start step throws `FinalFailure`; exactly one `SagaFailed(STEP, FINAL)` after the `FAILED`
commit; none when the write records nothing.

### Tests for User Story 1

- [X] T033 [P] [US1] Write `saga-forge-runtime/…test/runtime/OutcomeEventsTest.java` failure cases over `support/CapturingOutbox`:
  - a `FinalFailure` in the start step (Q1);
  - retries exhausted before any commit (Q2);
  - an unclassified failure after an earlier commit;
  - the `FAILED` write finding the instance terminal, which yields no event (Q3).

  For each, assert the event's fields, `eventId`, the one-event batch with its own `unitOfWorkId`, and that the batch carries no failure message.
- [X] T034 [P] [US1] Write fault-injection cases in `saga-forge-runtime/…test/runtime/InTransactionOutboxTest.java`: an `IN_TRANSACTION` outbox throwing on the outcome batch of a `RecordFailure` unit of work leaves the instance non-terminal, logs `saga.instance.failedNotRecorded` and propagates; with `AFTER_COMMIT` the instance stays `FAILED` and the failure is in `outboxFailures` (Q9, O9)

### Implementation for User Story 1

- [X] T035 [US1] Make `internal/AttemptLoop.java` carry the `RecordFailure` unit of work's `afterRelease` outbox failures into the returned `SagaHandlingResult.outboxFailures()` (until now the `FAILED` result always had `List.of()`)
- [X] T036 [US1] Update existing runtime tests whose exact-batch assertions now see the trailing outcome event: `OutboxObligationsTest.java`, `OutboxObligationsPropertyTest.java`, `RecordedEventsTest.java`, `ImmediateDispatchOutboxTest.java` and `FailurePositionTest.java` in `saga-forge-runtime/…test/runtime/`. Assert the outcome event explicitly rather than filtering it out.

**Checkpoint**: MVP. A failed saga is announced durably with no application polling.

---

## Phase 5: User Story 2 - React to every way a saga ends (Priority: P1)

**Goal**: `SagaCompleted`, `SagaCompensated` and `SagaFailed(COMPENSATION)`, one per terminal transition, last in their
batch (FR-003, FR-005, FR-006, FR-012).

**Independent Test**: the canonical saga run to completion, to compensation and to a failed compensation yields
exactly one matching event each, and no second event on a later event.

### Tests for User Story 2

- [X] T037 [P] [US2] Add `OutcomeEventsTest` cases in `saga-forge-runtime/…test/runtime/OutcomeEventsTest.java`:
  - completion: the event is last after the step's recorded and still-queued events (Q4);
  - a `@StartCompensation` step with a committed step, and one with nothing to compensate (Q5);
  - a final failure after commit that compensates;
  - a failed compensation: `SagaFailed(COMPENSATION)` with the trigger and `eventId` ending `:COMPENSATION_FAILED` (Q6);
  - a resumed compensation, which yields one event;
  - a further event after the end, which yields no second event (Q7);
  - `outcomeEvents(false)`, which yields batches with no outcome (Q16).
- [X] T038 [P] [US2] Write `OutcomeEventsPropertyTest` in `saga-forge-runtime/…test/runtime/OutcomeEventsPropertyTest.java`: over random step outcomes, failures and compensation failures, every instance that reaches a terminal status yields exactly one outcome event of the matching kind, and every non-terminal instance yields none (Property 38).

### Implementation for User Story 2

- [X] T039 [US2] Update the remaining compensation and completion suites whose exact batches now end with an outcome event: `BusinessFailureCompensationTest.java`, `FinalFailureCompensationTest.java`, `CompensationOrderTest.java`, `CompensationResumeTest.java`, `SagaRuntimeHappyPathTest.java` and `TestTransportEndToEndTest.java` in `saga-forge-runtime/…test/runtime/`.
- [X] T040 [US2] Add Property 38 to `.kiro/specs/saga-forge-framework/design.md` after Property 37, worded as in research §12, and add 011 to the closing per-feature paragraph.

**Checkpoint**: every terminal transition is announced.

---

## Phase 6: User Story 3 - Outcome events reach application listeners durably (Priority: P1)

**Goal**: outcome events survive a crash with an in-transaction outbox, are ordered per instance by the in-memory
transport, reach Spring and Modulith listeners after commit, and are ignored by the runtime on delivery (FR-013 to
FR-015, FR-017).

**Independent Test**: over Postgres, stop after the terminal commit and restart. The outcome event is delivered once,
and a Spring transactional listener and a Modulith module listener each receive it.

### Tests for User Story 3

- [X] T041 [P] [US3] Add a case to `saga-forge-transport-inmemory/…test/transport/inmemory/support/TransportAcceptanceSuite.java`: a saga that fails and one that completes; the transport delivers each outcome event to its listeners once, keyed `saga:<sagaId>`; the runtime's report is empty and the message ends consumed, not set aside (Q11, C1, C3).
- [X] T042 [P] [US3] Add a case to `saga-forge-storage-postgres/…test/storage/postgres/RestartBacklogContainerTest.java`: stop the relay after a terminal commit and before delivery, restart, and expect the outcome event delivered once with the same `eventId` (Q8, SC-003).
- [X] T043 [P] [US3] Add to `saga-forge-storage-postgres/…test/storage/postgres/PostgresMessageStoreContainerTest.java` a `PayloadCodec` round-trip of `SagaCompleted`, `SagaFailed` (both phases) and `SagaCompensated` through `PostgresMessageStore` (research §11).
- [X] T044 [P] [US3] Add to `saga-forge-spring-boot-starter/…test/spring/modulith/ModuleListenerContainerTest.java` (Modulith interop) and `saga-forge-spring-boot-starter/…test/spring/runtime/RuntimeAssemblyContainerTest.java` (own log): a failing saga's `SagaFailed` reaches a `@TransactionalEventListener` and an `@ApplicationModuleListener` once each, after commit, and never on rollback (Q10, C4).
- [X] T045 [P] [US3] Add to `saga-forge-spring-boot-starter/…test/spring/transport/ApplicationEventBridgeTest.java`: under `application-events.mode=after-delivery`, the outcome event is republished once.

### Implementation for User Story 3

- [X] T046 [US3] In `saga-forge-transport-inmemory/…/transport/inmemory/internal/OrderingKeys.java` `ofEvent`, key a `SagaOutcome` as `"saga:" + sagaId` (C3).
- [X] T047 [US3] Add `saga-forge.outcome-events.enabled` (default `true`) to `saga-forge-spring-boot-starter/…/spring/SagaForgeProperties.java`, pass it to `SagaRuntime.Builder.outcomeEvents` in `saga-forge-spring-boot-starter/…/spring/runtime/SagaRuntimeConfiguration.java`, and describe it in `saga-forge-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json` and the property table of `saga-forge-spring-boot-starter/README.md`. `ReadmePropertiesTest` must pass.
- [X] T048 [US3] Update the Javadoc of `saga-forge-core/…/core/spi/Outbox.java` ("Which events") and `OutgoingMessages.java` (`events` may end with an outcome event), as listed in contracts/outcome-events.md "Javadoc changes".
- [X] T049 [US3] In `saga-forge-example-spring`, add a module listener on `SagaFailed` that records failed orders, in `saga-forge-example-spring/src/main/java/io/github/gkosharovdev/sagaforge/example/spring/`. Assert it in `saga-forge-example-spring/src/test/java/io/github/gkosharovdev/sagaforge/example/spring/OrderApplicationContainerTest.java` for a rejected and a failing order.

**Checkpoint**: outcome events are usable in production setups.

---

## Phase 7: User Story 5 - Test outcome events with the saga test fixture (Priority: P2)

**Goal**: one expectation call asserts a saga's outcome event (FR-018, SC-007, research §10).

**Independent Test**: a fixture test for the canonical saga's compensation path passes with
`expectOutcomeEvent(SagaCompensated.class, ...)` and fails readably with a wrong expectation.

### Tests for User Story 5

- [X] T050 [P] [US5] Write `saga-forge-test/…test/test/OutcomeEventExpectationsTest.java`: `expectOutcomeEvent` passes for exactly one matching event, and fails listing the events for zero, two, a wrong type and a non-matching predicate; `expectNoOutcomeEvent`; `outcomeEvents(false)`.
- [X] T051 [P] [US5] Extend `saga-forge-test/…test/test/FixtureParityTest.java` so the fixture and a hand-assembled runtime produce the same outcome events for its scenarios.

### Implementation for User Story 5

- [X] T052 [US5] Collect committed `SagaOutcome`s in `saga-forge-test/…/test/LoopBackTransport.java` `committed`, expose them as `EventResult.outcomeEvents()` in `saga-forge-test/…/test/EventResult.java`, add `expectOutcomeEvent(Class<T>, Predicate<? super T>)`, `expectOutcomeEvent(Class<?>)` and `expectNoOutcomeEvent()` to `saga-forge-test/…/test/Then.java`, and add `outcomeEvents(boolean)` to `SagaFixture.Builder` in `saga-forge-test/…/test/SagaFixture.java`.
- [X] T053 [P] [US5] Add outcome-event assertions to `saga-forge-example/src/test/java/io/github/gkosharovdev/sagaforge/example/OrderProcessingSagaFixtureTest.java` (completion, rejection, start-step failure) and `saga-forge-example-spring/src/test/java/io/github/gkosharovdev/sagaforge/example/spring/OrderSagaFixtureTest.java`.
- [X] T054 [US5] Document the new expectations in `saga-forge-test/README.md` and `docs/guide/testing.md`, with snippets copied from T053's compiling tests.

**Checkpoint**: all stories complete.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T055 [P] Add an "Outcome events" section to `docs/guide/operations.md` and a recipe to `docs/guide/recipes.md`, with snippets from T049 and T053:
  - listening in Spring and in a plain host;
  - the guarantee-per-outbox table of contracts/outcome-events.md;
  - "not a domain event; no saga step can take one";
  - `eventId` deduplication;
  - switching them off.

  Also add a note pointing to logging-backend masking and the OpenTelemetry Collector's redaction processor for failure text (G-ADOPT-03). Update `docs/guide/limitations.md` for G-011-01.
- [X] T056 [P] Update `docs/agents/saga-forge/SKILL.md` and `docs/agents/AGENTS-snippet.md` (outcome events, the renamed status, and D1 from G-DOC-03: a final failure compensates only after a commit), then run `docs/guide/build-llms-full.sh`.
- [X] T057 [P] Add the 011 entry to AGENTS.md "Current Status" (one unit of work, outcome events, the rename with `schema-upgrade-011.sql`), and update its Module Map line for `saga-forge-runtime` (ArchUnit test scope).
- [X] T058 [P] In `.specify/memory/memory.md`: close G-ADOPT-01 and G-011-02 with this feature, mark the adoption-backlog item done, and record any gap found during implementation.
- [X] T059 Write release notes in `CHANGELOG.md` (create it if missing) covering:
  - the renamed enum values and telemetry value;
  - `schema-upgrade-011.sql`;
  - outcome events on by default and how to switch them off;
  - that the `FAILED` write can now fail on an `IN_TRANSACTION` outbox error.
- [X] T060 Run the release build `mvn -P release -Dgpg.skip -DskipTests install` (Javadoc of the new types), then `mvn verify -Pcontainer-tests`, then every scenario in `specs/011-saga-outcome-events/quickstart.md`. Record the test count and date in AGENTS.md "Current Status".

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (1)** → **2a refactoring** → **2b outcome production** → **US4** → **US1** → **US2**, then **US3** and
  **US5**, which can run in parallel → **Polish**.
- 2a must land as its own commit and pass the gate (T014) before 2b starts. That commit is the evidence that the
  refactoring changed nothing.
- US4 runs before US1 to US3 only to avoid writing the old status name into new code and tests. US1 does not need
  US4 technically.
- US2 needs US4 for `COMPENSATION_FAILED` in the failed-compensation event.
- US3 and US5 need 2b and US1 (an event to deliver), and are independent of each other.

### Within phases

- T003 is written first and fails until T013 removes the old classes.
- T004 and T005 come before T006 to T009, which can run in parallel. T010 to T012 come after the works, and T013 last.
- In each story, write the tests first and confirm they fail, then implement.

### Parallel opportunities

- 2a: T006, T007, T008 and T009 (four new files).
- 2b: T015, T016, T017 and T022.
- US4: T023, T024, T025, T028, T029 and T030.
- US3: T041 to T045 (different modules).
- US5: T050, T051 and T053.
- Polish: T055 to T058.

## Parallel Example: User Story 3

```text
T041 transport acceptance case    (saga-forge-transport-inmemory)
T042 restart container case       (saga-forge-storage-postgres)
T043 PayloadCodec round-trip      (saga-forge-storage-postgres, other file)
T044 Spring/Modulith listeners    (saga-forge-spring-boot-starter)
T045 after-delivery bridge        (saga-forge-spring-boot-starter, other file)
```

## Implementation Strategy

### MVP

Setup, 2a (gate), 2b, US4 and US1. A failed saga, including one that fails in its start step, is announced durably,
which removes the adopter's polling for failures (W1, W2).

### Incremental delivery

1. 2a alone: a pure refactoring, reviewable and mergeable on its own.
2. Add 2b and US4: the rename plus outcome production.
3. Add US1: the MVP.
4. Add US2: all outcomes.
5. Add US3: production delivery.
6. Add US5: fixture support.
7. Polish.

Each step keeps `mvn verify -Pcontainer-tests` green.
