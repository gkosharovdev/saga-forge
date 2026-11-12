# Tasks: Saga Test Fixture

**Input**: Design documents from `/specs/009-saga-test-fixture/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: requested. The spec requires a parity suite (FR-017), reference tests (FR-018), failure-message coverage
(SC-004) and timing goals (SC-002, SC-005), so each story has test tasks written before its implementation.

**Organization**: tasks are grouped by user story. Priority order: US1 and US2 (P1); US3, US4 and US6 (P2); US5 (P3).
US6 comes before US4 here because every later message check depends on it.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: the user story the task belongs to (US1–US6)

## Path Conventions

- Core SPI: `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/spi/`
- Runtime: `saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime/` (`internal/` for the
  package-private classes)
- Fixture main: `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/` (`internal/` for the
  non-public classes)
- Fixture tests: `saga-forge-test/src/test/java/io/github/gkosharovdev/sagaforge/test/` (`fixtures/` for test sagas)

The paths in the tasks below are written in full.

---

## Phase 1: Setup (shared infrastructure)

**Purpose**: create the new module and wire it into the build.

- [X] T001 Create module `saga-forge-test/pom.xml` (artifact `saga-forge-test`, parent `saga-forge-parent`). Main dependencies: `saga-forge-core` and `saga-forge-runtime` only. Test scope: JUnit 5, AssertJ, and the `saga-forge-runtime` test-jar (for the parity suite). Add `<module>saga-forge-test</module>` after `saga-forge-runtime` in the root `pom.xml`, and add `saga-forge-test` to the root `dependencyManagement` if the other modules are listed there.
- [X] T002 [P] Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/package-info.java`. It documents the fixture's purpose, record-and-answer as the default, handler mode, and that no Spring, database or transport is involved (FR-001, FR-011, FR-015). Also create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/internal/package-info.java`.
- [X] T003 [P] Create `saga-forge-test/README.md` as a stub: coordinates, a one-paragraph purpose, and a placeholder "Usage" section (completed in T070).

---

## Phase 2: Foundational (blocking prerequisites): the command interceptor

**Purpose**: the additive core SPI and the runtime support that record-and-answer and failure injection need
([contracts/command-interceptor.md](contracts/command-interceptor.md), research §2). No fixture story can start
before this phase is done.

### Tests

- [X] T004 [P] Write `saga-forge-runtime/src/test/java/io/github/gkosharovdev/sagaforge/runtime/CommandInterceptorTest.java` over the runtime's in-memory test doubles. Cover each obligation in the contract:
  - (1) the interceptor is called exactly once per handling, for a step command, a compensation command, a command sent outside a unit of work, and an outbox command delivered through `CommandReceiver`; a command whose identity already committed (005 skip) is not passed to it;
  - (2) it runs inside the unit of work, with the `CommandId` bound, so a handler's `CommandId` parameter equals `invocation.commandId()`;
  - (3) an exception it throws is treated like a handler exception: a `TransientFailure` is retried, a `FinalFailure` compensates, an unclassified one ends `FAILED`;
  - (4) returning without `proceed()` counts as handled, and the identity is recorded in `StepRecord.handledCommands`; with `hasHandler() == false` the step still completes;
  - (5) a second `proceed()` throws `IllegalStateException`;
  - (6) `hasHandler()` reflects whether a route exists.
- [X] T005 [P] Write `saga-forge-runtime/src/test/java/io/github/gkosharovdev/sagaforge/runtime/CommandInterceptorPropertyTest.java` for Property 36. Over generated scenarios (step counts, retries, compensation), a runtime built with `CommandInterceptor.proceeding()` produces the same `HandlingReport`s, stored instances and handled-command identities as a runtime built without an interceptor. The interceptor's call count equals the number of handled commands.

### Implementation

- [X] T006 [P] Create `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/spi/CommandInvocation.java` with `command()`, `commandId()`, `hasHandler()` and `proceed()`. Its Javadoc states the obligations from the contract.
- [X] T007 [P] Create `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/spi/CommandInterceptor.java`: a `@FunctionalInterface` with `intercept(CommandInvocation)` and the static `proceeding()`.
- [X] T008 Add the `commandInterceptor` setting to `saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime/internal/EngineSettings.java` (default `CommandInterceptor.proceeding()`). Add `SagaRuntime.Builder.commandInterceptor(CommandInterceptor)` to `saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime/SagaRuntime.java`: non-null, at most one (a second call throws `IllegalStateException`), with Javadoc.
- [X] T009 Route every command handling through the interceptor in `saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime/internal/EngineCommandGateway.java`:
  - Wrap the handler route in a package-private `CommandInvocation` implementation. `proceed()` runs at most once and throws `NoCommandHandlerException` when there is no route.
  - Call the interceptor after the 005 duplicate skip and with the identity bound. Do not call `routeFor` eagerly when there is no handler.
  - Record the identity as handled when the interceptor returns normally.
  - Cover the saga, non-saga (`NonSagaUnitOfWork`) and `CommandReceiver` outbox paths.
- [X] T010 Check that the compensation path (`saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime/internal/CompensationUnitOfWork.java`) and the startup checks (`saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime/internal/StartupChecks.java`) work with commands that have no handler. If startup validation rejects a command type that has no handler, keep that check (production behaviour) and record in memory.md how the fixture deals with it (T020).
- [X] T011 Add Property 36 (command interception) to `.kiro/specs/saga-forge-framework/design.md`, worded as in the contract, after Property 35.
- [X] T012 Run `mvn -B -pl saga-forge-core,saga-forge-runtime -am verify`. T004, T005 and every existing runtime test must pass unmodified (obligation 6, risk R1).

**Checkpoint**: the interceptor exists, Property 36 holds, and the runtime's behaviour is unchanged by default.

---

## Phase 3: User Story 1: test how a saga reacts to an event (Priority: P1) 🎯 MVP

**Goal**: given/when/then over the shipped runtime in record-and-answer mode: commands, context values, completed
step, status, outcome.

**Independent Test**: given `OrderCreatedEvent(o-1)`, when `PaymentProcessedEvent(payment-1, o-1)`, expect
`ReserveStockCommand(o-1)`, context `paymentId = payment-1`, step `processPayment` completed, status `RUNNING`, with
no infrastructure.

### Tests for User Story 1

- [X] T013 [P] [US1] Create test sagas in `saga-forge-test/src/test/java/io/github/gkosharovdev/sagaforge/test/fixtures/`: a three-step order-like saga (`@StartSaga` → step → `@EndSaga`) with `associationProperty = "orderId"`, its events (with `@DomainEvent(aggregate = …)` and an `@Aggregate` type with `@AggregateIdentifier`), and its commands as records. Steps dispatch through a `CommandGateway` field and write `SagaContext` values. Keep it independent of `saga-forge-example`.
- [X] T014 [P] [US1] Write `saga-forge-test/src/test/java/io/github/gkosharovdev/sagaforge/test/FixtureApiTest.java` for US1 acceptance scenarios 1–6:
  - a start event creates an instance and reports its commands and status;
  - given events are routed to the existing instance and don't count towards expectations;
  - `expectCommands` fails on a different set, a different order or different content;
  - `expectNoCommands` fails naming the dispatched command;
  - an `@EndSaga` step gives `expectEndedWith`;
  - `expectContext` and `expectNoContext` pass and fail.
  Also cover: `when` without `given`; `given(...).then()` with no `when`; `expectCommandsMatching`; `expectCommand(type, predicate)`; `expectStarted`; `expectOutcome(WAITING)`; deterministic `saga-1` ids; reusing a fixture across two `when`s (edge case "Fixture reuse"). Each failing case asserts `SagaExpectationError` (an `AssertionError`).

### Implementation for User Story 1

- [X] T015 [P] [US1] Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/FixtureClock.java`: a mutable `Clock` starting at a fixed instant (default `2026-01-01T00:00:00Z`), with `set(Instant)`, `advance(Duration)`, `instant()`, UTC zone, and thread-safe. Research §5.
- [X] T016 [P] [US1] Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/internal/FixtureTransactions.java`: an in-memory `TransactionBoundary` like the runtime's `InMemoryTransactionBoundary` (begin/commit/rollback, rollback-only, nesting behaviour matching the runtime's expectations). It runs registered commit and rollback callbacks for each transaction (research §3, §6).
- [X] T017 [P] [US1] Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/internal/FixtureStorage.java`: an in-memory `StorageProvider` with copy-on-write `SagaInstance`s, `findByIdAndLock`, lookup by association, and `save(instance, entries)` keeping lifecycle entries. Writes are transactional: staged through `FixtureTransactions` and dropped on rollback, as with `TransactionalInMemoryStorageProvider`.
- [X] T018 [P] [US1] Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/internal/FixtureContextStore.java`: an in-memory `SagaContextStore`. Values are written immediately and survive rollback (as the runtime expects). It exposes a hook for the value check, which stays empty until T052.
- [X] T019 [P] [US1] Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/internal/LoopBackTransport.java`: an `EventDispatcher` plus an `IN_TRANSACTION` `Outbox` (with `supportsCommands`) that queue what the runtime hands off and release it only on `committed`. `drain(SagaRuntime)` delivers in order, events to `runtime.receive` and outbox commands to the runtime's `CommandReceiver`, until the queue is empty. It collects every `HandlingReport` produced (research §4).
- [X] T020 [US1] Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/RecordedCommand.java` (command, `CommandId`, step name, saga type, saga id, attempt, `handled`, `outbox`) per [data-model.md](data-model.md). Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/internal/FixtureCommandInterceptor.java`:
  - it records every invocation into the current transaction's buffer, which is kept on commit and dropped on rollback; every attempt is also kept in an all-attempts list;
  - it calls `proceed()` only if `hasHandler()` and the handler's host is registered through `handlers(...)`, so with no handlers registered nothing is handled (FR-011);
  - failure rules are left empty until T029.
  If T010 found that startup rejects commands without a handler, give the runtime a placeholder `HandlerInstanceProvider` route only as far as the contract allows, and record the decision in memory.md.
- [X] T021 [US1] Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/InstanceView.java` (saga type, saga id, association value, status, outcome step, failure, compensation trigger, step records, context values) and `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/EventResult.java` (the event, the `SagaHandlingResult`s, the committed commands per instance, all attempts' commands, the touched `InstanceView`s), per [data-model.md](data-model.md). Both are immutable snapshots.
- [X] T022 [US1] Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/SagaExpectationError.java` (`extends AssertionError`, with a cause constructor). Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/internal/Messages.java` with the header line from [contracts/fixture-api.md](contracts/fixture-api.md) (`Saga[assoc=value] (saga-id), when Event[...]:`, the steps that ran with their attempts, the status) and an expected/actual block. Polished in US6.
- [X] T023 [US1] Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/SagaFixture.java` with its `Builder`:
  - builder methods `of(Class<?>...)`, `register(...)`, `startingAt(Instant)`, `maxTotalRetryBackoff(Duration)`, `build()`;
  - `build()` assembles `SagaRuntime.builder()` with `FixtureStorage`, `FixtureContextStore`, `FixtureTransactions`, `LoopBackTransport` (dispatcher and outbox), `FixtureClock`, `sagaIds` (`saga-N`), `unitOfWorkIds` (`uow-N`), a `RetryPause` that advances the clock, and `FixtureCommandInterceptor`, then calls `runtime.start()` and lets `SagaRuntimeStartupException` propagate (FR-003, research §9);
  - accessors `clock()`, `commands()`, `instance(type, associationValue)`.
  Each fixture owns all its state (FR-016).
- [X] T024 [US1] Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/Given.java`. `given(Object... events)` publishes each event through `runtime.receive` and drains the loop-back, without recording results for `then`. `when(event)` and `then()` (over the last given event) return a `Then`. Add `SagaFixture.given(...)` and `SagaFixture.when(...)`.
- [X] T025 [US1] Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/Then.java` with the US1 methods from [contracts/fixture-api.md](contracts/fixture-api.md): `expectCommands`, `expectCommandsMatching`, `expectNoCommands`, `expectCommand`, `expectOutcome`, `expectStarted`, `expectCompletedStep`, `expectStatus`, `expectEndedWith`, `expectContext`, `expectNoContext`, `instance(type, assoc)` and `result()`. The subject is the single instance the event touched, and an ambiguous subject throws an error that names the candidates. Commands of the event under test are the committed commands of the units of work it caused.
- [X] T026 [US1] Run `mvn -B -pl saga-forge-test -am verify`; T014 passes.

**Checkpoint**: US1 works on its own. This is the MVP.

---

## Phase 4: User Story 2: test failure, retry and compensation paths (Priority: P1)

**Goal**: command failure rules, retries without real waiting, compensation, and failure/intervention expectations.

**Independent Test**: given `OrderCreatedEvent(o-2)` and `PaymentProcessedEvent(o-2)`, when `StockUnavailableEvent(o-2)`,
expect `compensatePayment` dispatching `RefundPaymentCommand` with the stored payment id and amount, and status
`COMPENSATED` with trigger `rejectOrder`.

### Tests for User Story 2

- [X] T027 [P] [US2] Extend the test sagas in `saga-forge-test/src/test/java/io/github/gkosharovdev/sagaforge/test/fixtures/` with:
  - a `@StartCompensation` step and `@SagaCompensation(forStep = …)` methods that send commands;
  - a step with `@SagaStep(retry = @RetryPolicy(...))` whose policy totals 30 s of backoff;
  - a compensation that can be made to throw;
  - exception types marked `TransientFailure` and `FinalFailure`.
- [X] T028 [P] [US2] Write `saga-forge-test/src/test/java/io/github/gkosharovdev/sagaforge/test/FailureAndCompensationTest.java` for US2 acceptance scenarios 1–6:
  - business-failure compensation order and commands;
  - transient twice then success: three attempts, the same `CommandId` on every attempt, the clock advanced by the backoff;
  - transient beyond the policy: `EXHAUSTED` and compensated;
  - unclassified: `FAILED`, nothing compensated;
  - a compensation that fails: `REQUIRES_INTERVENTION`, showing which compensations succeeded;
  - `failCommand` by type, by content predicate, once, n times and always.
  Assert SC-005: a 30 s backoff policy test takes under 1 s of wall time. Also cover the edge case that context values written by a failed attempt stay visible.

### Implementation for User Story 2

- [X] T029 [US2] Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/internal/CommandFailureRule.java` (command type, optional predicate, exception supplier, remaining count or always), per [data-model.md](data-model.md). Apply the rules in `FixtureCommandInterceptor` before recording or proceeding; the first matching rule throws (research §2). A failed invocation is still recorded in the all-attempts list.
- [X] T030 [US2] Add the four `failCommand(...)` overloads to `SagaFixture.Builder` in `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/SagaFixture.java`. Validate the arguments: non-null, `times >= 1`.
- [X] T031 [US2] Add `expectCompensations(String...)`, `expectCompensatedAfter(String)`, `expectFailure(Class, FailureClassification)` and `expectAttempts(String, int)` to `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/Then.java`. Read compensations from `StepRecord.compensation`, ordered by `completionSequence` (most recently completed first, as they ran), with each compensation's committed commands from the recorded commands of its step name. Expose all-attempts commands on `EventResult` (risk R2).
- [X] T032 [US2] Run `mvn -B -pl saga-forge-test -am verify`; T028 passes.

**Checkpoint**: US1 and US2 work on their own.

---

## Phase 5: User Story 3: test correlation and duplicates (Priority: P2)

**Goal**: per-instance addressing, unhandled events, duplicates and duplicate starts.

**Independent Test**: orders `o-3` and `o-4` started; `PaymentProcessedEvent(o-4)` advances only `o-4`; the same event
again reports `DUPLICATE` with no command.

### Tests for User Story 3

- [X] T033 [P] [US3] Write `saga-forge-test/src/test/java/io/github/gkosharovdev/sagaforge/test/CorrelationTest.java` for US3 acceptance scenarios 1–4: two instances, of which only one advances, each addressed with `instance(type, assoc)`; an event no step accepts (`expectUnhandled`, and `expectHandled` fails); a redelivered completed step (`expectOutcome(DUPLICATE)`, `expectNoCommands`); a second start (`DUPLICATE_START`). Also cover the edge cases: an event not taken by any saga is reported as unhandled, not as an error; two saga types reacting to one event are reported separately; G-ENG-24 (an event a step of its own instance runs is not handed to other saga types) behaves as in the runtime.

### Implementation for User Story 3

- [X] T034 [US3] Add `expectHandled()` and `expectUnhandled()` to `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/Then.java`. Both treat `UNMATCHED`/`NOT_HANDLED`/no result as unhandled. Make `instance(type, assoc)` resolve the instance by association value through `FixtureStorage`, and report every touched instance in `EventResult` (FR-006, FR-007).
- [X] T035 [US3] Make `SagaFixture.instance(type, assoc)` in `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/SagaFixture.java` return an `InstanceView` read from storage and the context store. An unknown instance throws `SagaExpectationError` naming the type and value.
- [X] T036 [US3] Run `mvn -B -pl saga-forge-test -am verify`; T033 passes.

**Checkpoint**: US1 to US3 work on their own.

---

## Phase 6: User Story 6: failures explain themselves (Priority: P2)

**Goal**: every failed expectation names the saga type, association value, event, steps that ran, and expected and
actual values; unexpected failures are the cause; model errors fail at setup.

**Independent Test**: expecting `ReserveStockCommand` when nothing was dispatched fails with a message that names the
instance, the event, the step that ran, and "expected 1 command, dispatched none".

### Tests for User Story 6

- [X] T037 [P] [US6] Write `saga-forge-test/src/test/java/io/github/gkosharovdev/sagaforge/test/MessagesTest.java`:
  - Deliberately fail every `Then` method once. Assert that each message contains the saga type, the association value, the event and the differing values (SC-004: 100% of failing cases).
  - A step that throws unexpectedly is reported with the step, the exception and the status, with the original exception as the cause.
  - An invalid saga class (for example two `@SagaId` fields) fails `build()` with `SagaRuntimeStartupException` listing every error.
  - The command block shows expected and dispatched commands with their content, and "expected 1 command, dispatched none".

### Implementation for User Story 6

- [X] T038 [US6] Complete `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/internal/Messages.java` to match the format in [contracts/fixture-api.md](contracts/fixture-api.md):
  - the header (`Saga[assoc=value] (saga-id), when Event[...]`);
  - the steps and compensations that ran, with their attempts;
  - the status, outcome and failure with its classification;
  - an aligned expected/actual block for commands, context values and statuses;
  - command counts ("expected N commands, dispatched M/none").
  Use the `toString` of records.
- [X] T039 [US6] Make every `Then` method in `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/Then.java` build its error through `Messages`. When the instance recorded an unexpected failure and the expectation fails, attach the original exception (from the failed unit of work, captured by the interceptor or the `HandlingReport`) as the cause (FR-014).
- [X] T040 [US6] Run `mvn -B -pl saga-forge-test -am verify`; T037 passes.

**Checkpoint**: messages meet SC-004.

---

## Phase 7: User Story 4: test a saga with its command handlers and aggregates (Priority: P2)

**Goal**: registered handlers run as in production; aggregates come from an in-memory store the test seeds and reads;
outbox commands are handled after commit.

**Independent Test**: with `PaymentService` and `Order` registered and an in-stock `o-5` seeded,
`OrderCreatedEvent(o-5)` drives the saga to `COMPLETED` in one call, and the order is `COMPLETED`.

### Tests for User Story 4

- [X] T041 [P] [US4] Extend the test fixtures in `saga-forge-test/src/test/java/io/github/gkosharovdev/sagaforge/test/fixtures/` with:
  - a service command handler that takes a `CommandId` parameter;
  - an `@Aggregate` with instance `@CommandHandler`s that record events via `DomainEvents.record`;
  - a loader/saver host for a second aggregate type;
  - a command type with `@Command(delivery = CommandDelivery.OUTBOX)`.
- [X] T042 [P] [US4] Write `saga-forge-test/src/test/java/io/github/gkosharovdev/sagaforge/test/HandlerModeTest.java` for US4 acceptance scenarios 1–4:
  - registered handlers continue the saga in the same unit of work;
  - a seeded aggregate is loaded by `@AggregateIdentifier` and saved, and `fixture.aggregate(type, id)` reads its new state;
  - a handler that is not registered is recorded and answered by the test;
  - an outbox command is handled after the commit, and its failure does not roll the step back.
  Also cover: a client `@AggregateLoader`/`@AggregateSaver` takes precedence over the store; `givenAggregates` without handler mode throws `IllegalStateException`; `failCommand` also applies to handled commands.

### Implementation for User Story 4

- [X] T043 [P] [US4] Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/internal/AggregateStore.java`: a map keyed by aggregate type and identifier value, read reflectively from the `@AggregateIdentifier` field, per research §7. Writes are staged through `FixtureTransactions` and dropped on rollback.
- [X] T044 [US4] Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/internal/FixtureAggregateResolver.java`, an `AggregateResolver` over `AggregateStore`. `load` returns `Optional`, `save` writes back, and it supports only the registered aggregate types.
- [X] T045 [US4] Add to `SagaFixture.Builder` in `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/SagaFixture.java`:
  - `handlers(Object...)`: registers each object's class and serves those instances through a `HandlerInstanceProvider`, while other classes are constructed by the provider;
  - `handleAggregates()`: runs aggregate handlers without a service host;
  - registration of `FixtureAggregateResolver` when handler mode is on.
  Then:
  - mark in `FixtureCommandInterceptor` which handler hosts are registered (service hosts by class, aggregate handlers when handler mode is on);
  - add `givenAggregates(Object...)` to `SagaFixture` and `Given`, which throws `IllegalStateException` outside handler mode;
  - add `fixture.aggregate(type, id)`.
- [X] T046 [US4] Mark outbox commands as `outbox` in `RecordedCommand` by reading `OutgoingMessages.commands` in `LoopBackTransport` at store time. Make sure the drain delivers them through the `CommandReceiver` after the commit, so their units of work become part of the event under test's report (research §3, §4).
- [X] T047 [US4] Run `mvn -B -pl saga-forge-test -am verify`; T042 passes.

**Checkpoint**: US1 to US4 and US6 work on their own.

---

## Phase 8: FR-019: the durable `SagaContext` value rule (supports US1, SC-007)

**Goal**: a `SagaContext` value that durable storage cannot persist fails the test at the step that wrote it; the
rule is checked against Postgres ([contracts/context-value-rule.md](contracts/context-value-rule.md)).

### Tests

- [X] T048 [P] [US1] Write `saga-forge-test/src/test/java/io/github/gkosharovdev/sagaforge/test/ContextValueRuleTest.java`:
  - a step storing a `BigDecimal` fails `when(...)` with `SagaExpectationError` naming the saga type, the association value, the step, the key and `java.math.BigDecimal`, with the hint text from the contract (SC-007);
  - a nested map or list value names its key path (`items[2].price`);
  - a non-`String` map key, `NaN` and infinity are rejected;
  - every accepted type passes;
  - `checkContextValues(false)` lets the `BigDecimal` through.
- [X] T049 [P] Write `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/DurableContextValueContractTest.java`, tagged `@Tag("container")` and using Testcontainers like the module's other container tests. Use one shared sample set of accepted and rejected values, held in a public `DurableValues.samples()` or in a test-scope sample class. Every accepted value must be saved and read back equal by `PostgresSagaContextStore`, and every rejected value must be rejected. Add `saga-forge-test` as a test-scope dependency in `saga-forge-storage-postgres/pom.xml`.

### Implementation

- [X] T050 [P] [US1] Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/internal/DurableValues.java`: a recursive check that returns the first offending key path and type, per the contract.
- [X] T051 [P] [US1] Create `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/UnsupportedContextValueException.java` (saga type, saga id, key path, value type).
- [X] T052 [US1] Wire the check into `FixtureContextStore.save` in `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/internal/FixtureContextStore.java`. Add `checkContextValues(boolean)` (default `true`) to `SagaFixture.Builder`. In `Given`/`SagaFixture`, recognise `UnsupportedContextValueException` among the unit of work's failures and turn the `given`/`when` call into a `SagaExpectationError` built by `Messages` with the step name (research §8). Do not report it as a `FAILED` saga.
- [X] T053 Run `mvn -B -pl saga-forge-test -am verify`, then `mvn -B -pl saga-forge-storage-postgres -am verify -Pcontainer-tests -Dtest=DurableContextValueContractTest -Dsurefire.failIfNoSpecifiedTests=false`; both pass.

**Checkpoint**: SC-007 is met, and the rule and Postgres are tied by a contract test (G-009-03).

---

## Phase 9: User Story 5: use the fixture in Spring Boot application tests (Priority: P3)

**Goal**: supplied saga and handler instances or factories; no Spring dependency; works next to the starter without a
database or transport.

**Independent Test**: a saga with a constructor-injected `Clock` and a handler with a test-double collaborator, handed
to the fixture, are tested as in US1.

### Tests for User Story 5

- [X] T054 [P] [US5] Write `saga-forge-test/src/test/java/io/github/gkosharovdev/sagaforge/test/SuppliedInstancesTest.java`:
  - a saga with a constructor-only collaborator, supplied with `sagaObjects(type, Supplier)`, gets a fresh object per unit of work (the supplier is counted);
  - a handler instance with a test-double collaborator is used as given;
  - a saga without a no-argument constructor and without a supplier fails `build()` with the runtime's startup report.
- [X] T055 [P] [US5] Write `saga-forge-example-spring/src/test/java/io/github/gkosharovdev/sagaforge/example/spring/OrderSagaFixtureTest.java` (quickstart scenario 9, SC-006). Hand the example's saga and its `PaymentService`, built by hand with a stub `PaymentProvider`, to the fixture, and test the happy path with no `DataSource`, Spring context or transport. Add `saga-forge-test` in test scope to `saga-forge-example-spring/pom.xml`. Check the example-spring package and class names before writing.

### Implementation for User Story 5

- [X] T056 [US5] Add `sagaObjects(Class<T>, Supplier<? extends T>)` to `SagaFixture.Builder` in `saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/SagaFixture.java`, backed by a `SagaObjectProvider` that uses the supplier per unit of work and the no-argument constructor otherwise. Confirm that `saga-forge-test/pom.xml` has no Spring dependency (FR-013).
- [X] T057 [US5] Run `mvn -B -pl saga-forge-test,saga-forge-example-spring -am verify`; T054 and T055 pass.

**Checkpoint**: all user stories work on their own.

---

## Phase 10: Polish and cross-cutting concerns

**Purpose**: parity (FR-017, SC-003), the reference tests (FR-018, SC-002), documentation (Constitution Principle V),
memory and full verification.

### Parity and reference tests

- [X] T058 Write `saga-forge-test/src/test/java/io/github/gkosharovdev/sagaforge/test/FixtureParityTest.java` (research §11). Run every scenario of US1 to US3 twice:
  - through `SagaFixture`;
  - through a `SagaRuntime` assembled by hand over the runtime test-jar doubles (`InMemoryTransactionBoundary`, `TransactionalInMemoryStorageProvider`, `TestTransport` loop-back) with the same clock, ids, failure interceptor and handlers.
  Compare the handling outcomes, the stored instances (status, steps, attempts, failure, compensation, outcome) and the dispatched commands. Report 0 differences (SC-003).
- [X] T059 [P] Write `saga-forge-example/src/test/java/io/github/gkosharovdev/sagaforge/example/OrderProcessingSagaFixtureTest.java` (FR-018, quickstart 1–3). Cover the happy path, the compensation path (`rejectOrder` → `compensatePayment` → `RefundPaymentCommand`, `COMPENSATED`) and a retried payment (`failCommand(ProcessPaymentCommand, transient, 2)` with `PaymentService` registered, three attempts, the same `CommandId`). Each test body is at most 15 lines (SC-001). Assert that the three tests together run in under 1 s, for example by timing them in an `@AfterAll` or with a JUnit `@Timeout` on the class (SC-002). Add `saga-forge-test` in test scope to `saga-forge-example/pom.xml`. Check the example's actual event/command constructors and amount types first.

### Documentation

- [X] T060 [P] Add a "Testing sagas" section to the root `README.md`: the dependency snippet, the quickstart-1 test, record-and-answer versus handler mode, `failCommand`, the fixture clock, the context value check, and a link to `saga-forge-test/README.md`. It shows `associationProperty` and the start/end annotations as AGENTS.md requires. Update the module list and status in the README if it has them.
- [X] T061 [P] Update `docs/architecture.md`: the `saga-forge-test` module (responsibilities, dependencies), the `CommandInterceptor` SPI among the host SPIs, and the status table.
- [X] T062 [P] Update `docs/use-cases.md`. Add a short "Testing the order saga" worked example (happy path, compensation, retry) using the fixture, or link to the example tests if the document's style favours links.
- [X] T063 [P] Update `AGENTS.md`:
  - "Current Status": a 009 bullet covering the module, the interceptor, Property 36, the fixture and the parity suite;
  - "Module Map": `saga-forge-test`;
  - "Known Gaps": the G-009 references;
  - the canonical-example note: the fixture tests in `saga-forge-example`.
- [X] T064 [P] Update the starter's `saga-forge-spring-boot-starter/README.md` with a short "Testing" note pointing to the fixture and the example-spring test (SC-006). The README test (`ReadmePropertiesTest`) only checks properties, so no new property may be documented.
- [X] T065 Update `.specify/memory/memory.md`:
  - confirm G-009-01/02/03;
  - add any decision from T010/T020 (commands without a handler at startup);
  - note that G-ENG-13's harness decision is untouched;
  - add any gap found during implementation (for example the timing of multi-saga reports and an ambiguous subject).

### README polishing

- [X] T066 Polish the README docs as one pass after T060 to T064:
  - Re-read the root `README.md` from top to bottom. The new "Testing sagas" section must sit naturally in the reader's path (after running a saga, before the reference material).
  - Its code must compile against the final API: copy it from `OrderProcessingSagaFixtureTest` rather than retyping it.
  - Terminology must match the docs' vocabulary (Command, Domain Event, Step, Compensation, unit of work).
  - Remove anything the README now states twice. Check every link and anchor.
  - Apply the same checks to `saga-forge-test/README.md` (T070) and the starter README's Testing note.
  - Keep `docs/architecture.md` and AGENTS.md consistent with what the README now says.

### Fixture README and final checks

- [X] T067 [P] Review the public Javadoc of `SagaFixture`, `Given`, `Then`, `EventResult`, `InstanceView`, `RecordedCommand`, `FixtureClock`, `SagaExpectationError`, `UnsupportedContextValueException`, `CommandInterceptor` and `CommandInvocation`. Every public method says what it checks or returns and what it throws. Make sure the package `internal` exports nothing public that users need.
- [X] T068 Run the quickstart scenarios in [quickstart.md](quickstart.md) one by one and tick them off. Fix any drift between the quickstart and the API, in either direction, noting doc changes.
- [X] T069 Run the full build `mvn -B clean verify` and then `mvn -B clean verify -Pcontainer-tests`. All existing suites (runtime, storage, transport, starter, both examples) pass unchanged, along with the new ones. Record the date and test count in AGENTS.md "Current Status" as for 007/008.
- [X] T070 Complete `saga-forge-test/README.md`:
  - coordinates;
  - a 15-line first test;
  - the `Then` methods (a table from [contracts/fixture-api.md](contracts/fixture-api.md));
  - handler mode and the aggregate store;
  - failure rules;
  - time and ids;
  - supplied instances, including the Spring usage pattern;
  - the context value check and how to turn it off;
  - gotchas: G-ENG-24, one event at a time, no transport semantics, committed-attempt commands versus all attempts.

---

## Dependencies and Execution Order

### Phase dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2, interceptor)**: depends on Setup only for T001's module list. T004 to T012 touch only core and the runtime. It blocks every user story.
- **US1 (Phase 3)**: depends on Phase 2. This is the MVP.
- **US2 (Phase 4)**: depends on US1 (T020 interceptor, T023 to T025 fixture/`Then`).
- **US3 (Phase 5)**: depends on US1; independent of US2.
- **US6 (Phase 6)**: depends on US1. Its message checks cover the `Then` methods that exist by then, so run it after US2 and US3 for full SC-004 coverage, or extend `MessagesTest` later.
- **US4 (Phase 7)**: depends on US1; independent of US2, US3 and US6. Its `failCommand` case needs US2's T029.
- **FR-019 (Phase 8)**: depends on US1 (T018, T022, T024); independent of US2 to US4.
- **US5 (Phase 9)**: depends on US1; T055 uses handler mode, so it needs US4.
- **Polish (Phase 10)**: T058 needs US1 to US3; T059 needs US2 and US4; the docs need the final API; T066 needs T060 to T064 and T070; T069 comes last.

### Within each story

Fixtures and tests first (they fail), then implementation, then the module build. Within the fixture, `SagaFixture`,
`Given` and `Then` are edited by several stories in turn, so tasks on those files are sequential across phases.

### Parallel opportunities

- Phase 1: T002 and T003.
- Phase 2: T004, T005, T006 and T007 together; then T008 → T009 → T010 one after another; T011 alongside them.
- Phase 3: T013, T014, T015, T016, T017, T018 and T019 together (separate files); then T020 to T025 in sequence.
- Phase 8: T048, T049, T050 and T051 together.
- Phase 9: T054 and T055 together.
- Phase 10: T059 to T064 and T067 together; T066 after them.
- Across stories, once US1 is done: US2, US3, US4 and Phase 8 can be worked by different people, but they serialise on `SagaFixture.java` and `Then.java`.

## Parallel Example: User Story 1

```text
Task: "T013 Create test sagas in saga-forge-test/src/test/java/.../test/fixtures/"
Task: "T014 Write FixtureApiTest.java"
Task: "T015 Create FixtureClock.java"
Task: "T016 Create internal/FixtureTransactions.java"
Task: "T017 Create internal/FixtureStorage.java"
Task: "T018 Create internal/FixtureContextStore.java"
Task: "T019 Create internal/LoopBackTransport.java"
```

## Implementation Strategy

### MVP first (User Story 1 only)

1. Phase 1, Setup.
2. Phase 2, the interceptor. The runtime's suites must stay green.
3. Phase 3, US1.
4. **Stop and validate**: write the quickstart-1 test by hand against the fixture.

### Incremental delivery

1. Setup and the interceptor, then US1 (MVP: record-and-answer tests).
2. US2: failures, retries without waiting, compensation.
3. US3 and US6: correlation, and messages that explain.
4. US4 and FR-019: handler mode, the aggregate store, the durable-value rule with its Postgres contract.
5. US5: supplied instances and the Spring example test.
6. Polish: parity suite, reference tests, docs, the README polishing pass, then the full build with container tests.

## Implementation notes (2026-11-12)

- **Package layout**: the fixture's in-memory plug-ins (`FixtureTransactions`, `FixtureStorage`,
  `FixtureContextStore`, `AggregateStore` (also the `AggregateResolver`, so no separate `FixtureAggregateResolver`),
  `LoopBackTransport`, `FixtureCommandInterceptor`, `CommandFailureRule`, `FixtureObjects`, `FixtureLogger`, `Model`,
  `Messages`) are package-private in `io.github.gkosharovdev.sagaforge.test`, not public classes in an `internal`
  package, so users see only the API (T067).
- **Value rule**: `DurableValues` became the public `DurableContextValues` (`isDurable`, `SUPPORTED`) so the Postgres
  contract test can call it. `DurableContextValueContractTest` checks the rule against `JsonCodec`, the codec
  `PostgresSagaContextStore` writes with, in the default build rather than as a container test (T049, T053).
- **T010**: the startup checks have no rule against a command without a handler; only R23 rejects outbox commands that
  nobody handles, which the fixture documents as G-009-04.
- **T020/T046**: outbox commands are recorded when delivered, not read from `OutgoingMessages.commands` at store time
  (G-009-07). Unexpected exceptions are attached as causes from the runtime's logged diagnostics (`FixtureLogger`).
- **T059**: the canonical saga now stores its amount as a `String` (`toPlainString`), like the Spring example, so its
  fixture tests run with the value check on; `docs/use-cases.md` and `OrderProcessingSagaRuntimeTest` follow.
- New gaps G-009-04 to G-009-07 in `.specify/memory/memory.md`.

## Notes

- `[P]` means different files and no dependency on an unfinished task.
- Record any gap found during implementation in `.specify/memory/memory.md` with a G-009-NN ID; don't fix it silently.
- Commit after each phase checkpoint.
