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
first, each in its own unit of work. A step can start a named deadline; if the awaited event does not arrive in time,
a timeout step runs instead, and leaving `RUNNING` cancels every deadline still open. A step can also schedule a
command for later through the same `CommandGateway`. You write steps; Saga Forge forges the process around them.

The example is a web shop's checkout: hold the stock at Inventory, authorise the card at Payments, confirm the order.

```java
@Saga
public class CheckoutSaga {

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    // Inventory answers later: ReserveStockCommand is an outbox command, sent once this step commits.
    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void onOrderPlaced(OrderPlacedEvent event) {
        context.putState("orderId", event.orderId());
        context.putState("amount", event.amount().toPlainString());
        context.putState("paymentWindow", event.express() ? "PT10M" : "PT30M");
        commandGateway.send(new ReserveStockCommand(event.orderId(), event.lines()));
    }

    // The provider answers within the call; a timeout is transient, so this unit of work runs up to 3 times.
    // The customer now has the payment window, read from the SagaContext, to pass the card challenge.
    @SagaStep(associationProperty = "orderId",
            retry = @RetryPolicy(maxAttempts = 3, initialBackoffMillis = 200, multiplier = 2.0))
    @StartDeadline(name = "paymentWindow", durationFrom = "paymentWindow")
    public void onStockReserved(StockReservedEvent event) {
        context.putState("reservationId", event.reservationId());
        commandGateway.send(new AuthorizePaymentCommand(event.orderId(), amount()));
    }

    // Outcome "confirmed". The order records OrderConfirmedEvent, which starts FulfilmentSaga.
    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void onPaymentAuthorized(PaymentAuthorizedEvent event) {
        String reservationId = context.getState("reservationId", String.class).orElseThrow();
        commandGateway.send(new ConfirmOrderCommand(event.orderId(), reservationId, event.paymentId()));
    }

    // Failure outcome: an item is out of stock.
    @StartCompensation
    @SagaStep(associationProperty = "orderId")
    public void onStockUnavailable(StockUnavailableEvent event) {
        context.putState("unavailableSku", event.sku());
    }

    // Failure outcome: the card was declined.
    @StartCompensation
    @SagaStep(associationProperty = "orderId")
    public void onPaymentDeclined(PaymentDeclinedEvent event) {
        context.putState("declineReason", event.reason());
    }

    // Failure outcome: the customer abandoned the card challenge and the payment window closed.
    @StartCompensation
    @DeadlineExpired("paymentWindow")
    @SagaStep
    public void onPaymentWindowExpired(DeadlineReached deadline) {
        context.putState("timedOutAt", deadline.dueAt().toString());
    }

    // Undoes the step that sent ReserveStockCommand, whatever ended the checkout. Inventory releases
    // whatever it holds for the order, nothing if it holds nothing.
    @SagaCompensation(forStep = "onOrderPlaced")
    public void cancelCheckout(CompensationTrigger trigger) {
        Order.RejectionReason reason;
        if (trigger.kind() == CompensationTriggerKind.DEADLINE_EXPIRED) {
            reason = Order.RejectionReason.PAYMENT_TIMED_OUT;
        } else if ("onStockUnavailable".equals(trigger.stepName())) {
            reason = Order.RejectionReason.OUT_OF_STOCK;
        } else {
            reason = Order.RejectionReason.PAYMENT_NOT_AUTHORIZED;
        }
        commandGateway.send(new ReleaseStockCommand(orderId()));
        commandGateway.send(new RejectOrderCommand(orderId(), reason));
    }

    // orderId() and amount() read the SagaContext; the amount is kept as a string, a JSON scalar.
}
```

Source: `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/checkout/CheckoutSaga.java`.

The commands land on plain classes that extend and implement no Saga Forge type. A handler that calls an outside
system asks for the command's identity and passes it on as that system's idempotency key. The identity is the same on
every retry and every redelivery, so the customer is never charged twice:

```java
public class PaymentService {

    @CommandHandler
    public void handle(AuthorizePaymentCommand command, CommandId commandId) {
        provider.authorize(commandId.value(), command.amount()).ifPresentOrElse(
                paymentId -> DomainEvents.record(
                        new PaymentAuthorizedEvent(paymentId, command.orderId(), command.amount())),
                () -> DomainEvents.record(new PaymentDeclinedEvent("declined-" + commandId.value(),
                        command.orderId(), provider.declineReason())));
    }
}
```

The order is an `@Aggregate` that the gateway loads by the command's `@AggregateIdentifier`, invokes, and saves:

```java
@Aggregate
public class Order {

    @AggregateIdentifier
    private final String orderId;

    @CommandHandler
    public void handle(ConfirmOrderCommand command) {
        requireStatus(Status.PLACED);
        status = Status.CONFIRMED;
        DomainEvents.record(new OrderConfirmedEvent(orderId, lines, deliveryAddress, command.reservationId(),
                command.paymentId()));
    }
}
```

The application saves the order and publishes `OrderPlacedEvent`, which starts the first unit of work. It runs
`onOrderPlaced` and commits with `ReserveStockCommand` stored in the outbox; the saga waits `RUNNING`. Inventory's
`StockReservedEvent` starts the second unit of work: `onStockReserved` authorises the payment in the same transaction,
the handler records `PaymentAuthorizedEvent`, and the `@EndSaga` step `onPaymentAuthorized` confirms the order. No
checkout step takes `OrderConfirmedEvent`, so it leaves through the outbox and starts the next saga.

When things go wrong:

| What happens | What runs | Saga ends |
|---|---|---|
| An item is out of stock | `onStockUnavailable`, then `cancelCheckout` in its own unit of work | `COMPENSATED` |
| The card is declined | `onPaymentDeclined`, then `cancelCheckout`: the stock is released, the order rejected | `COMPENSATED` |
| No answer within the payment window (30 minutes, 10 for express) | `onPaymentWindowExpired`, then `cancelCheckout`: the order is rejected `PAYMENT_TIMED_OUT` | `COMPENSATED` |
| The provider times out | `onStockReserved`'s unit of work again, up to 3 times, with the same idempotency key | as if it had not happened |
| Retries run out, or a `FinalFailure` | `cancelCheckout` for the step that committed | `COMPENSATED` |
| An unmarked exception | the unit of work rolls back | `FAILED` |
| A compensation fails | nothing retries it; the stored instance shows what was undone | `COMPENSATION_FAILED` |
| Inventory's event arrives twice | nothing: the step already completed | reported `DUPLICATE` |

`OrderConfirmedEvent` starts the second saga of the example, `FulfilmentSaga`, which waits days for the parcel as
easily as checkout waits seconds for Inventory:

```text
OrderConfirmedEvent ─► RequestShipment ─► ShipmentDispatchedEvent ─► CapturePayment, CommitStock ─► ShipmentDeliveredEvent
   (@StartSaga)                                                                                       (@EndSaga)
                                      └─► DeliveryImpossibleEvent (@StartCompensation) ─► cancelOrder: refund or void, restock, cancel
                                      └─► deadline courierAssignment expires (@StartCompensation) ─► cancelOrder
```

`onOrderConfirmed` starts the `courierAssignment` deadline, two hours unless the property
`checkout.courier-assignment` says otherwise, and `cancelledBy = "onShipmentDispatched"` closes it when the parcel
leaves in time. Deadlines are messages with a due time in the durable message store, so they survive a restart; an
overdue deadline runs once the application is back. A fixture test moves time on with `whenTimeElapses`:

```java
fixture.given(CONFIRMED)
        .whenTimeElapses(Duration.ofHours(2))
        .expectDeadlineExpired("courierAssignment")
        .expectCompensations("cancelOrder")
        .expectCommands(new CancelOrderCommand("o-1", "no courier assigned"),
                new ReleasePaymentCommand("o-1", "payment-1"),
                new ReleaseStockCommand("o-1"))
        .expectCompensatedAfter("onCourierNotAssigned")
        .expectOutcomeEvent(SagaCompensated.class);
```

Source: `saga-forge-example/src/test/java/io/github/gkosharovdev/sagaforge/example/checkout/FulfilmentSagaFixtureTest.java`.

Both sagas, every failure path and their tests are in
[docs/use-cases.md](docs/use-cases.md#1-ordering-and-checkout-checkoutsaga-and-fulfilmentsaga).

Loading and saving aggregates stays in your code, through `@AggregateLoader`/`@AggregateSaver` methods, or, in a
Spring Boot application, goes through the aggregate's Spring Data repository.

Nothing in these classes imports a datastore driver, a Kafka client, or a transaction manager. Those
are cross-cutting concerns Spring Boot autoconfiguration wires up for you based on what it finds on
the classpath — see [docs/architecture.md](docs/architecture.md).

## Why

Multi-aggregate business transactions (the kind that touch an Order, a stock reservation, a card
payment and a shipment in one logical flow) are usually hand-rolled as one large service method.
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
copied from compiling code in this repository. It is also published as a site at
[gkosharovdev.github.io/saga-forge](https://gkosharovdev.github.io/saga-forge/).

| Page | Covers |
|---|---|
| [Getting started](docs/guide/getting-started.md) | Maven/Gradle coordinates, Postgres schema, the canonical saga end to end |
| [Model reference](docs/guide/model-reference.md) | every annotation: where it goes, what it needs, attributes and defaults |
| [Validation rules](docs/guide/validation-rules.md) | each startup error text, what it means and how to fix it |
| [Recipes](docs/guide/recipes.md) | multi-aggregate unit of work, compensation, retry and idempotency, outbox commands, modular monolith |
| [Deadlines](docs/guide/deadlines.md) | saga deadlines, timeout steps and scheduled commands |
| [Testing](docs/guide/testing.md) | `SagaFixture` given/when/then tests, in memory |
| [Operations](docs/guide/operations.md) | properties, Actuator, OpenTelemetry spans and metrics, schema upgrades |
| [Limitations](docs/guide/limitations.md) | what to design around in 0.3.0 |

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

Saga Forge 0.3.0 is the latest release on Maven Central (master builds the next one), in production use in one Spring
Boot monolith.
It ships the annotation model with startup
validation, the saga runtime (event routing by association, the saga-scoped unit of work in your transaction, step
retry with stable command identities, compensation, saga deadlines and scheduled commands), Postgres storage, the in-memory transport with a durable message
store (at least once, survives restarts, no broker; several application instances share it, one relay active and the
others on standby), the Spring Boot starter (Spring Modulith and Actuator support),
OpenTelemetry instrumentation and the `SagaFixture` test fixture. Not available yet: the Kafka transport, MySQL and
MongoDB storage, a Quarkus extension. See [Limitations](docs/guide/limitations.md) for what to design around.

## Modules

| Module | Role |
|---|---|
| `saga-forge-core` | Annotations, startup validation, `CommandGateway`, `DomainEvents`/`EventPublisher`, `SagaContext`, SPI. No dependencies. |
| `saga-forge-runtime` | The saga runtime (`SagaRuntime`): routing, units of work, retry, compensation. |
| `saga-forge-spring-boot-starter` | Auto-configuration for Spring Boot 4.1; see [its README](saga-forge-spring-boot-starter/README.md). |
| `saga-forge-storage-postgres` | Postgres storage for saga instances, `SagaContext` values and the message store. |
| `saga-forge-transport-inmemory` | In-process transport over a durable message store (transactional outbox and relay); with several instances one relay delivers, the others stand by. |
| `saga-forge-test` | `SagaFixture`, for tests; see [its README](saga-forge-test/README.md). |
| `saga-forge-example`, `saga-forge-example-spring` | The canonical order saga, in plain Java and as a Spring Boot application (not published). |

The `saga-forge-storage-mysql`, `saga-forge-storage-mongodb` and `saga-forge-transport-kafka` modules are placeholders.

## Getting started

In a Spring Boot 4.1 application on Java 21, add the starter and the Postgres storage module:

```xml
<dependency>
    <groupId>io.github.gkosharovdev</groupId>
    <artifactId>saga-forge-spring-boot-starter</artifactId>
    <version>0.3.0</version>
</dependency>
<dependency>
    <groupId>io.github.gkosharovdev</groupId>
    <artifactId>saga-forge-storage-postgres</artifactId>
    <version>0.3.0</version>
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

## Running several instances

Run as many replicas of the application as you need over one database; there is nothing to configure. Every
instance accepts requests, runs its sagas' units of work and appends their messages to the shared message store. The
relays of the instances elect one leader through the `saga_message_store_leader` table: the **active** relay delivers
every message, deadline and scheduled command, and the **standby** relays wait to take over.

- After a graceful stop a standby takes over within the check interval (5 s by default); after a crash or a
  partition, within the lease plus the check interval (35 s).
- A deposed leader is fenced: the store refuses its leases once another relay holds a newer fencing token, and the old
  leader stops leasing before its lease ends by its own clock. Delivery stays at least once, so command handlers keep
  passing their `CommandId` on.
- Not supported: spreading delivery over the instances. Delivery throughput is that of one relay; partitioned delivery
  is the Kafka transport's job (not available yet).

```properties
saga-forge.transport.leadership.lease-duration=30s
saga-forge.transport.leadership.renew-interval=10s
saga-forge.transport.leadership.check-interval=5s
```

Each instance shows its role in the Actuator `sagaforge` endpoint and health (`DEGRADED` while no relay leads) and in
the `saga_forge.relay.active` gauge. Without Spring, pass `TransportSettings` with `LeadershipSettings` to
`InMemoryTransport.builder().settings(...)`. See [Operations](docs/guide/operations.md#running-several-instances) and
the [patterns behind the design](docs/architecture.md#patterns-behind-the-design).


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
git tag v0.4.0 && git push origin v0.4.0
```

The `Release` workflow takes the version from the tag, runs the full build with the container suites, signs every
artifact and uploads one bundle to the Sonatype Central Portal, then creates the GitHub release. By default the deployment waits in the portal (central.sonatype.com, Deployments) for a
manual **Publish**; set the repository variable `CENTRAL_AUTO_PUBLISH` to `true` to publish validated deployments
automatically. A published version can never be changed or removed.

**After the release** the workflow opens a pull request against master, `Start X.(Y+1).0-SNAPSHOT after vX.Y.Z` on
branch `chore/next-snapshot-X.(Y+1).0`; it never pushes to master. The pull request sets the POMs to the next
`-SNAPSHOT` and the Saga Forge version in the docs (dependency snippets and "Saga Forge N.N.N" mentions in the READMEs,
`docs/guide/` and `docs/agents/`, through `.github/scripts/set-docs-version.sh`) to the version just released, so the
docs always name the latest published version, and regenerates `llms-full.txt`. Review the proposed next version before
merging: it is the next minor by default; for a patch line, change it on the branch with
`mvn versions:set -DnewVersion=X.Y.(Z+1)-SNAPSHOT -DgenerateBackupPoms=false -DprocessAllModules=true`. A tag with a
qualifier (`v1.0.0-RC1`) gets no pull request. A push made with `GITHUB_TOKEN` starts no workflow, so the job starts
CI on the branch itself (`gh workflow run ci.yml`); its result shows on the pull request. The job needs the repository
setting "Allow GitHub Actions to create and approve pull requests" (Settings, Actions, General).

The workflow needs four repository secrets: `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD` (a Central Portal
user token), `GPG_PRIVATE_KEY` (the armored private key, whose public half is on `keyserver.ubuntu.com`) and
`GPG_PASSPHRASE`. To check the release build locally without signing or uploading:

```bash
mvn -P release -Dgpg.skip -DskipTests install
```

## License

Apache License 2.0, see [LICENSE](LICENSE).

