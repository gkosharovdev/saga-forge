# Contract: Saga events as Spring application events (FR-018a)

## Core, additive

`OutgoingMessages` gains `List<Object> recordedEvents`: every event recorded in the unit of work, in recording order,
including those a step of the owning instance ran in process. Existing constructors keep working (`recordedEvents`
defaults to `events`). The runtime calls `Outbox.store` when `events`, `commands` or `recordedEvents` is non-empty,
for saga, compensation and non-saga units of work alike. Property 26 is extended accordingly.

## `ApplicationEventPublishingOutbox` (starter)

- Decorates the transport's `Outbox`; same `timing()` (`IN_TRANSACTION`), same `supportsCommands()`.
- `store(batch)`: `delegate.store(batch)`, then `ApplicationEventPublisher.publishEvent(event)` for each of
  `batch.recordedEvents()`, in order, inside the unit of work's transaction.
- `committed(batch)`: delegates.
- Spring semantics then apply: `@TransactionalEventListener` / `@ApplicationModuleListener` run after commit, never on
  rollback; with Spring Modulith, the publication registry stores one publication per such listener in the same
  transaction and completes or resubmits it. A plain `@EventListener` runs synchronously inside the unit of work; its
  failure rolls the unit of work back.

## Modes (`saga-forge.application-events.mode`)

| Mode | Behaviour |
|---|---|
| `in-transaction` (default) | `ApplicationEventPublishingOutbox` active; no after-delivery bridge |
| `after-delivery` | 007's `ApplicationEventBridge`: after the relay delivered an event, outside any transaction |
| `none` | no publication to Spring |

Exactly one mode is active, so a listener sees each event once.

## Not covered (G-008-03)

Events published by application code through Spring's own `ApplicationEventPublisher` do not reach sagas; sagas
receive events through Saga Forge's `EventPublisher` or `DomainEvents.record`.
