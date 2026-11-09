---
description: "Task list for Step Retry and Idempotent Command Processing"
---

# Tasks: Step Retry and Idempotent Command Processing

**Input**: Design documents from `/specs/005-step-retry/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Key decisions (plan.md Summary)**: `CommandId` is a name-based UUID supplied as a handler parameter; handled
identities live in `StepRecord.handledCommands`; retry is a nested `@RetryPolicy` attribute of `@SagaStep`; total
backoff is bounded by `maxTotalRetryBackoff` (default 30 s); the next attempt is claimed on
`SagaInstance.pendingRetry` before each wait; shutdown during a wait is thread interruption → `NOT_HANDLED`;
duplicates and skipped commands are logged, not written to history.

**Tests**: Required (spec Independent Tests, quickstart.md §1–§6). **Not TDD** (same convention as 004): each task
writes production code first; its tests come in the same task or a later task of the same phase. No task in the
middle of a phase runs tests (compiling is allowed). Each phase ends with exactly one checkpoint task that runs the
unit suites of the modules it touched. Testcontainers classes (`@Tag("container")`) are edited and compiled only,
never run by the implementer; they are handed to the user at T072.

**Organization**: by user story (spec.md: US1 P1, US2 P1, US3 P1, US4 P2, US5 P3).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 to US5; Setup, Foundational, Postgres and Polish tasks carry no story label
- All paths are relative to the repository root `saga-forge/`

## Path Conventions

- Core main: `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/` (abbreviated `core/…` below)
- Core test: `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/` (`core-test/…`)
- Engine main: `saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/` (`engine/…`; package-private
  runtime in `engine/internal/`)
- Engine test: `saga-forge-engine/src/test/java/io/github/gkosharovdev/sagaforge/engine/` (`engine-test/…`;
  `support/` doubles, `fixtures/` test sagas, `internal/` package-private tests)
- Postgres main: `saga-forge-storage-postgres/src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/`
  (`postgres/…`); resources `saga-forge-storage-postgres/src/main/resources/io/github/gkosharovdev/sagaforge/storage/postgres/`
  (`postgres-res/…`); tests `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/`
  (`postgres-test/…`)
- Example: `saga-forge-example/src/{main,test}/java/io/github/gkosharovdev/sagaforge/example/` (`example/…`,
  `example-test/…`)
- **Compile check** (allowed in any task, runs no test):
  `mvn -B -pl <modules> -am -DskipTests clean verify` (`clean` avoids stale IDE classes, G-REPO-02).
- **Unit checkpoint**: `mvn -B -pl <modules> -am clean verify` (container suites excluded without `-Pcontainer-tests`).

---

## Phase 1: Setup

**Purpose**: Formal design first (Constitution Principle III, spec FR-012).

- [X] T001 Update `.kiro/specs/saga-forge-framework/design.md`: add `@RetryPolicy` (attributes, defaults, meaning per
  [contracts/retry-policy.md](./contracts/retry-policy.md)) to the `@SagaStep` annotation section and the
  `retry()` attribute to `@SagaStep`; add "Property 29: RetryPolicy values validity" with the rules of
  contracts/retry-policy.md "Declaring a policy" and its error message format to the correctness properties, the
  validation-rule table and the error-message table; add the test row `retryPolicyValidity` → Property 29. State
  that Properties 1–28 are unchanged.
- [X] T002 Record a green baseline: run `mvn -B clean verify` at the repository root and note the result in the task
  log (no code change). If it fails, stop and report.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: Core vocabulary and record changes that every story compiles against. Callers are updated to pass the
defaults; no behaviour changes yet.

- [X] T003 [P] Create `core/failure/TransientFailure.java` and `core/failure/FinalFailure.java` (public marker
  interfaces, no methods, Javadoc per contracts/retry-policy.md) and `core/failure/FailureClassification.java` (enum
  `TRANSIENT, FINAL, EXHAUSTED, UNCLASSIFIED` with Javadoc per data-model.md) and `core/failure/package-info.java`.
- [X] T004 [P] Create `core/annotation/RetryPolicy.java` (`@Retention(RUNTIME)`, `@Documented`, `@Target({})` so it is
  usable only as an attribute value), attributes `int maxAttempts() default 1`,
  `long initialBackoffMillis() default 0`, `double multiplier() default 1.0`, `long maxBackoffMillis() default 0`,
  `Class<? extends Throwable>[] retryOn() default {}`; add `RetryPolicy retry() default @RetryPolicy` to
  `core/annotation/SagaStep.java` with Javadoc pointing at Property 29.
- [X] T005 [P] Create `core/command/CommandId.java` per data-model.md: record
  `(String sagaId, String stepName, String commandType, int ordinal, String value, boolean sagaScoped)` with
  validation, factories `of(sagaId, stepName, commandType, ordinal)` (value =
  `UUID.nameUUIDFromBytes((sagaId+"\0"+stepName+"\0"+commandType+"\0"+ordinal).getBytes(UTF_8)).toString()`) and
  `unscoped(commandType)` (random UUID, `ordinal = 0`, null saga fields), `toString()` returning `value`.
- [X] T006 Extend `core/spi/SagaFailure.java`: components `classification` (non-null) and `attempts` (`>= 1`) after
  `failedAt`; keep a 4-argument constructor and `of(stepName, cause, at)` defaulting to `UNCLASSIFIED`, `1`; add
  `of(stepName, cause, at, classification, attempts)`; Javadoc.
- [X] T007 Create `core/spi/PendingRetry.java` (record `triggerStep`, `attemptsStarted >= 2`, `lastFailure` non-null
  with classification `TRANSIENT` and `attempts == attemptsStarted - 1`), per data-model.md.
- [X] T008 Extend `core/spi/StepRecord.java`: components `attempts` and `handledCommands` (`List<CommandId>`) per
  data-model.md rules; keep the 2-argument constructor, `notRun()` and `completed(touched)` (attempts 1, no
  commands); add `completed(touched, attempts, handledCommands)`; defensive copy; reject duplicates and unscoped ids.
- [X] T009 Extend `core/spi/SagaInstance.java` with the last component `PendingRetry pendingRetry` (nullable, only
  with status `CREATED`/`RUNNING`); add a constructor without it (null) so existing callers compile; Javadoc.
- [X] T010 [P] Add `ATTEMPT_FAILED` to `core/spi/SagaLifecycleEventKind.java` and update
  `core/spi/SagaLifecycleEntry.java` rules (`stepName` iff `STEP_COMPLETED` or `ATTEMPT_FAILED`; `failure` iff
  `FAILED` or `ATTEMPT_FAILED`).
- [X] T011 [P] Add `DUPLICATE` to `core/spi/HandlingOutcome.java` with Javadoc (triggering step already
  `COMPLETED`; nothing ran or was written); confirm `core/spi/SagaHandlingResult.java` rules need no change and
  document `DUPLICATE` there.
- [X] T012 Update every caller of the changed records to compile with defaults: core test harness and fixtures
  under `core-test/`, engine (`engine/internal/EngineSagaLifecycle.java`, `FailedWrite.java`, any `SagaInstance`
  construction), engine test-jar doubles `engine-test/support/TransactionalInMemoryStorageProvider.java` (must store
  and return `pendingRetry`, `attempts`, `handledCommands`, classification unchanged), Postgres
  `postgres/SagaInstanceRows.java`, `postgres/LifecycleEntryRows.java` (pass `null`/defaults for now; persistence
  is Phase 8). Compile check: `mvn -B -DskipTests clean verify`.
- [X] T013 [P] Unit tests for the new and changed core types in `core-test/spi/` and `core-test/command/`:
  `SagaFailureTest`, `PendingRetryTest`, `StepRecordTest` (new rules), `SagaInstanceTest` (pendingRetry only while
  active), `SagaLifecycleEntryTest` (ATTEMPT_FAILED), and `CommandIdPropertyTest` (jqwik: equal components → equal
  `value`; any component differs → different `value`; `value` is a 36-character UUID; `unscoped` values differ).
- [X] T014 Checkpoint: `mvn -B -pl saga-forge-core,saga-forge-engine,saga-forge-storage-postgres -am clean verify`
  green.

**Checkpoint**: vocabulary in place, behaviour unchanged.

---

## Phase 3: User Story 1 — A command is never applied twice (P1) 🎯 MVP

**Goal**: Stable `CommandId` per dispatched command, available to handlers, and committed identities never
re-handled (FR-001 to FR-005).

**Independent Test**: quickstart.md §2 rows 1–3 — a handler sees the same identity on a re-run; two commands of one
type get ordinals 1 and 2; an identity already in committed `handledCommands` is not re-handled.

- [X] T015 [US1] Create `engine/internal/CommandIds.java`: per unit of work, a counter keyed by
  `(stepName, commandType)` reset when a step starts (`startStep(name)`), `next(commandType)` returning
  `CommandId.of(sagaId, step, type, n)`; a bound-identity stack (`push`/`pop`/`current()`) for the handler call;
  Javadoc per contracts/command-identity.md "Assigning identities".
- [X] T016 [US1] Extend `engine/internal/UnitOfWork.java` with `CommandId nextCommandId(Class<?> commandType)`,
  `boolean alreadyHandled(CommandId id)`, `void commandHandled(CommandId id)` and `Optional<CommandId>
  currentCommand()`/`withCommand(CommandId, Runnable)`; implement in `engine/internal/NonSagaUnitOfWork.java`
  (unscoped ids, never handled, no recording).
- [X] T017 [US1] Implement them in `engine/internal/SagaUnitOfWork.java`: a `CommandIds` per unit of work;
  `runStep` calls `startStep`; `alreadyHandled` checks the committed `StepRecord.handledCommands` of the instance
  loaded by `findByIdAndLock` (keep the loaded instance's step records); `commandHandled` collects ids for the
  running step, passed to `lifecycle.recordStepCompleted`.
- [X] T018 [US1] Extend `engine/internal/EngineSagaLifecycle.java`: `recordStepCompleted(sagaId, step, touched,
  handledCommands, attempts)` stores `StepRecord.completed(touched, attempts, handledCommands)`; existing callers pass
  attempt `1` until Phase 5.
- [X] T019 [US1] Change `engine/internal/EngineCommandGateway.java` `sendWithin`: get `id =
  unitOfWork.nextCommandId(type)`; if `alreadyHandled(id)` log `saga.command.skipped` and return; else run the
  route inside `withCommand(id, …)` and call `commandHandled(id)` on success; outside a unit of work bind
  `CommandId.unscoped(type)` for the call.
- [X] T020 [US1] Add the built-in `CommandId` resolver in `engine/internal/EngineCommandGateway.java`
  (`HandlerArguments.of` consults it before host resolvers: `supports` iff the parameter type is `CommandId`;
  `resolve` returns the bound current identity, failing with `IllegalStateException` if none is bound).
- [X] T021 [US1] Add `saga.command.skipped` (INFO, saga type, instance, step, command type, identity value) to
  `engine/internal/Diagnostics.java`.
- [X] T022 [US1] Add a startup error in `engine/internal/StartupChecks.java` for a `@CommandHandler` declaring more
  than one `CommandId` parameter (message names handler and positions), surfaced through `StartupReport`.
- [X] T023 [P] [US1] Create fixture `engine-test/fixtures/idempotency/` with a saga (`ChargeSaga`, start step
  dispatching two `ChargeCommand`s and one `NotifyCommand`, end step), events, commands, a `ChargeService` handler
  taking `CommandId`, and `FakePaymentProvider` recording charges per key (rejects a second charge with the same key
  but a different amount, returns the first result for a repeated key).
- [X] T024 [US1] Tests in `engine-test/CommandIdentityTest.java`: identities stable when the unit of work fails after
  the handler ran and the event is delivered again (handler fails on first call via `FaultPlan`-style toggle);
  ordinals 1/2 per type and independent across types; different instances → different identities; aggregate
  instance, creation and service handlers all receive the identity; non-saga dispatch gets an unscoped identity;
  more than one `CommandId` parameter fails startup.
- [X] T025 [US1] Tests in `engine-test/internal/EngineCommandGatewayTest.java` (extend): a dispatch whose identity is
  in committed `handledCommands` does not invoke the handler, logs `saga.command.skipped` (use `RecordingLogger`), and
  the step still completes; a handler failure leaves the identity unrecorded.
- [X] T026 [US1] Checkpoint: `mvn -B -pl saga-forge-engine -am clean verify` green.

---

## Phase 4: User Story 2 — A duplicate event for a completed step is skipped (P1)

**Goal**: `DUPLICATE` outcome for events of completed steps; in-process duplicates not queued (FR-006, FR-007).

**Independent Test**: quickstart.md §2 rows 4–5.

- [X] T027 [US2] In `engine/internal/SagaUnitOfWork.java` `lockInstance`: after a non-empty locked read and before the
  terminal check, if the triggering step's `StepRecord` is `COMPLETED` roll back, release the lease, log
  `saga.event.duplicate`, return `HandlingOutcome.DUPLICATE` (no `unitOfWorkId`). Skip the start step (its duplicates
  stay `DUPLICATE_START` in `EventRouter`).
- [X] T028 [US2] In `engine/internal/SagaUnitOfWork.java` `inProcessStepFor`: do not queue an event whose step is
  `COMPLETED` on the loaded instance or completed earlier in this run (track names in `stepsRun`); it stays buffered
  and is handed off.
- [X] T029 [P] [US2] Add `saga.event.duplicate` (DEBUG) to `engine/internal/Diagnostics.java` and map `DUPLICATE` in
  `engine/SagaEngine.java` `HandlingReport` building and any outcome switch (`EventRouter`, report summaries).
- [X] T030 [US2] Tests in `engine-test/DuplicateEventTest.java`: redelivery to a running instance → `DUPLICATE`, step
  not invoked, no command dispatched, storage unchanged (`TransactionalInMemoryStorageProvider` write count);
  redelivery after a rollback → step runs; redelivery to a `COMPLETED` and to a `FAILED` instance for a completed step
  → `DUPLICATE` (not `UNEXPECTED`); event for a not-run step of an ended instance → still `UNEXPECTED`; in-process
  duplicate recorded twice in one unit of work runs the step once and hands the second off.
- [X] T031 [US2] Update `engine-test/HandlingReportOutcomesTest.java` and `engine-test/RoutingOutcomesTest.java` for the
  new outcome and the changed precedence.
- [X] T032 [US2] Checkpoint: `mvn -B -pl saga-forge-engine -am clean verify` green.

---

## Phase 5: User Story 3 — A transient failure is retried under the step's policy (P1)

**Goal**: Policy validation, classification, bounded backoff, and the in-process attempt loop (FR-008 to FR-014a).

**Independent Test**: quickstart.md §3 rows 1–2 and the startup-bound row.

- [X] T033 [P] [US3] Property 29 in `core/scanner/ReflectiveAnnotationScanner.java`: validate each `@SagaStep`'s
  `retry` per contracts/retry-policy.md (including "maxAttempts 1 ⇒ defaults", no `Error` in `retryOn`), messages in
  the documented format, reported by `validate` and `validateAll`.
- [X] T034 [P] [US3] Fixtures `core-test/scanner/fixtures/retry/` (one saga per invalid rule plus a valid one) and
  tests: `core-test/scanner/RetryPolicyValidationTest.java` and a jqwik property in
  `core-test/scanner/ReflectiveAnnotationScannerPropertyTest.java` (valid policies produce no Property 29 error).
- [X] T035 [P] [US3] Create `engine/internal/Backoff.java`: `waitBefore(policy, k)` and `total(policy)` per
  contracts/retry-policy.md (overflow-safe: saturate at `Long.MAX_VALUE`); `engine-test/internal/BackoffPropertyTest.java`
  (jqwik: waits non-decreasing, each `<=` cap, total equals the sum, fixed policy = `(maxAttempts-1) * initial`).
- [X] T036 [P] [US3] Create `engine/internal/FailureClassifier.java` per contracts/retry-policy.md "Classification"
  (cycle-safe cause walk, `Error` → `UNCLASSIFIED`, final beats transient on one element, `retryOn` by `isInstance`);
  `engine-test/internal/FailureClassifierTest.java` covering every row, wrapped handler exceptions (G-ENG-18) and a
  cause cycle.
- [X] T037 [US3] Create public `engine/RetryPause.java` (functional interface, `pause(Duration) throws
  InterruptedException`, `static RetryPause sleeping()` = `Thread.sleep`); add `Builder.retryPause(RetryPause)` and
  `Builder.maxTotalRetryBackoff(Duration)` (default 30 s, positive) to `engine/SagaEngine.java`; carry both in
  `engine/internal/EngineSettings.java`.
- [X] T038 [US3] Startup check in `engine/internal/StartupChecks.java`: every step whose `Backoff.total` exceeds
  `maxTotalRetryBackoff` adds the documented `StartupReport` error.
- [X] T039 [US3] Refactor lease ownership: `engine/internal/SagaUnitOfWork.java` no longer closes the instance lease;
  it receives the attempt number `k` and a `started` flag in `Trigger`, and on failure returns a result carrying the
  rolled-back cause and failing step instead of calling `FailedWrite` itself (keep `conflicted` and `notRun` paths
  returning results directly).
- [X] T040 [US3] Create `engine/internal/AttemptWrite.java` (sibling of `FailedWrite`): own transaction,
  `findByIdAndLock`, set `pendingRetry(triggerStep, k + 1, SagaFailure(F, cause, TRANSIENT, k))`, append an
  `ATTEMPT_FAILED` entry through `EngineSagaLifecycle`, save, commit; skip if the instance is gone or terminal;
  propagate its own failure with the cause suppressed (contracts/attempt-loop.md "Guarantees").
- [X] T041 [US3] Create `engine/internal/AttemptLoop.java` implementing contracts/attempt-loop.md within one handling
  call (holding the lease, classify via `FailureClassifier` with the failing step's policy, `AttemptWrite`, pause
  `Backoff.waitBefore(k + 1)` through `RetryPause`, new `SagaUnitOfWork` per attempt with `started = false` after the
  first); release the lease once, before the `AFTER_COMMIT` handoff of a committed attempt.
- [X] T042 [US3] Wire `AttemptLoop` into `engine/internal/EventRouter.java` in place of the direct
  `new SagaUnitOfWork(...).run()` call; the creation write stays before the loop.
- [X] T043 [US3] On commit, `engine/internal/SagaUnitOfWork.java` passes attempt `k` to every
  `recordStepCompleted` of the run and clears `pendingRetry` when its `triggerStep` equals the trigger step
  (`engine/internal/EngineSagaLifecycle.java` gains `clearPendingRetry`).
- [X] T044 [US3] Add `saga.uow.retrying` (INFO) to `engine/internal/Diagnostics.java`.
- [X] T045 [P] [US3] Fixture `engine-test/fixtures/retry/` with a saga whose steps declare policies (3 attempts, 200 ms,
  ×2, `retryOn = CourierTimeout`), a handler failing a configurable number of times with a transient, final or
  unmarked exception, and a two-step-in-one-unit-of-work variant where the second step fails.
- [X] T046 [US3] Tests in `engine-test/StepRetryTest.java` with a recording `RetryPause`: transient twice then success →
  committed once, `StepRecord.attempts == 3`, pauses 200/400 ms, two `ATTEMPT_FAILED` entries, `pendingRetry` cleared;
  no policy → one attempt; second-step failure re-runs the first step with unchanged `CommandId`s and the
  `FakePaymentProvider` from T023 sees one charge; `SagaContext` value written by attempt 1 visible to attempt 2;
  each attempt gets a new `unitOfWorkId`; a waiting second event for the instance runs only after the loop ends
  (`ConcurrentUnitsOfWorkTest` style); total-backoff startup error.
- [X] T047 [US3] Checkpoint: `mvn -B -pl saga-forge-core,saga-forge-engine -am clean verify` green.

---

## Phase 6: User Story 4 — Final failures are not retried; exhaustion is recorded (P2)

**Goal**: `FAILED` carries classification and attempts (FR-016, FR-017).

**Independent Test**: quickstart.md §3 rows 3–6.

- [X] T048 [US4] Extend `engine/internal/FailedWrite.java` `record(...)` to take the classification and attempt
  count and build `SagaFailure.of(step, cause, at, classification, attempts)`; `AttemptLoop` maps a last-attempt
  `TRANSIENT` to `EXHAUSTED`; the `FAILED` lifecycle entry carries the same failure.
- [X] T049 [P] [US4] Add `saga.uow.exhausted` (WARNING, step, attempts, cause type) to
  `engine/internal/Diagnostics.java`; include classification and attempts in the existing `instanceFailed` message.
- [X] T050 [US4] Tests in `engine-test/FailureClassificationTest.java`: `FinalFailure` on attempt 1 → `FINAL`,
  attempts 1, handler called once; unmarked failure not in `retryOn` → `UNCLASSIFIED`, no retry; transient on every
  attempt → `EXHAUSTED`, attempts 3, last cause; transient, transient, final → `FINAL`, attempts 3; `Error` →
  `UNCLASSIFIED`; `SagaHandlingResult.failure()` and the stored instance agree.
- [X] T051 [US4] Update `engine-test/FailureDifferentialTest.java` and `engine-test/FailurePositionTest.java`
  expectations: the 002 harness comparison ignores the new `SagaFailure` fields (harness records `UNCLASSIFIED`, 1),
  and failure positions report attempts 1 without a policy.
- [X] T052 [US4] Checkpoint: `mvn -B -pl saga-forge-engine -am clean verify` green.

---

## Phase 7: User Story 5 — Retries survive a crash without double effects (P3)

**Goal**: Continuation after restart or interrupted wait, never beyond `maxAttempts` (FR-014b, FR-015, SC-005).

**Independent Test**: quickstart.md §3 rows 7–8.

- [X] T053 [US5] In `engine/internal/SagaUnitOfWork.java` (first attempt of a handling call only): if the loaded
  instance's `pendingRetry.triggerStep` equals the trigger step, start at `k = attemptsStarted + 1`; if `k` exceeds
  the `maxAttempts` of `lastFailure.stepName`, roll back and return a result that `AttemptLoop` turns into
  `FailedWrite(EXHAUSTED, attempts = k - 1, lastFailure's cause type and message)` without running steps.
- [X] T054 [US5] In `engine/internal/AttemptLoop.java`: on `InterruptedException` from `RetryPause`, restore the
  interrupt flag, log `saga.uow.retryInterrupted` (add to `engine/internal/Diagnostics.java`), and return
  `NOT_HANDLED` with the claimed attempt left recorded.
- [X] T055 [US5] Tests in `engine-test/RetryResumeTest.java` (two engines over one shared storage, like
  `engine-test/ResumeAcrossEnginesTest.java`): attempt 1 fails and engine A is abandoned after the `AttemptWrite`
  (pause throws an unchecked "crash" from the recording `RetryPause`) → redelivery to engine B runs attempt 3 and never
  a 4th; interrupted pause after attempt 2 → `NOT_HANDLED`, interrupt flag set, `pendingRetry.attemptsStarted == 3`,
  redelivery → `FAILED` `EXHAUSTED` without invoking the step; command identities identical across engines; an
  unrelated event's commit does not clear another trigger step's `pendingRetry`.
- [X] T056 [US5] Fault-injection suite `engine-test/IdempotencyFaultInjectionTest.java` (SC-001): parameterised over
  every step and handler boundary of the T023/T045 fixtures × {fail transient, fail final, redeliver after commit,
  crash after attempt write}; assert one charge per order in `FakePaymentProvider` and attempts `<= maxAttempts`.
- [X] T057 [US5] Checkpoint: `mvn -B -pl saga-forge-engine -am clean verify` green.

---

## Phase 8: Postgres persistence

**Purpose**: Persist the new data (FR-019, contracts/storage-delta.md). Serves US1, US3–US5.

- [X] T058 [P] Update `postgres-res/schema.sql` with the new columns and constraints, and create
  `postgres-res/schema-upgrade-005.sql` with the statements from contracts/storage-delta.md.
- [X] T059 Extend `postgres/JsonCodec.java`: `StepRecord` `attempts`/`handledCommands` (`CommandId` as object) and
  `PendingRetry` (with nested `SagaFailure` including classification/attempts); readers default absent fields
  (`attempts` 1 for `COMPLETED`, 0 for `NOT_RUN`; `handledCommands` `[]`).
- [X] T060 Extend `postgres/SagaInstanceRows.java` (write/read `failure_classification`, `failure_attempts`,
  `pending_retry`) and `postgres/LifecycleEntryRows.java` (`ATTEMPT_FAILED`, failure classification/attempts columns);
  update the SQL strings in `postgres/PostgresStorageProvider.java`.
- [X] T061 Extend `postgres/ForwardTransition.java`: reject a completed step whose `attempts` or `handledCommands`
  changed, and `pendingRetry` on a terminal incoming snapshot.
- [X] T062 [P] Unit tests (no container): `postgres-test/JsonCodecTest.java` (round-trip and pre-005 defaults),
  `postgres-test/LifecycleEntryRowsTest.java` (`ATTEMPT_FAILED`), `postgres-test/ForwardTransitionPropertyTest.java`
  (new rules), and statement-recording checks in `postgres-test/PostgresStorageProviderJoinTest.java` /
  `postgres-test/JdbcFake.java` for the new columns.
- [X] T063 [P] Container tests (edit and compile only, never run): `postgres-test/SchemaTest.java` (new columns,
  constraints; `schema-upgrade-005.sql` applied to a database created from the pre-005 schema),
  `postgres-test/PostgresStorageProviderTest.java` `RoundTrip` (pendingRetry, attempts, handledCommands,
  classification), `postgres-test/LifecycleHistoryTest.java` (`ATTEMPT_FAILED` rows).
- [X] T064 Checkpoint: `mvn -B -pl saga-forge-storage-postgres -am clean verify` green (container suites excluded).

---

## Phase 9: Polish & cross-cutting

- [X] T065 Update the example: `example/OrderProcessingSaga.java` `processPayment` declares
  `retry = @RetryPolicy(maxAttempts = 3, initialBackoffMillis = 200, multiplier = 2.0, maxBackoffMillis = 1_000,
  retryOn = PaymentGatewayTimeoutException.class)`; create `example/PaymentGatewayTimeoutException.java`
  (implements `TransientFailure`); `example/PaymentService.java` `handle(ProcessPaymentCommand, CommandId)` passes
  `commandId.value()` to a simple in-memory provider class `example/PaymentProvider.java`; keep
  `associationProperty` and `@StartSaga`/`@EndSaga` visible; refresh the class Javadoc table.
- [X] T066 Tests in `example-test/OrderProcessingSagaEngineTest.java`: provider timeout on the first attempt → one
  charge, order completed, `StepRecord.attempts == 2`; redelivery of `PaymentProcessedEvent` after completion →
  `DUPLICATE`; `example-test/OrderProcessingSagaValidationTest.java` still valid with the policy.
- [X] T067 [P] Docs: `README.md` canonical example and feature list (retry policy, `CommandId`);
  `docs/architecture.md` — move "Idempotent step execution" and "Per-step retry policy with failure classification"
  from gaps to implemented with links to this spec, keep the triggering-command dedupe as a gap (G-005-04), update the
  status table; `docs/use-cases.md` §2 and Process D lessons (retry and idempotency now provided; compensation in
  006).
- [X] T068 [P] `AGENTS.md`: Current Status entry for 005; remove "`@SagaStep` has no retry/backoff attributes yet";
  state the `CommandId` handler-parameter convention and that duplicates are `DUPLICATE`; Known Gaps line for
  G-005-01 to G-005-05.
- [X] T069 [P] `.specify/memory/memory.md`: set G-006-01 to closed-by-005 once implemented; record any gap found during
  implementation with a new `G-005-NN` ID; confirm G-005-01 to G-005-05 still describe the implementation.
- [X] T070 [P] Update `specs/006-saga-compensation/spec.md` where it references 005 so the names match the
  implementation (`FailureClassification`, `SagaFailure.classification()`, `CommandId`).
- [X] T071 Final checkpoint: `mvn -B clean verify` at the repository root green; walk through quickstart.md §1–§4 and
  §6 against the test names actually created and fix the quickstart if any name differs.
- [X] T072 Hand off to the user: list the container classes changed in T063 with the command
  `mvn -B -pl saga-forge-storage-postgres -am clean verify -Pcontainer-tests`, noting they have never been run
  (G-003-23).

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1** (T001–T002) → **Phase 2** (T003–T014) → stories.
- **US1** (Phase 3) and **US2** (Phase 4) depend only on Phase 2; they touch `SagaUnitOfWork.java` and
  `Diagnostics.java` both, so run them one after the other (US1 first).
- **US3** (Phase 5) depends on Phase 2 and on US1 (T046 asserts unchanged `CommandId`s across attempts) and US2
  (lease/outcome changes in the same files).
- **US4** (Phase 6) depends on US3 (`AttemptLoop`, classifier).
- **US5** (Phase 7) depends on US3 and US4.
- **Postgres** (Phase 8) depends on Phase 2 only; it can run in parallel with Phases 3–7 (different module).
- **Polish** (Phase 9) depends on all stories and Phase 8.

### Story completion order

```text
Setup → Foundational ─┬─ US1 → US2 → US3 → US4 → US5 ─┬─ Polish
                      └──────── Postgres ──────────────┘
```

### Within each phase

Production code first, tests in the same phase, one checkpoint at the end. `[P]` tasks touch different files.

## Parallel Examples

**Phase 2**: T003, T004, T005, T010, T011 together (separate files); then T006–T009 (record chain), T012, T013.

**US1**: T023 (fixture) in parallel with T015–T022 (production code).

**US3**: T033+T034 (core scanner), T035 (backoff), T036 (classifier) and T045 (fixture) together; then T037–T044 in order.

**Postgres vs stories**: a second implementer can take T058–T064 while another works through US1–US5.

## Implementation Strategy

### MVP (US1)

Phases 1–3: stable command identities and no re-handling of committed commands. Delivers the "never charge twice"
guarantee for every redelivery path a transport has today, before any retry exists.

### Incremental delivery

1. MVP (US1) → 2. US2 (duplicate events skipped) → 3. US3 (retry) → 4. US4 (classification recorded; unblocks
   006-saga-compensation) → 5. US5 (crash safety) → 6. Postgres → 7. Polish.

Each checkpoint leaves `master`-quality green builds; stop after any story if priorities change.
