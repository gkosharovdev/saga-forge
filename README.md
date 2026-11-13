# Saga Forge

[![CI](https://github.com/gkosharovdev/saga-forge/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/gkosharovdev/saga-forge/actions/workflows/ci.yml)
[![Release](https://github.com/gkosharovdev/saga-forge/actions/workflows/release.yml/badge.svg)](https://github.com/gkosharovdev/saga-forge/actions/workflows/release.yml)
[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fgkosharovdev%2Fsaga-forge-core%2Fmaven-metadata.xml&label=Maven%20Central)](https://central.sonatype.com/namespace/io.github.gkosharovdev)
[![Javadoc](https://javadoc.io/badge2/io.github.gkosharovdev/saga-forge-core/javadoc.svg)](https://javadoc.io/doc/io.github.gkosharovdev/saga-forge-core)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F)](saga-forge-spring-boot-starter/README.md)
[![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-instrumented-425CC7)](#observability)

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

## Documentation

The **[user guide](docs/guide/README.md)** is written for applications that use Saga Forge. Every snippet in it is
copied from compiling code in this repository.

| Page | Covers |
|---|---|
| [Getting started](docs/guide/getting-started.md) | Maven/Gradle coordinates, Postgres schema, the canonical saga end to end |
| [Model reference](docs/guide/model-reference.md) | every annotation: where it goes, what it needs, attributes and defaults |
| [Validation rules](docs/guide/validation-rules.md) | each startup error text, what it means and how to fix it |
| [Recipes](docs/guide/recipes.md) | multi-aggregate unit of work, compensation, retry and idempotency, outbox commands, modular monolith |
| [Testing](docs/guide/testing.md) | `SagaFixture` given/when/then tests, in memory |
| [Operations](docs/guide/operations.md) | properties, Actuator, OpenTelemetry spans and metrics, schema upgrades |
| [Limitations](docs/guide/limitations.md) | what to design around in 0.1.0 |

API reference: [Javadoc on javadoc.io](https://javadoc.io/doc/io.github.gkosharovdev/saga-forge-core). Spring Boot
details: the [starter README](saga-forge-spring-boot-starter/README.md). Design: [docs/architecture.md](docs/architecture.md),
[docs/use-cases.md](docs/use-cases.md).

## For AI coding agents

Saga Forge ships material for coding agents working in an application that uses it:

- [`llms.txt`](llms.txt) ([llmstxt.org](https://llmstxt.org) format): a summary with links to every guide page, and
  [`llms-full.txt`](llms-full.txt): the whole guide in one file, for a single fetch.
- [`docs/agents/saga-forge/SKILL.md`](docs/agents/saga-forge/SKILL.md): a drop-in [Claude Code skill](https://docs.claude.com/en/docs/claude-code/skills)
  with the do/don't rules and the most common startup errors. It triggers on Saga Forge work.
- [`docs/agents/AGENTS-snippet.md`](docs/agents/AGENTS-snippet.md): a short section to paste into your own
  `AGENTS.md` or `CLAUDE.md`.

Install the skill in your project (or in `~/.claude/skills/` for all projects):

```bash
mkdir -p .claude/skills/saga-forge && curl -fsSL -o .claude/skills/saga-forge/SKILL.md https://raw.githubusercontent.com/gkosharovdev/saga-forge/master/docs/agents/saga-forge/SKILL.md
```

Other agents: point them at `https://raw.githubusercontent.com/gkosharovdev/saga-forge/master/llms-full.txt`, or paste
the snippet into `AGENTS.md`.

## Status

0.1.0 is the first release, in production use in one Spring Boot monolith. It ships the annotation model with startup
validation, the saga runtime (event routing by association, the saga-scoped unit of work in your transaction, step
retry with stable command identities, compensation), Postgres storage, the in-memory transport with a durable message
store (at least once, survives restarts, no broker), the Spring Boot starter (Spring Modulith and Actuator support),
OpenTelemetry instrumentation and the `SagaFixture` test fixture. Not available yet: the Kafka transport, MySQL and
MongoDB storage, a Quarkus extension. See [Limitations](docs/guide/limitations.md) for what to design around.

## Modules

| Module | Role |
|---|---|
| `saga-forge-core` | Annotations, startup validation, `CommandGateway`, `DomainEvents`/`EventPublisher`, `SagaContext`, SPI. No dependencies. |
| `saga-forge-runtime` | The saga runtime (`SagaRuntime`): routing, units of work, retry, compensation. |
| `saga-forge-spring-boot-starter` | Auto-configuration for Spring Boot 4.1; see [its README](saga-forge-spring-boot-starter/README.md). |
| `saga-forge-storage-postgres` | Postgres storage for saga instances, `SagaContext` values and the message store. |
| `saga-forge-transport-inmemory` | In-process transport over a durable message store (transactional outbox and relay). |
| `saga-forge-test` | `SagaFixture`, for tests; see [its README](saga-forge-test/README.md). |
| `saga-forge-example`, `saga-forge-example-spring` | The canonical order saga, in plain Java and as a Spring Boot application (not published). |

The `saga-forge-storage-mysql`, `saga-forge-storage-mongodb` and `saga-forge-transport-kafka` modules are placeholders.

## Getting started

In a Spring Boot 4.1 application on Java 21, add the starter and the Postgres storage module:

```xml
<dependency>
    <groupId>io.github.gkosharovdev</groupId>
    <artifactId>saga-forge-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>io.github.gkosharovdev</groupId>
    <artifactId>saga-forge-storage-postgres</artifactId>
    <version>0.1.0</version>
</dependency>
```

Point Spring Boot at your database as usual (`spring.datasource.*`) and create Saga Forge's tables
(`saga-forge.storage.postgres.initialize-schema=always` in development, `schema.sql` in your migrations in
production). The starter finds the saga, handler and aggregate classes in your application's packages and starts the
runtime; publish the first event through the `EventPublisher` bean. Continue with
[Getting started](docs/guide/getting-started.md) (Gradle, schema, the complete example) and
[Testing](docs/guide/testing.md).

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


## Observability

The runtime and the in-memory transport instrument themselves with the OpenTelemetry API: every unit of work, step,
command and compensation is a span in the trace of the code that published the start event, and saga metrics count
starts, outcomes, retries and compensations. With Spring Boot nothing is needed beyond the application's own
OpenTelemetry setup. Span and metric names, privacy switches and example alerts:
[Operations](docs/guide/operations.md#opentelemetry).

## Vocabulary

Saga Forge borrows its vocabulary from EventStorming / DDD rather than inventing new terms. See
[docs/architecture.md § Vocabulary](docs/architecture.md#vocabulary) for the full glossary (Command,
Event, Aggregate, Policy, Projection, Saga, Step, Compensation).

## Releasing

Saga Forge is published to Maven Central as `io.github.gkosharovdev:saga-forge-*`. Published modules:
`saga-forge-core`, `saga-forge-runtime`, `saga-forge-test`, `saga-forge-transport-inmemory`,
`saga-forge-storage-postgres` and `saga-forge-spring-boot-starter`, each with sources and Javadoc. Core, runtime and
the in-memory transport also publish their `tests` jars, which hold the in-memory test doubles and the contract suites
a storage or transport implementation can reuse. The examples and the placeholder modules (MySQL, MongoDB, Kafka) are
not published.

**Versions** follow semantic versioning. Before 1.0, a minor release (0.x.0) may break the API and a patch release
(0.x.y) never does. Classes in packages named `internal` are public only for wiring between modules and carry no
compatibility promise. Each release's notes list any schema upgrade script (for example `schema-upgrade-010.sql`).

**To release** version X.Y.Z, push a tag from master:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

The `Release` workflow takes the version from the tag, runs the full build with the container suites, signs every
artifact and uploads one bundle to the Sonatype Central Portal, then creates the GitHub release. Master stays on the
next `-SNAPSHOT` version. By default the deployment waits in the portal (central.sonatype.com, Deployments) for a
manual **Publish**; set the repository variable `CENTRAL_AUTO_PUBLISH` to `true` to publish validated deployments
automatically. A published version can never be changed or removed.

The workflow needs four repository secrets: `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD` (a Central Portal
user token), `GPG_PRIVATE_KEY` (the armored private key, whose public half is on `keyserver.ubuntu.com`) and
`GPG_PASSPHRASE`. To check the release build locally without signing or uploading:

```bash
mvn -P release -Dgpg.skip -DskipTests install
```

## License

Apache License 2.0, see [LICENSE](LICENSE).

