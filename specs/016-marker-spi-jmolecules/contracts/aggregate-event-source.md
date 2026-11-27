# Contract: `AggregateEventSource` and the harvest point

```java
package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * Takes the domain events an aggregate holds, so the engine records them as if the handler had called
 * DomainEvents.record. Implemented by applications (or integrations) for the way their aggregates keep
 * pending events; never by the domain itself.
 */
public interface AggregateEventSource {

  /** Whether this source takes events from aggregates of {@code aggregateType}. @throws IllegalArgumentException on null */
  boolean supports(Class<?> aggregateType);

  /**
   * The events {@code aggregate} holds, in registration order, and clears them: a later call returns only events
   * registered after this one. Never null; empty when there are none.
   * @throws IllegalArgumentException on null
   */
  List<Object> takeEvents(Object aggregate);
}
```

## Harvest point (runtime, `EngineCommandGateway`)

| Route | When | Then |
|---|---|---|
| Aggregate instance method handler | after the handler method returned | take events, record each, then `AggregateAccess.save` |
| Creation handler (constructor or static factory on an aggregate) | after the new aggregate was returned | take events, record each, then save |
| Service handler (non-aggregate host) | — | not harvested |

- Source choice: the first registered source whose `supports(aggregate.getClass())` is true; none: nothing harvested.
- Recording: each event goes to the recorder `DomainEventScope` binds for the unit of work, after any event the
  handler recorded explicitly (they were recorded while it ran). Validation, in-process continuation, outbox, recorded
  events for Spring, fan-out (Property 47) and rollback discard are the recorder's, unchanged.
- Failure: a source throwing, or returning an event no vocabulary marks as a domain event, fails the unit of work like
  a handler exception, with `IllegalArgumentException("Event source {Source} returned {EventType} for aggregate {Aggregate}, which is not a domain event (accepted: {describe})")` (FR-025).
- Retries: a rolled-back attempt's taken events are gone with the attempt (the aggregate instance is reloaded); the
  next attempt takes whatever its own handler run registers.
- Telemetry: no new span or attribute; harvested events carry the unit of work's trace context like recorded ones.

## Hosts

`SagaRuntime.Builder.aggregateEventSource(AggregateEventSource)` (repeatable, registration order);
`SagaFixture.Builder.aggregateEventSource(...)`; the starter adds every `AggregateEventSource` bean in `@Order` order.
The starter ships none (D9): Spring Data `@DomainEvents` reach sagas through 014's inbound route.

## Tests that bind this contract

`HarvestedEventsTest` (order, explicit-first, creation route, service handler not harvested, rollback, source failure,
unmarked event), `HarvestPropertyTest` (Property 49), fixture `HarvestParityTest`, starter `HarvestWithSpringDataTest`
(FR-024: source + `@DomainEvents` aggregate, `in-transaction` mode and Modulith interop, each listener once).
