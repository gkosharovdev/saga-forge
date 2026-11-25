# Feature Specification: Saga Deadlines

**Feature Branch**: `017-saga-deadlines`

**Created**: 2026-11-25

**Status**: Planned

**Input**: User description: "017 Saga deadlines (roadmap feature 017, .specify/memory/roadmap.md "### 017: Durable scheduling"; closes G-ENG-25; booking-service feedback SF-11, W16). Taken ahead of 013 by decision: deadlines are supported for one running instance per message store until 013 (active/standby relay) lands, and 013 must keep timed messages working under its leader lease. Scope: (1) the message store holds messages with a due time, not leased before it and cancellable by id, appended in the unit of work's transaction; (2) CommandGateway.schedule(command, Instant) returning a ScheduledCommandId, plus cancel(id); (3) annotation-driven saga deadlines: a step declares that a named deadline starts, with a duration from a property or from SagaContext; a timeout @SagaStep, typically @StartCompensation, runs when it expires; a deadline is cancelled when the step it waits for completes and whenever the instance leaves RUNNING; a deadline that comes due while the instance is COMPENSATING or terminal never runs a step. Out of scope: durable retry backoff and cron-style recurring schedules. Example: both deadlines in saga-forge-example's checkout package with fixture tests that advance FixtureClock; update docs/use-cases.md §1 and the README. Open questions: clock source and precision, poll-interval delay, overdue deadlines after downtime, deadlines with the default ImmediateDispatchOutbox, scanner rules R24+ and Properties 40+, and the timeout outcome's trigger kind." (full text in the `/speckit-specify` request of 2026-11-25)

## Context

A saga instance waits for its next event forever (G-ENG-25). "The customer must confirm the card challenge within
15 minutes" or "a courier must be assigned within two hours" cannot be said in a saga today: the first adopter runs its
own scheduler that finds stale instances and publishes a timeout event (booking-service SF-11, workaround W16).

This feature lets a saga own its deadlines declaratively (Constitution Principle II). A step says that a named deadline
starts; another step, typically a `@StartCompensation` step, says that it runs when that deadline expires. The deadline
is stored durably, in the same transaction as the step that starts it, so it survives restarts, and it is cancelled
when the awaited step completes or the instance stops running. Underneath, the message store learns to hold messages
with a due time, and `CommandGateway` gains a general `schedule`/`cancel` for commands an application wants run later.

Durable retry backoff (G-005-01, G-005-02) needs the same mechanism but is a later spec. Cron-style recurring schedules
are out of scope.

## Decisions Taken In This Spec

These settle the open questions in the request; each is reflected in the requirements below.

- **D1 — One step per deadline, selected by name, not by event type.** A timeout step is a `@SagaStep` that names the
  deadline it handles (for example `@DeadlineExpired("paymentWindow")`) and takes no parameter or one framework
  `DeadlineReached` value (name, due time, start step). The scanner keys such steps by deadline name, so a saga may
  have several (checkout's payment window and fulfilment's courier deadline, or two in one saga) without Property 11
  conflicts and without applications inventing a `@DomainEvent` per deadline. `DeadlineReached` is not a domain event
  and is never routed by association; it is addressed to one instance by its saga id.
- **D2 — Clock.** Due times are computed and compared with the runtime's clock (the host-supplied clock, the system
  UTC clock by default; `FixtureClock` in the test fixture), at millisecond precision. A due message is never handed
  over before its due time; under normal load it is handed over no later than its due time plus one relay poll
  interval.
- **D3 — Overdue after downtime.** Messages that came due while no relay was running are delivered as soon as the relay
  starts, in append order (for one instance, the order its deadlines started); a deadline is never skipped because it
  is late. Correctness never depends on due order, because each delivery is checked against the instance's open
  deadlines (plan research R11).
- **D4 — Outbox requirement.** Deadlines and scheduled commands need an `IN_TRANSACTION` outbox whose message store
  supports due times. A model that declares a deadline is rejected at startup with the default `ImmediateDispatchOutbox`
  (or any outbox without scheduling support), naming the saga and deadline; `CommandGateway.schedule` fails at the call
  under such an outbox. Nothing is silently kept in storage that no one will deliver.
- **D5 — Spring Modulith interop.** The starter's `ModulithMessageStore` rejects scheduled messages explicitly: with
  interop enabled, a model declaring a deadline fails startup through a failure analyzer that names the conflict.
  Supporting due times inside Modulith's event publication registry is recorded as a gap.
- **D6 — Timeout outcome.** A timeout step is an ordinary step. When it is a `@StartCompensation` step its method name
  is the recorded failure outcome and compensation starts as for any business failure; the compensation trigger and
  the outcome trigger gain a new kind `DEADLINE_EXPIRED` carrying the deadline name, so listeners can tell a timeout
  from a business rejection without parsing step names.
- **D7 — Cancellation.** Each deadline declares the step(s) whose completion cancels it. Every open deadline of an
  instance is cancelled in the unit of work that moves the instance out of `RUNNING` (completed, compensating, failed).
  A deadline message delivered for an instance that is not `RUNNING`, or for a deadline already cancelled or already
  handled, runs no step and is reported with a diagnostic (no outcome event, no error).
- **D8 — One running instance per message store.** Until 013 (active/standby relay) lands, deadlines are supported for
  one running application instance per message store, as for the in-memory transport today (FR-021, G-007-09). 013 must
  keep due messages working under its leader lease.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A saga times out and compensates (Priority: P1)

A developer declares on `CheckoutSaga` that the payment window (15 minutes, or a per-order value from the saga's
context) starts when payment is requested and is cancelled when payment is confirmed. If the customer abandons the
card challenge, the payment-window timeout step (a `@StartCompensation` step) runs once, and the saga compensates its
committed steps, ending `COMPENSATED` with a `SagaCompensated` outcome whose trigger says `DEADLINE_EXPIRED` /
`paymentWindow`.

**Why this priority**: it is the gap the first adopter works around (SF-11, W16) and the reason for the feature.

**Independent Test**: a fixture test starts the checkout, advances `FixtureClock` past the window without a payment
event, and expects the timeout step, the compensations and the outcome event.

**Acceptance Scenarios**:

1. **Given** a running checkout whose payment window started at T, **When** the clock passes T + window with no payment
   confirmation, **Then** the timeout step runs exactly once and the instance compensates.
2. **Given** a running checkout whose payment window started, **When** the payment confirmation arrives before the due
   time, **Then** the deadline is cancelled in that step's unit of work and no timeout step ever runs, even after the
   clock passes the due time.
3. **Given** the window duration comes from a `SagaContext` attribute written before the starting step commits,
   **When** the deadline starts, **Then** its due time uses that value; **Given** the attribute is missing or not a
   duration, **Then** the starting step fails (`FINAL` failure) with a message naming the deadline and attribute.
4. **Given** a deadline started, **When** the application restarts before it comes due, **Then** it still fires once
   after the restart; **Given** it came due during the downtime, **Then** it fires as soon as the relay starts.

---

### User Story 2 - Several deadlines in one saga or across sagas (Priority: P1)

`FulfilmentSaga` declares a courier-assignment deadline (two hours, express or standard from the context) that is
cancelled when `CourierAssigned` arrives while the saga keeps waiting days for delivery; `CheckoutSaga` has its own
payment window. A saga may declare more than one deadline, each with its own timeout step.

**Why this priority**: the checkout story already needs two deadlines; a single framework event per saga would not do.

**Independent Test**: fixture tests for both sagas; a scanner test with two deadlines and two timeout steps in one saga.

**Acceptance Scenarios**:

1. **Given** a fulfilment saga with an open courier deadline, **When** `CourierAssigned` completes its step, **Then**
   the courier deadline is cancelled and the instance stays `RUNNING` waiting for delivery with no deadline.
2. **Given** one saga declares deadlines `a` and `b`, **When** `a` expires, **Then** only the step for `a` runs.

---

### User Story 3 - A deadline never acts on a saga that stopped running (Priority: P1)

A deadline that comes due after the instance completed, failed, or started compensating never runs a step and never
starts a second compensation.

**Why this priority**: a late timeout undoing a completed order would be a correctness failure.

**Independent Test**: fixture and runtime tests that end or compensate an instance, then deliver its deadline message
directly (simulating a cancel lost in a race) and expect no step, no status change, one diagnostic.

**Acceptance Scenarios**:

1. **Given** an instance reached `@EndSaga`, **When** the clock passes any deadline it had started, **Then** nothing
   runs (the deadline was cancelled in the ending unit of work).
2. **Given** an instance is `COMPENSATING` and its deadline message is delivered anyway, **Then** no step runs, the
   message is consumed, and `saga.deadline.ignored` is logged; compensation continues as before.

---

### User Story 4 - Schedule and cancel a command (Priority: P2)

Application code (a step, a handler, or a service with a unit of work) calls `CommandGateway.schedule(command, at)` and
gets a `ScheduledCommandId`; `cancel(id)` withdraws it before it is due. The command is stored in the caller's
transaction and handled at its due time like an outbox command (007), including 012's failure feedback when sent from
a saga.

**Why this priority**: the general building block; saga deadlines are the main user, applications the second.

**Independent Test**: transport tests over `InMemoryMessageStore` and Postgres: a scheduled command is not handled
before its due time, is handled once after it, and a cancelled one is never handled.

**Acceptance Scenarios**:

1. **Given** a command scheduled for T in a transaction that rolls back, **Then** nothing is stored and nothing runs.
2. **Given** a command scheduled for a time in the past, **Then** it is due at once.
3. **Given** `cancel(id)` for a message already handed over, consumed or unknown, **Then** it returns `false` and
   changes nothing; for a pending one it returns `true` and the message is never handed over.

---

### User Story 5 - Misconfiguration fails at startup (Priority: P2)

A model with deadlines under the default `ImmediateDispatchOutbox`, or under Spring Modulith interop, fails startup
with a message naming the saga, the deadline and the fix. Invalid declarations fail model validation (R24+).

**Independent Test**: runtime builder and starter failure-analyzer tests; scanner tests per rule.

**Acceptance Scenarios**:

1. **Given** a deadline whose timeout step does not exist, or two timeout steps for one deadline, or a cancelling step
   that is not a step of the saga, **Then** validation reports the matching rule.

### Edge Cases

- A step starts a deadline that is already open on the instance: the earlier one is replaced (cancelled, new due time).
- The starting step's unit of work rolls back: the deadline was never stored.
- The timeout step itself fails: same retry and failure handling as any step (005, 006); a `FINAL`/`EXHAUSTED` failure
  compensates.
- The cancelling step and the deadline delivery race: the instance lock serialises them; whichever commits second sees
  the deadline cancelled or the step completed and runs nothing.
- A deadline's timeout step is also the step the deadline waits for: rejected by the scanner.
- A non-`@StartCompensation` timeout step (for example a reminder that sends a command and keeps waiting) is allowed.
- Clock moves backwards (host clock adjusted): due messages are judged against the current clock reading; not
  otherwise compensated for.
- Duration zero or negative: due at once.
- Many deadlines due at the same instant: delivered in append order. Scheduled messages carry no ordering key, so they
  never hold back an aggregate's events (plan research R2).

## Requirements *(mandatory)*

### Functional Requirements

Message store and transport

- **FR-001**: The message store MUST accept an appended message with an optional due time, in the appending unit of
  work's transaction; a message without one keeps today's behaviour.
- **FR-002**: A message with a due time MUST NOT be leased or handed over before that time according to the runtime's
  clock; afterwards it follows the existing lease, backoff and set-aside rules.
- **FR-003**: A pending message with a due time MUST be cancellable by id, transactionally; cancelling reports whether
  it withdrew a pending message. A cancelled message is never handed over and is kept for operator queries until
  retention removes it.
- **FR-004**: Due messages MUST be handed over as soon as they are due, including those that came due while no relay ran,
  in append order (D3).
- **FR-005**: `PostgresMessageStore` and `InMemoryMessageStore` MUST implement FR-001 to FR-004, proved by
  `MessageStoreContract`; existing Postgres databases apply `schema-upgrade-017.sql`.
- **FR-006**: The starter's `ModulithMessageStore` MUST reject messages with a due time, and the starter MUST fail
  startup when interop is enabled and the model declares a deadline (D5).

Scheduled commands

- **FR-007**: `CommandGateway` MUST offer `schedule(command, Instant)` returning a `ScheduledCommandId`, and
  `cancel(ScheduledCommandId)`. Scheduling stores the command in the caller's unit of work; at its due time it is handled
  like an outbox command, with the `CommandId` assigned at scheduling time.
- **FR-008**: `schedule` MUST fail at the call when the outbox does not support due times (D4).

Saga deadlines

- **FR-009**: A `@SagaStep` MUST be able to declare that one or more named deadlines start when it commits, each with a
  duration given either as a fixed ISO-8601 value or property placeholder resolved at startup, or as the name of a
  `SagaContext` attribute read when the step completes, and with the step(s) whose completion cancels it.
- **FR-010**: A `@SagaStep` MUST be able to declare that it handles a named deadline's expiry (D1), taking no parameter
  or one `DeadlineReached`; it may also be `@StartCompensation`, never `@StartSaga`.
- **FR-011**: When a deadline comes due for a `RUNNING` instance on which it is still open, its timeout step MUST run
  once, in a unit of work like any step, under the instance lock.
- **FR-012**: A deadline MUST be cancelled in the unit of work in which a step it names as cancelling completes, and
  every open deadline MUST be cancelled in the unit of work that moves the instance out of `RUNNING` (D7).
- **FR-013**: A deadline delivered for an instance that is not `RUNNING`, or that is no longer open, MUST run no step,
  change no state, and be consumed with a `saga.deadline.ignored` diagnostic.
- **FR-014**: Open deadlines (name, due time, message id) MUST be persisted with the instance so they survive restarts
  and can be cancelled; Postgres persists them.
- **FR-015**: Compensation and outcome triggers MUST gain kind `DEADLINE_EXPIRED` with the deadline name when a
  `@StartCompensation` timeout step starts compensation (D6).
- **FR-016**: The runtime MUST reject at startup a model that declares deadlines under an outbox without due-time
  support (D4).
- **FR-017**: The scanner MUST add rules R24 onward: a step declares a deadline name at most once (several steps may start the same deadline, a later start replacing an
  open one); every declared deadline has
  exactly one timeout step and every timeout step names a declared deadline; cancelling steps exist on the saga and are
  not the deadline's own timeout step; a fixed duration parses and is not negative; a timeout step takes no parameter or
  one `DeadlineReached`; a timeout step is not `@StartSaga`; a step that leaves `RUNNING` (`@EndSaga`, `@StartCompensation`) starts no deadline (R29, added at implementation). `.kiro/specs/saga-forge-framework/design.md` gains these
  rules and Properties 40 onward (deadline fires once and only while `RUNNING`; cancellation on awaited step and on
  leaving `RUNNING`; no delivery before due; durability across restart).

Fixture, telemetry, example, docs

- **FR-018**: `SagaFixture` MUST support deadlines: advancing `FixtureClock` delivers due deadlines and scheduled
  commands in due order; expectations can assert an open, cancelled or expired deadline.
- **FR-019**: Telemetry MUST record deadline expiry as a `saga_forge.handle` span with the deadline name, and count
  started, cancelled, expired (fired) and ignored deadlines (counter `saga_forge.deadlines`).
- **FR-020**: `saga-forge-example`'s checkout package MUST add the payment window to `CheckoutSaga` and the
  courier-assignment deadline to `FulfilmentSaga`, each ending in a `@StartCompensation` timeout step, with fixture
  tests that advance `FixtureClock`; `docs/use-cases.md` §1, the README, `docs/guide/`, the agent skill and snippet,
  and `llms-full.txt` MUST be updated; memory.md closes G-ENG-25 and records new gaps (Modulith due times, 013 leader
  lease, durable backoff still open).

### Key Entities

- **Deadline declaration**: saga-level model data — name, duration source (fixed or context attribute), cancelling
  steps, timeout step.
- **Open deadline**: per instance — name, due time, the scheduled message's id; removed when cancelled or fired.
- **Scheduled message**: a message-store entry with a due time and a cancelled flag.
- **ScheduledCommandId**: handle returned by `schedule`, used to cancel.
- **DeadlineReached**: framework value given to a timeout step — deadline name, due time, starting step.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In acceptance tests, a saga whose deadline expires runs its timeout step exactly once, including when the
  application restarts between start and due time (Postgres container suite).
- **SC-002**: A deadline set before the awaited step, completion or compensation never fires afterwards, across 1,000
  randomized interleavings of cancelling events and due deliveries.
- **SC-003**: No due message is handed over before its due time, and under normal load none later than its due time plus
  one poll interval.
- **SC-004**: 10,000 deadlines overdue at startup are all delivered, with none lost, and each runs at most one timeout
  step.
- **SC-005**: Both checkout-story deadlines are expressed with annotations only, no scheduler or timeout event written by
  the application, and the first adopter can drop workaround W16.
- **SC-006**: The whole build, container suites included, passes.

## Assumptions

- The host supplies at most one clock to the runtime; the transport uses the same clock.
- Deadline precision of milliseconds and delays of up to one poll interval are acceptable for business deadlines
  (minutes to days).
- One running application instance per message store until 013 (D8).
- Durable retry backoff and cron schedules stay out of scope; G-005-01/-02 stay open.
- Existing instances have no open deadlines after the upgrade; no back-fill.
