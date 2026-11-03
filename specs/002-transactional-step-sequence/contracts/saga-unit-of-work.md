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
