# Feature Specification: Outbox Command Failure Feedback

**Feature Branch**: `012-outbox-command-failure-feedback`

**Created**: 2026-11-24

**Status**: Clarified

**Input**: User description: "012 Outbox command failure feedback (roadmap feature 012, .specify/memory/roadmap.md "### 012: Outbox command failure feedback"; closes G-ADOPT-02; booking-service feedback SF-2(b), W3/W4). A @Command(delivery = OUTBOX) command sent from a saga step that fails FINAL (FinalFailure, not retried by the relay) or EXHAUSTED (relay maxAttempts used up) is reported back to the sending saga instance as a framework event CommandFailed(commandId, commandType, classification, causeType, attempts), routed through CommandId.sagaId to exactly one instance. The reaction runs in the runtime's one UnitOfWork (011), reusing the compensation-start work: by default the instance compensates every committed step, newest first (006 order), and ends with one outcome event (SagaCompensated, or SagaFailed in the compensation phase). An instance that already ended is skipped with a diagnostic. Failures of outbox commands sent outside a saga keep today's set-aside behaviour. Out of scope: a step-level commit boundary (SF-2a) and success notifications. Open questions to settle in the spec: whether a TransientFailure uses the relay's backoff or the sending step's @RetryPolicy; whether the relay writes CommandFailed itself or hands the failure to the runtime's UnitOfWork (default: hand it to the runtime); which OutcomeTrigger the ending outcome event carries; whether the optional @StartCompensation step that takes CommandFailed is in scope, given G-011-01 (scanner accepts only @DomainEvent step parameters)."

## Context

A saga step can send a command in two ways. An ordinary command runs inside the step's unit of work: if its handler
fails, the whole unit of work rolls back. Because the events that continue the same instance also run in that unit of
work, a saga that chains its steps in-process is one unit of work from start to end. A rejection late in the chain rolls
everything back, so nothing has committed and compensation can never run.

A command declared `@Command(delivery = OUTBOX)` is stored with the sending unit of work and handled after it commits,
in a transaction of its own. That is the way to give a step its own commit, and the way to call a remote system from a
saga without holding the instance's writes open. But when an outbox command's handler fails, the failure goes only to
the transport's relay: it is retried up to the relay's attempt limit (a `FinalFailure` is retried like any other
exception) and then set aside. The saga that sent the command never learns about it. It waits for an event that will
never come, with its committed steps left in place.

The first adopter needed both things at once: commit each step on its own, and undo the committed steps when a later
step's command is finally rejected (roadmap G-ADOPT-02, workarounds W3 and W4). Today it has to choose one.

This feature closes the loop: a final or exhausted failure of an outbox command sent from a saga is reported to the
saga instance that sent it, and that instance compensates. The command remains the one unit that crosses a transaction
boundary; no second "commit here" concept is added (SF-2a stays deferred).

## Clarifications

### Session 2026-11-24

- Q: Whose retry rules apply to an outbox command whose failure is not final? → A: The relay's own settings (attempt
  limit and backoff) for every outbox command, as today (user). A step's `@RetryPolicy` stays about the step's own
  in-process attempts, and the transport needs no knowledge of the saga model.
- Q: Is a business-specific reaction (a step that takes the failure report) in scope? → A: No (user). 012 ships the
  default compensation only. A step on the failure report stays with G-011-01 (steps on framework events), to be solved
  once for outcome events and failure reports together.
- Q: What does a final or exhausted failure of a command sent by a compensation do? → A: A still-compensating instance
  ends `COMPENSATION_FAILED`, with a "saga failed" outcome event in the compensation phase; an instance that already
  ended `COMPENSATED` stays as it is, and a diagnostic is logged (user). Terminal statuses never move.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A finally rejected outbox command compensates its saga (Priority: P1)

An application developer writes a three-step saga whose steps commit on their own because each sends its follow-up
work as an outbox command. When the third step's command is rejected for good (its handler signals a final failure),
the saga undoes the second and the first step, newest first, without any application code polling or translating the
failure.

**Why this priority**: This is the adopter's blocking gap. Without it, committing steps on their own and compensating
them are mutually exclusive.

**Independent Test**: Run a three-step saga whose third step's outbox command handler throws a final failure. The
instance ends compensated, the compensations of steps 2 and 1 ran in that order, and exactly one "saga compensated"
outcome event was published.

**Acceptance Scenarios**:

1. **Given** an instance whose steps 1 and 2 committed and whose step 3 sent an outbox command, **When** that command's
   handler fails with a final failure, **Then** the command is not retried, the instance compensates steps 3, 2 and 1
   (those that committed and declare a compensation), newest first, and ends `COMPENSATED`.
2. **Given** the same saga, **When** it ends compensated, **Then** exactly one "saga compensated" outcome event is
   published, and its trigger says that a command failed, naming the command type and the step that sent it.
3. **Given** the same saga, **When** a compensation fails, **Then** the instance ends `COMPENSATION_FAILED` and exactly
   one "saga failed" outcome event in the compensation phase is published, as for any other compensation failure.
4. **Given** the failure is reported, **When** an operator reads the instance, **Then** it records which command failed,
   its classification (final or exhausted), its cause type and the number of attempts.

---

### User Story 2 - A command that keeps failing exhausts and compensates (Priority: P1)

An outbox command's handler fails with errors that may go away (a remote system is down). It is retried with a delay
between attempts. When the attempts allowed for it are used up, the failure counts as exhausted and the saga that sent
it compensates, exactly as for a final failure.

**Why this priority**: Most real rejections from remote systems are not marked final. Without this, only handlers that
classify their failures would benefit.

**Independent Test**: Run a saga whose outbox command handler always throws an unclassified or transient failure. After
the allowed attempts, the instance compensates and the outcome event's trigger reports an exhausted command.

**Acceptance Scenarios**:

1. **Given** an outbox command whose handler always fails with a failure that is not final, **When** its attempts are
   used up, **Then** the failure is reported to the sending instance as exhausted, with the attempt count, and the
   instance compensates.
2. **Given** the handler fails twice and then succeeds, **When** the third attempt succeeds, **Then** nothing is
   reported and the instance continues as today.
3. **Given** a retried command, **Then** every attempt carries the same command identity, so a remote system that
   deduplicates by it sees one logical request.

---

### User Story 3 - Late or repeated failure reports change nothing (Priority: P1)

A failure report can arrive when the instance has already moved on: it completed, it failed, it is already compensating
because of another failure, or the same report is delivered twice after a crash. In each case the report is ignored with
a diagnostic, and the instance is never compensated twice.

**Why this priority**: Delivery is at least once. Without this, a redelivered report or a race between two failures
would corrupt the instance or run compensations twice.

**Independent Test**: Deliver the same failure report twice, and deliver one for an instance that already completed.
The first compensates once; the duplicate and the late report change nothing and each logs a diagnostic.

**Acceptance Scenarios**:

1. **Given** an instance that already ended (completed, failed, compensated, compensation failed), **When** a failure
   report for one of its commands arrives, **Then** the instance is unchanged, no outcome event is published, and a
   diagnostic names the instance, the command and the instance's status.
2. **Given** an instance already compensating, **When** a second failure report arrives, **Then** the running
   compensation continues as it would have without the report, and the report is logged as ignored.
3. **Given** a report was handled and the instance compensated, **When** the same report is delivered again,
   **Then** nothing changes.
4. **Given** the report's instance does not exist, **Then** the report is logged as unmatched and dropped.

---

### User Story 4 - Commands sent outside a saga keep today's behaviour (Priority: P2)

An application sends an outbox command from a plain service, not from a saga step. Its failures keep the relay's
behaviour: retried, then set aside for an operator, with no saga involved. The one change is that a final failure is no
longer retried.

**Why this priority**: Protects existing users; the feature must not invent a saga where there is none.

**Independent Test**: Send an outbox command outside a saga whose handler fails finally. It is set aside after one
attempt, and no saga is touched.

**Acceptance Scenarios**:

1. **Given** an outbox command sent outside a saga step, **When** its handler fails with a final failure, **Then** it is
   set aside after that attempt with the reason "final failure", and no failure report is produced.
2. **Given** the same command fails with a failure that is not final, **Then** it is retried and set aside at the limit
   as today.

---

### User Story 5 - Test command failures with the saga test fixture (Priority: P2)

A developer tests the compensation path of a saga in a plain unit test, using the saga test fixture, without a database
or a transport: they declare that an outbox command fails finally (or keeps failing) and assert that the saga
compensates and publishes its outcome event.

**Why this priority**: The fixture is how applications test sagas (009). A path that cannot be tested there is a path
applications will not trust.

**Independent Test**: A fixture test declares a final failure for one outbox command type, sends the triggering event,
and expects the compensations, the `COMPENSATED` status and the outcome event.

**Acceptance Scenarios**:

1. **Given** a fixture with a failure rule for an outbox command type, **When** the step that sends it runs, **Then** the
   fixture reports the failure to the instance and the expectations see the compensations and the outcome event.
2. **Given** a failure rule that fails a given number of times and then succeeds, **Then** the fixture shows the
   attempts and no compensation.

### Edge Cases

- **The command was sent by a compensation.** A compensation can itself send an outbox command (for example a refund).
  Its failure arrives while the instance is compensating or after it ended compensated. A still-compensating instance
  ends `COMPENSATION_FAILED` (FR-012a). An instance that already ended `COMPENSATED` stays, with a diagnostic: the
  undo it reported did not fully happen, which is recorded as a known limitation.
- **The instance is waiting for the event the command would have caused.** This is the normal case: the instance is
  `RUNNING` at a wait point and the report starts compensation.
- **Later steps committed after the command was sent.** The command was fire-and-forget and the saga moved on. All
  committed steps are compensated, including those after the sender, newest first.
- **The instance completed before the failure arrived.** Ignored with a diagnostic (User Story 3). Whether a completed
  saga should be told is out of scope and recorded as a gap.
- **The sending step has no compensation.** It is skipped like any step without a compensation (006).
- **Nothing else committed.** The sending step always committed (the command was stored with its unit of work), so a
  report never meets an instance with nothing to compensate unless no committed step declares a compensation; the
  instance then ends `COMPENSATED` at once, with its outcome event.
- **A crash between handling the report and setting the message aside.** The command is delivered again, fails again and
  is reported again; the second report changes nothing (User Story 3).
- **Several commands of one step fail.** The first report compensates; the others are ignored as in User Story 3.
- **An unreadable command payload.** It is set aside at once as today. If its identity names a saga instance, the
  failure is reported like a final failure, so the saga does not wait forever.
- **Outcome events switched off.** Compensation still runs; only the outcome event is not published (011).

## Requirements *(mandatory)*

### Functional Requirements

**Classifying outbox command failures**

- **FR-001**: The relay MUST classify each failed delivery of an outbox command with the existing failure
  classification: a failure marked final is `FINAL` and is not retried; a failure that is not final is retried; when the
  attempts allowed are used up the failure is `EXHAUSTED`.
- **FR-002**: Retries of a failed outbox command whose failure is not final MUST follow the relay's own settings
  (attempt limit and backoff), whether or not the command was sent from a saga step. A step's `@RetryPolicy` keeps
  governing only that step's own attempts.
- **FR-003**: Every attempt of an outbox command MUST carry the same command identity (005), so handlers that pass it to
  a remote system keep that system's deduplication.
- **FR-004**: A final or exhausted outbox command MUST be set aside with a reason that states which of the two it was,
  whether or not the command came from a saga.

**Reporting the failure to the sending saga**

- **FR-005**: When an outbox command sent from a saga step ends `FINAL` or `EXHAUSTED`, the failure MUST be reported to
  exactly one saga instance: the one its command identity names. A command sent outside a saga step MUST produce no
  report.
- **FR-006**: The report MUST carry the command identity, the command type, the classification (`FINAL` or
  `EXHAUSTED`), the failure's cause type and the number of attempts. It MUST NOT carry the failure message or the
  command's payload.
- **FR-007**: The report MUST be handed to the saga runtime, which changes the instance through the same single write
  path as every other saga state change (011's one unit of work). The transport MUST NOT write saga state itself, so a
  later transport only has to deliver the report.
- **FR-008**: A report MUST eventually reach the runtime even if the process stops after the command was set aside: the
  report is stored durably with, or before, the set-aside, and delivered at least once.

**Reacting to the report**

- **FR-009**: When a report reaches an instance that is running or waiting (not terminal, not compensating), the instance
  MUST start compensation, with the report as its compensation trigger, and compensate every committed step that
  declares a compensation, most recently completed first (006 order).
- **FR-010**: The compensation trigger and the instance MUST record that a command failed: the command type, the step
  that sent it, the classification, the cause type and the attempts. A new trigger kind "command failed" MUST
  distinguish it from a step's own final or exhausted failure.
- **FR-011**: The outcome event that ends such an instance (`SagaCompensated`, or `SagaFailed` in the compensation
  phase) MUST carry that trigger, so an observer can tell a failed command from a failed step.
- **FR-012**: A report of a command sent by a step, for an instance that is terminal, already compensating or missing,
  MUST change nothing, publish no outcome event, and produce a diagnostic naming the instance, the command identity and
  the reason it was ignored.
- **FR-012a**: A report of a command sent by a compensation, for an instance that is still compensating, MUST end the
  instance `COMPENSATION_FAILED`, recording the failed command, and publish one "saga failed" outcome event in the
  compensation phase. Compensations not yet run do not run. For an instance that already ended `COMPENSATED`, the report
  MUST change nothing and produce a diagnostic.
- **FR-013**: Handling the same report twice MUST have the effect of handling it once.
- **FR-014**: The default compensation MUST be the only reaction to a report. No saga step can take the report; the
  scanner keeps rejecting a step whose parameter is not a domain event, and a step on framework events stays with
  G-011-01.

**Scope protection**

- **FR-015**: Steps, compensations and handlers that do not use outbox commands MUST behave exactly as today.
- **FR-016**: The model MUST stay annotation-driven: no callback or listener is required to get the default reaction.

**Observability and testing**

- **FR-017**: A reported failure MUST appear in telemetry: the command's delivery span records its classification, and a
  counter of reported command failures by classification exists; the saga's handling of the report is traced like any
  other handling.
- **FR-018**: The saga test fixture MUST let a test declare that an outbox command type fails finally, or fails a given
  number of times, and MUST then deliver the report to the instance, so expectations see the compensation and the
  outcome event.
- **FR-019**: The user guide, the agent skill and the agent snippet MUST describe when an outbox command's failure
  compensates its saga, and the guide's limitations page MUST drop the statement that it does not.

### Key Entities

- **Command failure report**: a framework fact that one outbox command sent from a saga ended without success. Carries
  the command identity (which names the instance and the sending step), the command type, the classification, the cause
  type and the attempt count. Never the message or the payload.
- **Compensation trigger "command failed"**: why the instance compensates, recorded on the instance and carried by its
  outcome event. Names the command type and sending step, with the classification and attempts.
- **Set-aside outbox command**: the stored command, now with a reason that says final or exhausted, still available to
  operators as today.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A three-step saga whose third step's outbox command fails finally ends compensated, with steps 3, 2 and 1
  compensated in that order and exactly one outcome event, in 100% of runs of the acceptance suite.
- **SC-002**: The adopter's workarounds W3 and W4 (application-side translation of set-aside commands into compensation)
  can be removed with no loss of behaviour.
- **SC-003**: In a fault-injection run with crashes between every write of the report path, no instance is compensated
  twice and no report is lost: every instance whose command failed ends compensated or compensation-failed.
- **SC-004**: A final failure costs one handler attempt, not the relay's full attempt limit.
- **SC-005**: A remote call made in an outbox command's handler never runs in the unit of work that holds the sending
  instance's earlier writes.
- **SC-006**: Every existing test of the runtime, transport, fixture, starter and examples passes unchanged except where
  it asserted that a final outbox command failure is retried.

## Assumptions

- The relay keeps its current role: it alone retries outbox commands and sets them aside; the saga runtime only learns
  the outcome. A saga step's own in-process retry (005) is unchanged.
- The command identity of a command sent from a saga step already names the instance and the step (005), so no new
  correlation field is needed.
- The report is delivered through the existing outbox and relay, ordered with the instance's other messages, like
  outcome events (011).
- Compensation is unchanged: step-targeted, newest first, each in its own unit of work, a failed compensation ends
  `COMPENSATION_FAILED` and is not retried (006, 011).
- Only the in-memory transport exists; the Kafka transport will deliver reports the same way when it is built.
- Postgres keeps the new trigger kind and the set-aside reason; existing databases apply an upgrade script if the
  stored shapes change.
- A completed saga is not reopened by a late failure report; telling a completed saga about a failure is a separate,
  later decision.
