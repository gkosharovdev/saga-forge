# Contract: `EventHandoff` (after-commit handoff, outbox-ready)

> **Renamed in 007** (`specs/007-in-memory-transport` research §14, 2026-11-11): `EventHandoff` → `Outbox`,
> `handOff` → `store`, `HandedOffEvents` → `OutgoingMessages`, `DispatchingEventHandoff` → `ImmediateDispatchOutbox`,
> `SagaEngine.Builder.handoff` → `outbox`, `SagaHandlingResult.handoffFailures` → `outboxFailures`. This document keeps
> the names it was written with.

**Package**: `io.github.gkosharovdev.sagaforge.core.spi` | **Kind**: interface plus the `HandedOffEvents` record;
default implementation `DispatchingEventHandoff` in `saga-forge-engine` | **Implemented by**: the engine (default),
later a transactional outbox (Kafka feature), tests. Spec: FR-029 to FR-032, SC-005, SC-011; research §5.

## `EventHandoff`

```java
enum Timing { AFTER_COMMIT, IN_TRANSACTION }
default Timing timing()             // read once at startup; default AFTER_COMMIT
void handOff(HandedOffEvents batch)
```

## `HandedOffEvents`

| Field | Type | Rule |
|---|---|---|
| `unitOfWorkId` | `String` | Non-null; unique per unit of work, saga-scoped or not |
| `sagaType` | `String`, nullable | Fully qualified `@Saga` class name; non-null iff saga-scoped |
| `sagaId` | `String`, nullable | Non-null iff saga-scoped |
| `events` | `List<Object>` | Non-empty, unmodifiable, no `null`; recording order |

`IllegalArgumentException` for a `null` `unitOfWorkId`, `events` or element, an empty `events`, or exactly one of
`sagaType`/`sagaId` present.

## Obligations of the engine

- **Which events**: every event recorded in the unit of work (through `DomainEvents.record` or `EventPublisher` on its
  thread) that no step of the owning instance ran in-process: events of other saga types or instances, events
  triggering a `@StartSaga` step, events no step reacts to, and events still queued when an `@EndSaga` step ended the
  unit of work. For a non-saga unit of work, every recorded event. Each event exactly once.
- **Never** for a unit of work that rolled back or lost a write-time conflict. An empty batch is not handed off.
- **Order**: recording order.
- **`AFTER_COMMIT`**: `handOff` is called after `commit()` returned, after the instance lock was released, on the
  unit-of-work thread, with no engine transaction active and the "unit of work running" marker cleared (so it may
  call `EventReceiver.receive`).
- **`IN_TRANSACTION`**: `handOff` is called after the instance write and before `commit()`, inside the unit of work's
  transaction (`TransactionBoundary.current()` is that transaction), so whatever the implementation writes through
  it commits or rolls back with the unit of work. It must not call `EventReceiver.receive` (rejected,
  `IllegalStateException`).

## Failure handling

| Timing | `handOff` throws | Effect |
|---|---|---|
| `AFTER_COMMIT` | any `RuntimeException` | The committed unit of work and instance are untouched; not `FAILED`. The exception is added to `SagaHandlingResult.handoffFailures` (or, for a non-saga unit of work, logged only) and logged (FR-031) |
| `IN_TRANSACTION` | any `RuntimeException` | Failure of the unit of work: rollback, `FAILED` write with the last step run as `SagaFailure.stepName` (FR-011). For a non-saga unit of work: rollback, rethrown from `send` |

## `DispatchingEventHandoff` (default, `AFTER_COMMIT`)

Calls `EventDispatcher.dispatch(event)` for each event in order. A failing `dispatch` does not stop the others; each
failure is collected and rethrown at the end as one exception whose suppressed exceptions are the others. At most
once: events of a committed unit of work are lost if the process stops before or during this call, and nothing
redelivers them (G-ENG-07).

## Building an outbox on it (Kafka feature, not here)

An `IN_TRANSACTION` implementation inserts one row per event keyed by `(unitOfWorkId, index)` through
`current().flatMap(tx -> tx.resource(Connection.class))`; a relay publishes committed rows at least once. No engine
change is needed (FR-030, FR-032).

## `EventPublisher` outside a unit of work

Not a handoff: `publish` outside any unit of work calls `EventDispatcher.dispatch` immediately (002 FR-016) and does
not go through `EventHandoff`.
