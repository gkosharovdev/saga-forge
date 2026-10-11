# Saga Forge

Declarative, DDD-native saga orchestration for Spring applications.

Saga Forge lets you *forge* a saga as a plain Java class annotated with `@Saga`. Each step is a
method that reacts to a domain **event** and, when it decides to act, emits a **command** aimed at
an aggregate. The framework owns everything that makes this durable and safe: correlating events to
the right saga instance, persisting process state in the same transaction as your business effect,
retrying transient failures, recording truthful step outcomes, and giving you a place to put
compensation. You write steps; Saga Forge forges the process around them.

```java
@Saga
public class CreateBookingSaga {

    @SagaId
    private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "bookingId")
    public ClaimAvailabilityCommand onBookingRequested(BookingRequestedEvent event) {
        return new ClaimAvailabilityCommand(event.bookingId(), event.dates());
    }

    @SagaStep(associationProperty = "bookingId")
    public InitiatePaymentCommand onAvailabilityClaimed(AvailabilityClaimedEvent event) {
        return new InitiatePaymentCommand(event.bookingId());
    }

    @EndSaga
    @SagaStep(associationProperty = "bookingId")
    public void onPaymentInitiated(PaymentInitiatedEvent event) {
        // terminal step — saga completes
    }

    @SagaCompensation(forStep = "onAvailabilityClaimed")
    public ReleaseAvailabilityCommand compensateAvailability(AvailabilityClaimedEvent event) {
        return new ReleaseAvailabilityCommand(event.bookingId());
    }
}
```

Nothing in this class imports a datastore driver, a Kafka client, or a transaction manager. Those
are cross-cutting concerns Spring Boot autoconfiguration wires up for you based on what it finds on
the classpath — see [docs/architecture.md](docs/architecture.md).

## Why

Multi-aggregate business transactions (the kind that touch a Booking, an Inventory hold, a Payment,
and a Scheduled follow-up in one logical flow) are usually hand-rolled as one large service method.
That method can't tell you which step failed, can't resume safely after a crash, can't retry only
the step that needs retrying, and silently drifts out of sync with its own side effects whenever a
"successful" step is recorded despite a rolled-back business change. Saga Forge exists to make the
process itself a first-class, inspectable, durable thing — without forcing teams onto a heavyweight
workflow engine or a non-Java DSL.

The design is distilled from running exactly this pattern in production (see
[docs/use-cases.md](docs/use-cases.md) for the worked examples) and generalizing it into a reusable
library instead of re-plumbing it per service.

## Design principles

1. **Declarative, not imperative.** You describe *what* each step does in response to *which*
   event. You never write the orchestration loop, the correlation lookup, or the retry loop.
2. **DDD-native.** Sagas coordinate **aggregates** through **commands** and react to their
   **events**. Steps may consult **projections** (read models) to enrich a command, but a saga never
   reaches into another aggregate's internals. Saga Forge is the home for cross-aggregate **policy**
   — "when X happens, Y should happen" — that doesn't belong inside any single aggregate.
3. **Event-driven transport, orchestrated process.** Steps are triggered by events (Kafka or Spring
   application events) — this is choreography-shaped at the transport level. But the saga keeps a
   durable, single process-level state machine per instance, so you always have one place to ask
   "where is this process, and why." That's orchestration layered on an event-driven substrate, not
   a contradiction.
4. **Truthful state, always.** A step is never recorded as completed unless its business effect
   actually committed. State persistence happens in the same transaction as the step's side effect.
5. **Zero-ceremony infrastructure.** You pick a starter dependency (Postgres, MySQL, MongoDB) and a
   transport dependency (Kafka or in-memory), and autoconfiguration does the rest. There is no
   `SagaEngineConfig` to hand-assemble.
6. **Boring is a feature.** No DSL, no XML, no custom build plugin. Plain classes, plain
   annotations, plain Spring beans for dependencies you inject into a saga (repositories, read
   models) via regular DI.

## Status

This is an early-stage framework. The annotation model, SPI contracts, and module layout exist and
are tested (`saga-forge-core`); the runtime engine, storage providers, transports, and
autoconfiguration are not implemented yet. This iteration is documentation-only — see
[docs/architecture.md](docs/architecture.md) for the target design and
[docs/use-cases.md](docs/use-cases.md) for the examples driving prioritization. Treat
`docs/Saga_Framework_Epic.md`, `docs/Saga_User_Stories.md`, and `docs/Saga_Technical_Tasks.md` as
historical planning artifacts, superseded by the docs above where they conflict.

## Modules

| Module | Role | Depends on |
|---|---|---|
| `saga-forge-core` | Annotations, SPI (`StorageProvider`, `EventDispatcher`), `SagaContext`/`SagaLifecycle`, scanner | nothing |
| `saga-forge-spring-boot-starter` | Autoconfiguration: detects datastore + transport, wires the engine | `saga-forge-core` |
| `saga-forge-storage-postgres` / `-mysql` / `-mongodb` | Durable `StorageProvider` implementations | `saga-forge-core` |
| `saga-forge-transport-inmemory` | `EventDispatcher` over Spring `ApplicationEventPublisher` | `saga-forge-core` |
| `saga-forge-transport-kafka` | `EventDispatcher` over Kafka topics | `saga-forge-core` |
| `saga-forge-example` | Runnable example saga used as a living spec | `saga-forge-core` |

## Getting started (target shape)

```xml
<dependency>
    <groupId>io.github.gkosharovdev</groupId>
    <artifactId>saga-forge-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>io.github.gkosharovdev</groupId>
    <artifactId>saga-forge-storage-postgres</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Add `saga-forge-transport-kafka` if you're on Kafka; omit it and the starter falls back to
in-memory Spring application events. See [docs/architecture.md](docs/architecture.md) for the full
detection matrix.

## Vocabulary

Saga Forge borrows its vocabulary from EventStorming / DDD rather than inventing new terms. See
[docs/architecture.md § Vocabulary](docs/architecture.md#vocabulary) for the full glossary (Command,
Event, Aggregate, Policy, Projection, Saga, Step, Compensation).
