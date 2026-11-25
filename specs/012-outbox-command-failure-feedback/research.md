# Research: Outbox Command Failure Feedback

**Feature**: `specs/012-outbox-command-failure-feedback/spec.md` | **Date**: 2026-11-24

Each section records a decision, why, and what else was considered. Code facts were read on master after PR #18
(011 merged).

## §1 What reports the failure: a value handed to the runtime, not a routed event

**Decision.** The failure is a value, `core.spi.CommandFailure`, handed by the transport to the runtime through a
second method on the existing `CommandReceiver` SPI. It is not a `@DomainEvent`, is never appended to the message store
as its own message, and is not routed by `EventRouter`.

**Rationale.**
- FR-014 (clarified): no saga step can take the report, so it needs none of an event's machinery (association
  routing, a step parameter type, delivery to listeners).
- The command message itself is already the durable record (FR-008): it stays leased until the relay sets it aside, and
  a crash before that redelivers it, so its handler fails again and the report is produced again. No second message
  is needed to make the report durable.
- `CommandReceiver` is the transport's inbound door for commands. A command's failure is part of the same exchange;
  the transport already holds a `CommandReceiver`, so no builder or wiring changes are needed.

**Alternatives considered.**
- *Relay appends a `CommandFailed` event in the set-aside transaction* (the roadmap's first sketch). Rejected: the
  transport would write a framework event whose only consumer is the runtime, the Kafka transport would repeat it, and
  it adds an event type that no step may take (G-011-01).
- *A separate `CommandFailureReceiver` SPI.* Rejected for now: every transport that delivers outbox commands must also
  report their failures, so one interface keeps the obligation together. It stays small (two methods, both used by
  every caller), so the interface segregation cost is nil.

**Familiarity.** High. `CommandFailure` sits beside the repo's `DeliveryFailure` and `SagaFailure`. The pattern is the
one MassTransit (`Fault<T>`) and NServiceBus (failed-message notification to the saga) use: the infrastructure tells
the process manager that a message it sent ended in the error queue.

## §2 One write path: the runtime reacts in its unit of work, and sets the message aside there

**Decision.** `CommandReceiver.receiveFailure(CommandFailure failure, Consumer<LocalTransaction> inTransaction)`,
mirroring `receive(OutboxCommand)`'s `inTransaction` callback. The runtime runs the reaction in
`UnitOfWork.forInstance(sagaId)` and calls `inTransaction` with that unit of work's transaction, where the relay sets
the message aside (`MessageStore.setAside(LocalTransaction, ...)`, a new overload like `markConsumed(tx, ...)`). The
reaction and the set-aside commit together.

When the reaction is skipped (missing, terminal or already compensating instance, FR-012), the runtime still calls
`inTransaction` in a standalone unit of work (the existing `HandleCommand` work), so the relay never needs to know which
case happened. A command sent outside a saga never reaches `receiveFailure` (FR-005); the relay sets it aside itself,
as today.

**Rationale.** FR-007: the transport writes no saga state, and the one unit of work (011, ArchUnit-enforced) stays the
only runtime write path. Committing the reaction and the set-aside together removes the window in which a crash leaves
one without the other (SC-003).

**If `receiveFailure` throws** (storage down, a conflict): the relay records the attempt with its normal backoff
instead of setting the message aside. The message comes back, the handler runs again (it is idempotent by its
`CommandId`, 005), fails again, and the report is retried. This costs one extra handler call only in that failure case;
SC-004 (one attempt for a final failure) holds on the normal path.

**Alternatives considered.** The relay sets aside first, then reports in a separate call. Rejected: a crash between the
two loses the report (FR-008).

## §3 Classification in the transport: markers only, shared from core

**Decision.** Core gains `FailureClassification.of(Throwable)`: walks the cause chain (stopping at a repeat) and returns
`FINAL` for the first `FinalFailure`, `TRANSIENT` for the first `TransientFailure`, `UNCLASSIFIED` otherwise, and
`UNCLASSIFIED` for an `Error`. The runtime's `FailureClassifier` keeps its step-policy `retryOn` rule and delegates the
marker part to it, so the two cannot drift.

The relay classifies each failed delivery of an outbox command:
- `FINAL` → no retry. A saga-scoped command is reported (`classification = FINAL`); any command is set aside with
  reason `FINAL_FAILURE`.
- anything else → retried with the relay's own `maxAttempts` and backoff (clarified: relay settings, FR-002). When
  `attempts >= maxAttempts`, a saga-scoped command is reported as `EXHAUSTED`; any command is set aside with the existing
  reason `MAX_ATTEMPTS`.
- an unreadable payload: set aside at once as today (`UNREADABLE`); if its stored `CommandId` is saga-scoped it is
  reported as `FINAL` with cause type `UnreadableMessageException` (spec edge case).

Events keep today's path; only `MessageKind.COMMAND` messages are classified.

**Rationale.** Principle I: the classification rule is pure JDK and belongs with the markers in core. The transport
must not depend on the runtime.

**Alternatives considered.** Duplicating the marker walk in the transport (drift risk); moving the whole
`FailureClassifier` to core (its `retryOn` rule needs a `RetryPolicy`, which is step-specific and stays in the runtime).

## §4 Which instance, and which kind of command

**Decision.** `CommandId.sagaScoped()` decides whether a report exists; `CommandId.sagaId()` names the instance;
`CommandId.stepName()` names the sender. The runtime resolves the sender against the saga model:
- a `@SagaStep` method name → a step-sent command (FR-009 to FR-012);
- a `@SagaCompensation` method name → a compensation-sent command (FR-012a), mapped to its `forStep`;
- neither (a saga type renamed since the command was sent) → ignored with a diagnostic, set aside as usual.

The saga type is read from the locked instance, not from the command, so no new field is needed on `CommandId`
(spec Assumptions).

## §5 The reaction to a step-sent command reuses `StartCompensation`

**Decision.** The existing `StartCompensation` work records a trigger the caller builds, instead of always building
`CompensationTrigger.failed(...)` itself: its constructor takes the `CompensationTrigger` to record. It gains one
admission reason, `NotStarted.ALREADY_COMPENSATING`. Then, as after a step's own final failure, the existing
`CompensationRun` compensates every committed step newest first, each in its own unit of work, under the instance lock
(006).

`ALREADY_COMPENSATING` is behaviour-preserving for today's caller: `RunSteps` diverts a `COMPENSATING` instance before a
step runs, so `StartCompensation` never met one; now it says so explicitly instead of failing in the lifecycle.

A new orchestrator `runtime.internal.CommandFailureHandler` does for a report what `AttemptLoop` does for an event:
acquire the instance lease (`InstanceLocks`), run the work, run compensations if it started them, release the lease,
then run the after-release outbox steps, and return. It holds no business rule of its own.

**Rationale.** Composition: the trigger becomes a constructor argument, not a subclass or a flag. FR-013 (idempotence)
falls out of the admission: after the first report the instance is `COMPENSATING` or terminal, so a second report is
skipped.

**Familiarity.** `...Handler` is common vocabulary (the repo's own `@CommandHandler`, Axon's event handlers). The works
keep the verb-object names 011 introduced.

## §6 The reaction to a compensation-sent command: a new work and one new transition

**Decision.** A new work `RecordCompensationCommandFailure(step, failure)`:
- admission: proceed only if the instance is `COMPENSATING`; `COMPENSATED` → skip with a diagnostic (G-012-01); any
  other status or missing → skip with a diagnostic;
- perform: `EngineSagaLifecycle.recordCompensationCommandFailed(sagaId, step, failure)`, which moves that step's
  compensation `COMPENSATED → FAILED` with the failure and the instance `COMPENSATING → COMPENSATION_FAILED`. The
  terminal write appends the `SagaFailed(COMPENSATION)` outcome event, as 011 does for any failed compensation.

Postgres `ForwardTransition` allows `COMPENSATED → FAILED` for a step's compensation only when the same save moves the
instance `COMPENSATING → COMPENSATION_FAILED`; every other backward move stays rejected. The runtime's and the
fixture's in-memory storages get the same rule.

`RunCompensation`'s admission already stops a run whose instance is no longer `COMPENSATING`, so compensations not yet
run do not run (FR-012a).

**Rationale.** The compensation did not fully happen, so marking its record `FAILED` states the fact, and satisfies
`SagaInstance`'s invariant that a `COMPENSATION_FAILED` instance has a failed compensation. A separate work keeps
`RecordCompensationFailure`'s admission (a compensation that has not run) unchanged, rather than adding a flag argument.

**Alternatives considered.** Reusing `RecordCompensationFailure` with a mode flag (flag argument, two admissions in one
work); keeping the step `COMPENSATED` and storing the failure elsewhere (breaks the instance invariant).

## §7 The trigger and the outcome event say "a command failed"

**Decision.**
- `CompensationTriggerKind.COMMAND_FAILED` (additive enum value).
- `CompensationTrigger` gains a nullable `commandType` component, non-null if and only if the kind is
  `COMMAND_FAILED`; factory `CompensationTrigger.commandFailed(CommandFailure failure, Instant at)`. `stepName` is the
  sending step; `failure` is a `SagaFailure` built from the report with a `null` message (FR-006: no message), its
  `classification`, `causeType` and `attempts`.
- `OutcomeTrigger` gains the same nullable `commandType`, copied by `OutcomeTrigger.of(trigger)`.
- Both records keep their current public constructors as secondary constructors that pass `null`, so code compiled
  against 0.1.x keeps compiling (released API, DEC-REL-01).

**Postgres.** The trigger is stored as JSONB (`compensation_trigger`), so no column changes. The codec writes and reads
`commandType` and treats a missing field as `null`, so rows written before 012 read as before. No upgrade script is
needed for the trigger. Telemetry's `trigger` attribute gains the value `command_failed`.

## §8 Telemetry

**Decision.**
- Transport: the delivery span of a failed outbox command records `saga_forge.command.classification`
  (`final`, `transient`, `unclassified`) and, when it reports, `saga_forge.command.reported = true`. A counter
  `saga_forge.command.failures` with `classification` (`final`, `exhausted`) counts reports.
- Runtime: `receiveFailure` runs under a `saga_forge.handle` span with `saga_forge.compensation.trigger = COMMAND_FAILED`
  (the existing key; see contracts/runtime-reaction.md), continuing
  the delivery span's context; compensation spans follow as for any compensation (010).

## §9 Test fixture

**Decision.** `LoopBackTransport` emulates the relay for outbox commands: it classifies a handler failure with
`FailureClassification.of`, re-delivers a non-final failure up to `SagaFixture.Builder.outboxCommandAttempts(int)`
(default 1, so existing fixture tests that expect a failed outbox command to surface see it after one attempt), and then
calls `receiveFailure` for a saga-scoped command. `EventResult` keeps reporting the command failures it reports today,
so existing tests keep passing (SC-006), and the instance view shows the compensation.

The default of 1 attempt means an unclassified failure is reported as `EXHAUSTED` after one attempt in the fixture.
That is a behaviour change for fixture tests whose saga sends an outbox command that fails: the saga now compensates.
Research of the existing tests (`FailureAndCompensationTest`, `HandlerModeTest`, `FixtureParityTest`) is task work;
any test that asserted the old silence is updated, which SC-006 allows.

**Alternatives considered.** Default to the transport's `maxAttempts` (10): makes simple tests slow to read and is not
what a unit test wants by default.

## §10 Known limits carried forward

- G-006-05: a crash after the reaction committed (`COMPENSATING`) but before the compensations ran leaves a
  `COMPENSATING` instance that resumes only on its next event. 012 inherits it; the operations sweeper (roadmap 015)
  closes it.
- G-012-01, G-012-02, G-012-03 (memory.md).
