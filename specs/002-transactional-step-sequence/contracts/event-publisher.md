# Contract: recording and publishing domain events

**Packages**: `core.event` (`DomainEvents`, `EventPublisher`), `core.spi` (`DomainEventScope`,
`DomainEventRecorder`) | **Kind**: one static facade, one injectable interface, one engine-facing binding SPI.
Spec: FR-014 to FR-017.

## Event type rules (checked by `validateAll`)

- Annotated `@DomainEvent(aggregate = X.class)`, where `X` is annotated `@Aggregate`.
- Exactly one `@AggregateIdentifier` field, holding the id of the `X` instance the event is about.

## `static void DomainEvents.record(Object domainEvent)`

- **Pre-conditions**: `domainEvent` is non-null and `@DomainEvent`-annotated; a unit of work is active on the
  calling thread.
- **Effect**: hands the event to the recorder bound for the current unit of work. Usable from any code on that
  thread (aggregate, domain service, handler) without inheriting from or implementing any Saga Forge type.
  Saga Forge does not check that a state transition happened (FR-015).
- **Failure**: `IllegalArgumentException` for a `null` or non-`@DomainEvent` argument; `IllegalStateException`
  if no unit of work is active (for example a thread the handler started itself).

## `static List<Object> DomainEvents.capture(Runnable action)`

Testing aid. Runs `action` with a temporary recorder and returns the recorded events in order. Lets aggregates
be unit-tested without the framework. Throws `IllegalStateException` if called while a unit of work is active.

## `void EventPublisher.publish(Object domainEvent)`

- **Pre-conditions**: as for `record`, except that no unit of work needs to be active.
- **Effect**: inside a unit of work, identical to `DomainEvents.record`. Outside one, hands the event to
  `EventDispatcher.dispatch` immediately (FR-016); this is how application code starts a saga.
- **Failure**: `IllegalArgumentException` for a `null` or non-`@DomainEvent` argument.

## `DomainEventScope.runWith(DomainEventRecorder recorder, Runnable work)` (engine-facing)

Binds `recorder` to the calling thread for the duration of `work` and restores any previously bound recorder
afterwards, also when `work` throws. Implemented with a `ThreadLocal`.

## Delivery rules (spec FR-017)

| Where the event is recorded | Triggers a step of the saga instance owning the unit of work | Delivery |
|---|---|---|
| Inside a saga-scoped unit of work | Yes (same event type, same `associationProperty` value) | In-process, inside the same unit of work, in recording order |
| Inside a saga-scoped unit of work | No | `EventDispatcher.dispatch` after commit; discarded on rollback |
| Inside a non-saga unit of work (see command-gateway.md) | n/a | `EventDispatcher.dispatch` after commit; discarded on rollback |
| Outside any unit of work (`EventPublisher` only) | n/a | `EventDispatcher.dispatch` immediately |

For every event recorded while a step runs, the engine adds `(aggregate type, @AggregateIdentifier value)` to
that step's touched aggregates (FR-004).

## 004 delta (2026-11-09, `specs/004-saga-engine`)

Source: `specs/004-saga-engine/contracts/contract-deltas.md`. Where this contract says "the future engine", read `saga-forge-engine`.

- Delivery rules table: "`EventDispatcher.dispatch` after commit" now reads "the after-commit handoff
  (`specs/004-saga-engine/contracts/event-handoff.md`); by default `EventDispatcher.dispatch` after commit". With
  `IN_TRANSACTION` timing the events are handed off inside the unit of work's transaction instead.
- `EventPublisher.publish` outside any unit of work is unchanged: an immediate `dispatch`, not looped back into the
  engine.
