# Contract: saga-scoped unit of work (engine execution contract)

**Kind**: behavioral contract the future saga engine MUST honor; no core type in this feature (research §9).
Verified by the test-only `SagaTestHarness`. Spec: FR-017 to FR-024.

## Lifecycle of one unit of work

1. **Begin.** An event arrives through `EventDispatcher` (or is published outside any unit of work) and
   triggers a step of a saga type:
   - `@StartSaga` step: `SagaLifecycle.start(...)` creates the instance (`CREATED`).
   - Any other step: the instance is resolved through `StorageProvider.findByAssociation` using the step's
     `associationProperty`. No active instance → the event is reported as unmatched; a `COMPLETED` or `FAILED`
     instance → reported as unexpected. Either way nothing else happens (FR-003).
   The engine opens the host's local transaction, loads the saga's `SagaContextRecord`, and populates the
   saga's `SagaContext` and `CommandGateway` fields. It binds a `DomainEventRecorder` for the whole unit of work
   through `DomainEventScope.runWith`, so every `DomainEvents.record` call on this thread lands in it.
2. **Run steps.** The triggered step runs. Every event raised inside the unit of work that triggers a step of
   the same instance is queued and run in raising order, in the same transaction (FR-019). After each step
   returns, `SagaLifecycle.recordStepCompleted` marks it with the aggregates touched by the events
   recorded while it ran (visible only if the unit of work commits).
3. **Commit** when the first of these happens (FR-020):
   - an `@EndSaga` step returns → `SagaLifecycle.complete(sagaId, thatStep)`; later queued events no longer
     affect the instance;
   - the queue is empty (wait point) → `SagaLifecycle.advance(sagaId)`.
   Aggregate changes, step statuses, status and outcome commit together. Then buffered events go to
   `EventDispatcher.dispatch`.
4. **Roll back** if any step, handler, loader, saver, resolver, or provider throws, including a failure a step
   caught (FR-012, FR-021). Every aggregate change and buffered event of this unit of work is discarded. The
   engine then writes, in a separate write, the instance as `FAILED` with a `SagaFailure` (step, cause type,
   message, time). If the first unit of work rolled back, that write creates the instance record. No further
   step of the instance runs.

## `SagaContext` writes (FR-023, FR-024)

`putState` / `updateStateAttribute` changes are saved to `SagaContextStore` outside the saga transaction, no
later than the end of the step that made them, so they remain after a rollback. Reads in later steps (same or
later unit of work) see them.

## Invariants

- At most one unit of work per saga instance at a time (FR-022). Cross-process enforcement (for example an
  optimistic version on `SagaInstance`) is deferred to the engine and storage features (research §11).
- No event passes through the transport between two steps of the same unit of work (FR-019).
- A step is never recorded `COMPLETED` unless its unit of work committed (FR-004).
- A resumed unit of work needs only `StorageProvider` and `SagaContextStore` contents, not memory from an
  earlier unit of work (FR-020, SC-003).

## 004 delta (2026-11-09, `specs/004-saga-engine`)

Source: `specs/004-saga-engine/contracts/contract-deltas.md`. Where this contract says "the future engine", read `saga-forge-engine`.

**Saga-scoped unit of work, made concrete by the engine:**

1. **Begin.** `EventReceiver.receive` resolves the step and the instance. A start step first runs the *creation write*
   in its own transaction (instance `CREATED`, lifecycle entry `CREATED`); this replaces "`SagaLifecycle.start(...)`
   creates the instance" inside the unit of work. Then: the in-process instance lock, `TransactionBoundary.begin()`,
   `StorageProvider.findByIdAndLock(sagaId)`, a re-check (terminal → `UNEXPECTED`, still `CREATED` for a non-start
   step → `NOT_HANDLED`), loading the `SagaContextRecord`, creating the saga object (`SagaObjectProvider` or the
   no-argument constructor), injecting the `@SagaId`, `SagaContext` and `CommandGateway` fields, and binding the unit
   of work as the thread's `DomainEventRecorder`.
2. **Run steps.** Unchanged. Each `recordStepCompleted` adds a `STEP_COMPLETED` entry to the unit of work.
3. **Commit.** `complete` adds a `COMPLETED` entry. Then `save(instance, entries)`, the `IN_TRANSACTION` handoff if
   configured, `commit()`, release of the in-process lock, then the `AFTER_COMMIT` handoff (not
   `EventDispatcher.dispatch` directly; the default handoff calls it).
4. **Roll back.** Also on a failing instance write, commit or `IN_TRANSACTION` handoff, and on a rollback-only mark.
   The `FAILED` write runs in its own transaction, locks the instance and records the `FAILED` entry; it always
   updates the committed creation record and never creates one. A write-time `SagaConcurrencyConflictException` is
   not a failure: rollback, no `FAILED` write, result `NOT_HANDLED`.

**Invariants:**

- "Cross-process enforcement ... is deferred" no longer holds: it is enforced by `findByIdAndLock` as the first
  statement of the transaction, plus the in-process lock.
- New: a unit of work that waits for another one of the same instance has run no step and written no context.
- New: at most one active instance per saga type and association value within one engine (FR-027); across processes
  only after the other creation write committed (G-ENG-08).

**`SagaContext` writes:** unchanged (write-through after each call, own transaction). New: every context write
follows the committed creation write (G-ENG-01).
