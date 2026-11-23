# Contract: The Unit of Work (runtime-internal)

**Feature**: `011-saga-outcome-events` | Decision: [research.md](../research.md) §1

The runtime's package-private contract. There is no public API and no compatibility promise. It exists so the
refactoring has a checkable target.

## Concept

A unit of work is **one transaction around one piece of work**. It does not know which execution phase it serves
(starting, running steps, retrying, failing, compensating, handling a command). That knowledge lives in the work it
runs and in the classes that decide what runs next (`AttemptLoop`, `CompensationRun`, `EventRouter`, the command
gateway and receiver).

## Creation

| Factory | Scope |
|---|---|
| `UnitOfWork.forInstance(settings, saga, sagaId, correlation)` | locks and writes one saga instance |
| `UnitOfWork.standalone(settings, correlation)` | no instance; a command handled outside any unit of work |

## `run(Work<T, R> work)`: the only sequence

| # | Step | Owner |
|---|---|---|
| 1 | `begin()`; mark the thread "unit of work running" | unit of work |
| 2 | Instance scope only: `findByIdAndLock(sagaId)`. A failure of this read rolls back and propagates; nothing was recorded | unit of work |
| 3 | Instance scope only: `work.admit(locked)` with the locked instance or empty. On `Admission.Skip(reason)`: rollback, return `Skipped(reason)` | the work decides, the unit of work acts |
| 4 | Load the lifecycle with the locked instance | unit of work |
| 5 | Bind itself as the thread's recorder; `work.perform(context)` | the work |
| 6 | If a failure was reported, or the transaction is rollback-only: fail (step 10) | unit of work |
| 7 | Drain the lifecycle; `save(instance, entries)` if there is a change | unit of work |
| 8 | Build the batch: recorded events the work did not run itself (`work.recorded`), in recording order; then the outcome event if the drained write has one; outbox commands in send order; `recordedEvents` = every recorded event, then the outcome event. The batch is empty when all of these are empty (or only `recordedEvents` is non-empty and the outbox does not observe recorded events) | unit of work |
| 9 | `IN_TRANSACTION`: `store(batch)` before `commit()`, then `committed(batch)` after the caller released the lock. `AFTER_COMMIT`: `store(batch)` after the release. Return `Committed(value, afterRelease)` | unit of work |
| 10 | On any failure: discard the queue, buffer and pending entries; roll back if still active; return `Failed(running, firstReportedElseThrown)`, or `Conflicted` for a `SagaConcurrencyConflictException` from step 7 or the commit | unit of work |

## `Work<T, R>`

| Member | Contract |
|---|---|
| `Admission<R> admit(Optional<SagaInstance> locked)`: `Proceed`, or `Skip(R reason)` | a pure check of the locked instance: never writes, changes no state of the work; default `Proceed`. What the work needs of the instance it reads in `perform` from the loaded lifecycle |
| `T perform(WorkContext context)` | run user code and/or lifecycle transitions; may throw |
| `boolean recorded(Object event)` | `true` if the work will run this event itself in this transaction; default `false` |

`WorkContext` offers:
- the loaded lifecycle;
- a fresh saga object with its `@SagaId`, `SagaContext` and `CommandGateway` injected;
- `running(String methodName)`, which scopes the next command identities;
- the attempt number.

## Rules

| # | Rule |
|---|---|
| U1 | Only `UnitOfWork` calls `TransactionBoundary.begin`, `StorageProvider.findByIdAndLock`, `StorageProvider.save`, `Outbox.store` and `Outbox.committed` in `saga-forge-runtime` (ArchUnit test). |
| U2 | No class name, method name or result of the unit of work names a phase (step, compensation, retry, failure). |
| U3 | Every committed unit of work hands its non-empty batch to the outbox; there is no other path to the outbox. `EventPublisher` outside any unit of work keeps its direct dispatch. |
| U4 | Diagnostics and spans are emitted with the same names and attributes as before the refactoring. |
| U5 | The refactoring changes no behaviour: every runtime, fixture, harness-comparison and container suite passes unchanged before any outcome-event change lands. |

## As built (phase 1, 2026-11-20)

Phase 1 refined the shape above in five places without changing behaviour:

| Item | Built as | Why |
|---|---|---|
| `Work.recorded(Object) → boolean` | `recorded(Recording, WorkContext)`; the work calls `Recording.ranHere()` when it runs the event itself. The default rejects a non-`@DomainEvent` without reporting it | Whether a queued event ran is known only when its step runs, not when it is recorded. Each work keeps its own validation (steps report it and record touched aggregates; compensations report it; commands only reject it) |
| Step 5 "bind, then `perform`" | `perform` runs unbound and binds the recorder around user code with `WorkContext.recording(Runnable)` | Saga-object creation stays outside the recorder binding, as before |
| Creation write | a third scope, `forNewInstance(with, saga, lifecycle, sagaId)`: no lock, no id drawn, it drains the lifecycle that ran `start` | Rule U1 covers the creation write too, and the instance does not exist yet to lock |
| Unit of work id | `forInstance` keeps the id that `about` names and draws a fresh one only when it names none | The lifecycle-only writes (`RecordFailure`, `ClaimAttempt`, `StartCompensation`, `RecordCompensationFailure`) report under the rolled-back unit of work's id, as before, and draw no `uow-N` id |
| Gateway view | `BoundUnitOfWork`, an interface `UnitOfWork` implements, with `current()` | The command gateway and the reentrancy guard need only the recorder view. Test doubles implement it: `EngineCommandGatewayTest`'s double changed its `implements` clause, the only test edit of phase 1 |

Results carry what callers need: `Committed(value, unitOfWorkId, afterRelease)`, `Failed(cause, unitOfWorkId)` and
`Conflicted(conflict, unitOfWorkId)`. Each work has a static entry point (`RunSteps.attempt`, `RunCompensation.next`,
`HandleCommand.run`, `RecordFailure.record`, `ClaimAttempt.afterFailure`/`onResume`, `StartCompensation.record`,
`RecordCompensationFailure.record`). These entry points map the result to their caller's vocabulary and log the
same diagnostics as before (U4).

## Mapping from the removed classes

| Removed class | Now |
|---|---|
| `SagaUnitOfWork` | `forInstance` + `RunSteps` |
| `CompensationUnitOfWork` | `forInstance` + `RunCompensation` |
| `NonSagaUnitOfWork` | `standalone` + `HandleCommand` |
| `FailedWrite` | `forInstance` + `RecordFailure` |
| `AttemptWrite` | `forInstance` + `ClaimAttempt` |
| `CompensationStartWrite` | `forInstance` + `StartCompensation` |
| `CompensationFailedWrite` | `forInstance` + `RecordCompensationFailure` |
| the `UnitOfWork` interface | `UnitOfWork` class (still a `DomainEventRecorder`) |
