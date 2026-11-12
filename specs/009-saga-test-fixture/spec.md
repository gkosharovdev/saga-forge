# Feature Specification: Saga Test Fixture

**Feature Branch**: `009-saga-test-fixture`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "009 saga test fixture"

## Context

Saga Forge applications can run their sagas: the runtime, Postgres storage, the in-memory transport and the Spring
Boot starter all work. What they cannot do easily is **test** a saga. Today an application developer has two options,
neither good:

- Start the whole application with a database container and drive it end to end: slow, and a failing assertion says
  little about which step went wrong.
- Assemble a `SagaRuntime` by hand over the runtime's test doubles, which ship only in the runtime's test sources, are
  undocumented, and expose internals.

Saga Forge's own repository has a test-only `SagaTestHarness` (feature 002) that proves execution semantics. It is not
published, it is not the runtime that ships (G-ENG-13), and its API was built for the framework's correctness
properties rather than for application tests.

This feature adds a published saga test fixture: a small, framework-neutral library an application adds in test
scope. A developer describes the history a saga has already seen, the event that arrives next, and what the saga must
do in response: the commands it dispatches, the business state it keeps, the step it completes, and how its instance
ends. The fixture runs the real saga semantics — routing by association, the unit of work, retries, compensation —
in memory, with no database, transport or Spring context, and reports a failed expectation in the saga's own
vocabulary.

Running example: the canonical `OrderProcessingSaga` (`handleOrderCreated` → `processPayment` → `completeOrder`, or
`rejectOrder` on `StockUnavailableEvent` followed by the `compensatePayment` refund), with `PaymentService`, `Order` and
`OrderRepository`.

## Clarifications

### Session 2026-11-12

- Q: Does the fixture also run the application's real command handlers and aggregates? → A: Both modes. By default a
  dispatched command is recorded and the test answers it with events; a test that registers handlers gets them run as
  in production, with aggregates in an in-memory store the test seeds and reads.
- Q: How far does Spring support go? → A: None beyond handing in instances: a test passes the saga and handler objects
  it wants used, and may take them from a Spring context itself. A Spring helper that builds the fixture from an
  application context, and a test slice, are deferred (G-009-01).
- Q: Should the fixture reject `SagaContext` values the durable stores cannot persist? → A: Yes, by default: a value
  outside what every supported durable store can persist fails the test, naming the key and the value's type; a
  fixture setting turns the check off.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Test how a saga reacts to an event (Priority: P1)

A developer writes a unit test for one step of a saga. They state which events the saga has already handled, publish
the next event, and state what must follow: which commands the saga dispatches (and with what content), which
`SagaContext` values it holds, which step completed, and whether the instance is still running, waiting, or has ended
with a named outcome. The test runs in milliseconds with no infrastructure.

**Why this priority**: this is the everyday test of saga logic and the reason the fixture exists.

**Independent Test**: a test over the canonical saga, given `OrderCreatedEvent` for order `o-1`, when
`PaymentProcessedEvent` for `o-1` arrives, expects `ReserveStockCommand(o-1)` dispatched, `paymentId` stored in the
saga's context, step `processPayment` completed and the instance still running.

**Acceptance Scenarios**:

1. **Given** a saga with no prior history, **When** its start event is published, **Then** the fixture reports a new
   instance associated by the start step's association property, the commands the step dispatched, and the instance
   status.
2. **Given** prior events for the same association value, **When** the next event is published, **Then** it is
   routed to the existing instance, and only the commands dispatched in response to that event are compared with the
   expected ones; earlier commands do not count.
3. **Given** an expectation of exactly these commands, in order, **When** the saga dispatches a different set, a
   different order, or a command with different content, **Then** the test fails, showing the expected and the actual
   commands.
4. **Given** an expectation that no command is dispatched, **When** the saga dispatches one, **Then** the test fails
   naming it.
5. **Given** an `@EndSaga` step, **When** its event arrives, **Then** the fixture reports the instance ended with that
   step as its outcome.
6. **Given** an expectation on a `SagaContext` value (present with a value, or absent), **When** the saga's state
   differs, **Then** the test fails naming the key, the expected and the actual value.

---

### User Story 2 - Test failure, retry and compensation paths (Priority: P1)

A developer tests what happens when things go wrong: a business failure (`@StartCompensation`) runs the compensations
of the steps that completed, most recently completed first, with the commands each one sends; a transient failure is
retried under the step's retry policy without the test actually waiting; a final failure or exhausted retries
compensate; an unclassified failure ends the saga failed; a failing compensation ends it requiring intervention.

**Why this priority**: compensation and retry are the hardest saga behaviour to get right and the hardest to test end
to end; teams need to prove them before production.

**Independent Test**: given `OrderCreatedEvent` and `PaymentProcessedEvent` for `o-2`, when `StockUnavailableEvent` for
`o-2` arrives, expect `compensatePayment` to run and dispatch `RefundPaymentCommand` with the stored payment id and
amount, and the instance to end `COMPENSATED` with trigger `rejectOrder`.

**Acceptance Scenarios**:

1. **Given** completed steps, **When** a `@StartCompensation` step's event arrives, **Then** the fixture reports the
   compensations that ran, in order, each with the commands it dispatched, and the instance ends `COMPENSATED` with the
   triggering step recorded.
2. **Given** a step with a retry policy and a command handling that fails transiently twice and then succeeds, **When**
   the step's event arrives, **Then** the step completes after three attempts, the fixture reports the attempt count,
   and the test does not wait for the policy's backoff in real time.
3. **Given** a transient failure that outlasts the retry policy, **When** the step's event arrives, **Then** the
   failure is reported as exhausted and the completed steps are compensated.
4. **Given** an unclassified failure in a step, **When** its event arrives, **Then** the instance ends `FAILED` with
   the step, the failure type and message, and nothing is compensated.
5. **Given** a compensation that throws, **When** compensation runs, **Then** the instance ends
   `REQUIRES_INTERVENTION`, and the fixture shows which compensations succeeded and which failed.
6. **Given** a failure the developer wants to provoke, **When** they tell the fixture that a given command (by type,
   optionally by content) fails with a given exception, once or a number of times, **Then** that command fails as
   specified and the saga reacts as it would in production.

---

### User Story 3 - Test correlation and duplicates (Priority: P2)

A developer tests that events reach the right instances: events with different association values reach different
instances; an event no step accepts for an existing association does nothing; an event that would start a second
instance for the same association is reported; a redelivered event of an already completed step is recognised as a
duplicate and runs nothing.

**Why this priority**: correlation bugs cause silent data loss in production but are rarely tested, because the
association lives in annotations.

**Independent Test**: two orders `o-3` and `o-4` started; `PaymentProcessedEvent` for `o-4` advances only `o-4`'s
instance; publishing the same event again is reported as a duplicate with no new command.

**Acceptance Scenarios**:

1. **Given** two instances with different association values, **When** an event for one of them arrives, **Then**
   only that instance advances, and the fixture can report on each instance by its association value.
2. **Given** an event no saga step accepts, **When** it is published, **Then** the fixture reports that no instance
   handled it, and an expectation that some instance handles it fails.
3. **Given** a step that already completed, **When** its event is published again, **Then** the fixture reports a
   duplicate and no command is dispatched.
4. **Given** a start event for an association value that already has an instance, **When** it is published, **Then**
   the fixture reports a duplicate start, as the runtime would.

---

### User Story 4 - Test a saga together with its command handlers and aggregates (Priority: P2)

By default the fixture records every dispatched command and the test answers it (User Story 1). When the developer
registers handlers, a dispatched
command is handled by the real `@CommandHandler` (on a service or an aggregate loaded from an in-memory store the test
seeds), the events it records drive the saga's next steps within the same unit of work, and the test asserts on the
whole flow and on the aggregates' resulting state.

**Why this priority**: testing a saga without its handlers proves its logic; testing it with them proves the
collaboration, which is where wiring and identifier bugs hide.

**Independent Test**: with `PaymentService` and `Order` registered and an in-stock order `o-5` seeded, publishing
`OrderCreatedEvent(o-5)` drives the saga to `COMPLETED` in one call, and the seeded order is `COMPLETED`.

**Acceptance Scenarios**:

1. **Given** registered handlers, **When** a step dispatches a command, **Then** the handler runs and the events it
   records continue the saga in the same unit of work, as in production.
2. **Given** an aggregate seeded in the fixture's store, **When** a command for it is handled, **Then** the aggregate
   is loaded by the command's identifier and saved; the test can read its resulting state.
3. **Given** a command whose handler is not registered, **When** it is dispatched, **Then** it is recorded for
   assertion and the test can answer it by publishing the resulting event itself.
4. **Given** a command with outbox delivery, **When** it is dispatched, **Then** it is handled after the sending unit
   of work committed, and its failure does not roll the step back, as in production.

---

### User Story 5 - Use the fixture in a Spring Boot application's tests (Priority: P3)

A developer whose sagas and handlers are Spring beans with injected collaborators writes fixture tests that hand the
fixture those objects — taken from the application context or built with test doubles — without a database or
transport. The fixture itself knows nothing about Spring; a helper that builds it from an application context is
deferred (G-009-01).

**Why this priority**: most adopters use Spring (008); a fixture that cannot see their beans forces them back to end
to end tests for any saga with collaborators.

**Independent Test**: a saga bean with a constructor-injected `Clock` and a handler bean with a mocked collaborator,
taken from a Spring test's context and handed to the fixture, are tested as in User Story 1.

**Acceptance Scenarios**:

1. **Given** a saga or handler that needs collaborators, **When** the developer supplies the instances (built by hand
   or taken from a Spring context), **Then** the fixture uses them instead of constructing the classes itself.
2. **Given** the starter on the class path of a Spring test, **When** the developer uses the fixture, **Then** no
   database, message store or transport is needed for the fixture's scenarios.

---

### User Story 6 - Failures explain themselves (Priority: P2)

When an expectation fails, the message says what the saga was expected to do and what it did, in Saga Forge terms:
the instance (saga type and association value), the event that was published, the step that ran or did not, the
commands expected and dispatched, the context values, the failure and its classification. A model error (an invalid
saga) fails the test at setup with the same report the runtime gives at startup.

**Why this priority**: a fixture's value is in how fast a failing test leads to the bug.

**Independent Test**: an expectation of `ReserveStockCommand` when the saga dispatched nothing fails with a message
naming the instance, the event, the step that ran, and "expected 1 command, dispatched none".

**Acceptance Scenarios**:

1. **Given** a failed command expectation, **Then** the message lists expected and actual commands with their content.
2. **Given** a saga whose step threw unexpectedly, **When** the test expected success, **Then** the message names the
   step, the exception and the instance's resulting status, and carries the original exception as its cause.
3. **Given** a saga class that violates a validation rule, **When** the fixture is set up, **Then** setup fails with
   every model error, as at runtime startup.

---

### Edge Cases

- **Time**: retry backoff and any time the saga reads through the runtime's clock are under the fixture's control;
  tests never wait in real time, and a test can set the current time.
- **Identities**: saga ids and `CommandId`s are deterministic within a test run, so a test can assert on a command's
  identity (for example that a retry kept the same idempotency key).
- **Business state round trip**: a `SagaContext` value no supported durable store can persist (for example a
  `BigDecimal`, which Postgres rejects, G-003-07) fails the test by default at the step that wrote it, naming the key
  and the value's type; a test can turn the check off.
- **Values written before a rollback**: as at runtime, `SagaContext` values written by a failed attempt stay visible;
  the fixture reports them, so tests can check that steps write them idempotently.
- **Events published by the test outside any saga** (a non-saga event the application publishes) are routed like any
  other event; one no saga takes is reported as unhandled, not an error.
- **Several saga types reacting to one event**: each saga type's instance is reported separately; an event a step of
  its own instance runs is not handed to other saga types (G-ENG-24) — the fixture behaves as the runtime does and its
  documentation says so.
- **An event the saga records itself and runs in process** within the same unit of work is part of that unit of
  work's report, not a separate publication.
- **A saga waiting with no further event** stays in its waiting status; the fixture reports it as waiting.
- **A test that publishes no event** and only asserts on given history is allowed; the "when" step is optional.
- **Parallel tests**: separate fixtures share nothing; tests may run concurrently.
- **Fixture reuse**: one fixture covers one scenario; reusing it after its expectations were checked continues from
  the state it reached.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Saga Forge MUST publish a test fixture library that an application adds in test scope; it MUST depend on
  nothing beyond Saga Forge's core and runtime and the JDK, so it works without Spring, a database or a transport
  (Constitution Principle I).
- **FR-002**: The fixture MUST run sagas with the shipped runtime's semantics (routing by association, saga-scoped
  unit of work, duplicate detection, retry, compensation, outcome and failure recording), not a separate
  re-implementation, so that a saga passing its fixture tests behaves the same in production.
- **FR-003**: The fixture MUST validate the registered model at setup with the same rules and the same full error
  report as the runtime's startup.
- **FR-004**: A test MUST be able to state, in order: the history (events already published, as given), the event that
  arrives next (when), and the expectations (then). Given events MUST run the same steps as published events but MUST
  NOT count towards the "then" expectations.
- **FR-005**: The fixture MUST let a test expect, for the event under test: the exact commands dispatched (types and
  content, in order), commands matched by a predicate, or no command; the steps and compensations that ran; the
  `SagaContext` values of an instance (present with a value, or absent); and an instance's status, outcome, failure
  (step, type, message, classification, attempts) and compensation trigger.
- **FR-006**: The fixture MUST let a test address an instance by saga type and association value, and MUST report
  every instance the event under test created or changed.
- **FR-007**: The fixture MUST report how an event was handled: started an instance, advanced one, completed it,
  ignored (no step accepts it), duplicate, or duplicate start.
- **FR-008**: A test MUST be able to make a dispatched command fail: by command type and optionally by content, with a
  given exception, once, a fixed number of times, or always. Failure classification MUST follow the runtime's rules
  (`TransientFailure`/`FinalFailure` markers, `retryOn`, unclassified).
- **FR-009**: Retries and their backoff MUST run without real-time waiting; the fixture MUST let a test read and set
  the time the runtime observes.
- **FR-010**: Saga ids and `CommandId`s MUST be deterministic within a fixture, so expectations may refer to them.
- **FR-011**: Commands whose handler the test did not register MUST be recorded and NOT handled, so a test answers them
  by publishing the resulting events itself. This is the default; no handler is run unless registered.
- **FR-012**: A test MUST be able to register command handlers and aggregates: a registered handler MUST run as in production, an
  aggregate MUST be loaded from and saved to an in-memory store the test can seed and read, and a client
  `@AggregateLoader`/`@AggregateSaver` MUST keep precedence over that store; outbox-delivered commands MUST be handled
  after the sending unit of work commits.
- **FR-013**: The fixture MUST accept instances for sagas and handlers that need constructor arguments (for example
  collaborators, test doubles, or beans a test took from a Spring context), and construct the others itself. A saga
  supplied as a factory MUST yield a fresh object per unit of work, as at runtime. The fixture MUST NOT depend on
  Spring; Spring-specific helpers are deferred (G-009-01).
- **FR-014**: A failed expectation MUST fail the test with a message naming the saga type, the association value, the
  event under test, the steps that ran, and the expected and actual values; an unexpected failure inside the saga MUST
  be attached as the cause.
- **FR-015**: The fixture MUST work with JUnit 5 and with any test framework that treats a thrown assertion error as a
  failure; it MUST not require a JUnit extension to be used.
- **FR-016**: Separate fixtures MUST share no state, so tests can run in parallel.
- **FR-017**: The fixture's behaviour MUST be checked against the runtime by the same scenarios run both ways
  (fixture and an assembled runtime over in-memory doubles), so the two cannot drift (supports FR-002).
- **FR-018**: The canonical example (`saga-forge-example`) MUST ship fixture tests for its happy path, its compensation
  path and a retried payment, as the reference for adopters.
- **FR-019**: By default, a `SagaContext` value outside the value types every supported durable store can persist MUST
  fail the test at the step that wrote it, naming the saga type, association value, key and value type; a fixture
  setting MUST turn the check off.

### Key Entities

- **Fixture**: one test scenario over a registered model: the saga, handler and aggregate classes (and supplied
  instances), the controlled clock, the in-memory stores, the configured command failures.
- **Given history**: the events already handled, applied before the event under test and excluded from expectations.
- **Event under test**: the one event whose consequences the expectations describe.
- **Handling result**: what happened to the event under test: per instance, the handling outcome, the steps and
  compensations that ran, the commands dispatched, the context values, and the resulting status, outcome or failure.
- **Command failure rule**: a command type (and optional content match), an exception, and how many times it applies.
- **Aggregate store**: the in-memory aggregates a test seeds before and reads after the event under test, used when
  handlers are registered.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer can write a test for one saga step — given history, event, expected commands, context value
  and status — in 15 lines or fewer, with no infrastructure setup.
- **SC-002**: The canonical saga's happy path, compensation path and retried payment are each covered by a fixture
  test, and the three tests together run in under 1 second.
- **SC-003**: Every scenario of User Stories 1 to 3 produces the same handling result in the fixture as in an assembled
  runtime over in-memory doubles (0 differences in the parity suite).
- **SC-004**: In 100% of the deliberately failing expectations in the fixture's own test suite, the failure message
  names the saga type, the association value, the event under test and the differing values.
- **SC-005**: A test of a retry policy with 30 seconds of total backoff completes in under 1 second.
- **SC-006**: An application on the Spring Boot starter can test its sagas with the fixture without starting a
  database or a transport.
- **SC-007**: A saga that stores a `BigDecimal` in its `SagaContext` fails its fixture test by default, naming the key,
  before any database is involved.

## Assumptions

- **Audience**: application developers testing their own sagas. Saga Forge's internal `SagaTestHarness` (002) stays a
  test-only reference for the framework's correctness properties; whether it is later retired in favour of the
  fixture is G-ENG-13's decision, not this feature's.
- **Same semantics as production**: the fixture runs the shipped runtime over in-memory doubles; it does not simulate
  the database or the transport's delivery (ordering across aggregates, redelivery after a crash, leases). Those stay
  the subject of integration tests with the real modules.
- **Distribution**: a new library module published with the others, added by applications in test scope; it carries
  the in-memory doubles it needs as its own main code rather than depending on the runtime's test sources.
- **Assertion style**: the fixture throws its own assertion errors; it does not require AssertJ or Hamcrest, though
  its results can be inspected with either.
- **Concurrency**: the fixture runs one event at a time, synchronously; concurrent units of work on one instance (the
  runtime's lock) are out of its scope.
- **Spring Modulith `Scenario`**: whether the fixture integrates with Spring Modulith's `Scenario` API stays with
  G-008-01; this feature must not grow a competing Modulith-specific API.
- **Performance testing utilities** (execution timing, load) are out of scope.
