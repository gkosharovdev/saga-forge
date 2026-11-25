---

description: "Task list for 012-outbox-command-failure-feedback"
---

# Tasks: Outbox Command Failure Feedback

**Input**: Design documents from `/specs/012-outbox-command-failure-feedback/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: included. The spec gives an independent test for every story, the quickstart names the suites, and the
repository adds tests with every change (AGENTS.md). Constitution 1.1.0: every new abstraction is tested together
with its first caller, in the same task or the task right before it.

**Organization**: setup, then the foundational phase (core vocabulary, the two SPI methods, storage support), which all
stories need. Then the stories in priority order. US1 builds the whole report path for a final failure; US2 adds the
exhausted path on top of it; US3 hardens the reaction (ignored reports, idempotence, commands sent by a compensation,
crash safety); US4 protects commands sent outside a saga; US5 brings the fixture along.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: the user story from spec.md (US1 to US5)

## Path Conventions

`…` stands for `src/main/java/io/github/gkosharovdev/sagaforge` and `…test` for
`src/test/java/io/github/gkosharovdev/sagaforge` inside the named module. Other paths are relative to the repository
root. `internal/` under the runtime means `saga-forge-runtime/…/runtime/internal/`; under the transport,
`saga-forge-transport-inmemory/…/transport/inmemory/internal/`.

---

## Phase 1: Setup

**Purpose**: a green baseline and the design skills.

- [X] T001 Run `mvn -q install` from the repository root and confirm it is green on this branch before any change
  (baseline for SC-006); note the test count in the PR description draft.
- [X] T002 Load the `effective-java`, `clean-code-java` and `solid-design-review` skills (AGENTS.md) and apply them to
  every type added below: records with validating compact constructors, no flag arguments, composition, small
  interfaces.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: the core vocabulary and SPI the report path needs ([data-model.md](data-model.md)). All additive; old
constructors and methods keep their behaviour (DEC-REL-01).

**⚠️ CRITICAL**: no user story work starts before this phase is done.

- [X] T003 [P] Add `static FailureClassification of(Throwable failure)` to
  `saga-forge-core/…/core/failure/FailureClassification.java` (markers through the cause chain, stop at a repeated
  cause, `Error` → `UNCLASSIFIED`, `null` → `IllegalArgumentException`, never `EXHAUSTED`); test it in
  `saga-forge-core/…test/core/failure/FailureClassificationOfTest.java` (direct marker, nested marker, both markers →
  first wins, cycle, `Error`, null).
- [X] T004 Make the runtime's `internal/FailureClassifier.java` delegate its marker walk to
  `FailureClassification.of`, keeping its `retryOn` rule; `FailureClassifierTest` and `FailureClassificationTest` in
  `saga-forge-runtime/…test/runtime/` stay unchanged and green.
- [X] T005 [P] Add the record `saga-forge-core/…/core/spi/CommandFailure.java` (`commandId`, `commandType`,
  `classification`, `causeType`, `attempts`; rules in [data-model.md](data-model.md); no message) with Javadoc; test it
  in `saga-forge-core/…test/core/spi/CommandFailureTest.java` (each rule, non-saga-scoped id rejected, `TRANSIENT` and
  `UNCLASSIFIED` rejected, value equality).
- [X] T006 Add `COMMAND_FAILED` to `saga-forge-core/…/core/compensation/CompensationTriggerKind.java`, and to
  `CompensationTrigger.java` the `commandType` component, the canonical six-argument constructor, the kept
  five-argument constructor (passes `null`), the `COMMAND_FAILED` invariants and the factory
  `commandFailed(CommandFailure report, Instant at)`; extend the core trigger test in
  `saga-forge-core/…test/core/compensation/` (create `CompensationTriggerTest.java` if none exists) for each invariant
  and for the old constructor.
- [X] T007 Add nullable `commandType` to `saga-forge-core/…/core/outcome/OutcomeTrigger.java`, keep the old
  constructor, copy it in `OutcomeTrigger.of(CompensationTrigger)`; test the copy and the old constructor in
  `saga-forge-core/…test/core/outcome/`.
- [X] T008 Add the default method `receiveFailure(CommandFailure, Consumer<LocalTransaction>)` to
  `saga-forge-core/…/core/spi/CommandReceiver.java` (throws `UnsupportedOperationException` naming the class; Javadoc
  states caller obligations C1 to C5 and guarantees R1 to R6 of
  [contracts/command-receiver-failure.md](contracts/command-receiver-failure.md)); test the default in
  `saga-forge-core/…test/core/spi/CommandReceiverDefaultsTest.java`.
- [X] T009 Add the default method `setAside(LocalTransaction tx, String consumerId, String messageId,
  DeliveryFailure failure, String reason)` to `saga-forge-core/…/core/spi/store/MessageStore.java` (throws
  `UnsupportedOperationException`); add commit and rollback cases to
  `saga-forge-transport-inmemory/…test/transport/inmemory/support/MessageStoreContract.java` and implement it in
  `…test/transport/inmemory/support/InMemoryMessageStore.java`.
- [X] T010 [P] Implement `setAside(tx, ...)` in `saga-forge-storage-postgres/…/storage/postgres/PostgresMessageStore.java`
  in the given transaction; `PostgresMessageStoreContainerTest` runs the new contract cases (`-Pcontainer-tests`).
- [X] T011 [P] Write and read `commandType` on the trigger in
  `saga-forge-storage-postgres/…/storage/postgres/JsonCodec.java` (omitted when `null`, missing field reads `null`);
  cover a `COMMAND_FAILED` round trip and a pre-012 trigger row in `JsonCodecTest.java`.
- [X] T012 Extend the runtime test double `saga-forge-runtime/…test/runtime/support/TestTransport.java` with a
  `reportFailure(CommandFailure)` helper that calls `receiveFailure` and records whether, and in which transaction,
  the set-aside callback ran (the first caller of the new SPI method in tests).

**Checkpoint**: `mvn -q -pl saga-forge-core,saga-forge-runtime,saga-forge-transport-inmemory,saga-forge-storage-postgres -am test` green.

---

## Phase 3: User Story 1 - A finally rejected outbox command compensates its saga (Priority: P1) 🎯 MVP

**Goal**: a `FINAL` failure of a step-sent outbox command is reported once, after one attempt, and the instance
compensates every committed step, newest first, ending with one outcome event whose trigger says a command failed.

**Independent Test**: a three-step saga whose third step's outbox command handler throws a `FinalFailure` ends
`COMPENSATED` with steps 3, 2, 1 compensated in that order, one `SagaCompensated` with `kind = COMMAND_FAILED`, and
the handler ran once (SC-001, SC-004).

### Tests for User Story 1

- [X] T013 [P] [US1] Write `saga-forge-runtime/…test/runtime/CommandFailureCompensationTest.java` over
  `TransactionalInMemoryStorageProvider` and `TestTransport`: report a `FINAL` failure for a command sent by step 3 →
  compensations 3, 2, 1; status `COMPENSATED`; one `SagaCompensated` with `OutcomeTrigger(COMMAND_FAILED, step 3,
  commandType)`; the instance's trigger holds classification, cause type, attempts and no message (US1 scenarios 1, 2,
  4); a failing compensation → `COMPENSATION_FAILED` and one `SagaFailed(COMPENSATION)` (scenario 3); the set-aside
  callback ran in the reaction's transaction, and rolled back with it when storage conflicts. Expected to fail until
  T019.
- [X] T014 [P] [US1] Add final-failure cases to `saga-forge-transport-inmemory/…test/transport/inmemory/support/TransportAcceptanceSuite.java`
  and `FailureHandlingTest.java`: a saga-scoped command throwing `FinalFailure` is attempted once, `receiveFailure` is
  called with `FINAL` and `attempts = 1`, the message is set aside `FINAL_FAILURE` inside the callback's transaction; a
  receiver that throws leaves the message for redelivery with backoff ([contracts/relay-classification.md](contracts/relay-classification.md)).

### Implementation for User Story 1

- [X] T015 [US1] Change `internal/StartCompensation.java` to take the `CompensationTrigger` to record as a constructor
  argument and an optional `Consumer<LocalTransaction>` it calls in `perform`; add `NotStarted.ALREADY_COMPENSATING`
  to its admission; update its current caller (the code that builds `CompensationTrigger.failed(...)` today) to pass
  the trigger; existing compensation suites (`FinalFailureCompensationTest`, `CompensationOrderTest`, ...) stay green.
- [X] T016 [US1] Add the sealed `internal/Sender.java` (`StepSender(stepName)`, `CompensationSender(forStep)`,
  `Unknown`) and its resolution from `CommandId.stepName()` against the saga model the runtime already holds (the
  model behind `saga.compensationFor(name)` in `EngineSagaLifecycle`); unit-test the resolution in
  `saga-forge-runtime/…test/runtime/internal/SenderTest.java`.
- [X] T017 [US1] Add `internal/CommandFailureHandler.java` ([contracts/runtime-reaction.md](contracts/runtime-reaction.md)
  §A): take the `InstanceLocks` lease, resolve the sender, run `StartCompensation(CompensationTrigger.commandFailed(...))`
  in `UnitOfWork.forInstance(sagaId)`, run `CompensationRun` when it committed, release the lease, run the after-release
  outbox steps; on a skip, run the callback in a standalone `HandleCommand` and log the diagnostic (no failure
  message). Holds collaborators by composition; no business rule of its own.
- [X] T018 [US1] Add a `COMMAND_FAILED` trigger value `command_failed` to the runtime telemetry in
  `internal/SagaInstrumentation.java`, and open the `saga_forge.handle` span for a report with
  `saga_forge.trigger = command_failed`, parented on the delivery context; cover it in
  `saga-forge-runtime/…test/runtime/telemetry/`.
- [X] T019 [US1] Implement `receiveFailure` in the runtime's receiver in `internal/EngineRuntime.java`, delegating to
  `CommandFailureHandler`; T013 passes; `UnitOfWorkBoundaryTest` stays green.
- [X] T020 [US1] In `internal/Relay.java` (transport), classify a failed `MessageKind.COMMAND` delivery with
  `FailureClassification.of`; for `FINAL`: no retry, a saga-scoped command calls `receiveFailure(FINAL, attempts)` and
  sets aside `FINAL_FAILURE` via `MessageStore.setAside(tx, ...)` inside the callback; a throwing receiver records a
  failed attempt with backoff. Events keep today's path. T014 passes.
- [X] T021 [US1] Add to `internal/TransportInstrumentation.java` the delivery span attributes
  `saga_forge.command.classification` and `saga_forge.command.reported` and the counter `saga_forge.command.failures`
  (`classification`); cover them in `TransportTelemetryTest.java`.
- [X] T022 [US1] End-to-end over the in-memory transport: add a case to
  `saga-forge-transport-inmemory/…test/transport/inmemory/PlainJavaAcceptanceTest.java` (or a new
  `CommandFailureAcceptanceTest.java` beside it) wiring the shipped runtime and transport: a step-sent command throws
  `FinalFailure` → saga `COMPENSATED`, message set aside `FINAL_FAILURE`, handler called once.

**Checkpoint**: US1 works end to end on the in-memory transport; MVP.

---

## Phase 4: User Story 2 - A command that keeps failing exhausts and compensates (Priority: P1)

**Goal**: a non-final failure retries with the relay's settings; when `maxAttempts` is used up it is reported as
`EXHAUSTED` and the instance compensates.

**Independent Test**: a handler that always throws an unclassified failure is attempted `maxAttempts` times, then the
instance compensates and the outcome event's trigger reports an exhausted command.

- [X] T023 [P] [US2] Add to `TransportAcceptanceSuite.java` and `FailureHandlingTest.java`: unclassified and
  `TransientFailure` failures retry with the relay's backoff (never the step's `@RetryPolicy`, FR-002); at
  `maxAttempts` the relay calls `receiveFailure(EXHAUSTED, maxAttempts)` and sets aside `MAX_ATTEMPTS` in the
  callback; two failures then a success → nothing reported (US2 scenario 2); every attempt carries the same
  `CommandId` (scenario 3, FR-003).
- [X] T024 [P] [US2] Add an `EXHAUSTED` case to `CommandFailureCompensationTest.java`: trigger kind `COMMAND_FAILED`,
  classification `EXHAUSTED`, attempts recorded.
- [X] T025 [US2] Implement the exhausted branch in `internal/Relay.java` (transport): at `attempts >= maxAttempts`, a
  saga-scoped command reports `EXHAUSTED` and is set aside `MAX_ATTEMPTS` inside the callback; T023 passes; the
  counter counts `exhausted`.

**Checkpoint**: US1 and US2 pass together.

---

## Phase 5: User Story 3 - Late or repeated failure reports change nothing (Priority: P1)

**Goal**: ignored reports leave the instance unchanged with a diagnostic; a duplicate report changes nothing; a
command sent by a compensation ends a still-compensating instance `COMPENSATION_FAILED`; crashes never cause a
double compensation or a lost report.

**Independent Test**: the same report twice and a report for a completed instance: one compensation, two diagnostics.

- [X] T026 [P] [US3] Write `saga-forge-runtime/…test/runtime/IgnoredCommandFailureTest.java`: reports for instances
  `COMPLETED`, `FAILED`, `COMPENSATED`, `COMPENSATION_FAILED`, `COMPENSATING` (step-sent) and missing → instance and
  history unchanged, no outcome event, one `WARNING` diagnostic naming saga id, command type and reason, set-aside
  callback still ran (US3 scenarios 1, 2, 4; FR-012); a second identical report after a compensation → nothing changes
  (scenario 3, FR-013); an unknown sender name → `unknown-sender`.
- [X] T027 [P] [US3] Write `saga-forge-runtime/…test/runtime/CompensationCommandFailureTest.java`: a command sent by
  `compensatePayment` fails while the instance is `COMPENSATING` → that step's compensation `FAILED`, instance
  `COMPENSATION_FAILED`, one `SagaFailed(COMPENSATION)`, compensations not yet run do not run (FR-012a); after the
  instance ended `COMPENSATED` → unchanged, diagnostic `already-compensated` (G-012-01).
- [X] T028 [US3] Add `EngineSagaLifecycle.recordCompensationCommandFailed(sagaId, step, failure)` in
  `internal/EngineSagaLifecycle.java` (step compensation `COMPENSATED → FAILED`, instance `COMPENSATING →
  COMPENSATION_FAILED`, lifecycle entry, outcome event as 011 does for a failed compensation).
- [X] T029 [US3] Add the work `internal/RecordCompensationCommandFailure.java` (`Work<Void, Ignored>`: admit only
  `COMPENSATING`; perform calls T028 and the set-aside callback) and route `CompensationSender` to it in
  `CommandFailureHandler.java` ([contracts/runtime-reaction.md](contracts/runtime-reaction.md) §B); T026 and T027 pass.
- [X] T030 [US3] Allow the guarded move (step compensation `COMPENSATED → FAILED` only when the same save moves the
  instance `COMPENSATING → COMPENSATION_FAILED`) in `saga-forge-runtime/…test/runtime/support/TransactionalInMemoryStorageProvider.java`
  and `saga-forge-test/…/test/FixtureStorage.java`.
- [X] T031 [P] [US3] Allow the same guarded move in `saga-forge-storage-postgres/…/storage/postgres/ForwardTransition.java`;
  extend `ForwardTransitionPropertyTest.java` (accepted only with the instance move, rejected alone) and
  `CompensationPersistenceTest.java` (round trip under `-Pcontainer-tests`).
- [X] T032 [US3] Crash safety (SC-003): add to `saga-forge-runtime/…test/runtime/CompensationFaultInjectionTest.java`
  a fault between the reaction's write and its commit (report retried, one compensation), and to
  `saga-forge-storage-postgres/…test/storage/postgres/TransportFaultInjectionContainerTest.java` a crash between the
  handler failure and the set-aside, then a restart: exactly one reaction, the message set aside once.

**Checkpoint**: all P1 stories pass, including the container suites.

---

## Phase 6: User Story 4 - Commands sent outside a saga keep today's behaviour (Priority: P2)

**Goal**: no report for a command whose `CommandId` is not saga-scoped; the only change is that a final failure is no
longer retried.

**Independent Test**: an outbox command sent from a plain service that throws `FinalFailure` is set aside after one
attempt with reason `FINAL_FAILURE`, and `receiveFailure` is never called.

- [X] T033 [P] [US4] Add to `TransportAcceptanceSuite.java` and `CommandDeliveryTest.java`: a non-saga command with a
  final failure → set aside `FINAL_FAILURE` after one attempt, no `receiveFailure`; with a non-final failure → retried
  and set aside `MAX_ATTEMPTS` as today (US4 scenarios 1, 2; FR-005).
- [X] T034 [US4] Make `internal/Relay.java` set aside a non-saga command itself (the existing non-transactional
  `setAside`) on both branches, and report an unreadable saga-scoped command as `FINAL` with cause type
  `UnreadableMessageException` (spec edge case); add that case to `FailureHandlingTest.java`.
- [X] T035 [P] [US4] Confirm `OperatorQueriesTest.java` lists `FINAL_FAILURE` set-asides like the others; extend it if
  the reason is filtered anywhere.

---

## Phase 7: User Story 5 - Test command failures with the saga test fixture (Priority: P2)

**Goal**: fixture tests see the reaction ([contracts/fixture.md](contracts/fixture.md)).

**Independent Test**: a fixture test declares a final failure for one outbox command type, sends the triggering event,
and expects the compensations, `COMPENSATED` and the outcome event.

- [X] T036 [P] [US5] Write `saga-forge-test/…test/test/OutboxCommandFailureTest.java`: `failCommand` with a
  `FinalFailure` → one attempt, compensations, `COMPENSATED`, `expectOutcomeEvent` with `COMMAND_FAILED`;
  `failCommand(..., times = 2)` with `outboxCommandAttempts(3)` → three attempts, no compensation (US5 scenario 2);
  an unmarked failure with the default → `EXHAUSTED` after one attempt; `outboxCommandAttempts(0)` rejected.
- [X] T037 [US5] Add `SagaFixture.Builder.outboxCommandAttempts(int)` (default 1, `>= 1`) in
  `saga-forge-test/…/test/SagaFixture.java` and pass it to the transport.
- [X] T038 [US5] In `saga-forge-test/…/test/LoopBackTransport.java`, classify an outbox command's failure with
  `FailureClassification.of`, redeliver a non-final failure up to the attempt setting, then call
  `runtime.commandReceiver().receiveFailure(...)` for a saga-scoped command (callback: no-op set-aside), keeping every
  failed attempt in `commandFailures`; update the class Javadoc. T036 passes.
- [X] T039 [US5] Run the fixture suites (`FailureAndCompensationTest`, `HandlerModeTest`, `FixtureParityTest`,
  `OutcomeEventExpectationsTest`) and the examples' fixture tests (including `checkout/CheckoutSagaFixtureTest`,
  `FulfilmentSagaFixtureTest`, `CheckoutAndFulfilmentTest`); update only expectations that asserted the old
  silence after a failing outbox command, and list each change in the PR description (SC-006).
- [X] T040 [US5] Add a final and an exhausted command-failure case to `FixtureParityTest.java`, comparing the fixture
  with a hand-assembled runtime over the in-memory transport.

---

## Phase 8: Polish and cross-cutting concerns

- [X] T041 [P] Add a fixture test to
  `saga-forge-example/…test/example/checkout/CheckoutSagaFixtureTest.java`: an outbox command `CheckoutSaga` sends
  (for example `CapturePaymentCommand`) fails finally → the saga compensates and its outcome event carries
  `COMMAND_FAILED`; the canonical `OrderProcessingSaga` sends no outbox command and stays as it is.
- [X] T042 [P] Formal design: add Property 39 and the `COMMAND_FAILED` trigger kind to
  `.kiro/specs/saga-forge-framework/design.md`.
- [X] T043 [P] Consumer docs (FR-019): describe when an outbox command's failure compensates, the relay's retry
  settings, `FINAL_FAILURE`, the fixture setting and the diagnostics in `docs/guide/recipes.md` (failure handling),
  `docs/guide/testing.md`, `docs/guide/operations.md` and `docs/guide/limitations.md` (G-012-01 to G-012-03); explain
  the relay's step as a recoverer, in Spring Kafka's terms; every snippet copied from compiling code with its source
  file named.
- [X] T044 [P] Update `docs/agents/saga-forge/SKILL.md`, `docs/agents/AGENTS-snippet.md` and `docs/architecture.md`
  (feature gaps: G-ADOPT-02 closed), then run `docs/guide/build-llms-full.sh`.
- [X] T045 Update `AGENTS.md` (Current Status entry for 012, Known Gaps line for G-012-01 to G-012-03),
  `.specify/memory/memory.md` (close G-ADOPT-02) and `.specify/memory/roadmap.md` (012 done).
- [X] T046 Run the quickstart ([quickstart.md](quickstart.md)) end to end: unit suites, container suites, the release
  build (`mvn -P release -Dgpg.skip -DskipTests install`) and the docs check; record the date and test count in
  AGENTS.md.

---

## Dependencies & Execution Order

### Phase dependencies

- Setup (Phase 1) → Foundational (Phase 2) → user stories.
- US1 (Phase 3) builds the report path; US2, US3 and US4 build on its `Relay` and `CommandFailureHandler` changes.
- US5 needs US1 (the runtime's `receiveFailure`); it does not need US2 to US4.
- Polish needs every story.

### Within stories

- Tests first (T013, T014; T023, T024; T026, T027; T033; T036), failing until their implementation tasks.
- `StartCompensation` (T015) and `Sender` (T016) before `CommandFailureHandler` (T017), before `EngineRuntime` (T019).
- T028 before T029; T030 and T031 before T027 passes against the doubles and Postgres.
- `Relay.java` is touched by T020, T025 and T034 in that order (one file, no parallelism).

### Parallel opportunities

- Phase 2: T003, T005 and T010/T011 run in parallel; T006 → T007 (both touch triggers); T009 before T010.
- US1: T013 and T014 in parallel; T018 and T021 in parallel with T020 once T017 exists.
- US3: T026, T027 and T031 in parallel.
- US5 can run beside US2 to US4 once US1 is done.
- Polish: T041 to T044 in parallel.

### Parallel example: User Story 1

```text
Task: "T013 CommandFailureCompensationTest in saga-forge-runtime"
Task: "T014 final-failure cases in TransportAcceptanceSuite and FailureHandlingTest"
```

---

## Implementation Strategy

### MVP first (US1)

1. Phases 1 and 2.
2. Phase 3 (US1): a final failure compensates the saga end to end on the in-memory transport.
3. Stop and validate: T013, T014, T022 green; the rest of the build green.

### Incremental delivery

1. US1 → MVP.
2. US2 (exhausted) and US3 (ignored, duplicate, compensation-sent, crash safety) complete the P1 behaviour; the
   container suites prove US3.
3. US4 protects non-saga commands; US5 brings the fixture.
4. Polish: example, formal design, docs, status, full quickstart.

Each phase leaves the build green; commit per phase.
