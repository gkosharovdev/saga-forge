# Contract: Runtime deadline behaviour (FR-011 to FR-016, FR-019)

All writes go through `runtime.internal.UnitOfWork` (ArchUnit `UnitOfWorkBoundaryTest`).

## Startup

1. Resolve every `@StartDeadline.duration` `${key[:default]}` through `Builder.propertyResolver` (default: none ⇒ a
   `${...}` without default fails startup). Parse; failure ⇒ `SagaRuntimeStartupException`.
2. If any deadline is declared and `outbox.supportsScheduling()` is `false` ⇒ `SagaRuntimeStartupException`
   (contracts/outbox-and-gateway.md).

## Starting, replacing, cancelling (per unit of work, after the steps ran, before the instance write)

For each step that completed in the unit of work, in completion order:
- for each `@StartDeadline` on it: duration = fixed, or `SagaContext` attribute parsed as ISO-8601 (missing/bad ⇒
  `DeadlineDurationException`, a `FinalFailure`, failing the unit of work); `dueAt = clock.instant() + duration`
  truncated to ms; if open already ⇒ cancel old id (`saga.deadline.replaced`); put `OpenDeadline`; add a
  `ScheduledMessage(EVENT, DeadlineReached)` with id `<sagaId>:deadline:<name>:<unitOfWorkId>`;
- for each open deadline whose `cancelledBy` contains the step ⇒ cancel.

If the write leaves `RUNNING` (end, compensation start, `FAILED`, compensation-command failure) ⇒ cancel every open
deadline, including any started in this unit of work (that one is simply never appended).

Cancels call `outbox.cancel(ids)` in the unit of work's transaction; lifecycle entries `DEADLINE_STARTED` /
`DEADLINE_CANCELLED` are written with the instance. A start or cancel in a unit of work that rolls back leaves nothing.

The `FAILED` write (after rollback) runs in its own transaction and cancels there.

## Delivery of `DeadlineReached`

`EventReceiver.receive(DeadlineReached d)`:
1. Unknown saga type ⇒ `EventHandlingException` (as an unknown event; model mismatch between processes).
2. Take the instance lock (`InstanceLocks`, `findByIdAndLock`).
3. Instance missing, not `RUNNING`, no open deadline `d.deadlineName()`, or its `deadlineId` ≠ `d.deadlineId()` ⇒
   `HandlingOutcome.IGNORED`, no write, diagnostic `saga.deadline.ignored` with reason `NOT_RUNNING` / `NOT_OPEN` /
   `REPLACED`, counter `event=ignored`. The relay marks the message consumed.
4. Else run the `@DeadlineExpired` step in a `forInstance` unit of work (retry policy, failure classification,
   compensation as any step). The deadline is closed in that unit of work (lifecycle `DEADLINE_EXPIRED`). Events the
   step records continue in the same unit of work as today.
5. If the timeout step is `@StartCompensation`: trigger kind `DEADLINE_EXPIRED`, `deadlineName`, `stepName` = timeout
   step, `eventType` = `DeadlineReached` FQN. The outcome event (`SagaCompensated` / `SagaFailed`) carries the same.

Concurrency: the cancelling step and a delivery serialise on the instance lock; whichever commits second sees the
other's result (step completed ⇒ deadline closed ⇒ delivery ignored; timeout committed ⇒ instance compensating or
step event handled per the existing compensating/duplicate rules).

## Telemetry

- `saga_forge.handle` span for a delivery, attribute `saga_forge.deadline.name`, outcome `ignored` for step 3.
- Counter `saga_forge.deadlines` with `saga_forge.saga.type`, `saga_forge.deadline.name` and `saga_forge.deadline.event` = `started` | `cancelled` | `expired` | `ignored`.
- Transport delivery span: `saga_forge.message.not_before` when set.
