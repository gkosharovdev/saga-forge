# Contract: `EventReceiver` and `HandlingReport` (inbound entry point)

**Package**: `io.github.gkosharovdev.sagaforge.core.spi` | **Kind**: interface plus result types; implemented by
`SagaEngine` | **Consumers**: transport modules, and tests standing in for them. Spec: FR-001 to FR-007, FR-026,
FR-036; research §6.

## `HandlingReport receive(Object domainEvent)`

- **Pre-conditions**: the engine is `STARTED`; no unit of work is running on the calling thread; `domainEvent` is
  non-null, its class is `@DomainEvent`-annotated, and every registered step reacting to its exact class can read
  a non-null, non-empty association value from it (record accessor, getter or field, read as `String.valueOf`).
- **Effect**: synchronously, on the calling thread:
  - for each registered saga type, in registration order, with a `@SagaStep` whose parameter type is the event's
    exact class:
    - `@StartSaga` step: start a new instance correlated by that step's `associationProperty` value, subject to
      the duplicate-start rule, and run its first unit of work;
    - other step: `StorageProvider.findByAssociation(associationProperty, value)`, keep instances of this saga
      type, and run one unit of work per active (non-terminal) instance, in the order returned;
  - each unit of work follows [contract-deltas.md](./contract-deltas.md) "Saga-scoped unit of work" and is
    committed or rolled back independently of the others.
- **Result**: `HandlingReport(event, results)`, one `SagaHandlingResult` per affected saga type and instance (see
  data-model.md for the fields and the outcome table). `ignored()` is true iff no registered saga type reacts to
  the event; then `results` is empty and nothing happened. A saga ending `FAILED` is a result.
- **Failure**:
  - `IllegalArgumentException` for a `null` or non-`@DomainEvent` event, or a missing association value, before
    anything runs; the message names the event type and, for a missing value, the saga type, step and property.
  - `IllegalStateException` if the engine is not `STARTED` (never started, or refused), or if a unit of work is
    running on this thread (a step, handler or `IN_TRANSACTION` handoff calling back in); in the latter case the
    running unit of work fails like any other failure.
  - `EventHandlingException` (unchecked; `report()` with the results that were recorded, causes as `getCause()`
    and suppressed exceptions) after every affected instance was attempted, if for at least one of them no outcome
    could be recorded: the store was unavailable, the creation write or the `FAILED` write failed, or a lock wait
    was aborted by the store. The transport should redeliver.

## Reentrancy and threading

- Called from inside an `AFTER_COMMIT` handoff (a synchronous transport looping a dispatched event back): accepted;
  the previous unit of work has committed and released its locks, so this is a new unit of work (FR-007, US5-5).
- Called concurrently from several threads: units of work of the same instance serialize (in-process lock, then the
  storage lock); different instances do not wait for each other (FR-025, FR-028).
- Called while the host has a transaction of its own active on the thread: the engine's `begin()` suspends it or
  throws `IllegalStateException`, depending on the `TransactionBoundary` ([transaction-boundary.md](./transaction-boundary.md),
  G-ENG-12).

## Outcomes per instance

| Situation | `outcome` | `started` | `sagaId` |
|---|---|---|---|
| Start step, no active instance, first unit of work commits at a wait point / `@EndSaga` / rolls back | `WAITING` / `COMPLETED` / `FAILED` | `true` | new id |
| Start step, an active instance of the saga type has the value | `DUPLICATE_START` | `false` | `null` |
| Non-start step, no instance of the saga type correlates | `UNMATCHED` | `false` | `null` |
| Non-start step, only terminal instances correlate (one result for the most recently returned one), or the locked instance is now terminal | `UNEXPECTED` | `false` | that instance |
| Non-start step, active instance, unit of work commits / rolls back | `WAITING` or `COMPLETED` / `FAILED` | `false` | that instance |
| Non-start step, instance still `CREATED` after locking | `NOT_HANDLED` | `false` | that instance |
| Write-time `SagaConcurrencyConflictException` | `NOT_HANDLED` | as above | that instance |

Duplicate starts are detected within one engine even for concurrent start events; across processes only once the
other instance's creation write has committed (FR-027, G-ENG-08), so a concurrent duplicate in another process may be
reported `WAITING`/`COMPLETED`/`FAILED` with `started = true`.
