# Contract: Event Delivery With Its Source, and Fan-out (core SPI, runtime)

## `EventReceiver` (core, `core.spi`)

```java
public interface EventReceiver {

  HandlingReport receive(Object domainEvent);

  /**
   * Delivers an event recorded by the unit of work of saga instance {@code sourceSagaId}.
   * Transports call this when the stored message has a source; the default ignores it.
   */
  default HandlingReport receive(Object domainEvent, String sourceSagaId) {
    return receive(domainEvent);
  }
}
```

- ER1: `sourceSagaId` must be non-blank; `IllegalArgumentException` otherwise. Callers with no source call
  `receive(domainEvent)`.
- ER2: every other rule of `receive(domainEvent)` applies unchanged (validation, outcome events, deadlines, reentrancy).

## `SagaRuntime` (runtime, public)

- `receive(Object domainEvent, String sourceSagaId)`: overrides the default (rules SR1 to SR3 below).
- `Set<Class<?>> eventTypes()`: the exact classes some registered saga has a step for; unmodifiable; available after
  `build()`, before `start()`.

## Fan-out at commit (`UnitOfWork`, Property 47)

- FO1: A recorded event that a step of the owning instance ran in process is added to `OutgoingMessages.events` when
  another registered saga type has a step for its exact class. It keeps its position in recording order.
- FO2: A recorded event the owning instance did not run is handed on as today, whatever other types exist.
- FO3: A rolled-back unit of work hands on nothing (unchanged).
- FO4: Applies to every outbox; with `ImmediateDispatchOutbox` the event is dispatched without a source (G-014-04).

## Routing with a source (`EventRouter`)

- SR1: For each reacting saga type, start reactions are unchanged.
- SR2: For a non-start reaction, a correlated instance whose id equals `sourceSagaId` and whose `StepRecord` for the
  reacting step is `COMPLETED` gets no unit of work and no `HandlingResult`; nothing is logged for it.
- SR3: Any other correlated instance, including the source instance when its step is not `COMPLETED` (for example
  `COMPENSATING` or terminal), is handled exactly as by `receive(domainEvent)`.

## Property 47 (`.kiro/specs/saga-forge-framework/design.md`)

*For any* committed unit of work of instance X of saga type A that records an event E, and *for every* registered saga
type B ≠ A with a step for E's class: B reacts to E once per delivery of the stored message (at least once), X's step
for E runs at most once, and the delivery yields no result for X when X's step for E is `COMPLETED`. *For any* rolled
back unit of work, no saga type reacts to E through it.

## Callers

| Caller | Change |
|---|---|
| `Relay` (in-memory transport) | `receive(payload, sourceSagaId)` when `StoredMessage.sourceSagaId()` is non-null, else `receive(payload)` |
| `LoopBackTransport` (fixture) | keeps the batch's `sagaId` with each queued event; same rule |
| `ImmediateDispatchOutbox` | unchanged (dispatches; no source) |
| test doubles (`TestTransport`) | same rule as `Relay` |

## Tests

`RecordedEventFanOutTest` (FO1 to FO3, Property 47 with generated saga pairs), `EventRouterSourceTest` (SR1 to SR3,
including a `COMPENSATING` source resumed by a queued event), `SagaRuntimeEventTypesTest`, a fan-out scenario in
`TransportAcceptanceSuite`, the fan-out case in `FixtureParityTest`.
