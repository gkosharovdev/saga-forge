# Feature Specification: Saga Outcome Events

**Feature Branch**: `011-saga-outcome-events`

**Created**: 2026-11-20

**Status**: Draft

**Input**: User description: "Saga outcome events and failure-text hygiene (roadmap feature 011, see .specify/memory/roadmap.md "011" brief; closes G-ADOPT-01 and G-ADOPT-03). When a saga instance reaches a terminal state (completed, failed, compensated, requires intervention) the runtime records a framework outcome event (SagaCompleted, SagaFailed, SagaCompensated, SagaRequiresIntervention: saga type, saga id, association, terminal step, classification, cause type, attempts, time; never the failure message) in the same transaction as the terminal write, handed to the Outbox so applications, Spring listeners and other sagas can react durably, exactly once per terminal transition, including a FINAL failure before any unit of work committed. Also a FailureMessageRedactor SPI applied before any failure message is persisted (instance, lifecycle history) or emitted (spans), with a generic default (length cap, generic credential shapes) and a setting to record only the cause type. Keep it generic: no application failure taxonomy, no provider-specific patterns, no @OnSagaFailed callbacks."

## Context

A saga instance ends in one of four terminal statuses: `COMPLETED` (an `@EndSaga` step ran), `FAILED` (an
unclassified failure, or a final or exhausted failure before any of its units of work committed), `COMPENSATED`
(every committed step was undone) or `REQUIRES_INTERVENTION` (a compensation failed; renamed by this feature, see
below). Today each of these is written to
the instance and the lifecycle history, and nothing else happens. The code that started the saga (a request handler,
a policy, another module) cannot learn the outcome except by reading storage. The first adopter had to poll storage
every few seconds to learn that a saga failed, and did so for two different sagas (roadmap G-ADOPT-01).

The `FAILED` case is the sharpest. A final failure in the start step rolls the start unit of work back and writes
`FAILED` in a transaction of its own. No step committed, so no business event was recorded either. The failure is
silent to everything outside Saga Forge.

The name `REQUIRES_INTERVENTION` states a judgement the saga cannot make. A compensation fails with an exception like
any other step; whether that failure needs a person is for an observer to decide. This feature renames the status to
the fact it records, `COMPENSATION_FAILED`.

This feature adds an outcome event when a saga completes, fails or is compensated, and when its compensation fails,
committed with that write.

## Clarifications

### Session 2026-11-20

- Q: Is there a "saga requires intervention" outcome event? → A: No (user). Whether a saga needs a person is a
  judgement an observer makes from the failures it sees and the time spent, not something a saga can decide about
  itself. 011 announces three outcomes only: completed, failed, compensated.
- Q: What does a failed compensation announce? → A: A failure, like any other (user). A compensating step fails with
  an exception and declares a failure; it does not know whether that calls for intervention. The "saga failed" event
  for it carries both facts: the failure that triggered compensation and the compensation's own failure. The observer
  sees that the happy path failed, compensation started and the compensation failed, and decides what to do (FR-006).
- Q: What happens to the `REQUIRES_INTERVENTION` status? → A: Renamed to `COMPENSATION_FAILED` in this feature
  (user). The framework can only say that the compensation failed, not whether the failure must be acted upon
  (FR-019 to FR-022; closes memory.md G-011-02).
- Q: Does 011 clean failure text (the roadmap's SF-8)? → A: No, dropped from this feature (user). Logs and spans can be
  cleaned with existing tools (logging-backend masking, the OpenTelemetry Collector's redaction processors); what Saga
  Forge stores stays a later decision (memory.md G-ADOPT-03 stays open).
- Q: How does the runtime structure its transactions for this? → A: One unit-of-work abstraction that does not know
  the execution phase it serves, replacing the phase-specific unit-of-work and write classes (user). It is a
  behaviour-preserving refactoring done first in this feature; see plan.md. It has no user-visible effect (SC-006).
- Q: Do saga steps consume outcome events in this feature? → A: No. 011 delivers outcome events to application
  listeners, policies and transport subscribers. A saga step reacting to another saga's outcome is deferred to its
  own spec (memory.md G-011-01); the scanner keeps rejecting a step whose parameter is not a domain event (FR-017).

## User Scenarios & Testing *(mandatory)*

The "users" of this feature are application developers who start sagas and need to react to how they end, and
operators who watch saga instances.

### User Story 1 - React to a failed saga without polling (Priority: P1)

An application starts a saga from a request it accepted. The saga's start step fails with a final failure before
anything committed. The application has a listener for the framework's "saga failed" event. After the failure write
commits, the listener receives one event that names the saga type, the instance, its association value, the failed
step, the failure classification, the cause type and the attempts made. The listener marks the request as failed. No
polling, no storage reads.

**Why this priority**: it is the gap that forced the first adopter to poll storage, and the one case where nothing
else (no business event, no committed step) reveals what happened.

**Independent Test**: a saga whose start step throws a final failure; a listener on the failure event; assert the
listener received exactly one event with the expected fields after the instance was stored `FAILED`.

**Acceptance Scenarios**:

1. **Given** a saga whose start step throws a final failure, **When** its start event is handled, **Then** the
   instance is stored `FAILED` and exactly one "saga failed" event is delivered, after the failure write committed,
   carrying the failed step, classification `FINAL`, the cause type and the attempt count.
2. **Given** a step with a retry policy whose attempts are all used up, **When** the last attempt fails before any
   unit of work committed, **Then** one "saga failed" event with classification `EXHAUSTED` and the attempt count
   is delivered.
3. **Given** an unclassified failure in a later step, after earlier units of work committed, **When** the instance
   is stored `FAILED`, **Then** one "saga failed" event with classification `UNCLASSIFIED` is delivered.
4. **Given** the failure write cannot be recorded (the instance is already terminal or the write fails), **When**
   the failure is handled, **Then** no "saga failed" event is delivered.

---

### User Story 2 - React to every way a saga ends (Priority: P1)

An application wants one consistent signal for how a saga ends: completed with a named outcome, failed, or
compensated after a business rejection or a final failure. It subscribes to the three outcome events and drives a read
model, an alert or a follow-up process from them.

**Why this priority**: completion and compensation have the same polling problem as failure.

**Independent Test**: the canonical order saga run to completion and to compensation; assert exactly one matching
outcome event per run.

**Acceptance Scenarios**:

1. **Given** a saga whose `@EndSaga` step runs, **When** that unit of work commits, **Then** one "saga completed"
   event naming the outcome step is delivered, after the events the step itself recorded.
2. **Given** a `@StartCompensation` step, or a final failure after a unit of work committed, **When** the last
   compensation commits, **Then** one "saga compensated" event is delivered that names what triggered the
   compensation (the business-failure step, or the failed step with its classification and cause type).
3. **Given** a compensation that fails, **When** the instance stops compensating, **Then** one "saga failed" event is
   delivered that names the compensation that failed, its classification, cause type and attempts, and the original
   compensation trigger; it says nothing about whether a person is needed.
4. **Given** an instance that ended, **When** a further event for it arrives later, **Then** no second outcome event
   is delivered.

---

### User Story 3 - Outcome events reach application listeners durably (Priority: P1)

An application uses an outbox (the in-memory transport, or Spring Modulith interop through the starter). Its
listeners receive outcome events with the same guarantee as any other event Saga Forge hands on: stored in the
transaction of the terminal write, delivered at least once after commit, and identifiable so a listener can ignore a
repeat. A crash right after the terminal write commits does not lose the event.

**Why this priority**: an outcome event that can be lost would push applications back to polling as a safety net.

**Independent Test**: with the in-memory transport over Postgres, stop the process right after a terminal write
commits and before delivery; on restart the outcome event is delivered.

**Acceptance Scenarios**:

1. **Given** an outbox that stores messages in the unit of work's transaction, **When** the terminal write commits,
   **Then** the outcome event is in the outbox in that same transaction; **When** the terminal write rolls back,
   **Then** it is not.
2. **Given** an outcome event delivered twice (redelivery after a crash), **When** a listener compares the events,
   **Then** both carry the same event identity.
3. **Given** a Spring Boot application with the starter, **When** a saga ends, **Then** a transactional application
   event listener and a Spring Modulith module listener each receive the outcome event after commit.
4. **Given** the default after-commit outbox (no transport), **When** a saga ends, **Then** the outcome event is
   handed on after commit with that outbox's documented at-most-once guarantee.

---

### User Story 4 - A failed compensation is recorded as a fact (Priority: P2)

An operator looks at an instance whose compensation failed. Its status reads `COMPENSATION_FAILED`, which says what
happened, not what should happen next. The operator's own tooling (an alert, a dashboard, a runbook) decides whether
it needs a person, from the failures recorded on the instance and the time it has been in that state.

**Why this priority**: the status name is part of the public model and of every stored instance; it must not state a
judgement the framework cannot make.

**Independent Test**: a saga whose compensation throws; assert the instance's status is `COMPENSATION_FAILED`, the
handling outcome is `COMPENSATION_FAILED`, and no status, outcome, history entry or event name says "intervention".

**Acceptance Scenarios**:

1. **Given** a compensation that throws, **When** the failure is recorded, **Then** the instance is
   `COMPENSATION_FAILED` and the handling result's outcome is `COMPENSATION_FAILED`.
2. **Given** a database written by 0.1.0 with instances in `REQUIRES_INTERVENTION`, **When** the upgrade script for
   this feature is applied, **Then** those instances read as `COMPENSATION_FAILED` and keep every other field.

---

### User Story 5 - Test outcome events with the saga test fixture (Priority: P2)

A saga author uses `SagaFixture` to check that the saga ends as intended. The fixture records outcome events like
other events, and the author can assert that exactly one outcome event of the expected kind and content was
produced.

**Why this priority**: saga authors should test outcome events where they test the saga, without a transport.

**Independent Test**: a fixture test for the canonical saga's compensation path asserting one "saga compensated"
event.

**Acceptance Scenarios**:

1. **Given** a fixture test whose saga completes, **When** the author asserts the outcome event, **Then** the
   assertion passes for one "saga completed" event naming the outcome step and fails with a readable message
   otherwise.
2. **Given** a fixture test with a failing step, **When** the instance ends `FAILED`, **Then** the recorded outcome
   event's failure fields match the instance's failure.

### Edge Cases

- **Final failure in the start step.** The start unit of work rolled back, the instance's creation write committed
  earlier, and the `FAILED` write runs in its own transaction: the outcome event is stored in that transaction.
- **The `FAILED` write finds the instance already terminal or missing** (another process moved it on, G-ENG-10): no
  outcome event; the earlier terminal transition already produced one.
- **The terminal write itself fails** (storage unavailable, conflict): no outcome event; the error is reported as
  today, and a later retry of the handling produces the event if it then records the terminal status.
- **`@EndSaga` step whose unit of work fails at commit**: no "saga completed" event; the failure path applies, and
  its outcome event is the one delivered.
- **Events still queued when an `@EndSaga` step ends the run** go on as today; the outcome event follows the step's
  own recorded events in recording order.
- **Compensation with nothing to compensate** (a `@StartCompensation` step in the first committed unit of work):
  the instance ends `COMPENSATED` at once and one "saga compensated" event is delivered.
- **Compensation interrupted by a crash and resumed later**: only the unit of work that makes the instance
  `COMPENSATED` stores the outcome event, so a resumed compensation still yields one.
- **Outbox store failure.** An in-transaction outbox that fails to store the outcome event fails the terminal write,
  which then rolls back like any other store failure of that outbox.
- **No consumer.** An outcome event with no listener is handed on and ignored like any unrouted event; it is not an
  error.
- **Upgrade script applied twice, or to a database with no such instance**: changes nothing.

## Requirements *(mandatory)*

### Functional Requirements

**Outcome events**

- **FR-001**: The framework MUST define three outcome event types: saga completed, saga failed and saga compensated. They are framework types, plain values with no dependency
  outside the core module.
- **FR-002**: Every outcome event MUST carry: an event identity, the saga type, the saga id, the instance's
  associations (property and value), and the time of the terminal transition.
- **FR-003**: A "saga completed" event MUST also carry the outcome step (the `@EndSaga` step that ended it).
- **FR-004**: A "saga failed" event MUST also carry the failed step, the failure classification (`FINAL`,
  `EXHAUSTED`, `UNCLASSIFIED`), the cause type and the number of attempts.
- **FR-005**: A "saga compensated" event MUST also carry the compensation trigger: its kind (business-failure step
  or failure), the step that triggered it, and, for a failure, that failure's classification, cause type and attempts.
- **FR-006**: A failed compensation (status `COMPENSATION_FAILED`) MUST produce one "saga failed" event. Its failed step is the step whose
  compensation failed, marked as a compensation, with that failure's classification, cause type and attempts; it also
  carries the compensation trigger as in FR-005. No outcome event MAY state or imply that an instance needs human
  intervention; that judgement belongs to observers (operators, alerts, the operations feature).
- **FR-007**: No outcome event MAY carry a failure message, context values or any saga-field data.
- **FR-008**: The runtime MUST record exactly one outcome event for every terminal transition it writes, as part of
  the same write: in the transaction that stores the terminal status, handed to the configured outbox as that
  transaction's outgoing messages. If that transaction rolls back, the outcome event MUST NOT be handed on.
- **FR-009**: This MUST include terminal writes outside a unit of work's own commit: the `FAILED` write after a
  rolled-back unit of work (including a failure before any unit of work of the instance committed) and the write that
  ends compensation after a compensation failed.
- **FR-010**: A terminal write that records nothing (instance missing or already terminal) MUST NOT produce an
  outcome event.
- **FR-011**: The event identity MUST be the same every time the same outcome event is delivered, and different
  for different instances. Because an instance has exactly one terminal transition, the identity MAY be derived from
  the saga id and the terminal status.
- **FR-012**: In a unit of work that ends the saga, the outcome event MUST be handed on after every event the unit
  of work's steps recorded, in the same batch.
- **FR-013**: Outcome events MUST travel through the existing outbox contract with the outbox's own guarantee: at
  least once with an in-transaction outbox (in-memory transport, Spring Modulith interop), at most once with the
  default after-commit outbox. Documentation MUST state both.
- **FR-014**: The in-memory transport MUST store, deliver and order outcome events like other events, with the
  ending instance as the ordering key, even though outcome events are not aggregate events.
- **FR-015**: The Spring Boot starter MUST publish outcome events to the application context in the terminal write's
  transaction, as it does for recorded events, so transactional event listeners and Spring Modulith module listeners
  receive them after commit.
- **FR-016**: Outcome events MUST be recorded whatever the saga declares: no annotation, setting or code in the saga
  is needed. A runtime setting MAY switch them off; they are on by default.
- **FR-017**: Saga steps MUST NOT consume outcome events in this feature: an outcome event that reaches the runtime
  through a transport is routed to no saga and reported as not handled, without error, and the scanner keeps rejecting a
  saga step whose parameter is an outcome event (it is not a domain event). Saga-to-saga reaction is deferred.
- **FR-018**: The saga test fixture MUST record outcome events and let a test assert the kind and content of the
  outcome event a saga produced, and that it produced exactly one.

**Compensation-failed status**

- **FR-019**: The terminal status of an instance whose compensation failed MUST be named `COMPENSATION_FAILED`,
  replacing `REQUIRES_INTERVENTION`, in the instance status, the handling outcome reported to callers, telemetry
  attribute values and documentation.
- **FR-020**: The lifecycle history MUST record a failed compensation with one entry, the existing "compensation
  failed" entry naming the step and its failure, which is also the instance's terminal entry; the separate "requires
  intervention" entry kind is removed.
- **FR-021**: The Postgres storage MUST ship an idempotent upgrade script that converts stored instances from
  `REQUIRES_INTERVENTION` to `COMPENSATION_FAILED` and updates the status constraint; history rows of the removed kind
  are removed, since the "compensation failed" row written with them holds the same facts.
- **FR-022**: No name in the public model (statuses, outcomes, entry kinds, event types, fields) MAY state that an
  instance needs intervention.

**Model and documentation**

- **FR-023**: The formal design (`.kiro/specs/saga-forge-framework/design.md`) MUST gain a correctness property for
  FR-008 to FR-011 (one outcome event per terminal transition, committed with it, none without it), and its compensation
  properties MUST use the renamed status. The consumer guide, the agent skill and the agent snippet MUST describe
  outcome events and the renamed status.

### Key Entities

- **Outcome event**: a framework fact that a saga instance ended. Three kinds (completed, failed, compensated). Carries identity, saga type, saga id, associations, time, plus the kind's fields
  (FR-003 to FR-006). Never carries a failure message.
- **Compensation trigger summary**: in "saga compensated" events and in "saga failed" events for a failed compensation, what started the
  compensation: kind, step, and for a failure its classification, cause type and attempts.
- **Compensation-failed status**: the terminal status recording that a compensation failed; a fact, not a call to
  act.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An application learns that a saga completed, failed (in a step or in a compensation) or was compensated without reading saga storage:
  in the acceptance suite, 100% of terminal transitions yield exactly one matching outcome event (a failed
  compensation a "saga failed" event naming it), and none yields two.
- **SC-002**: A final failure in a saga's start step is visible to the application within one delivery cycle of the
  failure write committing, with no polling code in the application.
- **SC-003**: With an in-transaction outbox, no outcome event is lost when the process stops between the terminal
  write and delivery: after a restart, 100% of outcome events from such interrupted runs are delivered.
- **SC-004**: No outcome event is delivered for a terminal write that rolled back: zero in the fault-injection suite.
- **SC-005**: No status, outcome, history entry kind, event type or documentation page of Saga Forge says that an
  instance requires intervention; an upgraded database holds no instance in the old status.
- **SC-006**: Existing sagas need no change: the existing example and acceptance suites pass unchanged apart from the
  added outcome-event assertions and the renamed status.
- **SC-007**: A saga author can assert a saga's outcome event in a fixture test with one expectation call.

## Assumptions

- Terminal statuses, compensation triggers and failure classification are as implemented by features 005 and 006;
  this feature does not change when a saga ends, only what is announced when it does.
- The outbox contract (007, 008) can carry framework events as well as recorded events; the transports and the
  starter learn to accept events without an `@DomainEvent` annotation for outcome events only.
- Outcome events need no new storage tables: they travel through the outbox and the message store like other events.
  Postgres needs no schema change for them (the rename does, FR-021).
- Consumers deduplicate by event identity or by the transport's message identity; Saga Forge does not deduplicate on
  their behalf.
- Callbacks on the saga class for its own outcome (`@OnSagaFailed`-style) are out of scope; a listener or policy
  reacting to the outcome event covers them.
- An application failure taxonomy (rejection, authorization, conflict) is out of scope; applications map cause types
  to their own categories.
- A saga step reacting to another saga's outcome is out of scope (Clarifications; G-011-01).
- An outbox command's failure being reported to the saga that sent it is feature 012, which will reuse these events'
  delivery path.
- Cleaning failure text (roadmap SF-8) is out of scope (Clarifications; G-ADOPT-03).
- The rename is a breaking change of public enum values. 0.x minor releases may break the API (DEC-REL-01); the
  release notes and the upgrade script cover it.
- Outcome events need no schema change; the rename needs the upgrade script of FR-021.
