# Contract: Outcome Events

**Feature**: `011-saga-outcome-events` | Types: [data-model.md](../data-model.md) | Decisions: [research.md](../research.md)

## Production

| # | Rule | Spec |
|---|---|---|
| O1 | Every terminal transition the runtime writes produces exactly one outcome event: `COMPLETED` → `SagaCompleted`, `FAILED` → `SagaFailed(STEP)`, `COMPENSATION_FAILED` → `SagaFailed(COMPENSATION)`, `COMPENSATED` → `SagaCompensated`. | FR-001, FR-006, FR-008 |
| O2 | The event is part of the unit of work that stores the terminal status: in the same `OutgoingMessages` batch it hands to the outbox ([unit-of-work.md](unit-of-work.md) step 8). With `IN_TRANSACTION` timing it is stored before that commit; with `AFTER_COMMIT` after it. | FR-008, FR-013 |
| O3 | A write that rolls back hands nothing on. A `FAILED` write that finds the instance missing or terminal produces nothing. | FR-008, FR-010 |
| O4 | The lifecycle-only works (`RecordFailure`, `StartCompensation`, `RecordCompensationFailure`) run in their own unit of work like any other, so their batch is `events = recordedEvents = [outcome]`, with that unit of work's own id. | FR-009 |
| O5 | In a unit of work that ends the instance, the outcome event is the batch's last event, after every event its steps recorded or left queued. | FR-012 |
| O6 | `eventId` is `sagaId:status`, identical on every delivery. | FR-011 |
| O7 | No outcome event carries a failure message, a context value or a saga field. | FR-007 |
| O8 | `SagaRuntime.Builder.outcomeEvents(false)` produces none; the default is `true`. | FR-016 |
| O9 | An `IN_TRANSACTION` outbox that throws while storing an outcome event fails the write it belongs to (rollback; for `RecordFailure`, `saga.instance.failedNotRecorded` and the exception propagates). An `AFTER_COMMIT` outbox that throws leaves the terminal status written and is listed in `SagaHandlingResult.outboxFailures()`. | Edge Cases |

## Consumption

| # | Rule | Spec |
|---|---|---|
| C1 | `EventReceiver.receive(SagaOutcome)` returns a `HandlingReport` with no results: no saga reacts, no exception. | FR-017 |
| C2 | A `@SagaStep` whose parameter is an outcome type fails validation with the existing "parameter is not a `@DomainEvent`" error. | FR-017 |
| C3 | The in-memory transport orders outcome events by `"saga:" + sagaId`. Its listeners (`InMemoryTransport.Builder.listener`, the starter's `after-delivery` bridge) receive them like other events. | FR-014 |
| C4 | The starter publishes outcome events as Spring application events according to `saga-forge.application-events.mode`, exactly as recorded events: `in-transaction` publishes inside the terminal write's transaction (transactional and Modulith listeners run after commit), `after-delivery` after transport delivery, `none` not at all. | FR-015 |
| C5 | `SagaFixture` collects outcome events into `EventResult.outcomeEvents()`; see [builders-and-properties.md](builders-and-properties.md). | FR-018 |

## Guarantee by outbox

| Outbox | Stored | Delivered |
|---|---|---|
| In-memory transport (own log) | in the terminal write's transaction | at least once |
| In-memory transport, Spring Modulith interop | in Modulith's registry, same transaction | at least once |
| `ImmediateDispatchOutbox` (default) | not stored; dispatched after commit | at most once |
| Saga test fixture | staged in the transaction, released on commit | exactly once, synchronous |

## Javadoc changes

- `Outbox` "Which events": add outcome events, the last event of the batch of the unit of work that ends an instance.
- `EventReceiver.receive`: outcome events are accepted and reach no saga.
- `OutgoingMessages`: `events` may contain an outcome event as its last element.
