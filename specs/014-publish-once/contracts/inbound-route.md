# Contract: Inbound Route and No Duplicates (starter)

All types in `io.github.gkosharovdev.sagaforge.spring.events`; only `InboundSagaEventListener` and
`ApplicationEventPublishingEventPublisher` are public (beans an application may replace).

## `InboundSagaEventListener implements ApplicationListener<PayloadApplicationEvent<?>>`

Collaborators: `Set<Class<?>> sagaEventTypes` (from `SagaRuntime.eventTypes()`), `EventDispatcher`.

On each `PayloadApplicationEvent` with payload `e`:

- IR1: `PublishingGuard.active()` → ignore.
- IR2: `e.getClass()` not in `sagaEventTypes` → ignore (subclasses do not match, like the router).
- IR3: a unit of work is active on the thread (`DomainEventScope.current()` present) → `AlreadyPublishedEvents.add(e)`,
  then `DomainEvents.record(e)`.
- IR4: otherwise → `dispatcher.dispatch(e)`: stored in the active transaction (joined through `PROPAGATION_REQUIRED`), or
  in its own when none is active. A failure propagates to the publisher's caller.
- IR5: works before the runtime is started and after it is stopped (the dispatcher stores; the relay delivers later).

Registered when `saga-forge.application-events.inbound.enabled` is `true` (default), a `SagaRuntime` and an
`EventDispatcher` bean exist; `@ConditionalOnMissingBean`.

## `ApplicationEventPublishingOutbox` (changed)

- PO1: publishes each `recordedEvents` entry through Spring under `PublishingGuard`, in recording order, skipping (by
  identity) and removing entries found in `AlreadyPublishedEvents`.
- PO2: everything else unchanged.

## `ApplicationEventPublishingEventPublisher implements EventPublisher`

Decorates the starter's `sagaForgeEventPublisher` bean when mode is `in-transaction` (not an application-defined
`EventPublisher`).

- EP1: a unit of work is active → `delegate.publish(e)` only (the outbox publishes it, PO1).
- EP2: otherwise → `delegate.publish(e)`, then `PublishingGuard.run(() -> publisher.publishEvent(e))`.
- EP3: the delegate's argument checks and "not started" failure come first; nothing is published to Spring then.

## `PublishingGuard` (package-private)

- `run(Runnable)`: sets the thread's flag, runs, restores the previous value in `finally`.
- `active()`: the flag.

## `AlreadyPublishedEvents` (package-private)

- `add(Object)`: identity add; on the first add in a transaction registers a `TransactionSynchronization` whose
  `afterCompletion` clears the thread's set. Outside a synchronized transaction it is cleared by PO1 only.
- `remove(Object)`: identity remove; `true` if present.

## Delivery matrix (FR-013)

| Route | Saga Forge log | Spring listeners |
|---|---|---|
| `DomainEvents.record` in a unit of work | once (unit of work) | once (PO1) |
| Spring `publishEvent` in a unit of work | once (unit of work, IR3) | once (Spring; PO1 skips) |
| Spring `publishEvent` outside | once (IR4) | once (Spring) |
| `EventPublisher.publish` outside | once (delegate) | once (EP2; IR1 ignores) |
| `EventPublisher.publish` in a unit of work | once (unit of work) | once (PO1) |
| outcome event | once (unit of work) | once (PO1; not a saga event type) |

## Tests

Unit: `InboundSagaEventListenerTest` (IR1 to IR5 over a recording dispatcher and `DomainEvents.capture`),
`PublishingGuardTest` (nesting), `AlreadyPublishedEventsTest` (identity, clearing on rollback),
`ApplicationEventPublishingEventPublisherTest`. Container: `PublishOnceContainerTest` (each matrix row, commit and
rollback, counts per saga step and per `@ApplicationModuleListener`, a restart between publication and delivery),
`InboundRouteInUnitOfWorkTest` (Spring Data `@DomainEvents` saved by a command handler continues its saga in process).
