# Research: Saga Outcome Events

**Feature**: `011-saga-outcome-events` | **Date**: 2026-11-20 (revised the same day) | **Spec**: [spec.md](spec.md)

Each section records a decision, why it was taken, and what else was considered. Code references are to the state of
`master` at `bfaa25f`.

**Revision (user, 2026-11-20).** Three changes to the first plan:
- failure-text cleaning was dropped from this feature;
- `REQUIRES_INTERVENTION` is renamed to `COMPENSATION_FAILED`;
- the runtime's phase-specific unit-of-work classes are consolidated into one unit of work, as the feature's first
  phase (§1).

## 1. One unit of work that does not know its execution phase

### Today

Seven runtime classes each own one transaction, and several encode the execution phase they serve:

| Class | What it does in its transaction |
|---|---|
| `SagaUnitOfWork` | runs a triggering step and the steps its events trigger in process |
| `CompensationUnitOfWork` | runs one `@SagaCompensation` method |
| `NonSagaUnitOfWork` | runs a command handler called outside any unit of work |
| `FailedWrite` | marks an instance `FAILED` |
| `AttemptWrite` | claims the next retry attempt |
| `CompensationStartWrite` | moves an instance to `COMPENSATING` (or `COMPENSATED`) after a final failure |
| `CompensationFailedWrite` | records a failed compensation |

`UnitOfWork` itself is an interface that only covers event recording and command identity. Each class repeats the
same transactional skeleton in its own way:
1. begin the transaction;
2. take the instance lock;
3. load the lifecycle;
4. run something;
5. drain and save the lifecycle write;
6. hand the batch to the outbox at its timing;
7. commit, or roll back and discard.

The four `*Write` classes skip step 6 entirely, which is why outcome events would have needed a special path for
them. The class names also tell readers that a unit of work is a phase-specific concept, which it is not.

### Decision

There is **one `UnitOfWork` class**: one transaction, from begin to commit or rollback, whatever it serves. What runs
inside it is a **`Work`**, a small strategy object. The unit of work never asks which phase it is in.

**The unit of work owns:**
- the transaction: begin, the rollback-only check, commit, rollback;
- the reentrancy marker;
- for an instance-scoped unit of work, the locked read (`findByIdAndLock`), loading the lifecycle, saving the drained
  `PendingWrite`, and the in-process lock hand-back. It does not interpret the locked instance: it passes it to the
  work's admission check;
- event recording (`DomainEventRecorder`), failure reports from the command gateway, outbox commands and command
  identities. Command identities are scoped to the instance and the name of the method running, which the work sets;
- the outgoing batch: events nothing ran in process, outbox commands, all recorded events, and the outcome event last
  (§4). It calls the outbox at its timing, then `committed`;
- conflict detection on the write and the commit, and discarding everything on rollback.

**A work owns:**
- `admit(SagaInstance locked)`: proceed, or a reason not to run;
- `perform(WorkContext)`: what runs. `WorkContext` gives the lifecycle, the saga object factory, the "running method"
  name for command ids and the recorder hooks;
- `recorded(Object event)`: whether the work runs the event itself in this transaction (step chaining), so that it is
  left out of the outbox's `events`. The default is "no".

**Instance scope is data, not a subclass.** A unit of work is created for an instance (`UnitOfWork.forInstance(...)`)
or without one (`UnitOfWork.standalone(...)`), for a command sent outside any unit of work.

**Works, named by what they do:**

| Work | Replaces | Content |
|---|---|---|
| `RunSteps` | `SagaUnitOfWork` | admission rules (duplicate, terminal, not started, compensating, resume a claimed retry); runs the trigger step, then queued events; `@EndSaga` → `complete`, `@StartCompensation` → `startCompensation`, else `advance`; clears the pending retry |
| `RunCompensation` | `CompensationUnitOfWork` | admits a `COMPENSATING` instance with a compensation left; runs the next one; `recordCompensated` |
| `HandleCommand` | `NonSagaUnitOfWork` | runs one command handler; standalone scope |
| `RecordFailure` | `FailedWrite` | admits a non-terminal instance; `fail` |
| `ClaimAttempt` | `AttemptWrite` | `attemptFailed` / `claimAttempt` |
| `StartCompensation` | `CompensationStartWrite` | admits a non-terminal instance with a committed step; `startCompensation` |
| `RecordCompensationFailure` | `CompensationFailedWrite` | admits a compensating instance whose step waits; `recordCompensationFailed` |

**Unit-of-work result.** A sealed `UnitOfWork.Result<T>`:

| Result | Meaning |
|---|---|
| `Committed<T>(T value, AfterRelease afterRelease)` | `afterRelease` runs the `AFTER_COMMIT` outbox or `committed()` after the caller released the in-process lock, as today |
| `NotRun<T>(T reason)` | rolled back; nothing ran (the work's own reason value) |
| `Failed(String running, Throwable cause)` | rolled back; the first reported failure, else the thrown one |
| `Conflicted` | rolled back; write-time conflict |

**Unchanged.** The classes that decide what happens next keep that responsibility:
- `AttemptLoop`: attempts, waits, classification, and choosing between `RecordFailure` and `StartCompensation`;
- `CompensationRun`: compensation order and when to stop;
- `EventRouter`: routing and fan-out;
- `EngineCommandGateway` / the command receiver: nested and outbox command handling.

They translate unit-of-work results into `SagaHandlingResult`s exactly as today.

### Rationale

The transaction boundary is one concept with one implementation. A reader sees that a unit of work is "a transaction
around some work", not a compensation-phase or saga-phase thing (the user's point). Writes that had no outbox step get
one for free, so outcome events (§4) need no special path. The duplicated skeleton disappears: the rollback, discard,
outbox-timing and conflict handling exist once.

### Behaviour preservation

The refactoring is the feature's first phase and changes no observable behaviour:
- same transactions;
- same writes and lifecycle entries;
- same outbox batches (the four former `*Write` classes produce empty batches, so they store nothing, as today);
- same diagnostic event names (`saga.uow.committed`, `saga.uow.rolledBack`, `saga.instance.failed`, ...);
- same span names and attributes;
- same `SagaHandlingResult`s.

The evidence is the unchanged runtime, fixture (`FixtureParityTest`), harness-comparison and container suites, run
before any outcome-event change.

A structural test (ArchUnit, test scope, accepted by G-GOV-01) keeps it that way. Only `UnitOfWork` may call
`TransactionBoundary.begin`, `StorageProvider.findByIdAndLock`, `StorageProvider.save` and `Outbox.store`/`committed`
in `saga-forge-runtime`.

### Alternatives

- **Keep the classes and add an outbox step to the four writes.** This is the first plan's `OutcomeHandOff`. It keeps
  the duplicated skeleton and the misleading hierarchy.
- **A template-method base class with phase subclasses.** It still encodes phases in the class hierarchy.
- **A separate refactoring feature before 011.** The user chose the first phase of 011 instead.

## 2. Where the outcome event is created: the lifecycle, at the terminal transition

**Decision.** `EngineSagaLifecycle` creates the outcome event when it sets a terminal status and returns it with the
write it already produces: `PendingWrite` gains `Optional<SagaOutcome> outcome`.

| Lifecycle method | Terminal status | Outcome event |
|---|---|---|
| `complete(sagaId, outcomeStep)` | `COMPLETED` | `SagaCompleted` |
| `fail(sagaId, failure)` | `FAILED` | `SagaFailed`, phase `STEP` |
| `endCompensatedIfDone` (from `startCompensation`, `recordCompensated`) | `COMPENSATED` | `SagaCompensated` |
| `recordCompensationFailed(sagaId, step, failure)` | `COMPENSATION_FAILED` | `SagaFailed`, phase `COMPENSATION` |

**Rationale.** The lifecycle is the only code that moves an instance to a terminal status. Since every unit of work
saves the drained `PendingWrite` and builds the batch from it (§1), "one event per terminal transition, committed with
it" (FR-008) is structural. A rolled-back unit of work discards the event with the entries. A second terminal
transition is impossible (`requireActive`).

## 3. Event identity

**Decision.** `eventId = sagaId + ":" + terminalStatus`, for example `saga-17:FAILED` or
`saga-17:COMPENSATION_FAILED`. The transport's message id stays `unitOfWorkId:index`.

**Rationale.** It is unique per instance and the same on every delivery (FR-011). With the rename (§6), no identifier
states a judgement.

## 4. Event shape and position in the batch

**Decision.** A new package `core.outcome`:
- a sealed interface `SagaOutcome` with three records, `SagaCompleted`, `SagaFailed` (with `FailurePhase` `STEP` or
  `COMPENSATION`) and `SagaCompensated`;
- `OutcomeTrigger`, a message-free summary of `CompensationTrigger`.

See [data-model.md](data-model.md).

The unit of work appends the outcome as the batch's last element of `events` and of `recordedEvents`. It comes after
every event the steps recorded, including events still queued when an `@EndSaga` step ended the run (FR-012). Batches
of the four lifecycle-only works then hold exactly the outcome event.

**Rationale.** Records keep core dependency-free (Principle I) and serialize through `PayloadCodec` and Spring. The
sealed parent lets a listener take all outcomes or one kind. `CompensationTrigger` cannot be reused because it holds a
`SagaFailure` with the message (FR-007).

**Alternatives.** One `SagaEnded` record with nullable parts forces consumers to branch on a status field. A fourth
event type for a failed compensation was rejected by the user: it is a failure like any other.

## 5. Outcome events and the `@DomainEvent` model

**Decision.** Outcome types carry no `@DomainEvent`.
1. **`EventRouter.receive`** returns an empty `HandlingReport` for a `SagaOutcome`, before its `@DomainEvent` check. A
   transport that loops one back gets "no saga reacted", and does not retry the message and set it aside (FR-017).
2. **`OrderingKeys.ofEvent`** (in-memory transport) keys it `"saga:" + sagaId`, the key saga-scoped commands already
   use (FR-014).
3. **The scanner is unchanged**: a `@SagaStep` parameter must be `@DomainEvent`-annotated, so a step taking an outcome
   type is rejected with the existing message. A scanner test pins it.

## 6. Renaming `REQUIRES_INTERVENTION` to `COMPENSATION_FAILED`

**Decision.**

| Where | Change |
|---|---|
| `core.spi.SagaStatus` | `REQUIRES_INTERVENTION` → `COMPENSATION_FAILED` |
| `core.spi.HandlingOutcome` | `REQUIRES_INTERVENTION` → `COMPENSATION_FAILED` |
| `core.spi.SagaLifecycleEventKind` | `REQUIRES_INTERVENTION` removed. The existing `COMPENSATION_FAILED` entry, which names the step and its failure, is the terminal entry (FR-020) |
| `SagaInstance`, `SagaHandlingResult` validation and Javadoc | renamed status |
| runtime `EngineSagaLifecycle`, `CompensationRun`, `EventRouter` | renamed. The telemetry outcome attribute value `requires_intervention` becomes `compensation_failed` |
| Postgres `ForwardTransition`, `schema.sql` (`trigger_iff_compensation`) | renamed |
| Postgres `schema-upgrade-011.sql` (new) | see below |
| fixture `InstanceView` Javadoc, tests, guide, README, architecture, use cases, AGENTS.md, `.kiro` design (Properties 30 to 34) | renamed |

**`schema-upgrade-011.sql`** runs in one transaction while no engine runs, and is idempotent (FR-021):
1. drop `trigger_iff_compensation`;
2. `UPDATE saga_instance SET status = 'COMPENSATION_FAILED' WHERE status = 'REQUIRES_INTERVENTION'`;
3. re-add `trigger_iff_compensation` with the new name;
4. `DELETE FROM saga_lifecycle_history WHERE kind = 'REQUIRES_INTERVENTION'`. Each such row was written together with a
   `COMPENSATION_FAILED` row that holds the step and failure, so nothing is lost. The history has no reader API
   (G-003-24), so no reader depends on the removed kind.

**Rationale.** The status says the fact: the compensation failed. Whether to act is the observer's decision (spec
Clarifications). Keeping a second terminal entry kind would duplicate the `COMPENSATION_FAILED` entry under a
judgemental name.

**Compatibility.** This is a breaking change of public enum values and of one telemetry attribute value, allowed for
a 0.x minor release (DEC-REL-01). The release notes list it with the upgrade script.

**Alternatives.**
- Keep the old name as a deprecated alias. Java enums cannot alias, and two names for one state invite both in data.
- Rename the history kind too and keep two entries. That is redundant.

## 7. Spring delivery

**Decision.** There is no starter code change for delivery. `ApplicationEventPublishingOutbox.store` publishes every
element of `recordedEvents`, which now includes the outcome event, inside the transaction under
`saga-forge.application-events.mode=in-transaction` (FR-015). Under `after-delivery`, `ApplicationEventBridge`
republishes it after delivery. Under `none`, nothing is published. The modes are exclusive, so a listener sees an
outcome event once.

**Starter addition.** The property `saga-forge.outcome-events.enabled` (default `true`). The README property table and
`ReadmePropertiesTest` follow.

## 8. Delivery guarantee per outbox

**Decision.** Outcome events get the guarantee of the outbox they go to (FR-013):

| Outbox | Guarantee |
|---|---|
| In-memory transport, own log | stored in the terminal write's transaction, at least once |
| In-memory transport, Spring Modulith interop | stored in Modulith's registry in that transaction, at least once |
| `ImmediateDispatchOutbox` (default) | dispatched after commit, at most once (G-ENG-07) |
| Saga test fixture | exactly once, synchronous |

**Outbox failure.** An `IN_TRANSACTION` outbox that throws fails the unit of work it belongs to, as it does today for
every unit of work.
- For `RecordFailure` this means the `FAILED` write is not recorded: `saga.instance.failedNotRecorded` is logged and
  the exception propagates, as for a store failure.
- An `AFTER_COMMIT` outbox failure is listed in `outboxFailures` and the terminal status stays written.

## 9. Switching outcome events off

**Decision.** `SagaRuntime.Builder.outcomeEvents(boolean)`, default `true` (FR-016). When it is off, the lifecycle
produces no outcome, and batches equal those of 0.1.0 apart from the rename.

## 10. Saga test fixture

**Decision.**
- `LoopBackTransport.committed` collects the batch's `SagaOutcome`s into the current `EventResult` before queueing
  the batch. The runtime ignores them on delivery (§5).
- `EventResult.outcomeEvents()` returns them.
- `Then` gains `expectOutcomeEvent(Class<T>, Predicate<? super T>)`, `expectOutcomeEvent(Class<?>)` and
  `expectNoOutcomeEvent()` (FR-018, SC-007).
- `SagaFixture.Builder.outcomeEvents(boolean)` mirrors the runtime.

The methods say "outcome event" because `Then.expectOutcome(HandlingOutcome)` already exists with another meaning.

## 11. Persistence

**Decision.**
- Outcome events need no schema change. They are stored as messages, as JSON through `PayloadCodec`. A container test
  round-trips each type: records with enums, a nested record, an `Instant` and a `Map<String, String>`.
- The rename needs `schema-upgrade-011.sql` (§6), and `schema.sql` is updated for new databases.

## 12. Formal design

`.kiro/specs/saga-forge-framework/design.md`:
- **Property 38: Outcome events.** For every terminal transition the runtime writes, exactly one outcome event of the
  matching kind is part of that write's outgoing batch. There is none when the write rolls back or records nothing. Its
  identity is `sagaId:status`, and it carries no failure message, context value or saga field.
- **Properties 30 to 34** read `COMPENSATION_FAILED` for `REQUIRES_INTERVENTION`, and Property 27's entry list drops
  the `REQUIRES_INTERVENTION` kind.
- The closing per-feature paragraph gains 011.

No annotation or scanner rule changes.

## 13. Documentation

- The guide (`docs/guide/`) gains outcome events: Spring and plain hosts, the guarantee table, fixture expectations.
  It renames the status everywhere and points to logging-backend masking and the OpenTelemetry Collector's redaction
  processor for anyone worried about failure text in logs and spans (G-ADOPT-03).
- `SKILL.md`, the AGENTS snippet and `llms-full.txt` follow.
- The starter README gains the new property.
- AGENTS.md gains a "Current Status" entry and the renamed status.
- memory.md: G-ADOPT-01 and G-011-02 close at implementation.

## 14. Dropped from the first plan

Failure-text cleaning (`FailureMessageRedactor`, the default masking rule, `RedactedFailure` copies, the
`failures.*` properties) is out of this feature (spec Clarifications). Logs and spans can be cleaned with existing
tools: Logback `%replace`, Log4j2 `RegexReplacement`, and the OpenTelemetry Collector's `redaction` and `transform`
processors. What Saga Forge stores stays open as G-ADOPT-03. Gaps G-011-03 to G-011-05, which described that design,
are withdrawn.
