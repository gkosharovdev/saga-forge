# Contract: Capture, Propagation and Restoration

**Modules**: `saga-forge-core` (`EventDelivery`, `EventReceiver`), `saga-forge-runtime`, `saga-forge-transport-inmemory`
| **Spec**: FR-006 to FR-012, FR-014, FR-018, D2 to D6

## `EventDelivery` and `EventReceiver.receive(EventDelivery)`

```java
public record EventDelivery(Object event, String sourceSagaId, SagaMetadata metadata) { }

public interface EventReceiver {
  HandlingReport receive(Object domainEvent);                             // unchanged
  default HandlingReport receive(Object domainEvent, String sourceSagaId)  // unchanged (014)
  default HandlingReport receive(EventDelivery delivery) {                 // new
    return delivery.sourceSagaId() == null
        ? receive(delivery.event())
        : receive(delivery.event(), delivery.sourceSagaId());
  }
}
```

- The runtime overrides all three; `receive(event)` and `receive(event, source)` become
  `receive(new EventDelivery(event, source, sanitize(accessor.capture())))`.
- A transport that delivers a stored message calls `receive(EventDelivery)` with the message's metadata.
- An `EventReceiver` decorator forwards `receive(EventDelivery)` (G-019-04).

## Where each piece of metadata comes from

| Produced by | Message metadata |
|---|---|
| A unit of work of instance `I` (events, outbox commands, deadlines, scheduled commands, outcome events) | `I.metadata` |
| A command-failure reaction unit of work (012) for instance `I` | `I.metadata` |
| A standalone unit of work handling outbox command `C` (its handler's events and commands) | `C.metadata` |
| `EventPublisher` / `CommandGateway.send` outside any unit of work | `sanitize(accessor.capture())` on the producing thread |
| The starter's inbound route (014) | Same, on the publishing thread |

| Instance field | Value |
|---|---|
| `SagaInstance.metadata` at the creation write | The start delivery's metadata |
| Later | Unchanged, including adoption (015) and duplicate starts |
| Fan-out start (014) | The source instance's metadata, carried on the message |

| History entry | `metadata` |
|---|---|
| Written by a unit of work started by a delivery (step, attempt, failure, start-compensation, compensation run caused by it) | That delivery's metadata |
| Written by a deadline step | The deadline message's metadata (= the instance's) |
| Written by an operator repair, a recovery sweep, a command-failure reaction | The instance's metadata |

## Restoration

| Scope | Opened by | Map restored |
|---|---|---|
| Instance unit of work (`forInstance`, `forNewInstance`), every attempt included | `UnitOfWork` | The instance's metadata |
| Standalone unit of work | `UnitOfWork` | The metadata of the work (outbox command's, or capture) |
| Relay delivery of an event, outcome event or command | `Relay` | The stored message's metadata |
| Ignored, duplicate or late deadline deliveries | Relay scope only | Nothing else runs |

Rules:
1. A scope is closed in `finally` on the thread that opened it, also when the work fails or conflicts.
2. Saga objects' `SagaMetadata` field and handlers' `SagaMetadata` parameter read the unit of work's map, not the
   accessor (so they work with `MetadataAccessor.NONE`).
3. Outcome events are unchanged values; their message carries the instance's metadata; a listener runs inside the
   unit of work's scope (recorded events) or the relay's scope (delivered messages) (D6).
4. Metadata is never a span or metric attribute.

## Tests

- Runtime: `MetadataPropagationTest` over the runtime test doubles with a `ThreadLocalMetadataAccessor`: start,
  later event with other metadata, outbox command, retry, compensation, deadline, operator resume, recovery sweep,
  fan-out; scopes unwind (User Story 1 scenarios 1 to 5, User Story 3).
- Transport: `TransportAcceptanceSuite` cases for metadata on events, commands, scheduled messages and published
  events, restored around delivery and after restart.
- Property 50 test in core's scanner tests and runtime tests.
