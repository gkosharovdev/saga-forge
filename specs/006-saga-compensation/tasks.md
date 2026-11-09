---
description: "Task list for Saga Compensation"
---

# Tasks: Saga Compensation

**Input**: Design documents from `/specs/006-saga-compensation/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Key decisions (plan.md Summary)**:
- **Failure outcome**: a business failure is a `@StartCompensation` step, whose method name is the outcome. Scanner
  rules R20 to R22 back it (Properties 30 to 32).
- **Statuses**: new terminal statuses `COMPENSATED` and `REQUIRES_INTERVENTION`; `COMPENSATING` while running.
- **Trigger**: a compensation receives the persisted `CompensationTrigger`, never the event.
- **Order**: reverse completion order by `StepRecord.completionSequence`, with the trigger step excluded.
- **Transactions**: each compensation runs in its own transaction, in the triggering handling call under the held
  lease.
- **Resume**: any event for a `COMPENSATING` instance resumes compensation; this is checked before 005's duplicate
  check.
- **No retry**: a failed compensation is never retried.

**Tests**: required (spec Independent Tests, quickstart.md §1 to §6). **Not TDD**, the same convention as 004 and
005:
- each task writes production code first, and its tests come in the same task or a later task of the same phase;
- no task in the middle of a phase runs tests, though compiling is allowed;
- each phase ends with exactly one checkpoint task that runs the unit suites of the modules it touched;
- Testcontainers classes (`@Tag("container")`) are edited and compiled only, never run by the implementer. They are
  handed to the user at T068.

**Organization**: by user story (spec.md: US1 P1, US2 P1, US3 P2, US4 P2, US5 P3).

**Behaviour change to carry through**: after 006, a `FINAL` or `EXHAUSTED` failure of an instance with at least one
`COMPLETED` step no longer ends `FAILED`. It enters compensation and ends `COMPENSATED`, even when no compensation is
declared, because every step is then `NOTHING_TO_COMPENSATE`. Only `UNCLASSIFIED` failures, and final failures with
nothing committed, stay `FAILED`. Existing 005 tests that assert `FAILED` for such cases are updated in T034, not
silently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 to US5; Setup, Foundational, Postgres and Polish tasks carry no story label
- All paths are relative to the repository root `saga-forge/`

## Path Conventions

- **Core**:
  - main: `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/`, written `core/…` below
  - test: `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/`, written `core-test/…`
- **Engine**:
  - main: `saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/`, written `engine/…`; the
    package-private runtime is in `engine/internal/`
  - test: `saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/`, written `engine-test/…`, with
    `support/` doubles, `fixtures/` test sagas and `internal/` package-private tests
- **Postgres**:
  - main: `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/`, written
    `postgres/…`
  - resources: `saga-forge-storage-postgres/src/main/resources/io/github/gkosharovdev/sagaforge/storage/postgres/`,
    written `postgres-res/…`
  - tests: `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/`, written
    `postgres-test/…`
- **Example**: `saga-forge-example/src/{main,test}/java/io/github/gkosharovdev/sagaforge/example/`, written
  `example/…` and `example-test/…`
- **Compile check** (allowed in any task, runs no test): `mvn -B -pl <modules> -am -DskipTests clean verify`. The
  `clean` avoids stale IDE classes (G-REPO-02).
- **Unit checkpoint**: `mvn -B -pl <modules> -am clean verify`. Container suites are excluded without
  `-Pcontainer-tests`.

---

## Phase 1: Setup

**Purpose**: formal design first (Constitution Principle III).

- [X] T001 Update `.kiro/specs/saga-forge-framework/design.md`:
  - **Annotations**: add a `#### @StartCompensation` section after `@EndSaga`, covering placement and meaning
    (research §1). Extend the `@SagaCompensation` section with the method shape: `void`, no parameter or one
    `CompensationTrigger`, not a `@SagaStep`.
  - **Rules table**: add rows R20, R21 and R22 with the exact texts of
    [contracts/declaring-compensation.md](./contracts/declaring-compensation.md). Extend the "R1 to R11 and R19 run
    in …" sentence to cover R20 to R22, and add `validateStartCompensation` / `validateCompensationShape` /
    `validateCompensationTargets` to the scanner pseudo-code.
  - **Status section**: list `SagaStatus.COMPENSATED` and `REQUIRES_INTERVENTION`.
  - **Properties 30 to 32**: the three scanner rules, with tests `StartCompensationValidationTest` and
    `CompensationValidationTest`.
  - **Property 33**: compensation selection and reverse order (test `CompensationSelectionPropertyTest`).
  - **Property 34**: each compensation at most once and none skipped across crashes (test
    `CompensationResumePropertyTest`).
  - **Property 27**: extend it with `COMPENSATION_STARTED`, `STEP_COMPENSATED`, `COMPENSATION_FAILED`, `COMPENSATED`
    and `REQUIRES_INTERVENTION`, in that order.
  - **Error-message table**: add the R20 to R22 messages.
  - **Note paragraph**: state that 006 adds `@StartCompensation`, R20 to R22 and Properties 30 to 34, and leaves
    Properties 1 to 26, 28 and 29 unchanged.
- [X] T002 [P] Add the stub section "Compensation (006), implementation notes" to `.specify/memory/memory.md`, under
  the existing "Compensation (006)" heading, after G-006-09. It holds a dated note pointing to this tasks.md; T066
  fills it in.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: the core vocabulary, the record changes and the completion sequence, with every module still compiling
and every existing test green.

- [X] T003 [P] Create `core/annotation/StartCompensation.java`: method-level, runtime retention, `@Documented`, no
  attributes, with Javadoc per data-model.md.
- [X] T004 [P] Create `core/compensation/CompensationTriggerKind.java` (`FAILURE_OUTCOME`, `FINAL_FAILURE`,
  `RETRIES_EXHAUSTED`) and `core/compensation/package-info.java`.
- [X] T005 Create `core/compensation/CompensationTrigger.java`:
  - **Components**: `record (kind, stepName, eventType, failure, at)`, with the validation rules of data-model.md.
  - **Factories**: `failureOutcome(stepName, eventType, at)` and `failed(SagaFailure failure, Instant at)`. The
    second derives `FINAL_FAILURE` from `FINAL` and `RETRIES_EXHAUSTED` from `EXHAUSTED`, and rejects `TRANSIENT` and
    `UNCLASSIFIED` with `IllegalArgumentException`.
- [X] T006 [P] Create `core/spi/CompensationStatus.java` (`NOT_ATTEMPTED`, `COMPENSATED`, `NOTHING_TO_COMPENSATE`,
  `FAILED`) and `core/spi/StepCompensation.java`:
  - **Components**: `record (status, at, failure)`, with the rules of data-model.md.
  - **Factories**: `notAttempted()`, `nothingToCompensate(at)`, `compensated(at)` and `failed(failure)`.
- [X] T007 Add `COMPENSATED` and `REQUIRES_INTERVENTION` to `core/spi/SagaStatus.java` and to
  `core/spi/HandlingOutcome.java`, with Javadoc per contracts/compensation-run.md "Handling results". Confirm that
  `SagaHandlingResult`'s rules need no change: no `outcomeStep` and no `failure` for the new outcomes.
- [X] T008 Extend `core/spi/StepRecord.java`:
  - **New components**: `int completionSequence` and `StepCompensation compensation`, appended after
    `handledCommands`.
  - **Rules**:
    - the sequence is 0 unless `COMPLETED`, and `>= 1` when it is;
    - `compensation` is `null` unless `COMPLETED`.
  - **Compatibility**:
    - keep the 2- and 4-argument constructors, defaulting the sequence to 1 when completed and 0 otherwise, and
      `compensation` to `null`;
    - keep `completed(...)` factories with the same defaults;
    - add `completed(touched, attempts, cmds, sequence)`.
  - **Withers**: add `withCompletionSequence(int)` and `withCompensation(StepCompensation)`.
- [X] T009 Extend `core/spi/SagaInstance.java`:
  - **New component**: append `CompensationTrigger compensationTrigger`.
  - **Rules** (data-model.md "Invariants it gains"):
    - the trigger is present if and only if the status is `COMPENSATING`, `COMPENSATED` or `REQUIRES_INTERVENTION`;
    - completed steps have distinct sequences;
    - no step has a `compensation` outside those three statuses;
    - `COMPENSATED` has no step that is `NOT_ATTEMPTED` or `FAILED`;
    - `REQUIRES_INTERVENTION` has exactly one `FAILED` step.
  - **Compatibility**: keep the 10- and 11-argument constructors, defaulting the trigger to `null`.
  - **Wither**: add `withCompensationTrigger(...)`.
  - **Messages**: in the "…if and only if…" style already used.
- [X] T010 Extend `core/spi/SagaLifecycleEventKind.java` with the five kinds from research §11, and
  `core/spi/SagaLifecycleEntry.java`:
  - **New component**: `CompensationTrigger trigger`, present if and only if `COMPENSATION_STARTED`.
  - **Extended rules**: `stepName` is required for `STEP_COMPENSATED` and `COMPENSATION_FAILED`, and `failure` for
    `COMPENSATION_FAILED`.
  - **Compatibility**: keep the existing constructor, defaulting `trigger` to `null`.
- [X] T011 Assign completion sequences in `engine/internal/EngineSagaLifecycle.java`:
  - `recordStepCompleted` stores `max(sequence of completed steps) + 1`;
  - widen `isTerminal` to `COMPLETED, FAILED, COMPENSATED, REQUIRES_INTERVENTION`;
  - `evolve` carries `compensationTrigger` forward. Every existing `new SagaInstance(...)` there passes it through.
  Grep the engine for other `SagaStatus.COMPLETED || … FAILED` checks and route them through `isTerminal`.
- [X] T012 [P] Make the engine test-jar and the core test doubles compile and stay green:
  - `engine-test/support/TransactionalInMemoryStorageProvider.java` and the core test-only
    `InMemorySagaLifecycle` / `SagaTestHarness`: carry the new components, and update terminal checks to the new
    statuses;
  - `engine-test/support/DifferentialRun.java` `InstanceView`: project out `completionSequence` and `compensation`,
    as 005 projected `attempts`;
  - existing exact `StepRecord` assertions, such as `SagaEngineHappyPathTest`: assert the expected sequence, or
    project it out.
- [X] T013 [P] Core unit tests in `core-test/spi/CompensationVocabularyTest.java`:
  - every accepting and rejecting combination for `CompensationTrigger`, `StepCompensation`, the new `StepRecord`
    components, the `SagaInstance` invariants of T009, and the `SagaLifecycleEntry` rules of T010;
  - the compatibility constructors keep their 005 meaning.
- [X] T014 [P] Engine unit test `engine-test/internal/CompletionSequenceTest.java`:
  - steps completed in one unit of work get consecutive sequences in execution order;
  - a later unit of work continues from the highest;
  - a rolled-back unit of work assigns none.
- [X] T015 Checkpoint: run `mvn -B -pl saga-forge-core,saga-forge-engine,saga-forge-storage-postgres,saga-forge-example -am clean verify`.
  - **Expected**: green, with every pre-006 test unchanged apart from T012's projections.
  - **Postgres at this point**: still on 005 JSON, so the sequence and compensation are dropped there until Phase 8.
    Its unit tests must pass because the decoder's compatibility defaults apply.

---

## Phase 3: Scanner rules (blocks every story)

**Purpose**: R20 to R22 (Properties 30 to 32), so the engine can rely on well-formed declarations.

- [X] T016 Implement R20 `validateStartCompensation` in `core/scanner/ReflectiveAnnotationScanner.java`:
  - `@StartCompensation` requires `@SagaStep`;
  - it cannot co-occur with `@StartSaga` or `@EndSaga`;
  - messages per contracts/declaring-compensation.md.
  Call it from `validate` in the same single pass.
- [X] T017 Implement R21 `validateCompensationShape`: the method returns `void`, declares zero parameters or exactly
  one `CompensationTrigger` parameter, and is not a `@SagaStep`.
- [X] T018 Implement R22 `validateCompensationTargets`: at most one `@SagaCompensation` per `forStep`, listing the
  method names alphabetically in the message, and none whose `forStep` is a `@StartCompensation` step. R6 keeps
  reporting a non-existent `forStep`, and R22 skips it.
- [X] T019 [P] Audit every existing `@SagaCompensation` in `saga-forge-core`, `saga-forge-engine` and
  `saga-forge-example` test and main fixtures against R21 and R22. Fix fixtures that now violate them. Record any
  test whose expectation changes in that test's Javadoc.
- [X] T020 [P] Tests in `core-test/scanner/StartCompensationValidationTest.java` (Property 30) and
  `core-test/scanner/CompensationValidationTest.java` (Properties 31 and 32):
  - one test per error message;
  - a valid saga with two failure outcomes and three compensations produces no error;
  - `validateAll` reports the same errors;
  - jqwik properties `startCompensationPlacement` and `compensationShape`, generating annotation combinations on
    fixture classes.
- [X] T021 Engine startup test in `engine-test/StartupValidationTest.java`: a saga violating R20, R21 and R22 is
  rejected by `SagaEngine` startup with all three errors in one `StartupReport`.
- [X] T022 Checkpoint: run `mvn -B -pl saga-forge-core,saga-forge-engine,saga-forge-example -am clean verify`; it
  must be green.

---

## Phase 4: User Story 1, business rejection triggers the refund (P1) 🎯 MVP

**Goal**: a `@StartCompensation` step commits `COMPENSATING`, then the payment compensation runs and the saga ends
`COMPENSATED` (spec US1, FR-001, FR-002, FR-004, FR-009 to FR-011, FR-013 to FR-015).

**Independent test**: quickstart.md §2, US1 part.

- [X] T023 [US1] Extend `engine/internal/SagaModel.java`:
  - `Step.startsCompensation()`;
  - `record Compensation(Method method, String forStep, boolean takesTrigger)`, built from `@SagaCompensation`;
  - `Optional<Compensation> compensationFor(String stepName)`;
  - `void run(Compensation, Object saga, CompensationTrigger trigger)`, which reflects and unwraps the same way as
    `run(Step, …)`.
- [X] T024 [US1] Add `startCompensation(sagaId, trigger)` to `engine/internal/EngineSagaLifecycle.java`:
  - **Selection**: the `COMPLETED` steps except `trigger.stepName()` when the kind is `FAILURE_OUTCOME`.
  - **Per-step status**: `NOT_ATTEMPTED` if the model has a compensation for the step, else
    `NOTHING_TO_COMPENSATE(now)`.
  - **Writes**: status `COMPENSATING`, set the trigger, clear any pending retry, add a `COMPENSATION_STARTED` entry.
  - **Nothing left to run**: if no step is `NOT_ATTEMPTED`, also end `COMPENSATED` with a `COMPENSATED` entry.
  - **Collaborator**: the model is passed in, or the lifecycle already holds it.
  Add `recordCompensated(sagaId, step)`: `NOT_ATTEMPTED → COMPENSATED(now)` plus a `STEP_COMPENSATED` entry. After
  the last `NOT_ATTEMPTED` step it ends `COMPENSATED` with an entry. Add `List<String> remainingCompensations()`,
  ordered by decreasing sequence. All of these require the working copy to be `COMPENSATING`, else
  `InvalidSagaTransitionException`.
- [X] T025 [US1] In `engine/internal/SagaUnitOfWork.java`:
  - **Trigger step**: after a `@StartCompensation` step completes, call
    `lifecycle.startCompensation(FAILURE_OUTCOME(step, event class, now))` and stop the queue, like an `@EndSaga`
    step. Queued events are handed off.
  - **Result**: `Committed` gains `boolean compensationStarted`, and the committed result is not built yet.
  - **Check order**: move the terminal check after a new `COMPENSATING` check. The `COMPENSATING` check returns a new
    `AttemptResult.Compensating(correlation)` after rolling back. It sits before the duplicate check (research §10),
    and US5 tests it.
- [X] T026 [US1] Create `engine/internal/CompensationUnitOfWork.java` per
  [contracts/compensation-run.md](./contracts/compensation-run.md):
  - **Lock and check**: `begin`, `findByIdAndLock`, then require `COMPENSATING` and the step `NOT_ATTEMPTED`.
    Otherwise roll back and return `Stopped`.
  - **Run**: create the saga object, inject its fields, and bind itself as the `DomainEventScope` recorder. Recorded
    events are buffered only, never queued. `CommandIds` is scoped to the compensation method name. Invoke the
    compensation and fail if the gateway reported a failure.
  - **Commit**: `recordCompensated`, save, the `IN_TRANSACTION` handoff, commit.
  - **Results**: `Committed(afterRelease)`, `Failed(step, cause)` or `Conflicted`. It implements `UnitOfWork`,
    reusing 005's `nextCommandId` / `commandHandled`, and reentrancy guards as `SagaUnitOfWork` does.
- [X] T027 [US1] Create `engine/internal/CompensationRun.java`: loop over `remainingCompensations()` of the latest
  instance, running one `CompensationUnitOfWork` each, until none is left or one fails, conflicts or stops. It
  collects each committed unit's after-release handoff and returns a summary with the final outcome and the last
  `unitOfWorkId`.
- [X] T028 [US1] In `engine/internal/AttemptLoop.java`:
  - **After a commit**: on `Committed` with `compensationStarted`, run `CompensationRun` while still holding the
    lease, release it, then run every handoff in commit order: the trigger unit of work's first, then each
    compensation's. Return `COMPENSATED`, or what the run reached, with merged `handoffFailures`.
  - **Resume**: on `Compensating`, run `CompensationRun` the same way. The resume diagnostics come in T052.
- [X] T029 [US1] Diagnostics in `engine/internal/Diagnostics.java`: `compensationStarted(correlation, trigger)`,
  `compensationStep(correlation, step, status)` and `compensationEnded(correlation)`, with the names and levels of
  contracts/compensation-run.md. Call them from T024 to T028.
- [X] T030 [P] [US1] Fixture `engine-test/fixtures/compensation/DeliverySaga.java` and its companions, per spec
  Context:
  - **Steps**: `onOrderPlaced` (`@StartSaga`) sends `ChargeCommand`; `onCharged` stores `paymentId` and sends
    `RequestDeliveryCommand`; `onDeliveryAccepted` (`@EndSaga`).
  - **Failure outcomes**: `onDeliveryRejected` (`@StartCompensation`, stores the reason) and `onStockUnavailable`
    (`@StartCompensation`).
  - **Compensation**: `refundCharge(CompensationTrigger)`, for `onCharged`, sends `RefundCommand` with the stored
    `paymentId`.
  - **Supporting types**: events and commands, a `DeliveryFixture` wiring an engine over the in-memory doubles, and a
    `RecordingPaymentProvider` that counts refunds by `CommandId`.
- [X] T031 [US1] Tests in `engine-test/BusinessFailureCompensationTest.java`, one test per US1 acceptance scenario
  1.1 to 1.4:
  - **1.1**: one refund, outcome `COMPENSATED`, the trigger is `FAILURE_OUTCOME`/`onDeliveryRejected`/the event type,
    `onCharged` is `COMPENSATED`, `onDeliveryRejected` has no compensation status, and the rejection reason
    committed.
  - **1.2**: the stock trigger is recorded distinctly.
  - **1.3**: `COMPLETED`, with no compensation.
  - **1.4**: a second rejection is `DUPLICATE` and the refund count stays 1.
  Also assert:
  - the lifecycle history order (Property 27, extended);
  - `CompensationTrigger` passed to the compensation equals the persisted one;
  - the compensation's `CommandId` is scoped to `refundCharge`.
- [X] T032 [US1] Checkpoint: run `mvn -B -pl saga-forge-engine -am clean verify`; it must be green.

---

## Phase 5: User Story 2, compensation unwinds committed steps in reverse order (P1)

**Goal**: reverse completion order, `NOTHING_TO_COMPENSATE`, and rolled-back steps never compensated (spec US2, FR-005,
FR-007).

**Independent test**: quickstart.md §2, US2 part.

- [X] T033 [P] [US2] Fixture `engine-test/fixtures/compensation/ThreeStepSaga.java`:
  - **Steps**: A (start), B and C each commit in their own unit of work. D runs in a unit of work that rolls back,
    through a fault switch.
  - **Failure outcome**: an `onAbort` `@StartCompensation` step.
  - **Compensations**: for A, B and C, each appending its name to a shared journal. A variant class declares no
    compensation for B.
  - **Extra case**: a further variant in which B and C complete in the same unit of work, B queued by A's handler, to
    exercise sequence order within one unit of work.
- [X] T034 [US2] Tests in `engine-test/CompensationOrderTest.java`:
  - **Scenario 2.1**: the journal is C, B, A.
  - **Scenario 2.2**: B is `NOTHING_TO_COMPENSATE` and the outcome is `COMPENSATED`.
  - **Scenario 2.3**: D's compensation never runs and D's compensation status is `null`.
  - **Same-unit order**: steps completed in one unit of work are undone in reverse execution order.
  - **Edge case "no committed step has a compensation"**: `COMPENSATED`, with every step `NOTHING_TO_COMPENSATE`.
  Also review every existing engine test that combines a `@SagaCompensation` fixture or a `FinalFailure` with
  committed steps, and update their expectations to the behaviour change stated at the top of this file. Each change
  is listed in the commit message and in T066.
- [X] T035 [P] [US2] Property test `engine-test/CompensationSelectionPropertyTest.java` (Property 33, jqwik):
  - **Generated input**: random sequences of committing and rolling-back units of work over a generated step set, then
    a trigger.
  - **Property**: the compensations run are exactly the committed steps with a compensation, minus the trigger, in
    decreasing sequence.
- [X] T036 [US2] Checkpoint: run `mvn -B -pl saga-forge-engine -am clean verify`; it must be green.

---

## Phase 6: User Story 3, technical failure after earlier commits leads to compensation (P2)

**Goal**: `FINAL` and `EXHAUSTED` failures start compensation when something committed; `UNCLASSIFIED` stays `FAILED`
(spec US3, FR-003, FR-006 to FR-006b).

**Independent test**: quickstart.md §3, US3 part.

- [X] T037 [US3] Create `engine/internal/CompensationStartWrite.java`, modelled on `FailedWrite`, with its own
  transaction:
  - **Locked read**: a missing or terminal instance is not recorded, as in `FailedWrite`.
  - **No step `COMPLETED`**: delegate to the `FAILED` path, with the same entries and diagnostics as 005.
  - **Otherwise**: `startCompensation(CompensationTrigger.failed(failure, now))`, then save and commit.
  - **Write failure**: roll back, add the original cause as suppressed, and propagate.
  It returns whether compensation started.
- [X] T038 [US3] In `engine/internal/AttemptLoop.java`, route `failedWrite(...)` and `exhausted(...)` by
  classification:
  - recorded `FINAL` or `EXHAUSTED` goes to `CompensationStartWrite`, then `CompensationRun` as in T028, then release
    and hand off;
  - `UNCLASSIFIED` stays on `FailedWrite`.
  The handling result is `COMPENSATED` or `REQUIRES_INTERVENTION`, or `FAILED` when nothing committed.
- [X] T039 [P] [US3] Extend the fixtures:
  - `engine-test/fixtures/compensation/DeliverySaga.java`: a `bookCourier` step after `onCharged`, reached through an
    event, whose handler throws a `CourierUnavailable` implementing `FinalFailure`, or a plain `IllegalStateException`,
    through a switch.
  - A retry variant with `@RetryPolicy(maxAttempts = 3)` and a `TransientFailure` cause, using 005's
    `RecordingPause`.
- [X] T040 [US3] Tests in `engine-test/FinalFailureCompensationTest.java`:
  - **Scenario 3.1**: the step's unit of work rolled back, the payment was refunded, the trigger is `FINAL_FAILURE`
    with step, cause type and message, and the outcome is `COMPENSATED`.
  - **Scenario 3.2**: `IllegalStateException` gives `FAILED` `UNCLASSIFIED` with no refund.
  - **Scenario 3.3**: three attempts, then the trigger is `RETRIES_EXHAUSTED` with `attempts == 3` and the last cause,
    and a refund.
  - **Exhausted on resume**: the 005 path still compensates.
  - **Start step final failure**: a `FinalFailure` in the start step gives `FAILED`, with no compensation (edge case).
- [X] T041 [US3] Checkpoint: run `mvn -B -pl saga-forge-engine -am clean verify`; it must be green.

---

## Phase 7: User Story 4, a compensation that cannot complete needs a human (P2)

**Goal**: a failed compensation stops the unwinding and records `REQUIRES_INTERVENTION`, with no retry (spec US4,
FR-016, FR-017, SC-007).

**Independent test**: quickstart.md §3, US4 part.

- [X] T042 [US4] Add `recordCompensationFailed(sagaId, step, failure)` to `engine/internal/EngineSagaLifecycle.java`:
  `NOT_ATTEMPTED → FAILED(failure)`, status `REQUIRES_INTERVENTION`, then a `COMPENSATION_FAILED` entry followed by a
  `REQUIRES_INTERVENTION` entry.
- [X] T043 [US4] Create `engine/internal/CompensationFailedWrite.java`, with its own transaction like `FailedWrite`:
  - **Locked read**: require `COMPENSATING` and the step `NOT_ATTEMPTED`; otherwise nothing is recorded and a
    diagnostic is logged.
  - **Classification**: `FailureClassifier.classify(cause, default @RetryPolicy)`, recorded with `attempts = 1`.
  - **Write**: `recordCompensationFailed`, save, commit.
  - **Write failure**: propagate with the cause suppressed.
  Wire it into `CompensationRun` for a `Failed` result; the run stops and returns `REQUIRES_INTERVENTION`. Add the
  diagnostic `compensationFailed` at `ERROR` level.
- [X] T044 [US4] Tests in `engine-test/CompensationFailureTest.java`, using `ThreeStepSaga` with B's compensation
  throwing:
  - **Scenario 4.1**: C is `COMPENSATED`, B is `FAILED` with cause, A is `NOT_ATTEMPTED`, and the outcome is
    `REQUIRES_INTERVENTION`.
  - **Scenario 4.2**: the stored instance alone shows the trigger, every committed step and each status with its time
    (SC-004).
  - **Failure reported but caught**: a handler failure reported by the gateway that the compensation caught still
    fails it.
  - **Transient cause**: still not retried (SC-007), and `RecordingPause` is never called.
  - **No retry later**: a later event for the instance is `UNEXPECTED` and runs nothing.
- [X] T045 [US4] Checkpoint: run `mvn -B -pl saga-forge-engine -am clean verify`; it must be green.

---

## Phase 8: Postgres persistence

**Purpose**: persist the trigger, the sequence and the compensation statuses
([contracts/storage-delta.md](./contracts/storage-delta.md)). Depends on Phase 2 only.

- [X] T046 Update `postgres-res/schema.sql`: add `saga_instance.compensation_trigger JSONB`, the
  `trigger_iff_compensation` constraint and `saga_lifecycle_history.compensation_trigger JSONB`. Update the header
  comment to name `schema-upgrade-006.sql`.
- [X] T047 Create `postgres-res/schema-upgrade-006.sql` per storage-delta.md:
  - **Columns and constraint**: `ADD COLUMN IF NOT EXISTS`, and the constraint guarded with a `DO` block.
  - **Sequence backfill**: one `UPDATE … SET steps = (SELECT jsonb_object_agg …)`, numbering completed steps by
    their `STEP_COMPLETED` `sequence_number`, with name-ordered fallbacks after those.
  - **Idempotence**: steps that already have `sequence` are left alone.
- [X] T048 Update `postgres/SagaInstanceRows.java`:
  - encode and decode `sequence` and `compensation` per step, and the instance's `compensation_trigger`;
  - missing-field defaults per storage-delta.md, with `UncheckedSqlException` for a completed step whose sequence is
    0;
  - add the column to the upsert in `postgres/PostgresStorageProvider.java`.
  Update `postgres/LifecycleEntryRows.java` with the `compensation_trigger` column and the five new kinds.
- [X] T049 Extend `postgres/ForwardTransition.java` with the rules of storage-delta.md "ForwardTransition additions".
  Add unit tests `postgres-test/CompensationPersistenceTest.java` over the existing `JdbcFake`:
  - codec round trips for every trigger kind and compensation status;
  - each forward-transition rejection;
  - 005-shaped step JSON decoding.
  Update `JdbcFake` and `LifecycleEntryRowsTest` for the new columns.
- [X] T050 [P] Container tests, edited and compiled but not run:
  - `postgres-test/SchemaTest.java`: `trigger_iff_compensation` accepts and rejects the right rows;
    `schema-upgrade-006.sql` on a 005 schema (new resource `schema-before-006.sql`, copied from the 005 `schema.sql`)
    backfills sequences from history and is idempotent.
  - `postgres-test/PostgresStorageProviderTest.java`: `compensationFieldsSurviveRestart`.
  - Lifecycle history: a container round trip for the five new kinds.
- [X] T051 Checkpoint: run `mvn -B -pl saga-forge-storage-postgres -am clean verify`; it must be green, with the
  container suites excluded.

---

## Phase 9: User Story 5, compensation survives a crash (P3)

**Goal**: any event for a `COMPENSATING` instance resumes the remaining compensations; none runs twice and none is
skipped (spec US5, FR-012, SC-003).

**Independent test**: quickstart.md §3 US5 and §4.

- [X] T052 [US5] Finish the resume path:
  - `AttemptLoop` on `Compensating` logs `saga.compensation.resumed` (the new `Diagnostics.compensationResumed`);
  - `SagaUnitOfWork`'s `COMPENSATING` check runs before the duplicate check, so a redelivered failure-outcome event
    resumes instead of returning `DUPLICATE`;
  - a `CompensationUnitOfWork` that finds its step no longer `NOT_ATTEMPTED`, because another process moved on, stops
    the run with `NOT_HANDLED` (G-ENG-10 style).
- [X] T053 [P] [US5] Fault script `engine-test/fixtures/compensation/CompensationFaults.java`:
  - **Crash points**: crash before or after each compensation's commit, and between the start write and the first
    compensation. A crash is simulated by an `Error` thrown out of the storage double or the pause, and the engine is
    rebuilt over the same stores (005 `RetryResumeTest` pattern).
  - **Failure points**: a failure at each step boundary.
- [X] T054 [US5] Tests in `engine-test/CompensationResumeTest.java`:
  - **Scenario 5.1**: crash after C's compensation committed; then a redelivered rejection resumes with B and A only,
    and the journal is C, B, A with C once.
  - **Forward event resumes**: a forward event (`onDeliveryAccepted`) for a `COMPENSATING` instance resumes and never
    runs its step.
  - **After the end**: after `COMPENSATED`, the same event is `UNEXPECTED`.
  - **Concurrent triggers** (edge case, FR-004): two triggers through `ConcurrentUnitsOfWorkTest`'s two-thread
    harness start compensation once.
- [X] T055 [US5] `engine-test/CompensationFaultInjectionTest.java` (SC-002, SC-003) and
  `engine-test/CompensationResumePropertyTest.java` (Property 34, jqwik):
  - over `ThreeStepSaga`, every failure and crash point of T053, with a resume after each;
  - no rolled-back step is compensated;
  - each committed step with a compensation is compensated exactly once;
  - no compensation is skipped;
  - refund `CommandId`s are equal across re-runs of an interrupted compensation.
- [X] T056 [US5] Checkpoint: run `mvn -B -pl saga-forge-engine -am clean verify`; it must be green.

---

## Phase 10: Polish & cross-cutting

- [X] T057 [P] Example (FR-019, SC-001):
  - **New types**: `example/events/DeliveryRejectedEvent.java`, with `@DomainEvent(aggregate = Order.class)`, `orderId`
    and `reason`; and `example/commands/RefundPaymentCommand.java`.
  - **`example/PaymentService.java`**: `handle(RefundPaymentCommand, CommandId)` calls
    `PaymentProvider.refund(key, paymentId, amount)`, which is idempotent by key.
  - **`example/OrderProcessingSaga.java`**: add `rejectDelivery` (`@StartCompensation` +
    `@SagaStep(associationProperty = "orderId")`, storing the reason). Replace the print-only `compensatePayment()`
    with `compensatePayment(CompensationTrigger)`, which sends the refund using `paymentId` and `orderAmount` from
    `SagaContext`. Update the class Javadoc.
- [X] T058 [P] Example tests in `example-test/OrderProcessingSagaEngineTest.java`:
  - **Rejection**: after payment, one refund, `COMPENSATED`, the trigger recorded, and a repeated rejection is
    `DUPLICATE`.
  - **Happy path**: unchanged.
  - **Validation**: the validation test still passes with the new annotations.
- [X] T059 [P] Update `README.md`: compensation in the feature list and in the canonical example snippet, with
  `@StartCompensation`, `@SagaCompensation(forStep = "processPayment")` and `associationProperty` visible.
- [X] T060 [P] Update `docs/architecture.md`:
  - **Status row**: compensation implemented.
  - **"Failure categorization and automatic compensation dispatch"**: rewrite it to the implemented semantics, covering
    triggers, order, units of work, outcomes and resume, and mark it implemented.
  - **Vocabulary**: the "Compensation" entry points to the runtime semantics.
- [X] T061 [P] Update `docs/use-cases.md`: the refund-on-rejection flow, multi-step unwinding with "nothing to
  compensate", technical final failure, and "requires intervention". Fix any text that still says compensation never
  runs.
- [X] T062 [P] Update `AGENTS.md`:
  - **Current Status**: a 006 bullet.
  - **Saga Model**: `@StartCompensation`, the compensation method shape, the new statuses, and that compensation runs.
  - **Known Gaps**: replace "No compensation-vs-intervention-required outcome categorization exists in code yet" and
    "a `FAILED` saga's committed effects currently stay in place" with the 006 limitations G-006-02 to G-006-09.
  - **Canonical example**: add `rejectDelivery` and the refund.
- [X] T063 [P] Update `.kiro/specs/saga-forge-framework/design.md` with anything that changed during implementation
  since T001: message texts and test names.
- [X] T064 Final checkpoint: `mvn -B clean verify` at the repository root must be green. Walk through quickstart.md §1
  to §4 and §6, and confirm every named test class exists and runs.
- [X] T065 Run `/speckit-analyze`-style self-check: every FR-001 to FR-021 and SC-001 to SC-007 maps to at least one
  completed task or a recorded gap. Note any FR not covered in T066.
- [X] T066 Fill the memory.md implementation notes from T002:
  - deviations from plan.md;
  - the existing tests whose expectations changed (T019, T034);
  - status updates of G-006-02 to G-006-09;
  - any new gap found during implementation, given a new ID.
- [X] T067 Update `specs/006-saga-compensation/spec.md` **Status** to "Implemented" and correct any FR wording that
  implementation refined, such as the R20 to R22 numbering.
- [X] T068 Hand off to the user: list the container classes changed in T050 with the command
  `mvn -B -pl saga-forge-storage-postgres -am clean verify -Pcontainer-tests`, and state that nothing is committed.

---

## Dependencies & Execution Order

### Phase dependencies

| Phase | Tasks | Depends on | Notes |
|---|---|---|---|
| 1. Setup | T001–T002 | — | |
| 2. Foundational | T003–T015 | Phase 1 | |
| 3. Scanner rules | T016–T022 | Phase 2 | the engine relies on well-formed declarations |
| 4. US1 | T023–T032 | Phase 3 | |
| 5. US2 | T033–T036 | US1 | reuses its run machinery; mostly tests and fixtures |
| 6. US3 | T037–T041 | US1 | touches `AttemptLoop.java` after T028 |
| 7. US4 | T042–T045 | US1 | touches `CompensationRun.java`; run after US3, as both edit `AttemptLoop`/`CompensationRun` wiring |
| 8. Postgres | T046–T051 | Phase 2 only | can run in parallel with Phases 3 to 7, in a different module |
| 9. US5 | T052–T056 | US1 to US4 | |
| 10. Polish | T057–T068 | all stories and Phase 8 | |

### Story completion order

```text
Setup → Foundational → Scanner ─┬─ US1 → US2 → US3 → US4 → US5 ─┬─ Polish
                                └──────────── Postgres ─────────┘
```

### Within each phase

Production code comes first and tests follow in the same phase, with one checkpoint at the end. `[P]` tasks touch
different files.

## Parallel Examples

| Phase | Parallel tasks |
|---|---|
| Phase 2 | T003, T004 and T006 together, then T005, then T007 to T010 (record chain), then T011, then T012, T013 and T014 together |
| Phase 3 | T019 and T020 alongside T016 to T018 (different files) |
| US1 | T030 (fixture) in parallel with T023 to T029 (production code) |
| US2 | T033 and T035 together |
| Postgres vs stories | a second implementer can take T046 to T050 while another works through US1 to US4 |

## Implementation Strategy

### MVP (US1)

The MVP is Phases 1 to 4: a declared business failure after a committed payment refunds exactly once and ends
`COMPENSATED`. That delivers SC-001 for the canonical case.

### Incremental delivery

1. MVP (US1).
2. US2: multi-step reverse order.
3. US3: technical final failures and exhaustion.
4. US4: requires intervention.
5. Postgres.
6. US5: crash resume.
7. Polish.

Each checkpoint leaves a green build, so work can stop after any story if priorities change.
