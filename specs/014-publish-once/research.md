# Research: Publish Once

**Feature**: `specs/014-publish-once/spec.md` | **Date**: 2026-11-26

Each entry: decision, rationale, alternatives. Code references are to the state of `master` after 015.

## What the code does today

- `EngineEventPublisher.publish` (runtime): inside a unit of work it is `DomainEvents.record`; outside it calls
  `EventDispatcher.dispatch`, which for the in-memory transport is `Relay.publish`: append to the `MessageStore` in the
  boundary's current transaction, or in a new one. `SpringTransactionBoundary.begin()` uses `PROPAGATION_REQUIRED`, so a
  host `@Transactional` method's transaction is joined: the message commits with the host's work. It does not publish to
  Spring. `SagaRuntime.eventPublisher().publish` throws until the runtime is started.
- `ApplicationEventPublishingOutbox.store` (starter, mode `in-transaction`) publishes every `OutgoingMessages.recordedEvents`
  entry through Spring inside the unit of work's transaction, on the unit of work's thread.
- `ApplicationEventBridge` (mode `after-delivery`) re-publishes delivered messages after consumption, outside a
  transaction.
- `UnitOfWork.buffered()` hands on only the recorded events no step of the owning instance ran (`Recording.ranHere`):
  the cause of G-ENG-24.
- `StoredMessage`/`NewMessage` already carry `sourceSagaType`/`sourceSagaId` for every event recorded in a unit of work
  (`LogAppender.entries`); `Relay` delivers with `EventReceiver.receive(payload)` and drops them.
- `EventRouter.receive` routes an event to every saga type with a step for its exact class; for a non-start step it
  correlates by association. An instance whose step for that type is `COMPLETED` gets `DUPLICATE` (005).
- `ModulithInteropConfiguration` (condition: property `true` or missing) defines the `MessageStore`; with a deadline in
  the model the runtime's startup check then refuses (017), explained by `DeadlinesRequireOwnLogFailureAnalyzer`.

## R1 — Inbound route: a synchronous Spring listener in the starter

**Decision**: `InboundSagaEventListener`, an `ApplicationListener<PayloadApplicationEvent<?>>` registered by the
starter. For a payload whose exact class is in `SagaRuntime.eventTypes()` (R4), and that is not a Saga Forge
publication (R3):

- a unit of work is active on the thread (`DomainEventScope.current()` present) → `DomainEvents.record(payload)` and
  note the payload as already delivered by Spring (R2);
- otherwise → `EventDispatcher.dispatch(payload)`, which joins the active transaction or commits on its own (D4).

**Rationale**: a synchronous listener runs at publication time, on the publishing thread, inside the publishing
transaction, so it decides with the right transaction and the right unit of work in scope. It uses the dispatcher, not
`EventPublisher`, so it works before the runtime is started (spec edge case) and adds no second path through the
runtime. Spring Data's `@DomainEvents` are published through the same `ApplicationEventPublisher`, so FR-006 needs
nothing extra.

**Alternatives**: `@TransactionalEventListener(phase = BEFORE_COMMIT)`: runs at commit, after the unit of work's scope
is gone, and needs `fallbackExecution` without a transaction; rejected. Intercepting `ApplicationEventPublisher` (a
decorating bean or `ApplicationEventMulticaster`): replaces a Spring infrastructure bean, which other libraries (Spring
Modulith) also customise; rejected. Routing through `EventPublisher`: fails before start; rejected.

**Name rating**: `ApplicationListener` is Spring's own interface (very well known); "inbound" is Spring Integration's
term for an adapter that brings messages into a system (well known). `InboundSagaEventListener` reads as "the listener
that brings saga events in".

## R2 — Inside a unit of work: record, and do not publish to Spring again

**Decision**: the starter keeps a thread-bound identity set, `AlreadyPublishedEvents`. The inbound listener adds the
payload before `DomainEvents.record`; `ApplicationEventPublishingOutbox.store` skips recorded events found in it (by
identity) and removes them. The set is cleared when the unit of work's Spring transaction completes
(`TransactionSynchronization.afterCompletion`), so a rolled-back unit of work leaves nothing behind.

**Rationale**: keeps core unchanged: `OutgoingMessages.recordedEvents` stays "every recorded event", and only the
Spring outbox decorator, which is the one that publishes to Spring, knows about Spring's delivery. Identity, not
equality, so two equal events published separately are both handled.

**Alternatives**: a core flag on the recorder (`DomainEvents.recordPublished`): leaks a Spring concern into core
(Principle I spirit) and widens a public API for one adapter; rejected. Not recording it (store it on its own): breaks
in-process continuation and the unit of work's atomicity (D3); rejected.

## R3 — No loop between Saga Forge's publications and the inbound route

**Decision**: a thread-bound flag, `PublishingGuard`, set while Saga Forge publishes to Spring
(`ApplicationEventPublishingOutbox.store`, the `EventPublisher` decorator of R5). The inbound listener ignores events
published under it. Outcome events are not `@DomainEvent`s with steps, so R4 already excludes them; the guard covers
them anyway.

**Rationale**: Spring's synchronous listeners run inside `publishEvent`, on the same thread, so a thread-bound flag
around the call is exact and cheap. Listeners that run later (`@TransactionalEventListener`, Modulith) never see
the flag, but they are not the inbound listener.

**Alternatives**: wrap Saga Forge publications in a marker type: changes what application listeners receive;
rejected. Compare against stored message ids: needs a store read per publication; rejected.

**Name rating**: "guard" is common vocabulary for a re-entry check (Guava, Spring's `ReentrancyGuard`-style idioms; the
runtime already has `ReentrancyGuard`).

## R4 — The set of saga event types comes from the runtime

**Decision**: `SagaRuntime.eventTypes()` → unmodifiable `Set<Class<?>>`, the event classes some registered saga has a
`@SagaStep` for, computed when the runtime is built (available before `start()`). The router and the fan-out rule (R6)
use the same models.

**Rationale**: one source of truth for "which events reach a saga", matching the router's exact-class rule; 016 widens
it (markers) in one place.

**Alternatives**: compute it in the starter by reflecting over `SagaModelCollector.collect()`: duplicates the router's
rule; rejected. `SagaRuntime.handles(Class<?>)`: equivalent, but a set is easier to log and test; either is fine.

## R5 — `EventPublisher` publishes to Spring: a decorator bean in the starter

**Decision**: in mode `in-transaction`, the starter wraps its own `sagaForgeEventPublisher` bean in
`ApplicationEventPublishingEventPublisher`: outside a unit of work it calls the runtime's publisher (stored for sagas,
in the caller's transaction) and then, under `PublishingGuard`, `ApplicationEventPublisher.publishEvent(event)`; inside
a unit of work it only delegates (the unit of work publishes recorded events, R2). An application-defined
`EventPublisher` bean is not wrapped.

**Rationale**: composition over inheritance (decorator, like `ApplicationEventPublishingOutbox`); the runtime stays
Spring-free. Store first, then publish, so a synchronous Spring listener's failure rolls back the caller's transaction
and with it the stored message.

**Alternatives**: publish to Spring only and let the inbound route store it: couples D5 to D11's property (with the
route off, sagas would lose the event); rejected.

**Name rating**: mirrors the existing `ApplicationEventPublishingOutbox` (project-known); long but self-explaining.

## R6 — Fan-out of recorded events consumed in process (G-ENG-24)

**Decision**:

1. `UnitOfWork.buffered()` hands on a recorded event that a step of the owning instance ran when another registered
   saga type has a step for its class. The runtime builds the lookup once from the saga models:
   `EventSubscriptions.otherSagaTypesHandle(eventClass, owningSagaType)`.
2. Delivery tells the runtime which instance recorded the event: core's `EventReceiver` gains a default method
   `receive(Object domainEvent, String sourceSagaId)` that calls `receive(domainEvent)`; `SagaRuntime` overrides it. The
   in-memory relay and the fixture call it when the stored message has a `sourceSagaId`.
3. `EventRouter`: for a non-start reaction whose correlated instance **is** the source instance and whose step for the
   event's class is already `COMPLETED`, it produces no result and runs no unit of work (no `DUPLICATE`, no log). A
   `COMPLETED` step never reverts, so the check is a plain read; the instance lock is not needed for it.

**Rationale**: the source instance id is already stored on every message, so no schema or `NewMessage` change is
needed. Skipping only when the source's step is `COMPLETED` keeps every other case exactly as today: a queued event
for a `COMPENSATING` or terminal source instance still resumes compensation or reports `UNEXPECTED`.

**Alternatives**: a new `MessageKind` or a column marking fan-out messages: needs a schema upgrade and a store contract
change; rejected. Skipping the whole owning saga type: wrong for an event that correlates to another instance of the
same type; rejected. Accept `DUPLICATE` for the source instance: harmless but noisy in logs and telemetry on every
fan-out, and the spec (FR-016) forbids it; rejected. Dropping the in-process run when others listen: changes the owning
saga's unit-of-work boundary; rejected.

**Name rating**: "subscription" is standard publish/subscribe vocabulary (JMS, Kafka consumer groups, Axon's event
processors); `EventSubscriptions` reads as "who is subscribed to which event type". `sourceSagaId` reuses the
existing `NewMessage` field name.

**Limitation**: `ImmediateDispatchOutbox` (the hand-assembled runtime's at-most-once default) dispatches through
`EventDispatcher.dispatch(event)`, which carries no source; with it, the source instance gets `DUPLICATE` on a
fanned-out event. Recorded as G-014-04 rather than widening `EventDispatcher`.

## R7 — `after-delivery` mode with the inbound route

**Decision**: `ApplicationEventsMode.AFTER_DELIVERY` is deprecated (Javadoc, README, a startup warning
`saga.applicationEvents.afterDeliveryDeprecated`). With `saga-forge.application-events.inbound.enabled=true` (the
default) and mode `after-delivery`, `ApplicationEventsConfiguration` throws `SagaForgeConfigurationException` naming both
properties; the existing `SagaForgeConfigurationFailureAnalyzer` explains it.

**Rationale**: the bridge re-publishes every delivered message, including events that came in through Spring, so
listeners would see them twice. Refusing beats a silent duplicate. Telling the bridge which messages came from Spring
would need a stored marker (schema change) for a deprecated mode.

## R8 — Deadlines choose the own log (D9)

**Decision**: `ModulithInteropConfiguration.sagaForgeMessageStore` returns `MessageStore`. When
`saga-forge.transport.modulith-interop` is not set in the `Environment` and any collected saga class
(`SagaModelCollector.collect()`) declares `@StartDeadline`, it returns a `PostgresMessageStore` (the own log) and logs
`saga.transport.modulithInterop.skipped reason=deadlines`. `DeadlinesRequireOwnLogFailureAnalyzer.interopActive()`
treats "not set" the same way, so it explains only an explicit `true`.

**Rationale**: the decision needs the model, which is known when beans are created but not when conditions are
evaluated. Keeping it inside the one bean method avoids a condition that scans packages early. Since
`ModulithInteropConfiguration` runs before `PostgresStorageConfiguration`, whose `MessageStore` is
`@ConditionalOnMissingBean`, returning the own log from there gives the same store the non-interop setup builds.

**Alternatives**: a custom `Condition` scanning packages: duplicates `SagaModelCollector` at condition time; rejected.
Flip the interop default to `false`: changes behaviour for applications without deadlines; rejected (not asked).

## R9 — Delivery latency for events published in a host transaction

**Finding**: `Relay.publish` signals the coordinator right after appending; inside a joined host transaction the
message is visible only after the host commits, so it is picked up by the next poll (G-017-04 latency, up to
`poll-interval`). Same as `EventPublisher` today.

**Decision**: unchanged in 014; recorded as G-014-05 (a commit hook on `LocalTransaction` would remove it, a core SPI
change of its own).

## R10 — Interop on

**Decision**: nothing special. The inbound route dispatches through the transport, which stores into whichever
`MessageStore` is configured (the registry under interop). D1 holds: no other change to interop.

## R11 — Testing approach

- Runtime (in-memory doubles): `RecordedEventFanOutTest` (Property 47: commit, rollback, no other type, source skip,
  `COMPENSATING` source still resumes), `EventRouterSourceTest`, `SagaRuntime.eventTypes()` test.
- Transport: `TransportAcceptanceSuite` gains a fan-out scenario (runs on the in-memory store, Postgres and Modulith
  interop); `Relay` passes `sourceSagaId`.
- Fixture: `FixtureParityTest` gains the fan-out case; a fixture test with two saga types on one event.
- Starter (Postgres container, `ApplicationContextRunner`/`SpringApplicationBuilder`, G-008-04):
  `PublishOnceContainerTest` (three routes × commit/rollback, counting saga step runs and `@ApplicationModuleListener`
  calls, a restart between publication and delivery), `InboundRouteInUnitOfWorkTest` (Spring Data `@DomainEvents`
  saved by a handler: in-process continuation, one listener call), `InboundRouteDisabledTest`,
  `AfterDeliveryConflictTest`, `DeadlineInteropDefaultTest`, `ReadmePropertiesTest`.
- Spring example: `OrderPlacement` publishes through `ApplicationEventPublisher`; its container test still passes (SC-003).

## R12 — Design-skill review (manual)

The `effective-java`, `clean-code-java` and `solid-design-review` skills are not installed in this session; their
principles were applied by hand:

- Effective Java: the new `EventReceiver` method is a default method (Item 21, design interfaces for posterity) with a
  non-null contract instead of a nullable parameter on the old one; `eventTypes()` returns an unmodifiable set (Item
  50, defensive copies); thread-locals are cleared in `finally`/after completion (Item 7, eliminate obsolete
  references); decorators by composition (Item 18).
- Clean Code: one responsibility per new class (guard, identity set, listener, decorator, subscriptions lookup);
  intention-revealing names (rated above); no boolean flag arguments.
- SOLID: open/closed: the outbox and publisher are extended by decoration, not modification of the runtime; dependency
  inversion: the starter depends on core SPIs (`EventDispatcher`, `DomainEventScope`), never on runtime internals;
  interface segregation: no new SPI interface, one default method on an existing one.
