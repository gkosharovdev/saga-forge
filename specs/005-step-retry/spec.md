# Feature Specification: Step Retry and Idempotent Command Processing

**Feature Branch**: `005-step-retry`

**Created**: 2026-11-09

**Status**: Draft

**Input**: User description: "Per-step retry with failure classification, and idempotent processing of commands. Transient failures of a step (timeouts, connection errors) are retried with a framework-owned per-step retry policy (max attempts, backoff); final failures (domain rule violations) are never retried. A shared classification — transient, final, final-by-exhaustion — is consumed later by 006-saga-compensation, where exhausted retries start compensation. Idempotent processing of commands is paramount: a retried or redelivered step must never apply a command's effect twice (e.g. never charge a customer twice); each dispatched command carries a stable idempotency identity that stays the same across attempts and redeliveries, and a command whose handling already committed is not handled again. A duplicate delivery of an event for a step that already completed is recognized and skipped."

## Context

Today any failure inside a saga-scoped unit of work rolls it back and ends the saga `FAILED`
(`specs/004-saga-engine`). A one-second timeout calling a payment provider therefore ends an order for good.
At the same time, nothing stops the same effect from being applied twice: if an event is delivered again, or a
failed unit of work is run again, its steps dispatch their commands again, and a command handler that calls an
outside system (a payment provider, a courier API) has no way to tell it already did so.

This feature adds both halves, in this order of importance:

1. **Idempotent processing of commands** — running a step again, for any reason, never applies a command's
   effect twice. This is the precondition for retrying at all.
2. **Per-step retry with failure classification** — transient failures are retried under a framework-owned
   policy; final failures are not; exhausting the policy is itself a recorded, final outcome that
   `006-saga-compensation` reacts to.

Retry re-runs the **whole unit of work** from the event that opened it, because a failure rolls the whole unit
of work back (002/004 contract). Steps that ran before the failing step in the same unit of work run again, and
their commands are dispatched again — which is exactly why command idempotency comes first.

Running example: `processPayment` dispatches `ChargePaymentCommand`; `PaymentService` calls the payment
provider; the next step in the same unit of work calls a courier service that times out.

## Clarifications

### Session 2026-11-09

- Q: How is a failure with no marker classified? → A: Transient only if the failing step's policy lists its type
  as retryable; otherwise never retried. Recorded as "unclassified", kept apart from author-declared "final" so
  `006-saga-compensation` compensates only on deliberate final failures and on exhaustion (FR-008, FR-010).
- Q: How is the wait between attempts carried out? → A: The engine waits in-process within the same handling
  call and reruns the unit of work, with total backoff bounded; long scheduled backoff is a gap (FR-014,
  FR-014a, FR-014b, G-005-01).

## User Scenarios & Testing *(mandatory)*

The users are saga authors, command-handler authors, and operators inspecting saga instances.

### User Story 1 - A command is never applied twice (Priority: P1)

A command-handler author charges a customer through an outside payment provider. Every command the framework
hands to the handler carries an idempotency identity that is the same every time that command is dispatched for
that saga instance and step — on a retry, on a redelivered event, after a crash. The handler passes it to the
provider as the provider's idempotency key, so the customer is charged once. Inside the framework, a command
whose handling already committed is recognized and not handled again.

**Why this priority**: The user called this paramount. A double charge is worse than a failed order, and every
other part of this feature (retry, redelivery) multiplies the number of times a command is dispatched.

**Independent Test**: Run `processPayment` so it dispatches `ChargePaymentCommand`, make the unit of work fail
after the handler ran, run it again, and check that the handler saw the same idempotency identity both times.
Separately, commit a unit of work, redeliver its event, and check that the handler is not invoked again.

**Acceptance Scenarios**:

1. **Given** a step that dispatches `ChargePaymentCommand`, **When** its unit of work fails after the handler
   ran and the unit of work runs again, **Then** the handler receives a command with the same idempotency
   identity as on the first run.
2. **Given** a step that dispatches two commands of the same type in one run, **When** they are handled,
   **Then** they carry two different identities, and each keeps its own identity on every later run.
3. **Given** a unit of work whose handling of `ChargePaymentCommand` committed, **When** the same command
   (same identity) is dispatched again for that instance, **Then** its handler is not invoked and the dispatch
   is treated as already handled.
4. **Given** the same saga type and step in two different saga instances, **When** each dispatches
   `ChargePaymentCommand`, **Then** the two commands carry different identities.

---

### User Story 2 - A duplicate event for a completed step is skipped (Priority: P1)

The transport delivers `PaymentProcessedEvent` twice (at-least-once delivery, a consumer rebalance, a manual
replay). The step it triggers already completed and committed for that instance, so the second delivery is
recognized and skipped: the step does not run, no command is dispatched, and the delivery is reported as a
duplicate, not as a failure.

**Why this priority**: It is the cheapest and most common protection against double effects, and it is what
lets a transport redeliver safely.

**Independent Test**: Commit a unit of work for `PaymentProcessedEvent`, deliver the same event again, and
check that the step method was not invoked and the result reports a duplicate.

**Acceptance Scenarios**:

1. **Given** step `processPayment` is `COMPLETED` for an instance, **When** a `PaymentProcessedEvent` for that
   instance arrives again, **Then** the step does not run, no command is dispatched, and the outcome is
   "duplicate, skipped".
2. **Given** the first delivery's unit of work rolled back (step `NOT_RUN`), **When** the event arrives again,
   **Then** it is not a duplicate and the step runs.
3. **Given** an instance that already ended, **When** an event for one of its completed steps arrives, **Then**
   it is reported as a duplicate, not as an error.

---

### User Story 3 - A transient failure is retried under the step's policy (Priority: P1)

A saga author declares, on a step, how many attempts it gets and how long to wait between them. When that step
fails with a transient failure (the courier service timed out), the framework rolls the unit of work back as
today, waits according to the policy, and runs the unit of work again from its triggering event. If an attempt
succeeds, the saga continues as if the failure never happened, and the instance records how many attempts it
took.

**Why this priority**: This is the retry half of the request and the reason most sagas end `FAILED` today.

**Independent Test**: Give a step a policy of 3 attempts, make it fail transiently twice and then succeed, and
check that the saga continued, the step is `COMPLETED`, and the record shows 3 attempts.

**Acceptance Scenarios**:

1. **Given** a step with 3 attempts and a transient failure on attempts 1 and 2, **When** attempt 3 succeeds,
   **Then** the unit of work commits once, the saga continues, and the step's record shows 3 attempts.
2. **Given** a step with a backoff policy, **When** it is retried, **Then** the wait before each attempt follows
   the declared policy (fixed, or growing up to a declared maximum).
3. **Given** a step with no declared policy, **When** it fails, **Then** it gets exactly one attempt and the
   saga ends `FAILED` as today (unchanged behaviour).
4. **Given** a retried unit of work, **When** the failing step reads `SagaContext` values written by the failed
   attempt, **Then** those values are visible (context writes survive rollback by design), so a step can see
   what it already recorded.

---

### User Story 4 - Final failures are not retried; exhaustion is recorded (Priority: P2)

A domain rule violation (the paid amount does not match the order) will fail the same way every time, so it is
never retried. When a transient failure keeps failing until the policy is used up, the saga ends `FAILED` with a
failure that says retries were exhausted, how many attempts were made, and the last cause — the trigger
`006-saga-compensation` uses to start compensation.

**Why this priority**: Without it, retry would waste attempts on hopeless cases and operators could not tell
"gave up after 5 tries" from "rejected outright".

**Independent Test**: Make a step with 3 attempts fail with a final failure and check it ran once; make it fail
transiently on every attempt and check the recorded failure is "exhausted" with 3 attempts.

**Acceptance Scenarios**:

1. **Given** a step with 3 attempts, **When** it fails with a final failure, **Then** it is not retried and the
   saga ends `FAILED` with classification "final".
2. **Given** a step with 3 attempts whose policy lists only timeouts as retryable, **When** it fails with an
   unmarked failure of another type (e.g. a programming error), **Then** it is not retried and the saga ends
   `FAILED` with classification "unclassified".
3. **Given** a step with 3 attempts, **When** all 3 fail transiently, **Then** the saga ends `FAILED` with
   classification "final by exhaustion", attempt count 3 and the last attempt's cause.
4. **Given** attempts 1 and 2 failed transiently, **When** attempt 3 fails with a final failure, **Then** the
   saga ends `FAILED` with classification "final", attempt count 3.

---

### User Story 5 - Retries survive a crash without double effects (Priority: P3)

The process stops during a retry. When the event is delivered again, the attempt count carries on from where it
was (it does not restart at 1 indefinitely), and command identities are unchanged, so outside systems still see
one charge.

**Why this priority**: Hardening for real deployments; it builds on US1 and US3.

**Independent Test**: Fail a step twice out of 3 attempts, stop the engine, redeliver the event, and check that
only one attempt remains and the handler saw the same command identity.

**Acceptance Scenarios**:

1. **Given** 2 of 3 attempts failed and the process stopped, **When** the event is redelivered, **Then** one
   attempt remains and the handler receives the same command identities as before.

---

### Edge Cases

- **Earlier step in the same unit of work**: when the third step of a unit of work fails, the first two run again
  on retry; their commands keep their identities, so outside effects are not repeated (US1).
- **Command handled inside the transaction**: an aggregate change made by a handler is rolled back with the unit
  of work, so running it again is correct; only committed handling counts as "already handled".
- **Step dispatches different commands on a later attempt** (e.g. branches on a value that changed): identities
  are assigned per step run by command type and order of dispatch; a command that did not exist on an earlier
  attempt gets a new identity, which is correct because nothing was charged for it before.
- **Failing step without a policy, earlier step with one**: the policy of the step that failed applies.
- **A command handler throws**: the failure is the dispatching step's failure and is classified the same way.
- **Retry while another event for the instance is waiting**: one unit of work per instance still holds (004);
  the waiting event is handled after the retried unit of work finishes.
- **Start step retried**: if the creation write committed and the first unit of work failed transiently, the
  retry runs that unit of work again; it is not a duplicate start.
- **Failure of the retry machinery itself** (the attempt count cannot be recorded): treated as a final failure,
  so the framework never retries without knowing how many attempts were made.
- **Duplicate event arriving during a retry**: waits for the instance like any event, then is skipped if the
  step has completed by then (US2).

## Requirements *(mandatory)*

### Functional Requirements

**Idempotent command processing**

- **FR-001**: Every command dispatched through `CommandGateway` from a step MUST carry an idempotency identity
  derived from the saga instance, the step, the command type and its order of dispatch within that step run.
- **FR-002**: The identity MUST be identical for the same command across retries of the unit of work, event
  redeliveries and process restarts, and MUST differ between saga instances, steps, and commands dispatched in
  one step run.
- **FR-003**: A command handler (aggregate method, aggregate constructor/factory, or service method) MUST be
  able to obtain the identity of the command it is handling, so it can pass it to outside systems as their
  idempotency key, without the command class having to declare a field for it.
- **FR-004**: The framework MUST record each command whose handling committed, in the same unit of work as the
  handler's effects, and MUST NOT invoke a handler again for a command identity already recorded for that
  instance; such a dispatch is reported as already handled and is not a failure.
- **FR-005**: Commands dispatched outside a saga step (no saga instance) are out of this guarantee; their
  behaviour is unchanged.

**Idempotent step execution**

- **FR-006**: An event that would trigger a step already `COMPLETED` for that instance MUST be skipped: the step
  does not run, no command is dispatched, and the outcome is reported as a duplicate, distinct from failure and
  from "not handled".
- **FR-007**: A step whose unit of work rolled back MUST NOT count as completed for FR-006.

**Failure classification**

- **FR-008**: The framework MUST classify every step failure as exactly one of **transient** (may be retried),
  **final** (declared final by the author; never retried), **final by exhaustion** (transient, but the policy is
  used up), or **unclassified** (neither marked nor listed as retryable; never retried). The vocabulary is
  framework-owned, lives in the zero-dependency core, and is the one `006-saga-compensation` consumes; keeping
  "unclassified" apart from "final" lets 006 compensate only on failures an author chose to treat as final.
- **FR-009**: Step and handler authors MUST be able to mark a failure as transient or final through
  framework-owned markers, without catching or throwing types from Spring, a retry library or a transport.
- **FR-010**: A failure carrying no marker MUST be transient only if the failing step's policy lists its type
  (or a supertype) as retryable; otherwise it is **unclassified** and never retried. A programming error is
  therefore never retried by default. A listed type marked final is final (the marker wins).

**Retry policy**

- **FR-011**: A saga author MUST be able to declare, per step, a retry policy of framework-owned primitive data:
  maximum attempts (including the first), initial backoff, backoff growth (fixed or multiplied per attempt),
  maximum backoff, and the failure types it treats as retryable (FR-010). A step without a policy gets one
  attempt.
- **FR-012**: Declaring a retry policy changes the formal annotation contract, so
  `.kiro/specs/saga-forge-framework/design.md` MUST be updated with the new attributes and their validation rules
  before implementation, and the scanner MUST reject invalid policies (e.g. fewer than 1 attempt, negative
  backoff, maximum below initial) through `validateAll` and at engine startup.
- **FR-013**: A transient failure with attempts left MUST roll the unit of work back, wait per the policy, and
  run the unit of work again from its triggering event. The policy that applies is the failing step's.
- **FR-014**: The engine MUST carry out retries itself, within the same handling call: after a rollback it waits
  in-process for the backoff and runs the unit of work again, keeping the instance's one-unit-of-work rule
  (004) for the whole sequence, and only then returns to the caller. This works the same with every transport.
- **FR-014a**: The total backoff a policy can produce (sum of waits over all attempts) MUST be bounded by a
  framework-wide maximum, and the scanner MUST reject a policy whose total exceeds it. Long, scheduled backoff
  (minutes or hours) is out of scope (G-005-01).
- **FR-014b**: If the engine is shut down while waiting for a retry, it MUST stop waiting, leave the attempt
  count recorded and report the event as not handled, so a redelivery continues the attempts (US5).
- **FR-015**: The attempt count of an instance's current unit of work MUST be stored so that it survives the
  rollback and a process restart (US5), and MUST be reset when the unit of work commits.
- **FR-016**: A final failure, or a transient failure on the last attempt, MUST end the saga `FAILED` as today,
  with the recorded failure extended by its classification, the attempt count and the last cause.

**Visibility and scope**

- **FR-017**: For each step, the persisted instance MUST show how many attempts its committed run took; for a
  `FAILED` instance it MUST show classification, attempts and last cause.
- **FR-018**: Lifecycle history MUST record each failed attempt that is retried, so storage modules persist it like
  other entries. Duplicates skipped and "already handled" commands change nothing, so they are not history entries;
  they MUST be logged and reported in the handling result instead (narrowed by the plan, research §11).
- **FR-019**: The Postgres storage module MUST persist the new data (command identities handled, attempt
  counts, classification); MySQL and MongoDB stay unimplemented. No new storage or transport is introduced.
- **FR-020**: The canonical example and docs MUST show a step with a retry policy and a command handler passing
  its command identity to an outside system, with `associationProperty` and the start/end lifecycle annotations
  visible.
- **FR-021**: Out of scope: compensation (`006-saga-compensation`), deduplicating the triggering command before
  a saga instance exists, retry of the after-commit handoff, the transactional outbox (G-ENG-07), circuit
  breaking, and retry policies attached to command handlers rather than steps.

### Key Entities

- **Command identity**: the stable idempotency identity of one dispatched command — saga instance, step, command
  type, order of dispatch; visible to the handler.
- **Handled-command record**: proof that handling of a command identity committed for an instance.
- **Failure classification**: transient, final, final by exhaustion, unclassified.
- **Retry policy**: per-step maximum attempts, initial backoff, growth, maximum backoff.
- **Attempt record**: attempt count for the instance's current unit of work, kept across rollback and restart;
  the attempts a step's committed run took.
- **Saga failure** (existing `SagaFailure`): extended with classification, attempt count and last cause.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In a fault-injection suite that fails, retries and redelivers at every step and handler boundary
  of the example saga, the payment provider double sees zero duplicate charges (one charge per order identity).
- **SC-002**: 100% of duplicate deliveries of an event for a completed step are skipped without invoking the
  step.
- **SC-003**: A saga whose step fails transiently fewer times than its attempt limit completes in 100% of runs
  with no manual action.
- **SC-004**: Final failures are retried 0 times; transient failures are attempted exactly the declared number
  of times before ending "final by exhaustion".
- **SC-005**: After a restart mid-retry, the total number of attempts never exceeds the declared maximum.
- **SC-006**: An operator can tell, from the stored instance alone, whether a `FAILED` saga was rejected
  outright or gave up after retries, and how many attempts it made.
- **SC-007**: Adding retry to a step needs only declared policy values; making a handler safe against outside
  double effects needs only passing the command identity on.

## Assumptions

- A step completes at most once per saga instance (one `StepRecord` per step, one step per event type), so
  instance + step + command type + order of dispatch is stable.
- The framework cannot make outside systems idempotent; it guarantees a stable identity and that committed
  handling is not repeated. Handlers calling outside systems are expected to pass the identity on.
- `SagaContext` values written by a failed attempt stay visible to the retry; step authors write them so that
  writing again is harmless (AGENTS.md: retry must not preclude reusing values already written).
- Default backoff growth is fixed; jitter is not required for the described cases.
- In-process waiting holds the instance and blocks the caller (for Kafka, the consumer) for the bounded total
  backoff; policies are expected to use short waits (seconds). The framework-wide bound's value is a planning
  decision.
- The default after-commit handoff stays at most once (G-ENG-07); this feature does not change handoff
  delivery guarantees, it makes redelivery safe once a transport provides it.
- Retry-exhaustion as a compensation trigger is defined by `006-saga-compensation` FR-006a/FR-006b, which will be
  updated to reference this feature's classification.
