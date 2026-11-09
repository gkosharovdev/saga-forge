# Contract deltas: what 004 changes in the 001, 002 and 003 contracts

Only differences are listed. Where a base contract says "the future engine", read "`saga-forge-engine`".

## 002 `contracts/saga-unit-of-work.md` (made concrete)

### Saga-scoped unit of work

1. **Begin.** `EventReceiver.receive` resolves the step and instance (event-receiver.md). A start step first runs the
   creation write in its own transaction (`CREATED`, entry `CREATED`), replacing "`SagaLifecycle.start(...)` creates
   the instance" inside the unit of work. Then: in-process instance lock, `TransactionBoundary.begin()`,
   `StorageProvider.findByIdAndLock`, re-check (terminal → unexpected, `CREATED` for a non-start step → not
   handled), load the `SagaContextRecord`, create the saga object (`SagaObjectProvider` or no-argument
   constructor), inject the three fields, bind the unit of work as `DomainEventRecorder`.
2. **Run steps.** Unchanged. Each `recordStepCompleted` adds a `STEP_COMPLETED` entry to the unit of work.
3. **Commit.** `complete` adds a `COMPLETED` entry. Then `save(instance, entries)`, the `IN_TRANSACTION` handoff if
   configured, `commit()`, release of the in-process lock, then the `AFTER_COMMIT` handoff (not
   `EventDispatcher.dispatch` directly; the default handoff calls it).
4. **Roll back.** Also on a failing instance write, commit or `IN_TRANSACTION` handoff, and on a rollback-only mark.
   The `FAILED` write runs in its own transaction, locks the instance and records the `FAILED` entry; it always
   updates the committed creation record (never creates it). A write-time `SagaConcurrencyConflictException` is not a
   failure: rollback, no `FAILED` write, result `NOT_HANDLED`.

### Invariants

- "Cross-process enforcement ... is deferred" → enforced by `findByIdAndLock` as the first statement of the
  transaction (storage-provider.md), plus the in-process lock.
- New: a unit of work that waits for another one of the same instance has run no step and written no context.
- New: at most one active instance per saga type and association value within one engine (FR-027); across processes
  only after the other creation write committed (G-ENG-08).

### `SagaContext` writes

Unchanged (write-through after each call, own transaction). New: every context write follows the committed creation
write (G-ENG-01).

## 002 `contracts/command-gateway.md`

- Rule 1: "the harness supports only the single-parameter form" → the engine supplies the `@Command` argument and
  resolves every other parameter through `HandlerParameterResolver`s, checked at startup (host-plugins.md).
- Rule 6 ("checked when the engine starts") → implemented as Property 21 (engine-startup.md).
- Several `AggregateResolver`s: the first that `supports` the type is used for both load and save.
- "Outside a unit of work": the non-saga unit of work runs in one `LocalTransaction`; its events go to the handoff
  (with `sagaType`/`sagaId` `null`) after commit; a failure rolls back and propagates from `send` unchanged.
- `send` on a thread with no engine unit of work (including a thread a handler started) is "outside a unit of
  work".
- Loader and saver host instances come from `HandlerInstanceProvider`.

## 002 `contracts/event-publisher.md`

Delivery rules table: "`EventDispatcher.dispatch` after commit" → "the after-commit handoff (event-handoff.md); by
default `EventDispatcher.dispatch` after commit". `IN_TRANSACTION` timing delivers inside the transaction instead.
`EventPublisher.publish` outside any unit of work is unchanged (immediate `dispatch`).

## 002 `contracts/saga-context-store.md`

New rule: a record is saved only after its instance's creation write committed. Unchanged: never joins the unit of
work's transaction.

## 001 `contracts/saga-lifecycle.md` and 002 `contracts/saga-lifecycle.md`

- "Relationship to other SPI contracts": `EventDispatcher` no longer resolves instances; the engine does, behind
  `EventReceiver`, through `StorageProvider.findByAssociation`.
- The production implementation (`EngineSagaLifecycle`) applies transitions to the unit of work's working copy and
  persists them with the three writes; every transition except `advance` yields one lifecycle entry
  (data-model.md "Lifecycle entry production"). `start` performs the duplicate check against storage and does not
  persist; the engine's creation write does.
- The engine only ever passes an `@EndSaga` step to `complete` (FR-015).

## 001 `contracts/saga-context.md`

"Relationship to other SPI contracts" (values map onto `SagaInstance.state`) was already superseded by 002; restated:
values live in `SagaContextRecord`, written through by `EngineSagaContext`.

## `EventDispatcher` Javadoc (no 001/002 contract file)

Outbound only (research §12). `registerSagaType` is called once per saga type at `start()`, before the first event
is accepted (FR-036).

## 003 `contracts/saga-lifecycle-entry.md`

- **Producer**: the engine, not `StorageProvider.save` diffing. **Consumer**: storage, through
  `save(instance, entries)`.
- `sequenceNumber` removed from the record; the stored position is assigned by storage in hand-over order.
- "Known limitation (research.md §5)" and "Known limitation, first save" no longer apply to engine-driven instances
  (G-003-04, G-003-16 close; G-003-01 cannot arise).
- "No existing interface gains a method that accepts or returns either type" no longer holds: `StorageProvider.save`
  accepts entries.

## 003 `contracts/concurrency-control.md`

- Mechanism step 1: `FOR UPDATE OF i` → `FOR NO KEY UPDATE OF i`, both in `save` and in the new `findByIdAndLock`
  (research §3).
- Under the engine the lock is taken at the start of the unit of work by `findByIdAndLock`; the lock in `save` is
  then re-entrant within the same transaction and the forward-transition check normally passes.
- Step 5: "derive the history entries" → "insert the entries handed over".
- `ConcurrentSagaUpdateException` extends `core.spi.SagaConcurrencyConflictException`.

## 003 `contracts/postgres-schema.md`

DDL unchanged. `saga_lifecycle_history.sequence_number` is assigned by `save` from the hand-over order. New failure
mode: a call in joined mode marks the transaction rollback-only before rethrowing a `SQLException` as
`UncheckedSqlException`.
