---
description: "Task list for Saga Units of Work, Command Gateway, and Command Handlers"
---

# Tasks: Saga Units of Work, Command Gateway, and Command Handlers

**Input**: Design documents from `/specs/002-transactional-step-sequence/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: Included. plan.md's Testing section scopes JUnit 5 + AssertJ unit tests, jqwik properties, and
harness scenario tests mapped to the spec's acceptance scenarios (quickstart.md). Write each story's tests
first and confirm they fail before implementing.

**Organization**: Tasks are grouped by user story (spec.md: US1 P1, US2 P1, US3 P2, US4 P3).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 / US2 / US3 / US4, per spec.md
- All paths are relative to the repository root

## Path Conventions

- Core main: `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/`
- Core test: `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/`
- Example: `saga-forge-example/src/{main,test}/java/io/github/gkosharovdev/sagaforge/example/`
- No new Maven module and no new dependency (plan.md Structure Decision, Constitution Principle I).

---

## Phase 1: Setup

- [x] T001 Create the new main packages `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/command/`
  and `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/event/`, each with a `package-info.java`
  Javadoc (command gateway contracts; domain event recording and publishing), and the test packages
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/execution/harness/` and
  `.../core/execution/fixtures/`. Do not modify any `pom.xml`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The contract types, the breaking changes to existing types, and their migration. Every user story
depends on these. After this phase `mvn -B test` must be green.

- [x] T002 [P] Create the annotations `CommandHandler` (`@Target({METHOD, CONSTRUCTOR})`), `Aggregate` (TYPE),
  `AggregateIdentifier` (FIELD), `AggregateLoader` (METHOD), and `AggregateSaver` (METHOD), all `RUNTIME` and
  `@Documented`, with Javadoc per data-model.md, in
  `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/annotation/`. Add metadata tests for each
  (target, retention, attributes) to
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/annotation/AnnotationMetadataTest.java`.
- [x] T003 Add the required attribute `Class<?> aggregate()` to
  `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/annotation/DomainEvent.java`, and update the
  Javadoc of `.../core/annotation/SagaStep.java` (a step returns `void` and dispatches commands through
  `CommandGateway`) and `.../core/annotation/Command.java` (commands are dispatched, not returned). Update the
  `@DomainEvent` case in `AnnotationMetadataTest.java` (it currently asserts no attributes). Depends on: T002.
- [x] T004 Migrate every existing core test `@DomainEvent` to the new attribute: add a fixture aggregate
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/scanner/fixtures/TestAggregate.java`
  (`@Aggregate`, one `@AggregateIdentifier` field) and give `TestEvent`, `TestEvent2`, `TestEvent3` in
  `.../core/scanner/fixtures/` `@DomainEvent(aggregate = TestAggregate.class)` plus one `@AggregateIdentifier`
  field. Depends on: T002, T003.
- [x] T005 Keep the example module compiling: add a placeholder `@Aggregate` class
  `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/Order.java` and give the events in
  `.../example/events/` `@DomainEvent(aggregate = Order.class)` plus an `@AggregateIdentifier` on `orderId`. The
  full rewrite is T031. Depends on: T002, T003.
- [x] T006 [P] Create `CommandGateway` (`void send(Object command)`, Javadoc per contracts/command-gateway.md),
  `NoCommandHandlerException` (exposes the command type), and `AggregateNotFoundException` (exposes aggregate type
  and id), unchecked, in `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/command/`.
- [x] T007 [P] Create the recording facade and its binding SPI per contracts/event-publisher.md:
  `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/event/DomainEvents.java` (`record`,
  `capture`), `.../core/event/EventPublisher.java` (interface), `.../core/spi/DomainEventRecorder.java`, and
  `.../core/spi/DomainEventScope.java` (`runWith`, `ThreadLocal`, restores the outer binding even on exceptions).
  Test it in `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/event/DomainEventsTest.java`:
  `IllegalStateException` with no binding, `IllegalArgumentException` for `null` and non-`@DomainEvent`, `capture`
  order, nested bindings, and no leak into another thread.
- [x] T008 [P] Create the records and enum `StepStatus`, `StepRecord`, `AggregateReference`, and `SagaFailure` per
  data-model.md, with defensive copies and null checks, in
  `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/spi/`, and their tests in
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/spi/StepRecordsTest.java`.
- [x] T009 Add the `steps`, `outcome`, and `failure` components to
  `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/spi/SagaInstance.java` with the invariants
  in data-model.md (`outcome` non-null iff `COMPLETED`, `failure` non-null iff `FAILED`). Update `SagaInstanceTest.java`
  in `.../core/spi/` and the constructor calls in
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/lifecycle/fixtures/InMemorySagaLifecycle.java`.
  Depends on: T008.
- [x] T010 Apply contracts/saga-lifecycle.md to
  `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/lifecycle/SagaLifecycle.java`: `start`
  initializes every step `NOT_RUN`; new `recordStepCompleted(sagaId, stepName, touchedAggregates)`; `advance`
  Javadoc narrowed to wait points; `complete(sagaId, outcomeStep)` replaces `complete(sagaId)`; new
  `fail(sagaId, failure)`. Update `.../core/lifecycle/fixtures/InMemorySagaLifecycle.java` and
  `.../core/lifecycle/SagaLifecycleTest.java` (new operations, unknown-step and terminal rejections).
  Depends on: T009.
- [x] T011 [P] Create `SagaContextRecord` and `SagaContextStore` per contracts/saga-context-store.md in
  `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/spi/`, with a record test in
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/spi/SagaContextRecordTest.java`. Update the "Representation" section of
  `.../core/lifecycle/SagaContext.java` and `.../core/lifecycle/package-info.java`: values live in a separate
  `SagaContextRecord`, not in `SagaInstance.state`.
- [x] T012 [P] Create `AggregateResolver` and `HandlerInstanceProvider` per data-model.md (host-implemented, never
  client-implemented) in `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/spi/`.
- [x] T013 Create `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/scanner/ValidationReport.java`
  and add `ValidationReport validateAll(Collection<Class<?>> classes)` to `.../core/scanner/AnnotationScanner.java`.
  Implement it in `.../core/scanner/ReflectiveAnnotationScanner.java` as a skeleton that runs `validate` for every
  `@Saga` class (new rules come in T019 and T024), with tests in
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/scanner/ValidationReportTest.java`.

**Checkpoint**: `mvn -B test` passes at the root; every contract type exists; user stories can start.

---

## Phase 3: User Story 1 - Event-triggered steps run in one saga-scoped unit of work (Priority: P1) 🎯 MVP

**Goal**: Steps run when their events are raised, never in declaration order, and every step triggered inside one
unit of work commits together at an `@EndSaga` step or rolls back in full (FR-001 to FR-006, FR-017 to FR-024).

**Independent Test**: A three-step saga whose events are all raised in-process, with service-hosted handlers
changing three aggregates, runs against `SagaTestHarness`: the happy path commits everything together, and a
failure at each step position leaves no aggregate change (spec US1, SC-002).

### Tests for User Story 1

- [x] T014 [P] [US1] Add invalid saga fixtures `StepReturningValueSaga`, `DuplicateEventTypeSaga`,
  `SagaWithCommandHandler`, and `MultipleCommandGatewayFieldSaga` in
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/scanner/fixtures/`, and a nested test class in
  `.../core/scanner/ReflectiveAnnotationScannerTest.java` asserting each error with the exact message from
  contracts/validation.md, plus `ValidSaga` and `SagaWithContextFieldSaga` still valid.
- [x] T015 [P] [US1] Add the in-process reference model in
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/execution/fixtures/inprocess/`: `ThreeStepSaga`
  (steps declared in a different order from their events; one `@EndSaga`; writes and reads `SagaContext`), its events
  and commands with `@AggregateIdentifier`, three marker aggregates, and service-hosted handlers that record the
  next event with `DomainEvents.record` and can be told to fail.
- [x] T016 [P] [US1] Write
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/execution/SagaUnitOfWorkTest.java` covering
  spec US1 scenarios 1 to 5: commit together with outcome and all steps `COMPLETED`; failure at step 2 and at the
  `@EndSaga` step rolls back every aggregate change and leaves `FAILED` with the step and cause; context visible to a
  later step; event order, not declaration order. Depends on: T015.
- [x] T017 [P] [US1] Write the jqwik property test
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/execution/SagaUnitOfWorkPropertyTest.java`:
  for a failure injected at any step position, zero aggregate changes remain, the saga is `FAILED`, and every
  `SagaContext` value written before the failure is still readable (SC-002). Depends on: T015.

### Implementation for User Story 1

- [x] T018 [US1] Create the harness stores in
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/execution/harness/`:
  `InMemoryStorageProvider` and an in-memory aggregate state store, both with snapshot and restore;
  `InMemorySagaContextStore`, never snapshotted; `RecordingEventDispatcher`, recording after-commit dispatches; and
  `MapHandlerInstanceProvider`.
- [x] T019 [US1] Implement the saga-class rules in
  `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/scanner/ReflectiveAnnotationScanner.java`
  (`validate` and therefore `validateAll`): `void` steps, unique event types per saga, no `@CommandHandler` on a
  saga, at most one `CommandGateway` field, with the messages from contracts/validation.md. Depends on: T013, T014.
- [x] T020 [US1] Implement the unit-of-work engine in
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/execution/harness/SagaTestHarness.java` (plus
  `HarnessCommandGateway.java` and `HarnessUnitOfWork.java` in the same package), following
  contracts/saga-unit-of-work.md:
  - start the saga on its `@StartSaga` event;
  - bind a recorder through `DomainEventScope.runWith`;
  - populate the `SagaContext` field (write-through to `InMemorySagaContextStore`) and the `CommandGateway` field;
  - route commands to service-hosted handlers through `HandlerInstanceProvider`;
  - run steps triggered in-process, in recording order;
  - call `recordStepCompleted` with each step's touched aggregates;
  - commit at the first `@EndSaga` step or at a wait point;
  - on any failure, including one a step caught, roll back and call `fail` with a `SagaFailure`;
  - dispatch buffered events only after commit.
  Depends on: T006, T007, T010, T011, T012, T018.

**Checkpoint**: T016 and T017 pass; a saga that finishes in one unit of work is fully specified and proven.

---

## Phase 4: User Story 2 - Steps dispatch commands through a command gateway; handlers emit events (Priority: P1)

**Goal**: Commands reach the single `@CommandHandler` for their type, on a service or on an `@Aggregate` that client
code loads and saves; events are recorded with `DomainEvents.record` or published through `EventPublisher`; all
declaration rules for handlers, loaders, savers, and identifiers are validated in one pass (FR-007 to FR-017).

**Independent Test**: `validateAll` over correct and incorrect handler, aggregate, event, and command fixtures
reports every rule precisely; one step run against the harness with an aggregate-hosted handler (plain class,
`@AggregateLoader`) and with a service-hosted handler triggers the next step in both cases (spec US2).

### Tests for User Story 2

- [x] T021 [P] [US2] Add fixtures in
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/scanner/fixtures/handlers/` and write
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/scanner/ValidateAllTest.java`, asserting every
  message in contracts/validation.md:
  - handlers with zero or two `@Command` parameters, and two handlers for one command;
  - an event or aggregate missing, or with two, `@AggregateIdentifier`s, and a command with two;
  - an aggregate instance-handler command without an identifier, and an event whose `aggregate` is not an
    `@Aggregate`;
  - malformed loader and saver signatures, and duplicate loaders or savers;
  - one fully valid model whose `@Aggregate` extends and implements no Saga Forge type.
- [x] T022 [P] [US2] Add the booking model in
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/execution/fixtures/booking/`:
  - `Inventory` (plain `@Aggregate`, instance-method handler, records `AvailabilityClaimedEvent`);
  - `InventoryRepository` (`@AggregateLoader` / `@AggregateSaver`, counts saves);
  - `Booking` (`@Aggregate` created by a constructor `@CommandHandler`, records `BookingConfirmedEvent`);
  - `Payment` (marker `@Aggregate`) and `PaymentService` (service handler recording `PaymentInitiatedEvent`);
  - every event and command of the reference saga, each with its `@AggregateIdentifier`.
- [x] T023 [P] [US2] Write
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/execution/CommandRoutingTest.java` covering spec
  US2 scenarios 6 to 11 and 13:
  - an aggregate-hosted handler loaded by `@AggregateLoader` and saved once by `@AggregateSaver`;
  - a creation handler whose new aggregate is saved;
  - a missing aggregate failing the unit of work;
  - a service-hosted handler's `DomainEvents.record` triggering the next step;
  - `EventPublisher` outside a unit of work starting a saga;
  - no handler, and a handler failure caught by the step, both rolling back;
  - each completed step's touched `(aggregate type, id)` list (FR-004).
  Depends on: T022.

### Implementation for User Story 2

- [x] T024 [US2] Implement the handler, identifier, loader, saver, and model rules of contracts/validation.md in
  `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/scanner/ReflectiveAnnotationScanner.java`
  `validateAll`, including reaching event and command types from step, handler, loader, and saver signatures.
  Depends on: T013, T021.
- [x] T025 [US2] Extend
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/execution/harness/HarnessCommandGateway.java` per
  contracts/command-gateway.md:
  - **Instance-method handlers**: load by the command's `@AggregateIdentifier`, with the client `@AggregateLoader`
    taking precedence over an in-memory `AggregateResolver` (`InMemoryAggregateResolver.java` in the harness
    package); save through `@AggregateSaver`, then the resolver, then nothing.
  - **Creation handlers**: constructors and static factories.
  - **Errors**: `AggregateNotFoundException` and `NoCommandHandlerException`.
  Depends on: T020.
- [x] T026 [US2] Add `HarnessEventPublisher.java` in
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/execution/harness/`: same as
  `DomainEvents.record` inside a unit of work, and an immediate `RecordingEventDispatcher` dispatch, which starts a
  saga if it matches, outside one. Depends on: T020.

**Checkpoint**: T021 and T023 pass; US1 tests still pass.

---

## Phase 5: User Story 3 - Long-running sagas wait between units of work and reach one of several outcomes (Priority: P2)

**Goal**: A unit of work commits at a wait point, a later transport event resumes the saga from persisted state
alone, the saga reaches one of several `@EndSaga` outcomes, and `SagaContext` survives rollbacks (FR-003 to
FR-005, FR-018 to FR-024).

**Independent Test**: `CreateBookingSaga` commits its first unit of work, resumes in a fresh harness from
`persistedState()` with *payment confirmed* and, separately, *payment declined*, and keeps context and committed
effects when the second unit of work fails (spec US3, SC-003, SC-004).

### Tests for User Story 3

- [x] T027 [P] [US3] Add
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/execution/fixtures/booking/CreateBookingSaga.java`
  matching the spec's reference saga: `@StartSaga` `onBookingRequested`, `onAvailabilityClaimed`,
  `onPaymentConfirmed`, and the two `@EndSaga` outcomes `onBookingConfirmed` and `onPaymentDeclined`, with
  `SagaContext` and `CommandGateway` fields and every `associationProperty` set to `bookingId`. Depends on: T022.
- [x] T028 [P] [US3] Write
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/execution/LongRunningSagaTest.java`
  covering spec US3 scenarios 1 to 6:
  - wait-point commit, with `RUNNING`, two steps `COMPLETED`, and *payment initiated* dispatched after commit;
  - resume in a fresh harness for both outcomes, with untaken steps `NOT_RUN` (SC-003, SC-004);
  - a failing second unit of work keeping the first one's effects;
  - context written in a rolled-back unit of work still readable;
  - an event for a `COMPLETED` saga, and one for no instance, reported with no effect.
  Depends on: T027.
- [x] T029 [P] [US3] Write the jqwik property test
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/execution/LongRunningSagaPropertyTest.java`:
  for any generated sequence of confirmed or declined outcomes and injected second-unit failures, exactly one
  outcome or `FAILED` results, untaken outcome steps stay `NOT_RUN`, and context values from every unit of work
  remain readable. Depends on: T027.

### Implementation for User Story 3

- [x] T030 [US3] Extend
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/execution/harness/SagaTestHarness.java`:
  - resolve non-start steps through `StorageProvider.findByAssociation`;
  - report unmatched and unexpected events (exposed through a harness event log) with no effect;
  - run one unit of work per instance at a time;
  - add `persistedState()` / `fromPersistedState(...)` so a fresh harness resumes from stored instances, context
    records, and aggregate state only.
  Depends on: T020, T025.

**Checkpoint**: T028 and T029 pass; US1 and US2 tests still pass.

---

## Phase 6: User Story 4 - Documentation and canonical examples show the real model (Priority: P3)

**Goal**: No example teaches steps that return commands; every example shows event-triggered steps, the command
gateway, `@CommandHandler`s, `DomainEvents.record`, `@AggregateIdentifier`, and `@EndSaga` outcomes (FR-027).

**Independent Test**: The example module passes `validateAll`, and the SC-005 search finds no step returning a
command (spec US4).

- [x] T031 [US4] Rewrite the example module:
  - `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/OrderProcessingSaga.java`: `void`
    steps, `CommandGateway` and `SagaContext` fields, `associationProperty` and `@StartSaga`/`@EndSaga` shown;
  - `Order.java`: `@Aggregate` with `@AggregateIdentifier`, an instance-method `@CommandHandler`, and
    `DomainEvents.record`;
  - one service-hosted handler, plus the commands with `@AggregateIdentifier` in `.../example/commands/`;
  - `saga-forge-example/src/test/java/io/github/gkosharovdev/sagaforge/example/OrderProcessingSagaValidationTest.java`
    switched to `validateAll`.
  Depends on: T019, T024.
- [x] T032 [P] [US4] Rewrite the pitch paragraph and the `CreateBookingSaga` example in `README.md` to the new model:
  steps dispatch through the command gateway, and handlers on an `@Aggregate` record events. Show both outcomes.
- [x] T033 [P] [US4] Rewrite examples 1 and 2 in `docs/use-cases.md` to the new model, and rename the "Emits" column
  of the deferred-booking tables to "Dispatches".
- [x] T034 [P] [US4] Update `docs/architecture.md`:
  - **Vocabulary**: Command (dispatched through the command gateway, not returned), Step, Aggregate, Domain Event
    (`aggregate`, `@AggregateIdentifier`, `DomainEvents.record`), plus new entries for Command gateway and
    Saga-scoped unit of work.
  - **Status table**: the new contracts.
  - **"Queryable process-level state" section**: values live in a separate `SagaContextRecord`.
  - **Transaction-boundary feature gap**: points to contracts/saga-unit-of-work.md.
- [x] T035 [P] [US4] Update `AGENTS.md`:
  - **Saga Model bullets**: `void` steps, the `CommandGateway` field, `@CommandHandler` hosts,
    `DomainEvents.record`, `@AggregateIdentifier`, `@DomainEvent(aggregate = ...)`, and several `@EndSaga`
    outcomes.
  - **Current Status**: the contracts added by this feature.
  - **Known Gaps**: the deferred items from plan.md.
- [x] T036 [P] [US4] Update Scenario A and Scenario B in `specs/001-core-lifecycle-context/quickstart.md` to the new
  model (`void` steps dispatching through `CommandGateway`, `complete(sagaId, outcomeStep)`), with a note that feature
  002 supersedes the step shape.
- [x] T037 [P] [US4] In `.kiro/specs/saga-forge-framework/requirements.md`, mark Requirement 8.4, Requirement 11, and
  the "marker annotation with no attributes" criterion for `@DomainEvent` as "Superseded by
  specs/002-transactional-step-sequence".
- [x] T038 [US4] Close the Constitution Principle III gate in `.kiro/specs/saga-forge-framework/design.md`:
  - add one correctness property per new rule in contracts/validation.md, in the format of Property 1 and Property 9;
  - add the matching rows to the Error Handling table;
  - correct the `@Command` / `@DomainEvent` sections and the "event-to-command flow" text;
  - confirm Property 6 still holds for valid sagas under the new rules.
  Depends on: T019, T024.
- [x] T039 [P] [US4] Amend `.specify/memory/constitution.md` to 1.0.1 (PATCH): replace the stale Technical Constraints
  line "`SagaContext` tracks step history" with the current meaning (named business state in a separate record),
  add the new annotations to Principle II's list, and update the Sync Impact Report and version line.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T040 [P] Update the Javadoc of
  `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/spi/EventDispatcher.java`: it is also the
  after-commit delivery path for recorded events (research §5), and the out-of-scope RabbitMQ module reference is
  dropped (Constitution Principle IV).
- [x] T041 Run `mvn -B test` at the root and confirm every existing and new test passes with zero regressions.
- [x] T042 Run the SC-005 search (quickstart scenario 11) over `README.md`, `docs/`, `specs/`, `AGENTS.md`, and the
  example module for step or handler examples that return a command, and fix any match. The three superseded
  planning files `docs/Saga_*.md` are excluded and get a one-line "superseded" banner instead.
- [x] T043 [P] Diff `saga-forge-core/pom.xml`, `saga-forge-example/pom.xml`, and the root `pom.xml` against their
  pre-feature state and confirm that no dependency was added (Constitution Principle I).
- [x] T044 Walk `specs/002-transactional-step-sequence/quickstart.md` scenarios 1 to 15 against the delivered code
  and update quickstart.md or contracts/ where signatures or messages drifted.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: depends on Phase 1 and blocks every story. T003 → T004/T005 restore a green build
  after the breaking `@DomainEvent` change; T008 → T009 → T010 is the `SagaInstance`/`SagaLifecycle` chain.
- **US1 (Phase 3)**: depends on Phase 2. Uses service-hosted handlers only, so it does not need US2.
- **US2 (Phase 4)**: depends on Phase 2 for validation (T021, T024) and on T020 (US1's harness engine) for the
  routing tests and harness extensions (T023, T025, T026).
- **US3 (Phase 5)**: depends on US1 (T020) and US2 (T022, T025).
- **US4 (Phase 6)**: depends on US1 and US2 code being final (T019, T024). T031 and T038 depend on them directly;
  T032 to T037 and T039 are documentation and can start once those land.
- **Polish (Phase 7)**: depends on all stories.

### Within Each User Story

- Fixtures and tests first, and confirm they fail; then scanner rules; then harness behavior.
- US1: T014 → T019; T015 → T016/T017; T018 → T020.
- US2: T021 → T024; T022 → T023; T020 → T025 → T023 passes; T020 → T026.
- US3: T022 → T027 → T028/T029; T020 + T025 → T030.

### Parallel Opportunities

- Phase 2: T002, T006, T007, T008, T011, T012, and T013 touch different files and can run together after T001.
- US1: T014, T015, and T018 together; then T016 and T017 together.
- US2: T021 and T022 together; T024 can proceed in parallel with T025 and T026 (scanner vs. harness).
- US3: T028 and T029 together after T027.
- US4: T032 to T037 and T039 together.

## Parallel Example: User Story 1

```bash
Task: "Add invalid saga fixtures and scanner tests (T014)"
Task: "Add the in-process reference model ThreeStepSaga (T015)"
Task: "Create the harness in-memory stores (T018)"
# then
Task: "Write SagaUnitOfWorkTest (T016)"
Task: "Write SagaUnitOfWorkPropertyTest (T017)"
```

## Implementation Strategy

### MVP Scope

US1 and US2 are both P1 and together form the MVP: US1 proves the unit-of-work semantics with service-hosted
handlers, and US2 adds aggregate-hosted handlers with client-owned persistence and full validation. Stop after
Phase 4 and validate both checkpoints before starting US3.

### Incremental Delivery

1. Phases 1 and 2: contracts exist and the build is green again after the breaking changes.
2. US1: single-unit-of-work sagas proven (SC-002).
3. US2: commands, handlers, loaders, identifiers, and events proven; validation complete (SC-001).
4. US3: waits, resume, and outcomes proven (SC-003, SC-004).
5. US4 and Polish: docs, example, `.kiro` specs, and constitution aligned (SC-005, SC-006); Principle III gate
   closed by T038.

## Notes

- `SagaTestHarness` and all `InMemory*` / `Harness*` types are test-only scaffolding; none moves to `src/main`.
  The production engine, the Spring Data `AggregateResolver` adapter, and the storage implementations of
  `SagaContextStore` are follow-up features (plan.md Post-Phase 1 Re-Check).
- The breaking changes (the required `@DomainEvent.aggregate`, `SagaInstance` components, and
  `SagaLifecycle.complete(sagaId, outcomeStep)`) are accepted pre-1.0 (plan.md Constraints).
- Commit after each task or logical group; stop at any checkpoint to validate.
