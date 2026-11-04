# Saga Forge

Declarative, DDD-native saga orchestration for Spring applications.

Saga Forge lets you *forge* a saga as a plain Java class annotated with `@Saga`. Each step is a
method that reacts to a domain **event** and dispatches **commands** through a `CommandGateway` to
`@CommandHandler` methods on aggregates or services. The framework correlates events to the right
saga instance, runs the steps triggered within one unit of work together (all of them commit or none
do), persists each step's status and the saga's `SagaContext` values, and waits between units of
work for events from outside. Compensation is declared per step with `@SagaCompensation`; its
semantics are defined in a future spec. You write steps; Saga Forge forges the process around them.

```java
@Saga
public class CreateBookingSaga {

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "bookingId")
    public void onBookingRequested(BookingRequestedEvent event) {
        context.putState("inventoryId", event.inventoryId());
        context.putState("amount", event.amount());
        commandGateway.send(new ClaimAvailability(
                event.inventoryId(), event.bookingId(), event.checkIn(), event.checkOut()));
    }

    @SagaStep(associationProperty = "bookingId")
    public void onAvailabilityClaimed(AvailabilityClaimedEvent event) {
        context.putState("claimId", event.claimId());
        long amount = context.getState("amount", Long.class).orElseThrow();
        commandGateway.send(new InitiatePayment(event.bookingId(), amount));
    }

    // Delivered by the payment provider through the transport: starts the second unit of work.
    @SagaStep(associationProperty = "bookingId")
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        context.putState("paymentId", event.paymentId());
        String claimId = context.getState("claimId", String.class).orElseThrow();
        commandGateway.send(new ConfirmBooking(event.bookingId(), claimId, event.paymentId()));
    }

    // Outcome "confirmed".
    @EndSaga
    @SagaStep(associationProperty = "bookingId")
    public void onBookingConfirmed(BookingConfirmedEvent event) {
        // nothing left to dispatch
    }

    // Outcome "declined": release the claimed availability.
    @EndSaga
    @SagaStep(associationProperty = "bookingId")
    public void onPaymentDeclined(PaymentDeclinedEvent event) {
        String inventoryId = context.getState("inventoryId", String.class).orElseThrow();
        String claimId = context.getState("claimId", String.class).orElseThrow();
        commandGateway.send(new ReleaseAvailability(inventoryId, event.bookingId(), claimId));
    }
}
```

The commands land on plain classes. `Inventory` is an `@Aggregate` that extends and implements no Saga
Forge type; the gateway loads it by the command's `@AggregateIdentifier`, invokes the handler, and saves it:

```java
@Aggregate
public class Inventory {

    @AggregateIdentifier
    private final String inventoryId;

    private final Map<String, Claim> claims = new LinkedHashMap<>();

    @CommandHandler
    public void handle(ClaimAvailability command) {
        String claimId = "claim-" + command.bookingId();
        claims.put(claimId, new Claim(claimId, command.bookingId(), command.checkIn(), command.checkOut()));
        DomainEvents.record(new AvailabilityClaimedEvent(inventoryId, command.bookingId(), claimId));
    }

    // handle(ReleaseAvailability) removes the claim and records AvailabilityReleasedEvent.
}
```

The first unit of work runs `onBookingRequested` and `onAvailabilityClaimed`. No step reacts to
`PaymentInitiatedEvent`, so that unit of work commits there and the saga waits `RUNNING`. The payment
provider's callback starts the second unit of work, which reaches one of the two `@EndSaga` outcomes. If a
step or handler fails, its unit of work rolls back and the saga ends `FAILED`. Loading and saving aggregates
stays in your code, through `@AggregateLoader`/`@AggregateSaver` methods or, once the starter ships, its
Spring Data adapter (not implemented yet).

Nothing in these classes imports a datastore driver, a Kafka client, or a transaction manager. Those
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

1. **Declarative, not imperative.** You describe *which* event triggers each step and *which*
   commands it dispatches. You never write the orchestration loop or the correlation lookup.
2. **DDD-native.** Sagas coordinate **aggregates** by dispatching **commands** to their
   `@CommandHandler`s and reacting to the **events** those handlers record. Steps may consult **projections** (read models) to enrich a command, but a saga never
   reaches into another aggregate's internals. Saga Forge is the home for cross-aggregate **policy**
   — "when X happens, Y should happen" — that doesn't belong inside any single aggregate.
3. **Event-driven transport, orchestrated process.** Steps are triggered by events (Kafka or Spring
   application events) — this is choreography-shaped at the transport level. But the saga keeps a
   durable, single process-level state machine per instance, so you always have one place to ask
   "where is this process, and why." That's orchestration layered on an event-driven substrate, not
   a contradiction.
4. **Truthful state, always.** All steps triggered in a unit of work commit together with their
   step status, so a step is never recorded as completed unless its business effect committed.
   `SagaContext` values are stored separately and survive a rollback.
5. **Zero-ceremony infrastructure.** You pick a starter dependency (Postgres, MySQL, MongoDB) and a
   transport dependency (Kafka or in-memory), and autoconfiguration does the rest. There is no
   `SagaEngineConfig` to hand-assemble.
6. **Boring is a feature.** No DSL, no XML, no custom build plugin. Plain classes, plain
   annotations, plain Spring beans for dependencies you inject into a saga (repositories, read
   models) via regular DI.

## Design Inspiration

The saga programming model is inspired by [AxonIQ's Axon Framework](https://axoniq.io/):
- Event-triggered steps returning void, dispatching commands through a gateway
- Domain-driven design patterns (aggregates, commands, domain events)
- Per-instance saga orchestration across aggregate boundaries

Saga Forge is an independent, zero-dependency implementation designed for JDK 21+.

## Status

This is an early-stage framework. The annotation model, SPI contracts, and module layout exist and
are tested (`saga-forge-core`). The `SagaContext`/`SagaLifecycle` interfaces are contracts only,
tested via test doubles, with no implementation. The step, command-handler, and unit-of-work contracts
(`CommandGateway`, `DomainEvents`, `@CommandHandler`, `@Aggregate`) are defined and proven by a test-only
harness (feature 002). The runtime engine, storage providers, transports, and autoconfiguration are not
implemented yet. See
[docs/architecture.md](docs/architecture.md) for the target design and
[docs/use-cases.md](docs/use-cases.md) for the examples driving prioritization. Treat
`docs/Saga_Framework_Epic.md`, `docs/Saga_User_Stories.md`, and `docs/Saga_Technical_Tasks.md` as
historical planning artifacts, superseded by the docs above where they conflict.

## Modules

| Module | Role | Depends on |
|---|---|---|
| `saga-forge-core` | Annotations, SPI (`StorageProvider`, `EventDispatcher`), `SagaContext`/`SagaLifecycle` contracts (no implementation), scanner | nothing |
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
