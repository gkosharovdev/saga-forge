# Phase 1 Data Model: Saga Engine Runtime

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

Three groups: (A) new and changed `saga-forge-core` types, all JDK-only; (B) the `saga-forge-engine` runtime model;
(C) the Postgres adaptation. Signatures are in [contracts/](./contracts/). Base packages:
`io.github.gkosharovdev.sagaforge.core` and `io.github.gkosharovdev.sagaforge.engine`.

## A. Core types (`core.spi`)

### New

| Type | Kind | Purpose | Contract |
|---|---|---|---|
| `TransactionBoundary` | interface | Begins the host's local transaction; tells whether one is active | [transaction-boundary.md](./contracts/transaction-boundary.md) |
| `LocalTransaction` | interface | Commit, rollback, mark rollback-only, query rollback-only, look up the transaction's resource by type | same |
| `EventReceiver` | interface | Inbound entry point: `HandlingReport receive(Object domainEvent)` | [event-receiver.md](./contracts/event-receiver.md) |
| `HandlingReport` | record | `event`, `results` (`List<SagaHandlingResult>`); `ignored()` iff `results` is empty | same |
| `SagaHandlingResult` | record | One saga type / instance's result for one event | same |
| `HandlingOutcome` | enum | `WAITING`, `COMPLETED`, `FAILED`, `DUPLICATE_START`, `UNMATCHED`, `UNEXPECTED`, `NOT_HANDLED` | same |
| `EventHandlingException` | unchecked exception | No outcome could be recorded for at least one instance; carries the partial report | same |
| `EventHandoff` | interface | Receives a committed unit of work's buffered events; `timing()` `AFTER_COMMIT` (default) or `IN_TRANSACTION` | [event-handoff.md](./contracts/event-handoff.md) |
| `HandedOffEvents` | record | `unitOfWorkId`, `sagaType`, `sagaId` (both `null` for a non-saga unit of work), `events` | same |
| `SagaConcurrencyConflictException` | unchecked exception | A storage write detected a concurrent change; `sagaId()` | [storage-provider.md](./contracts/storage-provider.md) |
| `HandlerParameterResolver` | interface | Supplies non-command `@CommandHandler` parameters | [host-plugins.md](./contracts/host-plugins.md) |
| `SagaObjectProvider` | interface | Supplies a fresh saga object per unit of work | same |

### Changed

| Type | Change |
|---|---|
| `StorageProvider` | + `void save(SagaInstance, List<SagaLifecycleEntry>)` (abstract); `save(SagaInstance)` becomes `default` = `save(instance, List.of())`; + `Optional<SagaInstance> findByIdAndLock(String)` (abstract); Javadoc (joining, three engine writes) |
| `SagaLifecycleEntry` | `sequenceNumber` component removed; storage numbers entries on insert (research §4) **(confirm)** |
| `SagaLifecycleEventKind` | Javadoc only (engine-produced) |
| `EventDispatcher`, `SagaContextStore`, `SagaInstance`, `DomainEventRecorder`, `HandlerInstanceProvider`, `core.lifecycle.SagaLifecycle`, `core.event.EventPublisher`, `core.command.CommandGateway` | Javadoc only (research §12) |

### `SagaHandlingResult`

| Field | Type | Rule |
|---|---|---|
| `sagaType` | `String` | Non-null; fully qualified `@Saga` class name |
| `sagaId` | `String`, nullable | The instance; `null` for `UNMATCHED`, for `DUPLICATE_START` and for a start that raised before its creation write |
| `outcome` | `HandlingOutcome` | Non-null |
| `started` | `boolean` | `true` iff this event's creation write committed a new instance |
| `outcomeStep` | `String`, nullable | Non-null iff `outcome == COMPLETED`; the `@EndSaga` step |
| `failure` | `SagaFailure`, nullable | Non-null iff `outcome == FAILED` |
| `unitOfWorkId` | `String`, nullable | Non-null iff a unit of work ran (`WAITING`, `COMPLETED`, `FAILED`, or `NOT_HANDLED` after a conflict) |
| `handoffFailures` | `List<Throwable>` | Unmodifiable; non-empty only for `WAITING`/`COMPLETED` whose `AFTER_COMMIT` handoff failed |

### `HandlingOutcome`

| Value | Meaning | Unit of work ran | Effect |
|---|---|---|---|
| `WAITING` | Committed at a wait point; status `RUNNING` | yes | committed |
| `COMPLETED` | Committed at an `@EndSaga` step | yes | committed |
| `FAILED` | Rolled back; instance written `FAILED` | yes | rollback + `FAILED` write |
| `DUPLICATE_START` | Start event while an active instance of the saga type has the association value | no | none |
| `UNMATCHED` | Non-start step, no instance of the saga type correlates | no | none |
| `UNEXPECTED` | Non-start step, only terminal instances correlate (or the locked instance turned terminal) | no | none |
| `NOT_HANDLED` | Write-time conflict, or the instance is still `CREATED` (start unit of work pending) | conflict: yes; `CREATED`: no | none; the transport may redeliver |

## B. Engine runtime model (`saga-forge-engine`)

### `SagaEngine` states

```text
build() ──► BUILT ──start(): report has errors──► REFUSED   (terminal; receive() → IllegalStateException)
                 └─start(): no errors, saga types registered with EventDispatcher──► STARTED (accepts events)
```

`receive`, `commandGateway().send` and `eventPublisher().publish` before `STARTED` throw `IllegalStateException`.

### Plug-ins (builder inputs)

| Input | Required | Default |
|---|---|---|
| Registered classes (sagas, aggregates, handler, loader and saver hosts; events and commands may be listed or are reached) | yes | |
| `StorageProvider`, `SagaContextStore`, `TransactionBoundary`, `EventDispatcher` | yes | |
| `HandlerInstanceProvider` | if any service handler, loader or saver exists | |
| `AggregateResolver`s (ordered; first supporting wins) | no | none |
| `HandlerParameterResolver`s (ordered; first supporting wins) | no | none |
| `SagaObjectProvider` | no | accessible no-argument constructor |
| `EventHandoff` | no | `DispatchingEventHandoff` (`AFTER_COMMIT`, `EventDispatcher.dispatch`) |
| `Clock`, saga-id `Supplier<String>`, unit-of-work-id `Supplier<String>` | no | system UTC clock, random UUIDs |
| `System.Logger` | no | `System.getLogger("io.github.gkosharovdev.sagaforge.engine")` |

### `StartupReport`

| Field | Type | Rule |
|---|---|---|
| `validation` | `ValidationReport` | From `validateAll` over the registered classes |
| `engineErrors` | `List<String>` | Loader availability, parameter resolvability, saga instantiability, `registerSagaType` failures ([engine-startup.md](./contracts/engine-startup.md)) |

`isValid()` iff both are clean. `allErrors()` = `validation.allErrors()` then `engineErrors`.

### Unit of work

| Attribute | Saga-scoped | Non-saga |
|---|---|---|
| Opened by | `receive` (per instance) | `CommandGateway.send` outside any unit of work |
| `unitOfWorkId` | fresh from the id supplier | fresh from the id supplier |
| Transaction | one `LocalTransaction` | one `LocalTransaction` |
| Recorder | the unit of work, via `DomainEventScope.runWith` | same |
| In-process queue | events triggering a non-start step of the owning instance (same type, same association value) | none |
| Buffered | every other recorded event | every recorded event |
| Ends | commit at the first `@EndSaga` step or a wait point; rollback on failure | commit when the handler returns; rollback on failure (rethrown to the caller) |
| Writes | instance + entries; `FAILED` write after rollback | none of its own |

### Instance lifecycle across the three writes

```text
start event ─(creation write, own tx)──► CREATED  [CREATED entry]
CREATED/RUNNING ─(unit of work commits at wait point)──► RUNNING   [STEP_COMPLETED × n]
CREATED/RUNNING ─(unit of work commits at @EndSaga)────► COMPLETED [STEP_COMPLETED × n, COMPLETED]
CREATED/RUNNING ─(unit of work rolls back; FAILED write, own tx)──► FAILED [FAILED]
COMPENSATING: never entered
```

- `CREATED` is observable between the creation write and the first unit of work's commit, and permanently after a
  crash in between (G-ENG-05).
- A wait point adds no status entry, even for `CREATED → RUNNING` (G-003-18 unchanged).
- `n ≥ 1` for every committed unit of work: the triggered step always completes before a commit.

### Saga-scoped unit of work, step by step

| # | Action | Lock state | On failure |
|---|---|---|---|
| 0 | (start step only) take the duplicate-start lock; `findByAssociation` → active instance of the saga type? `DUPLICATE_START`; else `SagaLifecycle.start`, take the instance lock, creation write, release the duplicate-start lock | duplicate-start + instance (in-process) | raise; nothing created |
| 1 | (other steps) take the instance lock for each correlated active instance in turn | instance (in-process) | |
| 2 | `begin()`; mark the thread "unit of work running" | | |
| 3 | `findByIdAndLock(sagaId)`; missing → `UNMATCHED`, terminal → `UNEXPECTED`, `CREATED` for a non-start step → `NOT_HANDLED` (roll back, release) | + storage lock | raise (store unavailable) |
| 4 | `contextStore.find(sagaId)`; new saga object; inject `@SagaId`, `SagaContext`, `CommandGateway` | | → 9 |
| 5 | run the triggered step, then queued events in recording order; after each step `recordStepCompleted` (→ `STEP_COMPLETED` entry); stop at the first `@EndSaga` (`complete` → `COMPLETED` entry), else `advance` at the empty queue | | → 9 |
| 6 | rollback-only? → 9. `save(instance, entries)` | | conflict → 10; else → 9 |
| 7 | `IN_TRANSACTION` handoff, if configured and the batch is non-empty | | → 9 |
| 8 | `commit()`; clear the marker; release the instance lock; `AFTER_COMMIT` handoff (failures collected) | released | conflict at commit → 10; else → 9 |
| 9 | **failure**: `rollback()`; discard buffered events; clear the marker; `FAILED` write (begin, `findByIdAndLock`, `fail`, `save(failed, [FAILED])`, commit); release the instance lock → `FAILED` | instance held until the `FAILED` write ends | `FAILED` write fails → raise |
| 10 | **conflict**: `rollback()`; discard; clear the marker; release → `NOT_HANDLED` | released | |

`SagaContext` writes happen in step 5 only, through `SagaContextStore` on its own connection, after every
`putState`/`updateStateAttribute` call (write-through), so they survive steps 9 and 10.

### Locks

| Lock | Key | Taken | Released | Scope |
|---|---|---|---|---|
| Duplicate-start | saga type + association property + value | before the duplicate check | after the creation write commits (or fails) | one engine (FR-027) |
| Instance | `sagaId` | before `begin()` (start: before the creation write) | after commit, or after the `FAILED` write; always before the `AFTER_COMMIT` handoff | one engine |
| Storage | `sagaId` | `findByIdAndLock`, first statement of the transaction | when that transaction commits or rolls back | all processes sharing the store (FR-025) |

Lock order is always duplicate-start → instance → storage; the engine never holds two instance locks at once
(fan-out runs instances one after another), so engine-level deadlocks cannot arise.

### Lifecycle entry production

| Engine call | Entry | Handed over with |
|---|---|---|
| `SagaLifecycle.start` | `CREATED` (`occurredAt = createdAt`) | creation write |
| `recordStepCompleted(sagaId, step, touched)` | `STEP_COMPLETED(step, touched)` | the unit of work's write |
| `complete(sagaId, step)` | `COMPLETED(outcomeStep = step)` | the unit of work's write, after its `STEP_COMPLETED` entries |
| `advance(sagaId)` | none | |
| `fail(sagaId, failure)` | `FAILED(failure)` | `FAILED` write |

`occurredAt` is the clock instant of the transition. Entries of a rolled-back or conflicting unit of work are
dropped with it.

### Buffered events and the handoff batch

| Field | Source |
|---|---|
| `unitOfWorkId` | the unit of work's id |
| `sagaType`, `sagaId` | the owning instance; `null` for a non-saga unit of work |
| `events` | recorded events not run in-process, recording order, including events still queued at the `@EndSaga` step and events triggering a `@StartSaga` step or another instance or saga type |

Every recorded event also adds `AggregateReference.of(@DomainEvent.aggregate, @AggregateIdentifier value)` to the
running step's touched aggregates (002 FR-004), whether it is queued or buffered.

## C. Postgres adaptation (`saga-forge-storage-postgres`)

| Type | Change |
|---|---|
| `PostgresStorageProvider` | + constructor `(DataSource, TransactionBoundary)`; every method joins `current()`'s `resource(Connection.class)` when active, else 003's own transaction; + `save(instance, entries)`; + `findByIdAndLock` (`FOR NO KEY UPDATE OF i`; `IllegalStateException` without an active transaction); lock in `save` → `FOR NO KEY UPDATE`; no derivation |
| `JdbcTransactionBoundary` *(new, public)* | `TransactionBoundary` over a `DataSource` for hosts without Spring/JTA and for tests |
| `LifecycleEntryRows` *(new, package-private)* | Binds one entry to the history insert with its storage-assigned number |
| `LifecycleHistoryDerivation` | → `ForwardTransition` (`requireForwardTransition` only; `derive` removed) |
| `ConcurrentSagaUpdateException` | `extends SagaConcurrencyConflictException` |
| `PostgresSagaContextStore` | unchanged (own connection) |
| Schema (`schema.sql`) | unchanged; `saga_lifecycle_history.sequence_number` now assigned from the hand-over order |
