# Saga Forge

Declarative, DDD-native saga orchestration for Spring applications.

Saga Forge lets you *forge* a saga as a plain Java class annotated with `@Saga`. Each step is a
method that reacts to a domain **event** and dispatches **commands** through a `CommandGateway` to
`@CommandHandler` methods on aggregates or services. The framework correlates events to the right
saga instance, runs the steps triggered within one unit of work together (all of them commit or none
do), persists each step's status and the saga's `SagaContext` values, and waits between units of
work for events from outside. A step can declare a retry policy for transient failures, and every command it
sends carries a stable identity, so a retried or redelivered step never applies a command's effect twice.
When the process fails after earlier units of work committed, a business failure marked with `@StartCompensation`
or a final technical failure, Saga Forge undoes those steps with their `@SagaCompensation` methods, most recent
first, each in its own unit of work. You write steps; Saga Forge forges the process around them.

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
        context.putState("bookingId", event.bookingId());
        context.putState("inventoryId", event.inventoryId());
        context.putState("amount", event.amount());
        commandGateway.send(new ClaimAvailability(
                event.inventoryId(), event.bookingId(), event.checkIn(), event.checkOut()));
    }

    // A payment-provider timeout is transient: run this step's unit of work up to 3 times, 200 ms then 400 ms apart.
    @SagaStep(associationProperty = "bookingId", retry = @RetryPolicy(maxAttempts = 3, initialBackoffMillis = 200,
            multiplier = 2.0, retryOn = PaymentTimeoutException.class))
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

    // Failure outcome "declined": the claim committed in the first unit of work, so it is compensated.
    @StartCompensation
    @SagaStep(associationProperty = "bookingId")
    public void onPaymentDeclined(PaymentDeclinedEvent event) {
        context.putState("declineReason", event.reason());
    }

    // Undoes the claim that onBookingRequested asked for; runs in its own unit of work once the saga compensates.
    @SagaCompensation(forStep = "onBookingRequested")
    public void releaseClaim(CompensationTrigger trigger) {
        String bookingId = context.getState("bookingId", String.class).orElseThrow();
        String inventoryId = context.getState("inventoryId", String.class).orElseThrow();
        String claimId = context.getState("claimId", String.class).orElseThrow();
        commandGateway.send(new ReleaseAvailability(inventoryId, bookingId, claimId));
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

A handler that calls an outside system asks for the command's identity and passes it on as the system's
idempotency key. The identity is the same on every retry and every redelivery:

```java
public class PaymentService {

    @CommandHandler
    public void handle(InitiatePayment command, CommandId commandId) {
        String paymentId = provider.charge(commandId.value(), command.amount()); // charged once per key
        DomainEvents.record(new PaymentInitiatedEvent(command.bookingId(), paymentId));
    }
}
```

The first unit of work runs `onBookingRequested` and `onAvailabilityClaimed`. No step reacts to
`PaymentInitiatedEvent`, so that unit of work commits there and the saga waits `RUNNING`. The payment
provider's callback starts the second unit of work. A confirmation reaches the `@EndSaga` outcome; a decline is the
`@StartCompensation` step, whose commit moves the saga to `COMPENSATING`, after which the runtime runs `releaseClaim`
for the claim that committed earlier and the saga ends `COMPENSATED`. If a step or handler fails, its unit of work
rolls back. A transient failure (marked `TransientFailure` or listed in `retryOn`) is retried under the failing
step's policy, re-running the whole unit of work. A final failure (marked `FinalFailure`) or running out of attempts
compensates the steps that already committed; an unmarked failure ends the saga `FAILED`. A compensation that fails
is never retried: the saga ends `REQUIRES_INTERVENTION`, and the stored instance shows what was and was not undone.
An event for a step that already completed is reported `DUPLICATE` and runs nothing. Loading and saving aggregates
stays in your code, through `@AggregateLoader`/`@AggregateSaver` methods, or, in a Spring Boot application, goes
through the aggregate's Spring Data repository.

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
   `SagaRuntimeConfig` to hand-assemble.
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
are tested (`saga-forge-core`). The step, command-handler, and unit-of-work contracts (`CommandGateway`,
`DomainEvents`, `@CommandHandler`, `@Aggregate`) are defined, first proven by a test-only harness (feature 002).
The saga runtime exists in `saga-forge-runtime` (feature 004): event routing by association, the
saga-scoped unit of work in the host's local transaction (`TransactionBoundary`), one unit of work per instance at
a time, an outbox SPI (`Outbox`, default `ImmediateDispatchOutbox` after commit), and fail-fast startup validation. It is verified by unit tests over
in-memory doubles. Postgres storage (`StorageProvider`, `SagaContextStore`) is implemented (feature 003), and its
`StorageProvider` joins the runtime's transaction (feature 004). Step retry with failure classification, stable
command identities and duplicate-event detection are implemented in the runtime and persisted by Postgres (feature
005). Compensation (feature 006) runs `@SagaCompensation` methods after a `@StartCompensation` business failure or a
final or exhausted step failure, in reverse completion order, and resumes after a crash. The in-memory transport
(feature 007) delivers events — and commands marked `@Command(delivery = OUTBOX)` — between the modules of one process,
from a durable message store written in the same transaction as the saga state, so unconsumed messages survive a
restart. The Spring Boot starter (feature 008) assembles and starts the runtime from the application's beans,
`DataSource` and Spring Data repositories, publishes saga events to Spring Modulith's module listeners, and reports
the message store through Actuator. The saga test fixture (feature 009, `saga-forge-test`) runs a saga's given/when/then
tests on the shipped runtime, in memory, with no database, transport or Spring context. The runtime and the
in-memory transport instrument themselves with the OpenTelemetry API (feature 010): saga spans join the request's trace,
also across the message store and restarts, and saga metrics feed dashboards and alerts. Not implemented yet: the Kafka transport, MySQL and MongoDB storage, and a
Quarkus extension. See
[docs/architecture.md](docs/architecture.md) for the target design and
[docs/use-cases.md](docs/use-cases.md) for the examples driving prioritization. Treat
`docs/Saga_Framework_Epic.md`, `docs/Saga_User_Stories.md`, and `docs/Saga_Technical_Tasks.md` as
historical planning artifacts, superseded by the docs above where they conflict.

## Modules

| Module | Role | Depends on |
|---|---|---|
| `saga-forge-core` | Annotations, scanner (`validate`, `validateAll` → `ValidationReport`), `SagaContext`/`SagaLifecycle` contracts (no implementation), `CommandGateway`, `DomainEvents`/`EventPublisher`, SPI (`StorageProvider`, `EventDispatcher`, `SagaContextStore`, `AggregateResolver`, `HandlerInstanceProvider`, `SagaInstance` step records, `SagaLifecycleEntry`) | nothing |
| `saga-forge-runtime` | Saga runtime over the core SPI: `SagaRuntime` (an `EventReceiver`) routes events, runs saga-scoped units of work in the host's transactions, hands committed events off, validates the model at startup | `saga-forge-core` |
| `saga-forge-spring-boot-starter` | Auto-configuration: collects the saga model, detects storage and transport, builds and starts the runtime, Spring host SPIs (beans, parameters, Spring Data aggregates), Spring Modulith delivery, Actuator. See [its README](saga-forge-spring-boot-starter/README.md) | `saga-forge-runtime`, `saga-forge-transport-inmemory`; Postgres, Spring Data, Modulith, Actuator (optional) |
| `saga-forge-storage-postgres` / `-mysql` / `-mongodb` | Durable `StorageProvider` + `SagaContextStore` implementations. Postgres ships both (JDBC, JSONB, lifecycle history; host applies `schema.sql`); MySQL and MongoDB are still placeholders | `saga-forge-core` |
| `saga-forge-transport-inmemory` | In-process transport with a durable message store (transactional outbox + relay, at-least-once, survives restarts); no Spring dependency | `saga-forge-core` |
| `saga-forge-transport-kafka` | `EventDispatcher` over Kafka topics | `saga-forge-core` |
| `saga-forge-test` | Test fixture for applications (test scope): given/when/then over the shipped runtime, in memory; record-and-answer or real handlers; failure rules, a controllable clock, deterministic ids. See [its README](saga-forge-test/README.md) | `saga-forge-runtime` |
| `saga-forge-example` | Runnable example saga used as a living spec | `saga-forge-core`; `saga-forge-runtime`, `saga-forge-test` (test scope) |
| `saga-forge-example-spring` | The canonical saga as a Spring Boot application: Spring Data JDBC aggregate, service beans, no Saga Forge assembly code | the starter, `saga-forge-storage-postgres` |

## Getting started

In a Spring Boot 4.1 application, add the starter and the Postgres storage module:

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

Point Spring Boot at your database as usual:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/orders
spring.datasource.username=orders
spring.datasource.password=orders
saga-forge.storage.postgres.initialize-schema=always   # development only
```

That is all the configuration there is. The starter finds the saga, handler and aggregate classes in your
application's packages, builds the runtime over your `DataSource` and transactions, delivers events through the
in-memory transport, and starts it. Publish the first event through the `EventPublisher` bean. See the
[starter README](saga-forge-spring-boot-starter/README.md) for what it assembles, the Spring Modulith and Actuator
setups, every property and how to replace each piece; `saga-forge-example-spring` is the complete application.

### Without Spring (plain Java)

```java
PostgresMessageStore log = new PostgresMessageStore(dataSource, transactions);   // tables in schema.sql
InMemoryTransport transport = InMemoryTransport.builder().messageStore(log).transactions(transactions).build();
SagaRuntime runtime = SagaRuntime.builder()
        .register(OrderProcessingSaga.class, Order.class, OrderRepository.class, PaymentService.class)
        .storage(new PostgresStorageProvider(dataSource, transactions))
        .contextStore(new PostgresSagaContextStore(dataSource))
        .transactions(transactions)
        .dispatcher(transport.dispatcher())
        .outbox(transport.outbox())
        .build();
runtime.start();
transport.start(runtime, runtime.commandReceiver());
```

A command type can opt into the log, so it is handled after the sending step commits instead of inside it:

```java
@Command(delivery = CommandDelivery.OUTBOX)
public record ChargeCard(@AggregateIdentifier String invoiceId) { }
```


## Testing sagas

Add the fixture in test scope:

```xml
<dependency>
    <groupId>io.github.gkosharovdev</groupId>
    <artifactId>saga-forge-test</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

A test states the history a saga has seen, the event that arrives next, and what the saga must do. Here the saga is
the canonical `OrderProcessingSaga` from `saga-forge-example`, whose steps associate events by `orderId`:
`handleOrderCreated` (`@StartSaga`), `processPayment`, then `completeOrder` (`@EndSaga`) or `rejectOrder`
(`@StartCompensation`).

```java
BigDecimal AMOUNT = new BigDecimal("10.00");

SagaFixture.of(OrderProcessingSaga.class).build()
        .given(new OrderCreatedEvent("o-1", AMOUNT))
        .when(new PaymentProcessedEvent("payment-1", "o-1", AMOUNT))
        .expectCommands(new ReserveStockCommand("o-1"))
        .expectContext("paymentId", "payment-1")
        .expectCompletedStep("processPayment")
        .expectStatus(SagaStatus.RUNNING);
```

The fixture runs the shipped runtime over in-memory parts of its own, so routing by association, the unit of work,
retries and compensation behave as in production. By default no command handler runs: each command a step sends is
recorded and the test answers it by publishing the resulting event. With `.handlers(new PaymentService(provider))`
the application's own handlers and aggregates run as well. `.failCommand(...)` makes a command fail, retry waits
advance the fixture's clock instead of sleeping, and saga ids are `saga-1`, `saga-2`, ..., so command identities are
predictable. A `SagaContext` value Postgres cannot store, such as a `BigDecimal`, fails the test at the step that
wrote it. A failed expectation names the saga, the association value, the event, the steps that ran and the
expected and actual values. The reference tests are `OrderProcessingSagaFixtureTest` in `saga-forge-example`; the
[fixture README](saga-forge-test/README.md) covers every expectation.


## Observability

The runtime and the in-memory transport instrument themselves with the OpenTelemetry API
(`io.opentelemetry:opentelemetry-api`). Without an OpenTelemetry SDK nothing is recorded and nothing changes. With
one, every saga unit of work, step, command and compensation is a span in the trace of the code that published the
start event, also when the message store delivers an event later or after a restart. Saga metrics count starts,
outcomes, retries and compensations, time each step, and report the message store's backlog.

**Spring Boot**: nothing to add if the application already traces with OpenTelemetry (Spring Boot's OpenTelemetry
setup, or Micrometer Tracing's OpenTelemetry bridge). The starter passes the application's `OpenTelemetry` bean along;
`saga-forge.telemetry.*` switches tracing, metrics and the optional details (see the
[starter README](saga-forge-spring-boot-starter/README.md#with-opentelemetry)).

**Plain Java**: give the runtime and the transport the same instance:

```java
InMemoryTransport transport = InMemoryTransport.builder().messageStore(log).transactions(transactions)
        .openTelemetry(openTelemetry).build();
SagaRuntime runtime = SagaRuntime.builder()
        .register(OrderProcessingSaga.class, Order.class, OrderRepository.class, PaymentService.class)
        .storage(storage).contextStore(contexts).transactions(transactions)
        .dispatcher(transport.dispatcher()).outbox(transport.outbox())
        .openTelemetry(openTelemetry)
        .telemetry(SagaTelemetrySettings.defaults())          // optional
        .build();
```

**Privacy**: spans carry saga types, step and command names, generated ids (saga id, command id), outcomes and the
failure's type. Association values (an order id), event and command contents, and failure messages are recorded only
when switched on (`SagaTelemetrySettings.withRecordAssociationValues(true)`, `withRecordContents(true)`,
`withRecordFailureMessages(true)`). Metrics carry only the saga type, step, command type, outcome and failure
classification, so their series never grow with traffic.

**Postgres**: the trace context is stored with each message in `saga_message_store.trace_context`; existing databases
apply `schema-upgrade-010.sql`.

### Telemetry reference

Spans (instrumentation scope `io.github.gkosharovdev.sagaforge`):

| Span | Kind | One per | Attributes (always) | Details |
|---|---|---|---|---|
| `saga_forge.handle` | internal | saga type and instance an event reaches | `saga_forge.saga.type`, `saga_forge.event.type`, `saga_forge.handling.outcome`, `saga_forge.saga.started`, `saga_forge.failure.classification`, `error.type` | `saga_forge.saga.id`; association value\*, content\* |
| `saga_forge.attempt` | internal | attempt of a unit of work | `saga_forge.saga.type`, `saga_forge.attempt.result`, `saga_forge.failure.classification`, `error.type` | `saga_forge.saga.id`, `saga_forge.unit_of_work.id`, `saga_forge.attempt.number` |
| `saga_forge.step` | internal | step run | `saga_forge.saga.type`, `saga_forge.step`, `error.type` | `saga_forge.saga.id` |
| `saga_forge.command` | internal | command handling | `saga_forge.command.type`, `saga_forge.command.delivery`, `saga_forge.command.result`, `error.type` | `saga_forge.command.id`, `saga_forge.step`, `saga_forge.saga.id`, content\* |
| `saga_forge.compensation` | internal | compensation | `saga_forge.saga.type`, `saga_forge.step`, `saga_forge.compensation.status`, `saga_forge.compensation.trigger` | `saga_forge.saga.id`, `saga_forge.unit_of_work.id` |
| `saga_forge.delivery` | consumer | message the transport delivers | `messaging.system`=`saga_forge.inmemory`, `messaging.operation.type`=`process`, `saga_forge.message.kind`, `saga_forge.payload.type`, `error.type` | `messaging.message.id`, `saga_forge.delivery.attempt` |
| `saga_forge.event.ignored` | internal | event no saga type reacts to | `saga_forge.event.type` | content\* |

Events on `saga_forge.handle`: `saga_forge.saga.started`, `saga_forge.saga.ended` (`saga_forge.saga.outcome`),
`saga_forge.retry.scheduled` (`saga_forge.attempt.number`, `saga_forge.retry.wait` in ms),
`saga_forge.compensation.started`. \* off by default; failure messages (`saga_forge.failure.message` and exception
events) likewise.

Metrics:

| Metric | Instrument | Unit | Attributes |
|---|---|---|---|
| `saga_forge.handle.duration`, `.attempt.duration`, `.step.duration`, `.command.duration`, `.compensation.duration`, `.delivery.duration` | histogram | s | the span's low-cardinality attributes |
| `saga_forge.saga.started` | counter | — | `saga_forge.saga.type` |
| `saga_forge.saga.ended` | counter | — | `saga_forge.saga.type`, `saga_forge.saga.outcome` (`completed:<step>`, `failed`, `compensated`, `requires_intervention`) |
| `saga_forge.event.handled` | counter | — | `saga_forge.saga.type`, `saga_forge.handling.outcome` |
| `saga_forge.retry.scheduled`, `saga_forge.retry.exhausted` | counter | — | `saga_forge.saga.type`, `saga_forge.step` |
| `saga_forge.message_store.messages` | gauge | `{message}` | `saga_forge.message.status` |
| `saga_forge.message_store.oldest_unconsumed.age` | gauge | s | — |

Runtime log lines written inside a unit of work carry `traceId=` and `spanId=`.

Example alerts, in PromQL, assuming the OpenTelemetry-to-Prometheus name translation (dots become underscores,
counters gain `_total`, units become suffixes):

```promql
# Sagas failing or needing intervention, per saga type
sum by (saga_forge_saga_type) (rate(saga_forge_saga_ended_total{saga_forge_saga_outcome=~"failed|requires_intervention"}[5m])) > 0

# Compensations as a share of finished sagas
sum(rate(saga_forge_saga_ended_total{saga_forge_saga_outcome="compensated"}[15m]))
  / sum(rate(saga_forge_saga_ended_total[15m])) > 0.05

# Retries exhausted
sum by (saga_forge_saga_type, saga_forge_step) (increase(saga_forge_retry_exhausted_total[15m])) > 0

# Messages set aside for an operator
saga_forge_message_store_messages{saga_forge_message_status="SET_ASIDE"} > 0

# Delivery falling behind
saga_forge_message_store_oldest_unconsumed_age_seconds > 60
```

## Vocabulary

Saga Forge borrows its vocabulary from EventStorming / DDD rather than inventing new terms. See
[docs/architecture.md § Vocabulary](docs/architecture.md#vocabulary) for the full glossary (Command,
Event, Aggregate, Policy, Projection, Saga, Step, Compensation).
