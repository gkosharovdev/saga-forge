# Feature Specification: Saga Units of Work, Command Gateway, and Command Handlers

**Feature Branch**: `002-transactional-step-sequence`

**Created**: 2026-11-02

**Status**: Draft

**Input**: User description: "The saga needs to explicitly declare a sequence of steps which need to complete
in the same transaction. If any of the steps fails the changes need to be rolled back. Each step of the
sequence reacts on a @DomainEvent — usually a domain event in the DDD sense, emitted as a result of an
aggregate successfully transitioning its state. In effect the saga provides means for declarative
orchestration across aggregate boundaries. Either an @Aggregate or a Service class which directly calls an
aggregate method features a method annotated as @CommandHandler, and at least one of its arguments is an
instance of a class annotated as @Command (all annotations from saga-forge-core). Saga steps must not return
command objects — the current examples (e.g. CreateBookingSaga in the README) are not representative. Whether
@StartSaga should also be a @CommandHandler is deferred to the next iteration."

## Clarifications

### Session 2026-11-02

- Q: How does a step issue its command? → A: The step hands the command to a framework-owned
  command-issuing facility, which routes it to the single matching `@CommandHandler`.
- Q: What is the transaction scope of a saga? → A: A saga may declare several atomic sequences. Each sequence
  commits as one unit of work; between sequences the saga instance is persisted and waits for an asynchronous
  event; a failure in a later sequence triggers compensation of the sequences already committed. (Refined
  below: sequences are not declared; see the unit-of-work answers.)
- Q: How does a command handler's domain event reach the next step? → A: The aggregate records the events of
  its state transition and the framework collects them after the handler returns. In addition, a dedicated
  framework-provided event publisher can be invoked programmatically. (Refined below: events are recorded
  through `DomainEvents.record`.)
- Q: Which programming model is the saga modeled on? → A: Axon-style sagas: `@SagaStep` methods are saga
  event handlers that dispatch commands through a command gateway (implementations can vary).
- Q: What saga state is tracked between steps? → A: The saga records each step's status (which steps
  completed). Steps lift values into `SagaContext` so later steps can read them to build and dispatch
  commands.
- Q: How much of compensation does this spec define? → A: Only that optional compensation methods exist
  (querying the affected aggregate and undoing its state, e.g. deleting it or dispatching compensating
  commands). What they do and when they run is defined in a separate spec.
- Q: Must steps run in the order the saga declares them? → A: No. Each step is triggered by its event, not by
  declaration order. `@EndSaga` stays, and a saga may declare several `@EndSaga` steps, because it can offer
  several execution paths with different outcomes depending on which events earlier steps raised.
- Q: How does a saga decide which steps commit together? → A: Implicitly, through a saga-scoped unit of work:
  steps triggered by events raised inside it run in it. It commits when an `@EndSaga` step completes, and the
  aggregate state changes of every step in it commit together. `SagaContext` is managed independently of that
  unit of work, associated with the saga by its id, and stays queryable by subsequent steps whether the unit
  of work committed or rolled back.
- Q: What happens to the saga-scoped unit of work when the saga must wait for an outside event before
  reaching an `@EndSaga` step? → A: A wait is a commit point. The unit of work commits when an `@EndSaga`
  step completes or when the chain of in-process events stops to wait, whichever comes first. The next
  outside event starts a new saga-scoped unit of work for the same instance. Undoing work committed before a
  wait is left to the compensation spec.
- Q: When a saga-scoped unit of work rolls back, does the saga end or stay alive for later events? → A: It
  ends `FAILED` for now; no further steps run. A step retry design is needed later and will revisit this.
- Q: Should steps be able to abort a saga programmatically (e.g. `SagaLifecycle.abort()`)? → A: Not in this
  feature. Abort was considered and dropped for now because it adds more complexity than value at this
  stage of the saga design; it may return in a later iteration.
- Q: How is `SagaContext` stored? → A: As its own record, separate from the saga instance (e.g. a separate
  table), carrying the saga id to reference the saga instance.
- Q: Which statuses can a saga have? → A: The existing `SagaStatus` values: `CREATED`, `RUNNING`,
  `COMPENSATING`, `COMPLETED`, and `FAILED`. A simpler three-status set was considered and rejected.
- Q: How does code record a domain event so the framework intercepts it? → A: Through a static
  `DomainEvents.record(event)` call that adds the event to the active unit of work. No base class or interface
  is required, and aggregates may be event-sourced or not. Saga Forge does not verify that an event follows a
  successful aggregate state transition; that is the client code's responsibility.
- Q: Who loads and persists the aggregate for an `@Aggregate`-hosted command handler? → A: Client code, never
  through a Saga Forge interface: the host integration adapts the client's existing repositories (Spring Data
  in the Spring starter), a client method marked `@AggregateLoader` overrides that, creation handlers are
  constructors or static factories, and service-hosted handlers remain available.
- Q: How is an aggregate persisted after a handler when the client supplies an `@AggregateLoader`? → A: An
  optional `@AggregateSaver` method is called if present; otherwise the client's own unit of work persists the
  change (for example JPA dirty checking).
- Q: How are aggregates identified on aggregates, commands, and events? → A: One `@AggregateIdentifier`
  annotation: exactly one per `@Aggregate` and per `@DomainEvent` type, at most one per `@Command` type
  (creation commands may have none).
- Q: Does a domain event declare which aggregate type it belongs to? → A: Yes, `@DomainEvent(aggregate =
  X.class)` is required, and the engine records the aggregate type and id touched by each completed step.

## User Scenarios & Testing *(mandatory)*

The "users" of this feature are **saga authors**: developers who declare a `@Saga` class, the aggregates and
services that handle its commands, and the domain events that connect them. The model is inspired by Axon
sagas: `@SagaStep` methods are saga event handlers that dispatch commands through a command gateway.

A reference saga used throughout this spec, `CreateBookingSaga`, has two possible outcomes:

- `onBookingRequested` (`@StartSaga`) stores the booking details in `SagaContext` and dispatches *claim
  availability*. The inventory aggregate raises *availability claimed* in-process, which triggers
  `onAvailabilityClaimed`. That step reads the amount from `SagaContext` and dispatches *initiate payment*.
  No step reacts to *payment initiated*, so the chain stops: the first unit of work commits and the saga waits
  for the payment provider's callback.
- **Outcome "confirmed"**: *payment confirmed* arrives through the transport and triggers
  `onPaymentConfirmed`, which dispatches *confirm booking*. The booking aggregate raises *booking confirmed*,
  which triggers `onBookingConfirmed` (`@EndSaga`).
- **Outcome "declined"**: *payment declined* arrives through the transport and triggers `onPaymentDeclined`
  (`@EndSaga`), which reads the claim id from `SagaContext` and dispatches *release availability*.

### User Story 1 - Event-triggered steps run in one saga-scoped unit of work (Priority: P1)

A saga author declares steps that each react to one domain event. Steps run when their event is raised, not in
the order they are declared. Every step of the saga instance that is triggered by an event raised inside the
same unit of work runs in that unit of work, and their aggregate changes commit together when an `@EndSaga`
step completes — or none of them do.

**Why this priority**: This is the core promise of the model — declarative, all-or-nothing orchestration
across aggregate boundaries. A saga whose whole path runs in one unit of work is already a useful MVP.

**Independent Test**: Declare a three-step saga whose events are all raised in-process and whose command
handlers change three different aggregates, and run it against a test-only execution harness that simulates
the unit of work. Verify the happy path commits all effects together, and that injecting a failure at each
step position leaves no aggregate effect behind.

**Acceptance Scenarios**:

1. **Given** a three-step saga whose events are all raised in-process, **When** the start event arrives and
   every dispatched command is handled successfully, **Then** the effects on all involved aggregates commit
   together, the saga is `COMPLETED` with its `@EndSaga` step recorded as the outcome, and no intermediate
   aggregate state is observable from outside the unit of work.
2. **Given** the same saga, **When** the command handler invoked by the second step fails, **Then** none of the
   first or second step's aggregate effects remain, and the failure is recorded on the saga instance with the
   failing step and the cause.
3. **Given** the same saga, **When** the `@EndSaga` step fails, **Then** the aggregate effects of all three
   steps are rolled back; a failure at the final position is not treated as a partial success.
4. **Given** a step that lifts a value into `SagaContext`, **When** a later step of the same unit of work
   reads it to build a command, **Then** the value is available.
5. **Given** a saga whose steps are declared in the class in a different order from the order their events
   are raised, **When** it runs, **Then** the steps run in the order their events are raised.
6. **Given** a saga with no `@StartSaga` step, no `@EndSaga` step, or two steps reacting to the same event
   type, **When** the saga class is validated, **Then** a validation error is reported for each problem.

---

### User Story 2 - Steps dispatch commands through a command gateway; handlers emit events (Priority: P1)

A step reacts to a domain event and dispatches zero or more `@Command`s through the framework's command
gateway, which routes each one to the single `@CommandHandler` for its type. The handler lives on an
`@Aggregate` (loaded and saved by the client's own persistence code) or on a service class that calls into an
aggregate. Code running in the unit of work records domain events with `DomainEvents.record(...)`, and the
framework intercepts them; application code outside a unit of work publishes through the event publisher.
Step methods never return commands.

**Why this priority**: The step → command → handler → event chain is how every saga actually advances.
Without it, US1 has nothing to execute.

**Independent Test**: Validate a set of saga, aggregate, and service classes containing correct and incorrect
`@CommandHandler` declarations and step signatures, and confirm every rule below is reported precisely. Then
run one step against the test harness, once with an aggregate-hosted handler on a plain class loaded through
an `@AggregateLoader` and once with a service-hosted handler, and confirm that in both cases the command
reaches the right handler and the event recorded with `DomainEvents.record` triggers the next step.

**Acceptance Scenarios**:

1. **Given** an `@Aggregate` class that extends and implements no Saga Forge type, with a `@CommandHandler`
   method taking a `@Command`-annotated parameter, **When** validated, **Then** it is accepted.
2. **Given** a non-aggregate service class with a `@CommandHandler` method that loads an aggregate and calls
   one of its methods, **When** validated, **Then** it is accepted.
3. **Given** a `@CommandHandler` method with no `@Command`-annotated parameter, or with two, **When**
   validated, **Then** a validation error names the method.
4. **Given** two `@CommandHandler` methods accepting the same command type, **When** validated, **Then** a
   validation error names the command type.
5. **Given** a `@SagaStep` method that declares a return value, **When** validated, **Then** a validation
   error states that steps dispatch commands through the command gateway rather than return them.
6. **Given** a running unit of work, **When** a step dispatches a command, **Then** exactly the one matching
   `@CommandHandler` handles it inside that unit of work, and the domain event recorded while it runs is
   delivered to the step of the same saga instance that reacts to it.
7. **Given** a service-hosted handler that records a domain event with `DomainEvents.record`, **When** the
   handler returns, **Then** that event triggers the next step exactly as an event recorded inside an
   aggregate would.
8. **Given** application code outside any unit of work (e.g. a request handler) that publishes the start
   event through the event publisher, **When** the event is delivered, **Then** it starts a new saga instance.
9. **Given** a step that dispatches a command type with no registered handler, or whose dispatched command's
   handler fails even though the step catches the failure, **When** the step runs, **Then** the unit of work
   is rolled back and the failure names the command type or the cause.
10. **Given** a creation command handled by an `@Aggregate` constructor, **When** a step dispatches it,
    **Then** the new aggregate is persisted through the client's `@AggregateSaver` or repository, inside the
    unit of work.
11. **Given** an instance-method handler whose target aggregate does not exist, **When** a step dispatches the
    command, **Then** the unit of work fails and is rolled back.
12. **Given** a `@DomainEvent` without exactly one `@AggregateIdentifier` field, or whose `aggregate` attribute
    names a class that is not an `@Aggregate`, **When** validated, **Then** a validation error names the event
    type.
13. **Given** code that calls `DomainEvents.record` with no active unit of work, **When** it runs, **Then** the
    call fails immediately and nothing is delivered.

---

### User Story 3 - Long-running sagas wait between units of work and reach one of several outcomes (Priority:
  P2)

A saga author models a process that waits on outside parties, such as a payment provider's callback. When the
chain of in-process events stops before an `@EndSaga` step completes, the unit of work commits and the saga
waits, possibly for minutes or days and possibly in another application instance, for the next event. That
event starts a new unit of work. Which `@EndSaga` step the saga reaches depends on the events raised along the
way. Throughout, the saga records which steps completed, and `SagaContext` carries values from earlier steps to
later ones, independently of whether a unit of work committed.

**Why this priority**: Real cross-aggregate processes wait on external parties and have more than one
outcome. It builds on US1 and US2, which already deliver value for sagas that finish in one unit of work.

**Independent Test**: Run `CreateBookingSaga` against the test harness. Commit the first unit of work, then
resume from the persisted saga state in a fresh harness instance, once with *payment confirmed* and once with
*payment declined*. Then inject a failure in the second unit of work and confirm the first unit of work's
effects, the step statuses, and `SagaContext` are all still there.

**Acceptance Scenarios**:

1. **Given** `CreateBookingSaga`, **When** the first unit of work stops after *payment initiated*, **Then**
   availability is claimed, payment is initiated, and the saga is persisted `RUNNING`, with
   `onBookingRequested` and `onAvailabilityClaimed` recorded as completed and every other step as not run.
2. **Given** the waiting saga, **When** *payment confirmed* arrives through the transport (possibly in a
   different application instance), **Then** it is correlated to the instance by `associationProperty`, a new
   unit of work runs `onPaymentConfirmed` and `onBookingConfirmed`, and the saga is `COMPLETED` with outcome
   `onBookingConfirmed`, while `onPaymentDeclined` stays not run.
3. **Given** the waiting saga, **When** *payment declined* arrives instead, **Then** `onPaymentDeclined` reads
   the claim id from `SagaContext`, dispatches *release availability*, and the saga is `COMPLETED` with outcome
   `onPaymentDeclined`.
4. **Given** the waiting saga, **When** the handler of *confirm booking* fails, **Then** the second unit of
   work is rolled back, the first unit of work's effects stay committed, and the failure is recorded on the
   instance with the failing step and the cause. Undoing the first unit of work's effects is the subject of
   the separate compensation spec.
5. **Given** a value a step wrote into `SagaContext` in a unit of work that was later rolled back, **When** a
   subsequent step of the same saga queries it, **Then** the value is still there.
6. **Given** a `COMPLETED` saga, **When** another event correlated to it arrives, **Then** it is reported as
   unexpected and has no effect.

---

### User Story 4 - Documentation and canonical examples show the real model (Priority: P3)

Every saga example a newcomer can find (the README pitch, `docs/use-cases.md`, the runnable
`saga-forge-example`, the 001 quickstart, and the agent guidance) shows event-triggered steps that dispatch
commands through the command gateway to `@CommandHandler`s, uses `SagaContext` to pass values between steps,
and has at least one `@EndSaga` outcome. None shows a step returning a command.

**Why this priority**: The current examples actively teach the wrong model, which is how this confusion
started. It depends on US1 to US3 being settled, so it comes last.

**Independent Test**: Search the repository's docs, specs, and example module for step methods returning
commands, and confirm the runnable example compiles and passes validation under the new rules.

**Acceptance Scenarios**:

1. **Given** the README, `docs/use-cases.md`, `docs/architecture.md` (vocabulary), `AGENTS.md` (saga model),
   the 001 quickstart, and the `saga-forge-example` module, **When** they are reviewed after this feature,
   **Then** no step returns a command, and every example shows `associationProperty`, `@StartSaga`, its
   `@EndSaga` outcomes, and the `@CommandHandler` that handles each dispatched command.
2. **Given** the runnable `OrderProcessingSaga` example, **When** validated, **Then** it passes every rule in
   this spec.
3. **Given** the historical `.kiro/specs/saga-forge-framework/` requirements that mandate steps emitting
   commands (Requirement 8.4, Requirement 11), **When** this feature lands, **Then** they are explicitly
   marked as superseded by this spec rather than silently contradicted.

---

### Edge Cases

- **Several steps triggered in one unit of work**: a handler raises events that trigger more than one step of
  the same saga instance. Each of those steps runs in the same unit of work, in the order the events were
  raised.
- **Outcome reached while events are still pending**: an `@EndSaga` step completes while other events raised in
  the same unit of work would trigger further steps of the instance. The first `@EndSaga` step to complete
  decides the outcome; the unit of work commits, and the remaining events no longer affect the completed
  saga.
- **Event that matches no instance**: an event that triggers a non-start step but correlates to no active saga
  instance is reported as unmatched and has no effect.
- **Event for a finished saga**: an event correlated to a `COMPLETED` or `FAILED` instance is reported as
  unexpected and has no effect (consistent with 001's `InvalidSagaTransitionException`).
- **Concurrent events for one instance**: at most one unit of work of a saga instance runs at a time. If two
  events for the same instance race, they are processed one after the other, each against the state the
  other left behind.
- **Same event type in two steps**: two steps of the same saga react to the same event type. Which one runs
  would be ambiguous, so this is a validation error.
- **Duplicate start event**: the start event is delivered twice while a correlated instance is active. This is
  already rejected by `SagaLifecycle.start` (`DuplicateSagaInstanceException`, feature 001).
- **Saga that never reaches an outcome**: a saga waiting for an event that never arrives stays `RUNNING`
  indefinitely in this feature; see Assumptions.
- **Non-transactional side effects**: a command handler calls an external system (e.g. a payment gateway)
  that a rollback cannot undo. Rolling back the unit of work does not undo that effect; see Assumptions.
- **Event about an aggregate in another service**: an event such as *payment confirmed* belongs to an aggregate
  that lives in another service. Its `aggregate` attribute must still name a local `@Aggregate` class, for
  example a handler-less class that represents the remote aggregate.
- **Recording from another thread**: a handler that records an event from a thread it started itself has no
  active unit of work on that thread, so the `DomainEvents.record` call fails (FR-015).
- **Target aggregate not found**: an instance-method handler's aggregate cannot be loaded for the command's
  `@AggregateIdentifier`. The unit of work fails and is rolled back (FR-013).
- **Saga class declaring a `@CommandHandler`**: rejected in this feature. Whether a `@StartSaga` step may also
  be a `@CommandHandler` is the next iteration's question.

## Requirements *(mandatory)*

### Functional Requirements

**Steps and outcomes (US1, US3)**

- **FR-001**: Steps MUST be triggered by their events, never by declaration order. Each step reacts to exactly
  one `@DomainEvent` type (existing rule), and no two steps of the same saga MAY react to the same event type.
- **FR-002**: A saga MUST have exactly one `@StartSaga` step and at least one `@EndSaga` step (existing rules).
  Each `@EndSaga` step represents one possible outcome of the saga.
- **FR-003**: Every step, not only the start step, MUST be correlated to its saga instance by
  `associationProperty`. An event that triggers a non-start step and correlates to no active instance MUST be
  reported as unmatched; an event correlated to a finished instance MUST be reported as unexpected. Neither
  has any effect.
- **FR-004**: The saga instance MUST record a status for each of its steps: not run or completed. A step is
  completed when its method returned without error, every command it dispatched was handled successfully, and
  its unit of work committed. Step statuses commit with the unit of work, so a step is never recorded as
  completed when its aggregate effects were rolled back. With each completed step the instance MUST also record
  the aggregates the step touched: the aggregate type and `@AggregateIdentifier` value of every domain event
  recorded while the step ran.
- **FR-005**: A saga instance's status MUST be one of the existing `SagaStatus` values. `CREATED`: the
  instance exists but its first unit of work has not committed yet. `RUNNING`: at least one unit of work has
  committed and no outcome has been reached. `COMPLETED`: an `@EndSaga` step completed and its unit of work
  committed; the instance MUST record which `@EndSaga` step was reached as the outcome, and steps on paths
  not taken stay not run. `FAILED`: a unit of work rolled back (FR-021). `COMPENSATING` is reserved for the
  separate compensation spec and is never entered in this feature.

**Commands and handlers (US2)**

- **FR-006**: A `@SagaStep` method MUST NOT return a value; declaring a return type is a validation error. This
  supersedes `.kiro/specs/saga-forge-framework/` Requirement 8.4 and Requirement 11.
- **FR-007**: The framework MUST provide a command gateway, owned by `saga-forge-core` as a transport-agnostic
  contract whose implementations can vary. A step MAY dispatch zero or more commands through it. The gateway
  MUST route each command to the single `@CommandHandler` for its type and run that handler inside the current
  saga-scoped unit of work.
- **FR-008**: `saga-forge-core` MUST provide the markers `@CommandHandler` (method or constructor),
  `@Aggregate` (type), `@AggregateIdentifier` (field), `@AggregateLoader` (method), and `@AggregateSaver`
  (method), consistent with the existing `@Command`/`@DomainEvent` markers and with zero dependencies.
  `@DomainEvent` gains one required attribute, `aggregate`, naming the event's aggregate type (FR-014).
- **FR-009**: A `@CommandHandler` method or constructor MUST declare exactly one parameter whose type is
  annotated with `@Command`. Other, non-command parameters are permitted. A handler with zero or several
  `@Command`-typed parameters is a validation error.
- **FR-010**: A `@CommandHandler` MAY be declared as an instance method of an `@Aggregate` class (the target
  aggregate is loaded first, FR-013), as a constructor or static factory method of an `@Aggregate` class (a
  creation handler that produces a new aggregate), or as a method of a non-aggregate service class. A `@Saga`
  class MUST NOT declare `@CommandHandler` methods in this feature.
- **FR-011**: No `@Command` type MAY be handled by more than one `@CommandHandler` across the validated
  classes; that is a validation error. A command dispatched at runtime with no handler fails the unit of work
  and names the command type.
- **FR-012**: A failing command handler MUST cause its unit of work to roll back, even if the dispatching step
  catches the failure.
- **FR-013**: Aggregate persistence MUST stay in client code: no client class has to implement or extend a
  Saga Forge type to have its aggregates loaded or saved. Before an instance-method handler of an `@Aggregate`
  runs, the framework obtains the target aggregate, identified by the command's `@AggregateIdentifier`, from a
  client method marked `@AggregateLoader` for that aggregate type if one exists, otherwise from the host
  integration's adapter for the client's existing repositories (Spring Data repositories in the Spring
  starter). After the handler (or a creation handler) returns, the framework calls the client's
  `@AggregateSaver` method for that type if one exists, otherwise the host adapter's save, otherwise it relies
  on the client's own unit of work to persist the change. A missing target aggregate fails the unit of work.
  An `@Aggregate` with instance-method handlers and no way to load it MUST be reported at application
  startup.
- **FR-014**: Every `@Aggregate` class and every `@DomainEvent` type MUST declare exactly one
  `@AggregateIdentifier` field, and every `@Command` type at most one. A command handled by an instance-method
  handler of an `@Aggregate` MUST declare one. Every `@DomainEvent` MUST name its aggregate type through
  `@DomainEvent(aggregate = ...)`, and that type MUST be an `@Aggregate` class. At most one `@AggregateLoader`
  and one `@AggregateSaver` MAY exist per aggregate type.

**Domain events and the event publisher (US2)**

- **FR-015**: Code running inside a unit of work (an aggregate, a command handler, a domain service) MUST be
  able to record a domain event through a static `DomainEvents.record(event)` call, without inheriting from or
  implementing any Saga Forge type and regardless of how the aggregate is stored, event-sourced or not. The
  framework intercepts every recorded event. Calling `record` with no active unit of work MUST fail
  immediately. Saga Forge does not verify that a recorded event follows a successful aggregate state
  transition; that is the client code's responsibility.
- **FR-016**: The framework MUST provide a dedicated, injectable event publisher, owned by `saga-forge-core`
  as a transport-agnostic contract, that application code can invoke programmatically to publish
  `@DomainEvent` instances, typically outside any unit of work (for example to start a saga). It is distinct
  from the `EventDispatcher` transport SPI. Inside a unit of work, publishing is equivalent to
  `DomainEvents.record`.
- **FR-017**: Events from both sources are treated identically. Inside a unit of work, an event that triggers a
  step of the same saga instance is delivered to that step within the same unit of work. All other events are
  delivered through the configured transport only after the unit of work commits, and are discarded if it
  rolls back. Events published outside any unit of work are delivered through the configured transport
  immediately.

**Saga-scoped unit of work (US1, US3)**

- **FR-018**: An event delivered through the transport, or published outside any unit of work, that triggers a
  step of a saga MUST start a new saga-scoped unit of work for the correlated instance. For the `@StartSaga`
  step it also creates the instance.
- **FR-019**: Every step of the same saga instance triggered by an event raised inside a unit of work MUST run
  in that unit of work, and the aggregate state changes of all its steps MUST commit together or not at all.
  Events MUST NOT pass through an asynchronous transport between steps of the same unit of work.
- **FR-020**: A unit of work MUST commit when an `@EndSaga` step completes, or when no further step of the
  instance is triggered in-process (a wait point), whichever comes first. At a wait point the instance stays
  `RUNNING`. The next transport event that triggers one of its steps starts a new unit of work, which MUST
  be able to run from the persisted saga state alone, in a different application instance if necessary.
- **FR-021**: If any step or command handler fails, the unit of work MUST be rolled back in full, and units of
  work that already committed are not affected. The failure MUST be recorded on the saga instance with the
  failing step and the cause, and the saga MUST end `FAILED`; no further steps of it run. The failure record
  is persisted independently of the rolled-back unit of work, so a failure in the very first unit of work
  still leaves an inspectable `FAILED` instance.
- **FR-022**: At most one unit of work of a given saga instance MAY run at a time.

**Saga context (US1, US3)**

- **FR-023**: Steps MUST be able to lift values into the saga's `SagaContext` (feature 001's `putState` /
  `updateStateAttribute`), and later steps, in the same or a later unit of work, MUST be able to query them
  (`getState`) to build and dispatch commands.
- **FR-024**: `SagaContext` MUST be persisted as its own record, separate from the saga instance record (e.g. a
  separate table, or a separate collection for a document store), carrying the saga id that references the
  saga instance. It is managed independently of the saga-scoped unit of work: values written by a step MUST
  stay queryable by subsequent steps whether that step's unit of work committed or rolled back.

**Compensation (acknowledged, defined separately)**

- **FR-025**: A saga MAY declare optional compensation methods (`@SagaCompensation(forStep = "...")`, existing
  validation unchanged). A compensation queries the affected aggregate and undoes its state change, for
  example by deleting the aggregate or dispatching compensating commands. What compensations do and when they
  run is defined in a separate spec; this spec only requires that step statuses, the aggregates each completed
  step touched (FR-004), the recorded failure, and `SagaContext` remain available to them.

**Validation and documentation (all stories)**

- **FR-026**: Every declaration rule in this spec MUST be reported through the existing scanner validation
  result, together with the existing rules, in a single pass that reports all errors rather than stopping at
  the first.
- **FR-027**: The README, `docs/use-cases.md`, `docs/architecture.md` vocabulary, `AGENTS.md` saga model, the
  001 quickstart, and the `saga-forge-example` module MUST be updated to the model in this spec. Superseded
  historical requirements MUST be marked as superseded.

### Key Entities

- **Saga**: the declarative orchestrator for one instance of a cross-aggregate policy, modeled on Axon sagas.
  Identified by `@SagaId`, correlated by `associationProperty`, with one `@StartSaga` step and one or more
  `@EndSaga` outcomes.
- **Saga instance**: one running or finished saga. Records its status (`CREATED`, `RUNNING`, `COMPLETED`,
  or `FAILED`), the status of each step with the aggregates it touched, the outcome reached, and any failure.
- **Step**: a `@SagaStep` method that reacts to one domain event type and dispatches zero or more commands. Its
  effect is the state transitions caused by the commands it dispatched.
- **Outcome**: one `@EndSaga` step. A saga reaches exactly one outcome when it completes.
- **Saga-scoped unit of work**: the transaction in which a chain of in-process events runs steps of one saga
  instance. It commits at an `@EndSaga` step or at a wait point, and rolls back on any failure.
- **Saga context**: named values steps lift for later steps. Stored as its own record that references the saga
  instance by saga id, and managed independently of the unit of work.
- **Command**: an intent to change one aggregate's state, marked `@Command`, handled by exactly one command
  handler.
- **Command gateway**: the framework-owned way steps dispatch commands to their handlers.
- **Command handler**: a method or constructor marked `@CommandHandler`, on an aggregate or a service, that
  accepts one command and performs the aggregate state transition.
- **Aggregate**: the consistency boundary whose state a command changes, marked `@Aggregate` and identified by
  its `@AggregateIdentifier` field. Loaded and persisted by client code, through the client's repositories or
  an `@AggregateLoader` / `@AggregateSaver` method; it extends and implements no Saga Forge type.
- **Domain event**: a fact about one aggregate, marked `@DomainEvent(aggregate = ...)` and carrying that
  aggregate's `@AggregateIdentifier`. Normally recorded after the aggregate's successful state transition,
  which client code is responsible for.
- **Domain event recorder**: the static `DomainEvents.record(...)` entry point that adds an event to the active
  unit of work, where the delivery rules of FR-017 apply.
- **Event publisher**: the injectable, programmatically invocable way for application code to publish domain
  events, typically outside a unit of work.
- **Compensation**: an optional, declared undo for one step's effect. Defined in a separate spec.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of the declaration errors defined in this spec are reported at application startup, in one
  validation pass, before any event is processed. These are: missing `@StartSaga` or `@EndSaga`, duplicate
  event type, step return value, handler without exactly one command, command with more than one handler,
  `@CommandHandler` on a saga class, missing or duplicate `@AggregateIdentifier`, an event `aggregate` that is
  not an `@Aggregate`, more than one loader or saver per aggregate type, and an aggregate with instance-method
  handlers but no way to load it.
- **SC-002**: For a three-step saga that runs in one unit of work, injecting a failure at each of the three
  step positions leaves zero aggregate effects behind in all three cases, while every `SagaContext` value
  written before the failure stays queryable.
- **SC-003**: For `CreateBookingSaga`, the second unit of work can run from the persisted saga state alone in a
  fresh execution environment, and ends with the same outcome as an uninterrupted run.
- **SC-004**: For `CreateBookingSaga`, both outcomes are reachable: 100% of runs with *payment confirmed* end
  with outcome `onBookingConfirmed`, 100% of runs with *payment declined* end with outcome
  `onPaymentDeclined`, and in both cases every step on the path not taken is recorded as not run.
- **SC-005**: A search of the README, `docs/`, `specs/`, `AGENTS.md`, and the example module finds zero step
  examples that return a command once this feature lands.
- **SC-006**: A developer reading the canonical example can name, for every step, the event that triggers it,
  the commands it dispatches, the handler of each command, and the outcomes it can lead to, without reading
  framework source.

## Assumptions

- **Scope**: this feature defines the programming model, its startup validation, and the execution contract
  (event-triggered steps, saga-scoped units of work, command gateway, event recording and publishing,
  aggregate loading through client code, step statuses, outcomes, independent `SagaContext`). Adapting Spring
  Data repositories for aggregate loading belongs to the Spring starter feature. The execution contract is
  exercised against a test-only harness, as feature 001 did with its test doubles. The production saga engine,
  storage providers, and transports remain separate follow-up features.
- **Builds on 001**: feature 001's `SagaLifecycle` never moves an instance into `FAILED`, and `SagaInstance`
  has no per-step status or outcome. This feature is expected to extend those contracts; the per-step status
  of FR-004 is also the missing "current step" information flagged as a concern in feature 001. Feature 001
  mapped `SagaContext` values onto `SagaInstance.state`; FR-024 instead stores them as a separate record
  referencing the saga instance by saga id, which supersedes that 001 decision. `SagaStatus` itself is
  unchanged; this feature uses `CREATED`, `RUNNING`, `COMPLETED`, and `FAILED`, and leaves `COMPENSATING` to
  the compensation spec.
- **Compensation**: compensation behavior and triggering are defined in a separate spec. Until then, a
  `FAILED` saga's committed effects stay in place.
- **Deferred**: whether a `@StartSaga` step may also be a `@CommandHandler` is explicitly left to the next
  iteration. Programmatic abort of a saga by a step (e.g. `SagaLifecycle.abort()`) is out of scope for now and
  may return later.
- **No waiting timeouts**: a saga waiting for its next event waits indefinitely in this feature. Deadlines
  (e.g. "expire if the owner never accepts") stay a separate, scheduled process, as in `docs/use-cases.md`.
- **Unit of work**: a saga-scoped unit of work is the host application's local transaction, the same one the
  saga's storage provider participates in. The framework introduces no distributed (two-phase) transactions.
- **External side effects**: effects outside that transaction (e.g. a call to a payment gateway) are not undone
  by a rollback. Authors who need them undone should let the unit of work commit at a wait point after that
  step, so the effect is committed and can be covered by compensation.
- **Step retry (planned follow-up)**: a rollback ends the saga `FAILED` in this feature (FR-021). A separate
  step retry design is planned and will revisit this, for example by keeping the saga running so a failed
  step can be re-attempted with the `SagaContext` values it already wrote. Nothing in this feature may
  preclude that.
- **Unchanged gaps**: idempotent step execution remains an open feature gap
  (`docs/architecture.md#feature-gaps`).
- **jMolecules**: `@Aggregate` is Saga Forge's own marker. It neither requires nor replaces jMolecules'
  `@AggregateRoot`, consistent with the documented optional-interop stance.
