# Feature Specification: Saga Engine Runtime

> **Renamed in 007** (`specs/007-in-memory-transport` research §14, 2026-11-11): `EventHandoff` → `Outbox`,
> `handOff` → `store`, `HandedOffEvents` → `OutgoingMessages`, `DispatchingEventHandoff` → `ImmediateDispatchOutbox`,
> `SagaEngine.Builder.handoff` → `outbox`, `SagaHandlingResult.handoffFailures` → `outboxFailures`. This document keeps
> the names it was written with.

**Feature Branch**: `004-saga-engine`

**Created**: 2026-11-06

**Status**: Draft

**Input**: User description: "Speckit feature 004, the saga engine: a production saga runtime that implements
the 002 saga-scoped unit-of-work contract (today proven only by the test-only harness); a transaction-boundary
contract through which the engine opens, commits and rolls back the host's local transaction, with the 003
Postgres provider adapted to join it (G-ENG-03); at most one unit of work per saga instance at a time, also
across processes; the two plug-in points a transport needs (inbound entry point, after-commit handoff to
`EventDispatcher`) and the delivery guarantee between commit and dispatch; engine-owned startup validation;
gap-register items G-ENG-01 and G-ENG-02. Verification uses in-memory/test implementations of the storage,
context-store, transaction and dispatcher SPIs only. No third-party dependency. Out of scope: Spring Boot
autoconfiguration and bridges, the in-memory and Kafka transports, the outbox implementation, idempotent step
execution, compensation, step retry, `@StartSaga` as a `@CommandHandler`, a lifecycle-history query API,
MySQL/MongoDB storage. Feature order: engine, then the Spring Boot starter, then the Kafka transport, then
compensation."

## Clarifications

### Session 2026-11-06

- Q: Which delivery guarantee holds between commit and dispatch (FR-030): at-most-once, a transactional outbox,
  or at-most-once by default with an outbox-ready handoff contract? → A: At-most-once by default with an
  outbox-ready handoff contract. Buffered events go to `EventDispatcher` right after commit and are lost if the
  process stops in between (a documented limitation, G-ENG-07). The spec defines the after-commit handoff
  contract's obligations (which events, in what order, with which saga, instance and unit-of-work identity) so
  that an implementation can capture the buffered events inside the unit of work's transaction instead of
  after it. The transactional outbox itself comes with the Kafka feature, without changing the engine.
- Q: Who produces the `SagaLifecycleEntry` values recorded for engine-driven instances (FR-033): the storage
  provider by diffing snapshots, or the engine? → A: The engine. It knows the true order in which steps complete
  in a unit of work and hands the entries to storage with each instance write, in the same transaction. Storage
  providers no longer derive history by diffing. This changes the `StorageProvider` write contract, the entry
  type stays in `core.spi` (G-ENG-02), the Postgres adaptation (FR-023) persists the engine's entries instead of
  deriving them, and G-003-04's alphabetical tiebreak goes away. The entry and the write contract stay
  storage-agnostic; mapping an entry to a row, document or fields is each storage module's own concern.
- Q: Must the engine guarantee at most one active instance per saga type and association value across
  processes (FR-027), or only within one process? → A: Within one process only. Cross-process concurrent
  duplicate starts are deferred to the Kafka feature's delivery and deduplication work, with no storage change in
  this feature (a documented limitation, G-ENG-08).

## User Scenarios & Testing *(mandatory)*

The users of this feature are:

- **Application developers** who write `@Saga` classes, `@CommandHandler`s, aggregates and domain events, and
  expect them to run as specified by features 001 and 002 instead of only inside the test harness.
- **Host integration authors** (first of all the upcoming Spring Boot starter) who assemble the engine, supply
  the host's transaction handling, handler instances and aggregate resolution, and route its diagnostics.
- **Transport and storage module authors** who plug a transport (in-memory, Kafka) or a storage backend
  (Postgres today; MySQL and MongoDB later) into the engine without the engine knowing which one it is.

The engine is the production counterpart of 002's test-only `SagaTestHarness`. Everything the harness proves
for the reference sagas (`ThreeStepSaga`, `CreateBookingSaga`) and the canonical `OrderProcessingSaga` must hold
when the same sagas run on the engine. The stories below use `OrderProcessingSaga`: `handleOrderCreated`
(`@StartSaga`) → `processPayment` → `completeOrder` (`@EndSaga`), all on `associationProperty = "orderId"`, with
`ProcessPaymentCommand` handled by `PaymentService` (a service) and `ReserveStockCommand` handled by `Order` (an
aggregate instance method, loaded and saved through `OrderRepository`), and `compensatePayment` declared for
`processPayment`.

### User Story 1 - An event starts a saga and runs it to an outcome in one atomic unit of work (Priority: P1)

An application developer registers `OrderProcessingSaga`, its handlers and its aggregate with the engine. An
*order created* event arrives through the transport. The engine starts a new saga instance, runs
`handleOrderCreated`, and runs every further step that the events recorded by the dispatched handlers trigger,
in-process and in recording order, until the `@EndSaga` step `completeOrder` returns. Aggregate changes, step
records, the saga's status and its outcome commit together in the host's local transaction. Only then are the
events no step of this instance reacted to handed to the transport.

**Why this priority**: This is the engine's reason to exist. Without it, every saga model in the repository
runs only inside a test harness. A saga that completes in one unit of work is already a usable MVP.

**Independent Test**: Assemble the engine with in-memory test implementations of storage, context store,
transaction boundary and dispatcher. Deliver *order created* through the inbound entry point and check the
committed instance, the aggregate changes, the context values and the after-commit dispatches. Repeat the
002 harness scenarios for `ThreeStepSaga` and compare results with the harness.

**Acceptance Scenarios**:

1. **Given** the engine with `OrderProcessingSaga` registered, **When** *order created* is delivered and every
   dispatched command is handled successfully, **Then** one instance exists, it is `COMPLETED` with outcome
   `completeOrder`, `handleOrderCreated`, `processPayment` and `completeOrder` are recorded `COMPLETED` with the
   aggregates each touched, and the payment and stock effects are committed.
2. **Given** the same run, **When** an observer outside the unit of work inspects storage and aggregates while
   the unit of work is still running, **Then** none of its aggregate changes, step records, status change or
   outcome is visible.
3. **Given** a step that writes a value to `SagaContext`, **When** a later step of the same unit of work reads
   it by name and type, **Then** it gets the value; reading it with an incompatible type fails as specified by
   feature 001.
4. **Given** a handler that records an event no step of this instance reacts to, **When** the unit of work
   commits, **Then** that event is handed to the after-commit handoff exactly once, after the commit, in
   recording order relative to other such events.
5. **Given** `ReserveStockCommand` dispatched by a step, **When** it runs, **Then** the `Order` aggregate is
   loaded through `OrderRepository`'s `@AggregateLoader` by the command's `@AggregateIdentifier`, the handler
   runs on it, and it is saved through the `@AggregateSaver`, inside the unit of work.
6. **Given** a saga class that declares its steps in a different order from the order their events are raised,
   **When** the saga runs, **Then** the steps run in event order.
7. **Given** application code outside any unit of work, **When** it publishes *order created* through the
   engine's `EventPublisher`, **Then** the event is handed to `EventDispatcher` immediately, and when the
   transport delivers it back to the inbound entry point a new instance starts.
8. **Given** the run of scenario 1, in which the creation write and one unit of work complete all three steps,
   **When** the instance's lifecycle history is inspected, **Then** it lists the creation, then the completion
   of `handleOrderCreated`, `processPayment` and `completeOrder` in the order they ran, then the outcome
   `completeOrder`, whatever the alphabetical order of the step names.

---

### User Story 2 - A failure rolls everything back and leaves the saga FAILED (Priority: P1)

Something throws inside a unit of work: a step, a command handler, a loader, a saver, the aggregate resolver,
the handler-instance provider, or the storage write at commit, including a failure a step caught. The engine
rolls the host's local transaction back in full, discards the buffered events, and then, in a separate write,
records the instance as `FAILED` with a `SagaFailure` naming the failing step, the cause type, the message and
the time. Values already written to `SagaContext` stay.

**Why this priority**: "Truthful state" is the framework's core promise (README principle 4,
`docs/architecture.md` "Truthful state and transaction boundaries"). A runtime that can half-commit a unit of
work is worse than none.

**Independent Test**: Run a three-step in-process saga on the engine and inject a failure at every position
(each step, each handler, a loader, a saver, the resolver, the instance write at commit). For every position,
check that no aggregate change, no `COMPLETED` step record of that unit of work, and no buffered event
survives; the instance is `FAILED` with the right step; context values written earlier are present.

**Acceptance Scenarios**:

1. **Given** the first unit of work of a new instance, **When** the handler of the second step throws, **Then**
   no aggregate change of the first or second step remains, no step is recorded `COMPLETED`, nothing is handed
   to the transport, and the instance exists and is `FAILED` with the second step as the failed step.
2. **Given** a step that catches the exception thrown by a dispatched command, **When** the step returns
   normally, **Then** the unit of work is still rolled back and the saga ends `FAILED`.
3. **Given** a failure in the `@EndSaga` step, **When** it throws, **Then** the effects of every step of the
   unit of work are rolled back; a failure at the last position is not a partial success.
4. **Given** a step that wrote a `SagaContext` value before a later step failed, **When** the unit of work has
   rolled back, **Then** the value is still readable for that instance.
5. **Given** a `FAILED` instance, **When** another event correlated to it arrives, **Then** it is reported as
   unexpected and has no effect.
6. **Given** a host whose storage provider supports the engine's transaction boundary, **When** a unit of work
   commits or rolls back, **Then** the instance write, its step records, status and outcome and the aggregate
   writes made by the dispatched handlers commit or roll back together in that one transaction, while
   `SagaContext` writes are unaffected by the rollback.

---

### User Story 3 - A waiting saga resumes on a later event routed by association (Priority: P2)

`CreateBookingSaga` stops after *payment initiated*: no step of the instance reacts to it, so the unit of work
commits at a wait point and the instance stays `RUNNING`. Minutes or days later, *payment confirmed* or
*payment declined* arrives through the transport, possibly at a different engine instance in another process.
The engine resolves the waiting instance by the step's `associationProperty` value, rebuilds everything it
needs from storage and the context store alone, and runs the next unit of work.

**Why this priority**: Real processes wait on outside parties and have more than one outcome. It builds on
US1 and US2, which already deliver value for sagas that finish in one unit of work.

**Independent Test**: Run `CreateBookingSaga` on one engine instance until it waits. Build a second engine
instance over the same test stores and nothing else, deliver *payment confirmed* (and, in a separate run,
*payment declined*) to it, and compare the outcome with an uninterrupted run.

**Acceptance Scenarios**:

1. **Given** `CreateBookingSaga`, **When** the first unit of work stops after *payment initiated*, **Then** the
   instance is `RUNNING`, `onBookingRequested` and `onAvailabilityClaimed` are `COMPLETED`, and every other step
   is not run.
2. **Given** the waiting instance, **When** *payment confirmed* is delivered to a second engine instance that
   shares only the stores, **Then** it is correlated by `associationProperty`, `onPaymentConfirmed` and
   `onBookingConfirmed` run in one new unit of work, and the saga is `COMPLETED` with outcome
   `onBookingConfirmed` while `onPaymentDeclined` stays not run.
3. **Given** the waiting instance, **When** *payment declined* is delivered instead, **Then**
   `onPaymentDeclined` reads the claim id written to `SagaContext` in the first unit of work and the saga is
   `COMPLETED` with outcome `onPaymentDeclined`.
4. **Given** the waiting instance, **When** the second unit of work fails, **Then** only it is rolled back; the
   first unit of work's effects stay committed and the instance is `FAILED`.
5. **Given** an event for a non-start step that correlates to no instance, **When** it is delivered, **Then**
   it is reported as unmatched and has no effect, and no error is raised to the transport.

---

### User Story 4 - Concurrent events for one instance are processed one after the other (Priority: P2)

Two events for the same saga instance arrive at the same time, on two threads of one process or at two engine
instances in different processes sharing the same storage. The engine never runs two units of work of the same
instance at once: the second starts only after the first has committed or rolled back, and runs against the
state the first left. Units of work of different instances do not wait for each other.

**Why this priority**: Without serialization, two units of work can each record a step complete against a
stale view, and the instance ends up reflecting only part of either. Real transports deliver concurrently, so
this is needed before any transport ships.

**Independent Test**: Use two engine instances over shared test stores whose transaction-boundary and storage
test doubles behave like a database with row-level locking. Deliver two events for one waiting instance
concurrently, many times, and check that the steps never interleave, that no update is lost, and that a
conflict never ends the saga `FAILED`.

**Acceptance Scenarios**:

1. **Given** a waiting instance and two concurrently delivered events that each trigger one of its steps,
   **When** both are handled, **Then** the two units of work run one after the other, and the second sees the
   step records, status and context values the first committed.
2. **Given** that the first unit of work completes the saga, **When** the second event is then handled,
   **Then** it is reported as unexpected and has no effect.
3. **Given** a unit of work that waits for another one of the same instance, **When** it is waiting, **Then**
   none of its steps has run and it has written no `SagaContext` value.
4. **Given** a concurrency conflict detected only when the instance is written, **When** the losing unit of
   work is rolled back, **Then** the saga is not marked `FAILED` for it; the event is processed again against
   the committed state, or reported to the caller as not handled so the transport can redeliver it.
5. **Given** events for two different instances, **When** they are handled concurrently, **Then** neither unit
   of work waits for the other.

---

### User Story 5 - Transports plug into the engine at two points with a defined dispatch guarantee (Priority: P2)

A transport module author implements a transport without knowing anything about sagas beyond two plug-in
points: an inbound entry point ("a domain event arrived, handle it"), which returns what happened, and the
after-commit handoff, through which the engine gives the transport the events a committed unit of work buffered.
By default the engine delivers them at most once, right after commit. The handoff contract is defined so that a
later transport (Kafka) can capture the buffered events inside the unit of work's transaction instead, as a
transactional outbox, without any change to the engine.

**Why this priority**: The next features (the Spring Boot starter with the in-memory transport, then Kafka)
build on these two points. Getting them wrong now forces the engine to be reworked per transport.

**Independent Test**: Write a test transport that only calls the inbound entry point and only receives
after-commit handoffs. Use it to run US1 and US3 end to end, including looping dispatched events back into the
entry point, and check what the entry point reports for every outcome.

**Acceptance Scenarios**:

1. **Given** a test transport, **When** it hands an event to the inbound entry point, **Then** the entry point
   reports per affected saga type and instance whether a saga was started, committed at a wait point, completed
   with an outcome, failed, a duplicate start, unmatched, unexpected, or the event was ignored.
2. **Given** a unit of work that rolled back, **When** the transport is inspected, **Then** it never received
   any event that unit of work buffered.
3. **Given** a committed unit of work, **When** its buffered events are handed off, **Then** this happens only
   after the commit, in recording order, including events left in the in-process queue when an `@EndSaga` step
   ended the unit of work.
4. **Given** a handoff that fails after the commit, **When** the failure occurs, **Then** the committed unit of
   work stays committed, the saga is not marked `FAILED`, and the failure is reported.
5. **Given** a synchronous transport that delivers a handed-off event straight back into the inbound entry
   point from inside the handoff, **When** that happens, **Then** it is handled as a new unit of work after the
   first one has finished.
6. **Given** a saga that ends `FAILED`, **When** the entry point returns, **Then** the transport is told the
   event was handled (the failure is recorded on the instance); only a failure that prevented the engine from
   recording any outcome is raised to the transport as an error.
7. **Given** a test handoff implementation that captures buffered events inside the unit of work's
   transaction, **When** a unit of work commits, **Then** it has received exactly that unit of work's buffered
   events, in recording order, each with the saga type, saga id and unit-of-work identity, before the commit and
   as part of the same transaction; **and when** a unit of work rolls back, nothing it received survives.

---

### User Story 6 - Startup validation fails fast (Priority: P3)

A host integration author starts the engine with a set of saga, handler, aggregate and repository classes. The
engine validates the whole model in one pass, including the checks the scanner cannot make because it does not
see the host: every aggregate with instance-method `@CommandHandler`s can be loaded, every non-command
`@CommandHandler` parameter can be resolved, and every saga class can be instantiated. If anything is wrong,
the engine refuses to start and reports every problem at once, before any event is accepted.

**Why this priority**: Fail-fast startup saves debugging time, but the engine is useful without it as long as
models are valid, so it ranks after the runtime behavior.

**Independent Test**: Start the engine with models containing each kind of error, separately and combined, and
check that start-up fails with one report listing every error and that no event is accepted.

**Acceptance Scenarios**:

1. **Given** a model that `validateAll` rejects, **When** the engine starts, **Then** it refuses to start and
   reports every error of the `ValidationReport`.
2. **Given** an `@Aggregate` with instance-method `@CommandHandler`s, no `@AggregateLoader` and no
   `AggregateResolver` supporting it, **When** the engine starts, **Then** it refuses to start and names the
   aggregate.
3. **Given** a `@CommandHandler` with a non-command parameter that no registered parameter resolver can supply,
   **When** the engine starts, **Then** it refuses to start and names the handler and the parameter.
4. **Given** several such problems in one model, **When** the engine starts, **Then** all of them are reported
   together in one report.
5. **Given** an engine that refused to start, **When** a transport hands it an event, **Then** the event is
   rejected and nothing is processed.

---

### Edge Cases

- **Event no saga reacts to**: ignored without error and reported as ignored (see Assumptions).
- **Event for a non-start step with no correlated instance**: reported as unmatched, no effect (002 FR-003).
- **Event for a finished instance**: an event correlated only to `COMPLETED` or `FAILED` instances is reported as
  unexpected, no effect.
- **Duplicate start**: a start event correlating to an active instance of the same saga type is rejected as a
  duplicate (001 FR-006), reported, and no unit of work runs. Within one process this holds even when the start
  events are handled concurrently (FR-027). Across processes it holds only once the first instance's creation
  write has committed: two start events with the same association value handled concurrently in different
  processes can both start an instance. That is a known limitation of this feature, left to the Kafka feature's
  delivery and deduplication work (see Assumptions, G-ENG-08).
- **Event missing its association value**: an event whose association property is absent or empty for a
  reacting step is rejected as malformed and has no effect for that saga type.
- **Event reacted to by several saga types or several instances**: each affected saga type and each correlated
  active instance gets its own unit of work, committed or rolled back independently. One instance's failure
  does not affect another.
- **In-process event that triggers a start step**: an event recorded inside a unit of work that triggers the
  `@StartSaga` step of a saga is never run in-process; it is handed off after commit and may start a new
  instance when delivered.
- **Event recorded for another instance**: an event recorded inside a unit of work that triggers a step of a
  different instance, or of a different saga type, is handed off after commit, not run in-process.
- **Outcome reached with events still queued**: the first `@EndSaga` step to complete decides the outcome; the
  unit of work commits, and the events still queued are handed off after commit (they arrive as unexpected if
  delivered back to the same instance).
- **Crash after the instance's creation write**: if the process stops after the `CREATED` instance was committed
  (FR-012) and before its first unit of work finished, the instance stays `CREATED`; a redelivered start event
  is rejected as a duplicate. Recovery is not addressed here (see Assumptions, G-ENG-05).
- **Crash between commit and handoff**: under the default at-most-once handoff (FR-030), the buffered events of
  a unit of work that committed are lost if the process stops before they reach `EventDispatcher`; the
  committed unit of work stays committed and nothing redelivers them. That is a known limitation of this
  feature, closed when the Kafka feature adds a transactional outbox behind the handoff contract (see
  Assumptions, G-ENG-07).
- **Capture failing inside the transaction**: a handoff implementation that captures buffered events inside the
  unit of work's transaction (FR-030) and fails while doing so fails the unit of work like the instance write
  does (FR-011); it is not an after-commit handoff failure (FR-031).
- **Failure while writing `FAILED`**: if the separate `FAILED` write itself fails, no outcome was recorded, so the
  error is raised to the transport (FR-005); the instance keeps the state its last committed unit of work left.
- **Concurrency conflict at write time**: handled as in US4 scenario 4, never as a saga failure (FR-026).
- **Entry point called from inside a running unit of work**: for example a step or handler handing an event to
  the inbound entry point itself. Rejected with an error, which fails that unit of work like any other failure.
- **Recording from another thread**: a handler that records an event on a thread it started itself has no
  active unit of work there; `DomainEvents.record` fails (002 FR-015) and the engine does not try to capture it.
- **Non-transactional side effects**: a handler that calls an external system is not undone by a rollback (002
  Assumptions); unchanged.
- **Saga that never reaches an outcome**: waits indefinitely; no timeouts in this feature.
- **`send` outside a unit of work**: application code calling `CommandGateway.send` outside any unit of work gets a
  non-saga unit of work for the handler's own changes (FR-016).

## Requirements *(mandatory)*

### Functional Requirements

**Event intake and routing (US1, US3, US5)**

- **FR-001**: The engine MUST expose one transport-agnostic inbound entry point through which a transport hands
  over a domain event that arrived. The engine, not the transport, decides which saga types and instances the
  event concerns.
- **FR-002**: For every registered saga type whose `@StartSaga` step reacts to the event's type, the engine MUST
  start a new instance correlated by that step's `associationProperty` value read from the event, subject to the
  duplicate-start rule of feature 001 (FR-006 there) and FR-027.
- **FR-003**: For every registered saga type with a non-start step reacting to the event's type, the engine MUST
  resolve the active instances of that saga type correlated by the step's `associationProperty` value through
  the storage provider's association lookup, and run one unit of work per correlated active instance.
- **FR-004**: An event no registered saga reacts to MUST be ignored; an event for a non-start step that correlates
  to no instance MUST be reported as unmatched; an event correlated only to finished instances MUST be reported
  as unexpected. None of them has any effect, and none is an error for the transport.
- **FR-005**: The inbound entry point MUST report what happened, per affected saga type and instance: started,
  committed at a wait point, completed with an outcome, failed, duplicate start, unmatched, unexpected, or
  ignored; and, for a committed unit of work, whether its after-commit handoff failed (FR-031). A saga ending
  `FAILED` is a handled outcome. Only a failure that prevented the engine from recording any outcome (for
  example the storage being unavailable, or the `FAILED` write itself failing) MUST be raised to the caller as an
  error, so the transport can redeliver the event. "Duplicate start" is reported only for duplicates the engine
  detects under FR-027; a concurrent duplicate start in another process may instead be reported as started.
- **FR-006**: An event that is `null`, not `@DomainEvent`-annotated, or lacks the association value a reacting
  step needs MUST be rejected with an error naming the problem and MUST have no effect.
- **FR-007**: The inbound entry point MUST accept calls made from inside an after-commit handoff (synchronous
  transports) and handle them as new units of work. A call made while a unit of work is still running on the
  same thread MUST be rejected with an error.

**Saga-scoped unit of work (US1, US2, US3)**

- **FR-008**: Every unit of work MUST follow `specs/002-transactional-step-sequence/contracts/saga-unit-of-work.md`
  in full: the triggered step runs first; every event recorded inside the unit of work that triggers a non-start
  step of the same instance (same event type, same `associationProperty` value) is queued and run in recording
  order in the same unit of work; after each step returns, the step is recorded `COMPLETED` with the aggregates
  touched by the events recorded while it ran; the unit of work commits when the first `@EndSaga` step returns
  (recording that step as the outcome) or when the queue is empty (a wait point, status `RUNNING`).
- **FR-009**: Before the first step of a unit of work runs, the engine MUST provide a saga object whose `@SagaId`
  field holds the instance's id, whose `SagaContext` field (if declared) is bound to that instance's context
  values, and whose `CommandGateway` field (if declared) dispatches inside this unit of work. The engine MUST
  create a fresh saga object per unit of work; no saga object is shared between units of work or instances.
- **FR-010**: The aggregate changes made by the handlers a unit of work dispatched, the instance's step records,
  its status and its outcome MUST commit together in one host local transaction, and none of them MUST be
  visible outside the unit of work before that commit.
- **FR-011**: If any step, handler, loader, saver, aggregate resolver, handler-instance provider, parameter
  resolver or the instance write throws, including a failure a step caught, the engine MUST roll the unit of work
  back in full (aggregate changes, step records, status, outcome, buffered events), then, in a separate write,
  record the instance as `FAILED` with a `SagaFailure` naming the failing step, the cause type, the message and
  the time. No further step of the instance runs afterwards.
- **FR-012**: The engine MUST durably persist a new instance (status `CREATED`, every step not run) in its own
  committed write before the instance's first unit of work runs, and so before any `SagaContext` write for it
  (G-ENG-01). If that first unit of work rolls back, the `FAILED` write updates this record.
- **FR-013**: The engine MUST provide production `SagaContext` behavior per feature 001 and 002: named,
  type-safe values; nulls rejected; structured values updatable in place. Writes MUST be saved through
  `SagaContextStore` outside the unit of work's transaction, no later than the end of the step that made them,
  so they survive a rollback and are visible to later steps of the same instance in the same or a later unit of
  work. Values of one instance MUST never be visible to another instance.
- **FR-014**: A unit of work MUST be able to run from what the storage provider, the context store and the
  client's own aggregate storage hold, with no memory carried over from an earlier unit of work, so it can run
  in a different engine instance or process.
- **FR-015**: The engine MUST provide a production `SagaLifecycle` whose transitions follow feature 001 and the
  002 contract delta (`start`, `recordStepCompleted`, `advance`, `complete(sagaId, outcomeStep)`, `fail`),
  including rejection of transitions on terminal instances. The engine MUST only pass an `@EndSaga` step as the
  outcome.

**Commands and events (US1, US2)**

- **FR-016**: The engine MUST provide a production `CommandGateway` that follows
  `specs/002-transactional-step-sequence/contracts/command-gateway.md`: each command goes to its single
  `@CommandHandler`, run synchronously in the caller's unit of work; service-hosted handlers run on the instance
  supplied by the host's `HandlerInstanceProvider`; creation handlers run and the new aggregate is saved;
  instance-method handlers run on the aggregate loaded by the command's `@AggregateIdentifier` through the
  client's `@AggregateLoader`, else a supporting `AggregateResolver`, and the aggregate is saved through the
  client's `@AggregateSaver`, else the resolver, else left to the client's own unit of work. A missing handler
  raises `NoCommandHandlerException`, a missing aggregate `AggregateNotFoundException`, and any failure marks the
  unit of work for rollback before it reaches the step. Called outside any unit of work, `send` MUST open a
  non-saga unit of work in the host's local transaction for the handler's own changes; the events recorded in it
  are handed off after it commits and discarded if it rolls back.
- **FR-017**: For every unit of work, saga-scoped or not, the engine MUST capture every event recorded with
  `DomainEvents.record` on the unit of work's thread, at any call depth, and apply the delivery rules of
  `specs/002-transactional-step-sequence/contracts/event-publisher.md`.
- **FR-018**: The engine MUST provide a production `EventPublisher`: inside a unit of work, publishing is
  equivalent to `DomainEvents.record`; outside one, the event is handed to `EventDispatcher` immediately.
- **FR-019**: The engine MUST supply the `@Command` argument of every handler. Every other handler parameter MUST be
  supplied through a parameter-resolution plug-in point the host provides (the Spring starter will resolve
  beans). A parameter no registered resolver can supply MUST be a startup error (FR-035); with no resolver
  registered, every handler with a non-command parameter is rejected at startup.

**Transaction boundary (US2)**

- **FR-020**: The engine MUST open, commit and roll back the host's local transaction only through a
  framework-owned transaction-boundary contract that host integrations implement. The contract MUST cover:
  beginning a transaction, committing it, rolling it back, marking it rollback-only, and telling whether one is
  active. The engine introduces no distributed (two-phase) transaction.
- **FR-021**: The transaction-boundary contract MUST be expressible as, or bridgeable without loss of meaning
  to, the Jakarta Transactions (JTA) API and Spring's transaction manager: each of its operations MUST
  correspond to an operation both of them offer, and it MUST require nothing (such as nested or distributed
  transactions) that either lacks, so that the starter and later integrations can adapt either one without
  changing the engine.
- **FR-022**: Within a unit of work, the storage provider's instance writes and the aggregate access performed
  for dispatched handlers MUST take part in the transaction the engine opened. A storage provider MUST be able to
  join that transaction instead of opening its own (G-ENG-03). `SagaContextStore` writes MUST NOT join it.
- **FR-023**: `saga-forge-storage-postgres`'s `StorageProvider` MUST be adapted so that its instance, association
  and lifecycle-history writes join the engine-controlled transaction when one is active, and keep 003's
  behavior (own transaction per call) when none is. It MUST also be adapted to the write contract of FR-033: it
  persists the lifecycle entries handed to it with each instance write, in the order given and in the same
  transaction as that write, and no longer derives entries by diffing snapshots. Its forward-transition check is
  unaffected. The adaptation MUST use only JDBC and add no dependency. `PostgresSagaContextStore` keeps its own
  connection and transaction (003 research §6).
- **FR-024**: The creation write (FR-012) and the `FAILED` write (FR-011) MUST each run in their own transaction
  through the same contract, separate from the unit of work's transaction.

**One unit of work per instance (US4)**

- **FR-025**: At most one unit of work of a saga instance MUST run at a time, within one process and across
  processes sharing the same storage. A second unit of work for the same instance MUST wait until the first has
  committed or rolled back, then run against the state it left (or report the event as unexpected if the
  instance is now finished). None of its steps MUST run, and it MUST write no `SagaContext` value, before the
  first one has finished.
- **FR-026**: A concurrency conflict detected when the instance is written MUST NOT be recorded as a saga failure.
  The losing unit of work is rolled back without a `FAILED` write, and its event is either processed again
  against the newly committed state or reported to the caller as not handled so the transport can redeliver it.
- **FR-027**: Within one process, the engine MUST guarantee at most one active instance per saga type and
  association value: when two start events with the same association value for the same saga type are handled
  concurrently by one engine instance, exactly one starts an instance and the other is rejected as a duplicate
  start (001 FR-006). Across processes the engine MUST still reject a start event once an active instance's
  creation write (FR-012) has committed, but it does not guarantee uniqueness for start events handled
  concurrently in different processes; that is deferred to the Kafka feature's delivery and deduplication work,
  and this feature MUST NOT add a storage-level uniqueness rule for it (G-ENG-08).
- **FR-028**: Units of work of different instances MUST NOT wait for each other.

**After-commit handoff and delivery guarantee (US5)**

- **FR-029**: The engine MUST hand every event a committed unit of work buffered (events no step of the instance
  ran in-process, including those still queued when an `@EndSaga` step ended it) to the after-commit handoff,
  which by default delivers them to `EventDispatcher`, in recording order, only after the commit. The same holds
  for the events of a committed non-saga unit of work (FR-016). Buffered events of a unit of work that rolled
  back MUST be discarded and never reach a transport.
- **FR-030**: Between commit and dispatch the engine MUST provide an at-most-once guarantee by default: the
  buffered events of a committed unit of work are handed to `EventDispatcher` right after the commit, each at
  most once, and are lost if the process stops in between (a known limitation, G-ENG-07). The engine MUST also
  define the after-commit handoff as an outbox-ready contract that a host or transport module can implement in
  place of the default, without any change to the engine. The contract MUST state:
  - **which events**: exactly the buffered events of one unit of work (FR-029), each once, and none of a unit of
    work that rolled back;
  - **in what order**: recording order within the unit of work;
  - **which identity**: for each handoff, the identity of the unit of work, unique per unit of work, and, for a
    saga-scoped unit of work, the saga type and saga id of the instance it ran for;
  - **when**: an implementation MUST be able to receive the buffered events inside the unit of work's
    transaction, before it commits, so that they commit or roll back with it (as a transactional outbox does),
    instead of after the commit; the default implementation receives them after the commit.

  The transactional outbox itself, and any at-least-once relay from it, are out of scope (Kafka feature).
- **FR-031**: A failure of the after-commit handoff MUST NOT roll back or alter the committed unit of work and MUST
  NOT mark the saga `FAILED`; it MUST be reported through the entry point's result and the diagnostics (FR-038).
  A failure of an implementation that receives the events inside the unit of work's transaction (FR-030) is a
  failure of that unit of work and is handled as FR-011 specifies.
- **FR-032**: Both plug-in points (inbound entry point, after-commit handoff) MUST be expressed in Saga Forge's
  own vocabulary only, with no transport-specific types or assumptions, so the in-memory and Kafka transports
  remain separate modules. The identities the handoff carries (FR-030) are Saga Forge's own, and an
  outbox-backed handoff MUST be implementable on top of the transaction-boundary contract (FR-020) without the
  engine knowing about it.

**Lifecycle history (G-ENG-02)**

- **FR-033**: The engine MUST produce the lifecycle history entries (`SagaLifecycleEntry`) of engine-driven
  instances and hand them to the storage provider together with each instance write, in the same transaction as
  that write: the creation entry with the creation write (FR-012); with a unit of work's write, one step-completed
  entry per step it completed, in the order the steps completed, followed by the completion entry if an
  `@EndSaga` step ended it; the failure entry with the `FAILED` write (FR-011). Entries of a unit of work that
  rolls back, including one lost to a concurrency conflict (FR-026), MUST NOT be recorded. In the persisted
  history, the entries of one write MUST follow every entry already recorded for the instance, in the order the
  engine handed them over. This changes the `StorageProvider` write contract (an SPI change): storage providers
  persist the entries they are given and no longer derive any by diffing snapshots, which removes G-003-04's
  alphabetical tiebreak. The entry type stays in `saga-forge-core`'s SPI (G-ENG-02). The entry is an immutable,
  storage-agnostic value, and the write contract the engine calls is the same for every storage provider and
  exposes no storage representation; mapping an entry to a relational row, a document or fields is each
  storage module's own concern, handled the same way by the Postgres module now and the MySQL and MongoDB
  modules later. The engine produces only the transition kinds that exist today (G-003-18 unchanged).

**Startup (US6)**

- **FR-034**: The engine MUST be started with the set of saga, handler, aggregate and loader/saver classes it
  runs, MUST validate them with `AnnotationScanner.validateAll`, and MUST refuse to start if the report has any
  error.
- **FR-035**: At startup the engine MUST also check what the scanner cannot see: every `@Aggregate` with
  instance-method `@CommandHandler`s has a client `@AggregateLoader` or a registered `AggregateResolver` that
  supports it; every non-command handler parameter can be supplied (FR-019); every saga class can be
  instantiated by the engine. All problems, together with the `validateAll` errors, MUST be reported in one pass,
  each naming the class, method or parameter concerned.
- **FR-036**: An engine that refused to start MUST accept no event. A started engine MUST register every saga
  type with `EventDispatcher` before it accepts the first event.

**Cross-cutting**

- **FR-037**: This feature MUST add no third-party dependency to any module; the engine uses only the JDK and
  `saga-forge-core`, and the Postgres adaptation uses only JDBC.
- **FR-038**: The engine MUST report its diagnostics (startup errors, ignored, unmatched, unexpected and
  duplicate events, rollbacks with their cause, `FAILED` writes, handoff failures, concurrency conflicts) through
  a logging facility the host can route to its own logging backend, including SLF4J later, without the engine
  depending on a logging library. A host MUST be able to redirect every diagnostic without changing the engine,
  and each diagnostic MUST name the event type, saga type and saga id it concerns where they are known.
- **FR-039**: The engine MUST satisfy the correctness properties in `.kiro/specs/saga-forge-framework/design.md`
  (Properties 1 to 20) and `.kiro/specs/saga-context-lifecycle/design.md` that still apply, and the 001 and 002
  contracts. The new startup checks of FR-035, the in-process duplicate-start guarantee of FR-027, the handoff
  obligations of FR-030 and the lifecycle-history order of FR-033 MUST be added as correctness properties
  (Constitution Principle III).
- **FR-040**: When this feature lands, README.md (Status), `docs/architecture.md` (Status at a glance, the
  "Transaction boundary relative to the triggering dispatch" gap), `AGENTS.md` (Current Status, Known Gaps) and
  the `saga-forge-example` module MUST describe the engine as it is, and `OrderProcessingSaga` MUST run end to end
  on it (Constitution Principle V).

### Key Entities

- **Saga engine**: the production runtime that drives saga instances. Assembled by a host integration from the
  registered classes and the host's plug-ins (storage provider, context store, transaction boundary, event
  dispatcher, handler-instance provider, aggregate resolvers, parameter resolvers, logging).
- **Inbound entry point**: where a transport hands the engine one domain event that arrived. Returns a handling
  report.
- **Handling report**: the per saga type and per instance result of handing over one event (started, committed
  at a wait point, completed with outcome, failed, duplicate start, unmatched, unexpected, ignored), including
  whether a committed unit of work's after-commit handoff failed.
- **Saga-scoped unit of work**: the transaction in which a triggered step and every step it triggers in-process
  run for one instance; commits at the first `@EndSaga` step or at a wait point, rolls back in full on failure
  (002 contract).
- **Non-saga unit of work**: the transaction `CommandGateway.send` opens when called outside any unit of work; its
  recorded events are handed off after commit.
- **Transaction boundary**: the framework-owned contract through which the engine begins, commits, rolls back
  or marks rollback-only the host's local transaction, and which storage providers and aggregate access join.
  Bridgeable to JTA and Spring's transaction manager.
- **After-commit handoff**: the outbox-ready contract through which the events a committed unit of work buffered
  leave the engine, in recording order, with the unit of work's identity and, for a saga-scoped unit of work, the
  saga type and saga id (FR-030). The default implementation hands them to `EventDispatcher` right after the
  commit, at most once; an implementation (the Kafka feature's outbox) may instead receive them inside the unit
  of work's transaction.
- **Unit-of-work identity**: an identifier unique per unit of work, saga-scoped or not, carried by every handoff
  so that a later outbox or deduplication step can tell units of work apart.
- **Buffered event**: a domain event recorded inside a unit of work that no step of the owning instance runs
  in-process; handed off after commit, discarded on rollback.
- **Parameter resolver**: a host plug-in that supplies non-command `@CommandHandler` parameters.
- **Saga instance** (existing, `SagaInstance`): status, per-step records with touched aggregates, outcome,
  failure. Created in its own write before the first unit of work; updated in the unit of work's transaction;
  `FAILED` written separately. Each write carries the lifecycle history entries it produces (FR-033).
- **Saga context record** (existing, `SagaContextRecord`): an instance's named business state, written outside the
  unit of work's transaction.
- **Lifecycle history entry** (existing, `SagaLifecycleEntry`): one recorded transition of an instance, an
  immutable, storage-agnostic value. Produced by the engine in true step order and handed to the storage provider
  with the instance write it belongs to, in the same transaction (FR-033). How it maps to a row, document or
  fields is private to each storage module.
- **Startup report**: every model and host-level problem found when the engine starts, reported together.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of the acceptance scenarios of feature 002's User Stories 1 to 3 that the test harness proves
  for `ThreeStepSaga` and `CreateBookingSaga` produce the same instance status, step records, outcome, failure,
  aggregate state, context values and dispatched events when the same sagas run on the engine.
- **SC-002**: For a three-step saga that runs in one unit of work, a failure injected at each of at least eight
  positions (each step, each handler, a loader, a saver, the aggregate resolver, the instance write) leaves zero
  aggregate changes, zero step records of that unit of work marked `COMPLETED` and zero events handed to the
  transport, and a `FAILED` instance naming the failing step, in 100% of runs; every context value written
  before the failure is still readable.
- **SC-003**: In repeated runs (at least 100) of two engine instances handling concurrent events for the same
  instance over shared stores, zero runs show two units of work of that instance overlapping, a lost step record,
  or a saga marked `FAILED` because of the conflict.
- **SC-004**: A saga waiting after its first unit of work resumes in a second engine instance that shares only the
  stores, and reaches the same outcome and recorded state as an uninterrupted run, in 100% of runs for both
  `CreateBookingSaga` outcomes.
- **SC-005**: Zero events of a rolled-back unit of work, and zero events of a committed one before its commit,
  reach `EventDispatcher` across every test in this feature.
- **SC-006**: 100% of the startup problems of FR-034 and FR-035 are reported in one report when the engine
  starts, and an engine that refused to start processes zero events.
- **SC-007**: A test transport using only the inbound entry point and the after-commit handoff runs the canonical
  `OrderProcessingSaga` and `CreateBookingSaga` end to end, with no access to any other part of the engine.
- **SC-008**: The feature adds zero third-party dependencies to any module, as shown by comparing the resolved
  dependency trees before and after.
- **SC-009**: 100% of the saga, handler, aggregate, repository and event classes used by the 002 harness tests and
  the `saga-forge-example` module run on the engine without any change to their source.
- **SC-010**: For engine-driven instances, the recorded lifecycle history lists step completions in the order the
  steps actually completed in 100% of runs, including every unit of work that completes more than one step and
  sagas whose step names sort differently from their execution order, and contains zero entries of rolled-back
  units of work.
- **SC-011**: A test handoff implementation that receives buffered events inside the unit of work's transaction
  gets, for 100% of committed units of work, exactly their buffered events in recording order with the
  unit-of-work identity (and saga type and saga id for saga-scoped ones), and retains zero events of rolled-back
  units of work, with no change to the engine compared with the default handoff.
- **SC-012**: In repeated runs (at least 100) of concurrent start events with the same association value for the
  same saga type handled by one engine instance, exactly one instance is started in 100% of runs and every other
  event is reported as a duplicate start.

## Assumptions

- **Scope**: this feature delivers the engine runtime, the transaction-boundary contract, the transport plug-in
  points and the Postgres adaptation of FR-023. Feature order agreed with the user: engine, then the Spring Boot
  starter, then the Kafka transport, then compensation.
- **Out of scope**, each for the feature named:
  - Spring Boot autoconfiguration, the Spring transaction-manager bridge and the Spring Data `AggregateResolver`
    adapter (Spring Boot starter);
  - the in-memory transport module over Spring `ApplicationEventPublisher` (comes with the starter);
  - the Kafka transport, the transactional outbox behind the handoff contract of FR-030 and its at-least-once
    relay, serialization and topic mapping, idempotent step execution or deduplication under at-least-once
    delivery, and preventing concurrent duplicate starts across processes (Kafka feature);
  - compensation semantics and dispatch (its own spec); a `FAILED` saga's committed effects stay in place;
  - per-step retry and failure classification (its own design); a rollback ends the saga `FAILED`, and nothing
    here precludes retrying a failed step with the context values it already wrote;
  - `@StartSaga` as a `@CommandHandler` (deferred in 002); programmatic abort (dropped in 002);
  - a query API over lifecycle history (G-003-24);
  - MySQL and MongoDB storage;
  - adopting any of the third-party libraries listed below.
- **Where the engine lives** (in `saga-forge-core` or a new module) is a plan decision, not a requirement. Either
  way it stays free of Spring, datastore and transport types (Constitution Principle I).
- **Third-party libraries later (G-GOV-01)**: focused, stack-neutral libraries will be adopted in later features
  where they fit: JSpecify (nullability), SLF4J (logging), ArchUnit (module-boundary tests, test scope), the JTA
  API (`jakarta.transaction-api`) for the transaction contract, and later Micrometer Observation,
  Failsafe/Resilience4j behind framework-owned retry attributes, an outbox library with the Kafka feature, and a
  jMolecules adapter. This feature adopts none of them; FR-021 and FR-038 keep the transaction contract and the
  logging open to them. The constitution amendment G-GOV-01 calls for happens at the first feature that adds one.
- **Verification**: tests use in-memory or test implementations of `StorageProvider`, `SagaContextStore`, the
  transaction boundary and `EventDispatcher` only. There are no Postgres, Kafka, Spring or container tests, as
  the user has limited container resources. The Postgres adaptation (FR-023) is checked without a database;
  checking it against a real Postgres stays open with G-003-23.
- **Engine assembly**: host integrations (the starter, and tests here) assemble the engine. Application
  developers using the starter still never hand-build an engine configuration (README principle 5).
- **Threading**: each unit of work runs synchronously on the thread that handed the event to the entry point (or
  called `send` or `publish`). The engine owns no thread pool. This is what lets `DomainEvents.record` find the
  unit of work through its thread binding.
- **Waiting by default**: a second event for an instance whose unit of work is running waits for it (FR-025)
  rather than being rejected. 003's row lock and forward-transition check may be reused across processes; how
  is a plan decision.
- **Ignored events**: an event that matches no `@StartSaga` and no instance is ignored without error (FR-004).
- **Delivery guarantee** (known limitation): with the default handoff, events are delivered at most once
  between commit and dispatch (FR-030). If the process stops after a unit of work commits and before its
  buffered events reach `EventDispatcher`, they are lost and the saga state stays committed. The handoff
  contract is outbox-ready, so the Kafka feature can close this window with a transactional outbox (events
  captured in the unit of work's transaction and relayed at least once) without changing the engine. Tracked as
  G-ENG-07 until then.
- **Duplicate starts across processes** (known limitation): at most one active instance per saga type and
  association value is guaranteed within one process only (FR-027). Two start events with the same association
  value handled concurrently in different processes can each start an instance, because neither sees the other's
  creation write before it commits. Closing this belongs to the Kafka feature's delivery and deduplication work;
  this feature adds no storage-level uniqueness rule for it. Tracked as G-ENG-08.
- **Lifecycle history and storage mapping** (note for planning): the engine produces the lifecycle entries
  (FR-033), and both the entry and the storage write contract the engine calls stay storage-agnostic and
  identical for every store. The plan should evaluate a generic mapping seam, parameterized by the storage
  representation, defined once and implemented per store: a mapper from an entry to a representation type `R`
  (for example a JDBC row binding for Postgres and MySQL, a BSON document for MongoDB). That type parameter
  belongs at the mapping seam inside or beside each storage module, not on the engine-facing `StorageProvider`:
  the engine never sees the representation, and a type parameter there would leak into engine wiring as a
  wildcard. The entry is an immutable value, not stateful. The plan also decides whether the engine or the
  storage provider assigns each entry's per-instance sequence number (the engine reads no history today,
  FR-014), as long as the persisted order is the order FR-033 requires, and how the `SagaLifecycleEntry` and
  `SagaLifecycleEventKind` Javadoc, which still describe diffing, are corrected.
- **Creation write**: committing the `CREATED` instance before its first unit of work (FR-012) is what makes
  instance-first ordering hold against Postgres's `saga_context` foreign key. It also means the first instance
  write always has every step not run and the engine produces its creation entry itself (FR-033), so G-003-01
  does not arise for engine-driven instances. A crash between the creation write and the end of the first unit
  of work leaves a `CREATED` instance a redelivered start event cannot resume (G-ENG-05).
- **Saga objects**: the engine creates a fresh saga object for each unit of work. By default it needs an
  accessible no-argument constructor; the host may supply saga objects instead (the starter will, so sagas can
  have injected dependencies such as read-model repositories). Fields other than the `@SagaId`, `SagaContext`
  and `CommandGateway` fields are dependencies, not state: they are not persisted, and `SagaInstance.state` stays
  empty (G-ENG-06). Values that must survive between steps go in `SagaContext`.
- **Handler instances**: whether a service-hosted handler's instance is available is checked when a command is
  first dispatched to it, not at startup, because host containers may create instances later.
- **Ids and time**: saga ids, unit-of-work identities and timestamps come from host-replaceable sources (by
  default random UUIDs and the system clock), as in the harness.
- **Event routing documentation**: the `EventDispatcher` and `SagaLifecycle` Javadoc still say the dispatcher
  resolves which instance an event correlates to. Under this feature the engine does that (FR-001, FR-003); the
  Javadoc is corrected with this feature (G-ENG-04).
- **No waiting timeouts**: a saga waits indefinitely for its next event; deadlines stay a separate scheduled
  process (`docs/use-cases.md` Process E).
