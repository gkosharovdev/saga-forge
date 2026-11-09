# Feature Specification: Saga Compensation

**Feature Branch**: `006-saga-compensation`

**Created**: 2026-11-09

**Status**: Implemented (2026-11-09)

**Input**: User description: "saga compensation steps. which should trigger in case of failures. For example after payment there has been established that a delivery is impossible maybe due to not enough stock or delivery partner refusing to accept the delivery request. Then a refund needs to be triggered. Or a technical failure is preventing future steps to complete and we need to rollback already completed steps with concrete business rules."

## Context

Today a saga author can declare `@SagaCompensation(forStep = "...")`, but the declaration is only
*discoverable*: nothing ever runs it (`docs/architecture.md`, "Failure categorization and automatic compensation
dispatch"). Every failure ends the saga `FAILED`, and whatever earlier units of work committed — a captured
payment, a reserved slot, a confirmed booking — stays in place.

One property of the existing model shapes this whole feature: everything inside a single saga-scoped unit of
work is already atomic. A failure there rolls the unit of work back, so steps that ran in it have no effect to
undo. Compensation is therefore needed only for steps whose unit of work **already committed** — typically
because the saga then waited for an outside party (a payment provider, a warehouse, a delivery partner) and
the bad news arrived later, in a new unit of work.

Throughout this spec, the running example extends the canonical order saga with such a wait point:

1. `handleOrderCreated` (`@StartSaga`) asks for payment.
2. `processPayment` records the payment and asks the delivery partner to accept the delivery. The unit of work
   commits; the payment is now real money taken from the customer. The saga waits.
3. Either `DeliveryAcceptedEvent` arrives and `completeOrder` (`@EndSaga`) ends the saga, or the delivery
   partner sends `DeliveryRejectedEvent` (no capacity, address not served) or the warehouse reports
   `StockUnavailableEvent`. In those cases the payment must be refunded.

## Clarifications

### Session 2026-11-09

- Q: How is a business failure declared? → A: The step handling the failure event is marked as a named
  failure outcome, the counterpart of `@EndSaga` (FR-001, FR-001a).
- Q: Which technical failures start compensation? → A: Only failures the step author marks final through a
  framework-owned marker; every other failure ends `FAILED` as today (FR-006).
- Q: Can a compensation wait for a confirming event? → A: No; a compensation is complete when its unit of work
  commits (FR-013).
- Added by the user: exhaustion of retry attempts counts as a final failure and starts compensation (FR-006a,
  FR-006b), as a contract for the not-yet-designed step retry.
- Reordered by the user: step retry and idempotent command processing come first as `005-step-retry`; this
  feature was renumbered from 005 to 006 and now uses 005's classification. "Marked final" means 005's
  **final**; 005's **unclassified** failures do not compensate (FR-006).

## User Scenarios & Testing *(mandatory)*

The "users" of this feature are saga authors (developers declaring sagas with Saga Forge annotations) and
operators who inspect saga instances when something goes wrong.

### User Story 1 - Business rejection triggers the refund (Priority: P1)

A saga author declares that a delivery rejection is a business failure of the order process and that the
payment step has a compensation that refunds the customer. When the delivery partner rejects the delivery
after the payment was committed, the framework runs the payment compensation, which dispatches a refund
command, and the saga ends with a recorded "compensated" outcome that names the rejection as its reason.

**Why this priority**: This is the core value of the feature and the case the user described first. Without
it, a rejected order keeps the customer's money and someone has to notice and refund by hand.

**Independent Test**: Run the extended order saga until it waits after the payment, deliver a
`DeliveryRejectedEvent` for the same order, and check that exactly one refund command was dispatched, that
the payment step is recorded as compensated, and that the saga's outcome is "compensated" with the rejection
as its reason.

**Acceptance Scenarios**:

1. **Given** an order saga whose payment step's unit of work committed and which is waiting for the delivery
   decision, **When** a `DeliveryRejectedEvent` for that order arrives, **Then** the payment compensation runs
   once, dispatches the refund command, and the saga ends "compensated" with the rejection recorded as the
   trigger.
2. **Given** the same waiting saga, **When** a `StockUnavailableEvent` for that order arrives instead, **Then**
   the same compensation runs and the recorded trigger is the stock shortage, so an operator can tell the two
   apart.
3. **Given** the same waiting saga, **When** a `DeliveryAcceptedEvent` arrives, **Then** no compensation runs
   and the saga completes normally.
4. **Given** a saga that has already ended "compensated", **When** a second `DeliveryRejectedEvent` for the
   same order arrives, **Then** no compensation runs again and no second refund is dispatched.

---

### User Story 2 - Compensation unwinds every committed step in reverse order (Priority: P1)

A saga has several steps whose units of work committed (for example: reserve stock, then take payment, then
book a courier slot). When the process fails after all three, the framework compensates each committed step
that declares a compensation, most recently completed first: release the courier slot, refund the payment,
release the stock. Steps that never committed are not compensated, and steps without a declared compensation
are skipped.

**Why this priority**: Real processes have more than one undoable effect. Undoing them in the wrong order or
undoing a step that never took effect (refunding a payment that was rolled back) causes new inconsistencies.

**Independent Test**: Build a saga with three committed steps, each with a compensation, plus a fourth step
whose unit of work rolled back; trigger a failure and check the order in which compensations ran and that the
rolled-back step's compensation did not run.

**Acceptance Scenarios**:

1. **Given** steps A, B, C completed and committed in that order, each with a compensation, **When**
   compensation is triggered, **Then** the compensations run in the order C, B, A.
2. **Given** step B has no declared compensation, **When** compensation is triggered, **Then** C and A are
   compensated, B is recorded as "nothing to compensate", and the saga still ends "compensated".
3. **Given** step D ran in a unit of work that rolled back, **When** compensation is triggered, **Then** D's
   compensation does not run, because D left no effect.

---

### User Story 3 - Technical failure after earlier commits leads to compensation (Priority: P2)

A step that runs after earlier units of work committed fails for a technical reason (for example, the courier
booking service is permanently unavailable and the step cannot proceed). Its own unit of work rolls back as
today, and the framework then compensates the steps that committed earlier, applying their business rules
(refund, release stock), instead of leaving the customer charged for an order that will never ship.

**Why this priority**: This is the second case the user described. It is P2 because only final failures
(marked final by the author, or retries exhausted) justify undoing business effects (FR-006, FR-006a); a
transient blip must never refund a customer.

**Independent Test**: Run a saga until its payment committed, make the next step fail with a failure the
author marked as final, and check that the failing step's unit of work rolled back, the payment
compensation ran, and the saga records both the original failure and the compensation.

**Acceptance Scenarios**:

1. **Given** a saga whose payment step committed, **When** the next step fails with a failure that FR-006 says
   triggers compensation, **Then** that step's unit of work rolls back, the payment is compensated, and the
   saga ends "compensated" with the failing step and cause recorded as the trigger.
2. **Given** the same saga, **When** the next step fails with a failure that is not marked final, **Then** the
   saga ends `FAILED` as today and no compensation runs.
3. **Given** a saga whose payment step committed and whose next step has a `005-step-retry` policy, **When**
   that step fails on every allowed attempt, **Then** after the last attempt the payment is
   compensated and the recorded trigger says retries were exhausted, with the attempt count and the last cause.

---

### User Story 4 - A compensation that cannot complete needs a human (Priority: P2)

A compensation itself can fail: the refund is refused by the payment provider, or the stock release fails.
The framework must not pretend the saga is consistent. It stops unwinding, records which compensation failed
and why, leaves the saga in a terminal "requires intervention" state that lists what was and was not
compensated, and never retries forever on its own.

**Why this priority**: Without this, a failed refund would be silent — the most damaging outcome for the
customer and the hardest for operators to detect.

**Independent Test**: Make the payment compensation throw, trigger compensation, and check the saga's state:
"requires intervention", the failed compensation and its cause recorded, and the already-compensated steps
still marked compensated.

**Acceptance Scenarios**:

1. **Given** compensation of C succeeded and compensation of B fails, **When** the failure occurs, **Then** A
   is not compensated, the saga is "requires intervention", and its record shows C compensated, B failed with
   cause, A pending.
2. **Given** a saga in "requires intervention", **When** an operator inspects it, **Then** they can see the
   original trigger, every committed step, and each step's compensation status without reading logs.

---

### User Story 5 - Compensation survives a crash (Priority: P3)

The process running compensation crashes halfway. When the saga is next handled, compensation resumes with the
first step not yet compensated; compensations that already succeeded do not run a second time.

**Why this priority**: Required for correctness under real deployments, but it builds on the durable step
records the engine already keeps, so it is a hardening slice rather than new user-visible behaviour.

**Independent Test**: Stop the engine after the first of three compensations committed, restart, resume, and
check that only the remaining two compensations run.

**Acceptance Scenarios**:

1. **Given** compensation of C committed and the process crashed before B, **When** compensation resumes,
   **Then** B and A are compensated and C is not compensated again.

---

### Edge Cases

- **Failure in the same unit of work as the effect**: if payment and the failing step ran in one unit of work,
  the rollback already undid the payment; no compensation runs for it.
- **Failure in the start step**: nothing committed yet; the saga ends `FAILED` without compensation (as today).
- **No committed step has a compensation**: the saga ends "compensated" with every step recorded as "nothing
  to compensate" — the author declared that nothing needs undoing.
- **Late forward events**: an event for a forward step (e.g. `DeliveryAcceptedEvent`) never runs its step while
  the saga is compensating or after it ended. After it ended, the event is reported as not handled, as for any ended
  saga. While the saga is compensating (only after a crash interrupted compensation), the event resumes the remaining
  compensations and the handling call reports their outcome (plan research §10).
- **Concurrent triggers**: a rejection event and a technical failure for the same instance at nearly the same
  time start compensation once; the second trigger is recorded as not handled.
- **Duplicate delivery of the trigger event**: covered by scenario 1.4 — compensation is not started twice.
- **Compensation needs data the forward step produced** (the payment reference to refund): the compensation
  reads it from the saga's `SagaContext`, which survives rollbacks by design.
- **A compensation dispatches a command whose handler fails**: treated as that compensation failing (User
  Story 4).
- **Compensation targets an effect in another saga class** (use-cases.md, Process D undoing Process B): the
  compensation dispatches a command aimed at the other process's aggregate; it never reaches into the other
  saga instance.

## Requirements *(mandatory)*

### Functional Requirements

**Triggering**

- **FR-001**: A saga author MUST be able to declare a business failure by marking the step that handles the
  failure event (e.g. `DeliveryRejectedEvent`, `StockUnavailableEvent`) as a **failure outcome** with a
  framework-owned annotation, the counterpart of `@EndSaga` naming a successful outcome. Handling an event in
  such a step starts compensation. Each failure-outcome step names its outcome, and the instance records which
  one triggered compensation.
- **FR-001a**: A failure-outcome step MUST also be a `@SagaStep` (same co-occurrence rule as `@StartSaga` and
  `@EndSaga`), MUST NOT also be `@StartSaga` or `@EndSaga`, and the scanner MUST report violations through
  `validateAll` and at engine startup.
- **FR-002**: The step that handles a business-failure event MUST be able to run its own logic (e.g. record
  the reason in `SagaContext`) before compensation starts, and its effects MUST commit together with the
  start of compensation.
- **FR-003**: A failure of a step after at least one earlier unit of work committed MUST be able to start
  compensation, subject to FR-006.
- **FR-004**: Compensation MUST start at most once per saga instance, regardless of how many triggers arrive.

**Selecting and ordering compensations**

- **FR-005**: Compensation MUST cover exactly the steps whose units of work committed (`StepStatus.COMPLETED`),
  in reverse order of completion. Steps that never ran or whose unit of work rolled back MUST NOT be
  compensated.
- **FR-006**: A step failure MUST start compensation only when `005-step-retry` classifies it as **final**
  (declared final by the author through the framework-owned marker) or **final by exhaustion**. A failure
  classified **unclassified** (neither marked nor listed as retryable, e.g. a programming error) ends the saga
  `FAILED` without compensation, as today. A **transient** failure with attempts left is retried by 005 and
  does not reach compensation.
- **FR-006a**: For retry exhaustion, the recorded trigger MUST say that retries were exhausted, with the attempt
  count and last cause that 005 records.
- **FR-006b**: This feature MUST reuse 005's failure classification and MUST NOT add a parallel one. As
  implemented: `core.failure.FailureClassification` (`TRANSIENT`, `FINAL`, `EXHAUSTED`, `UNCLASSIFIED`), read from
  the `FAILED` instance's `SagaFailure.classification()` and `attempts()`; final failures are marked with
  `core.failure.FinalFailure`.
- **FR-007**: A completed step with no declared `@SagaCompensation` MUST be treated as "nothing to compensate"
  and skipped without failing the process.
- **FR-008**: The existing rules MUST continue to hold: `forStep` names an existing `@SagaStep` on the same
  class (Property 5), and at most one compensation MAY target a given step (new scanner rule, reported through
  `validateAll` and at engine startup).

**Running a compensation**

- **FR-009**: A compensation MUST be able to dispatch commands through the saga's `CommandGateway` and read and
  write the saga's `SagaContext`, the same way a step does; it MUST NOT return a command.
- **FR-010**: A compensation MUST be able to learn why compensation was triggered (the triggering event or the
  recorded failure) so its business rules can differ by cause (e.g. refund in full on delivery rejection).
- **FR-011**: Each compensation MUST run in its own saga-scoped unit of work: its effects and its "compensated"
  record commit together or roll back together.
- **FR-012**: A compensation's completion MUST be durable before the next compensation starts, so a resumed
  saga never runs an already-committed compensation again (User Story 5).
- **FR-013**: A compensation MUST be complete once its unit of work commits (its commands were handled). It
  does not wait for a confirming event; a later refusal of the undo (e.g. the provider refuses the refund
  after accepting the command) is outside the saga and is handled by the host.

**Outcomes and visibility**

- **FR-014**: While compensating, the instance MUST be in the existing `COMPENSATING` status; forward steps
  MUST NOT run for it.
- **FR-015**: When every applicable compensation succeeded, the saga MUST end in a terminal "compensated"
  outcome that is distinguishable from `COMPLETED` and from `FAILED`.
- **FR-016**: When a compensation fails, the framework MUST stop unwinding, MUST NOT retry it automatically,
  and MUST end the saga in a terminal "requires intervention" outcome.
- **FR-017**: For every saga that compensated or requires intervention, the persisted instance MUST show: the
  trigger (business-failure event type or failing step and cause), each committed step's compensation status
  (compensated, nothing to compensate, failed with cause, not attempted), and when each happened.
- **FR-018**: Lifecycle history MUST record the start of compensation, each compensation's outcome, and the
  terminal outcome, so storage implementations persist it like other lifecycle entries.
- **FR-019**: The canonical example (`OrderProcessingSaga`) and the docs MUST show a working compensation
  (a refund after a business failure that follows the payment; in the example, stock found unavailable after the
  payment, because its forward flow runs in one unit of work; the delivery rejection is shown by the engine's test
  fixture and the docs), with `associationProperty` and the start/end lifecycle annotations visible.

**Scope boundaries**

- **FR-020**: Contracts MUST stay in `saga-forge-core` with zero dependencies; the engine runs them; storage
  modules persist the new state. No new storage or transport is introduced.
- **FR-021**: Out of scope: step retry and command idempotency (delivered by `005-step-retry`, which this
  feature depends on; compensation commands get 005's stable command identities like any step's commands),
  retrying a failed compensation, compensations waiting for confirming events,
  operator-initiated resume or manual compensation from
  "requires intervention", compensation timeouts, and programmatic abort (`SagaLifecycle.abort()`). Each is
  recorded as a gap, not designed here.

### Key Entities

- **Compensation**: the declared undo for exactly one step, named by `forStep`; carries the business rules for
  reversing that step's committed effect.
- **Business-failure event**: a domain event the saga author declares as ending the forward process and
  starting compensation (delivery rejected, stock unavailable).
- **Compensation trigger**: what started compensation for an instance — a failure-outcome step (outcome name
  and event type), a final step failure (step name, cause type, message), or retry exhaustion (step name,
  attempt count, last cause); recorded once.
- **Failure classification**: defined by `005-step-retry` (transient, final, final by exhaustion,
  unclassified); compensation reacts to final and final by exhaustion.
- **Step compensation status**: per committed step — not attempted, compensated, nothing to compensate, or
  failed (with cause and time). Extends the existing per-step record.
- **Saga outcome**: extended with "compensated" and "requires intervention" alongside the existing named
  `@EndSaga` outcomes and `FAILED`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In the extended example, 100% of orders rejected by the delivery partner after payment result in
  exactly one refund command, with zero manual steps.
- **SC-002**: Across a test suite of failure injections at every step boundary of a three-step saga, no step
  whose unit of work rolled back is ever compensated, and every committed step with a compensation is
  compensated exactly once.
- **SC-003**: When compensations are killed mid-run at every possible point, resuming produces zero duplicate
  compensations and zero skipped compensations.
- **SC-004**: An operator can determine, from the stored saga instance alone, why a saga was compensated and
  which effects were and were not undone, for 100% of compensated or intervention-required sagas.
- **SC-005**: A saga author can add a compensation to an existing saga by writing one annotated method and,
  for business failures, one declared failure event — no orchestration code.
- **SC-006**: Zero compensations are started by failures that are neither marked final nor the result of
  exhausted retries.
- **SC-007**: A failed compensation is never retried automatically and never leaves the saga in a non-terminal
  state.

## Assumptions

- Compensation applies only to effects committed in earlier units of work; same-unit-of-work effects are
  undone by the existing rollback. This follows from 002/004's unit-of-work contract and is not re-decided here.
- Reverse completion order is the default and only order; per-saga custom ordering is not needed for the
  described cases.
- A step without a compensation means "nothing to undo", not "irreversible". Marking a step as irreversible
  (forcing "requires intervention") is a possible later extension.
- Compensations are expected to be idempotent at the business level (e.g. refund keyed by the payment
  reference) because the default after-commit handoff is at most once and the outbox mode is at least once
  (G-ENG-07); the framework guarantees it does not re-run a committed compensation, not exactly-once delivery
  of its commands.
- `SagaContext` values written by forward steps (payment reference, amount) are the data source for
  compensations; nothing new is needed to keep them across rollbacks.
- The Postgres storage module is the only production store that needs updating; MySQL and MongoDB remain
  unimplemented.
- Recovery of a compensation interrupted by a crash relies on the instance being handled again; a scheduled
  sweeper for stranded instances is out of scope (as for G-ENG-05).
