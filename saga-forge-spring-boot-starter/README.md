# Saga Forge Spring Boot starter

The starter turns a Spring Boot application into a Saga Forge host with no assembly code. Add it with a storage module
and a `DataSource`, and a started `SagaRuntime` is in the context. The starter collects the sagas, handlers and
aggregates of the application, builds the runtime over the application's database and transactions, and starts it
before any message is delivered.

Requires Java 21 and Spring Boot 4.1.

## What you get by default

| Piece | Assembled from | Replace it with a bean of type |
|---|---|---|
| `SagaRuntime`: built, validated, started | everything below | `SagaRuntime` |
| Saga model | `@Saga`, `@Aggregate` and handler/loader/saver classes in the application's packages (those of `@SpringBootApplication`), beans of such classes, `saga-forge.scan-packages`, `SagaModelContributor` beans | add a `SagaModelContributor` |
| `CommandGateway`, `EventPublisher` beans | the runtime | `CommandGateway`, `EventPublisher` |
| Saga storage and `SagaContext` values | `saga-forge-storage-postgres` over the application's `DataSource` | `StorageProvider`, `SagaContextStore` |
| Transactions | the application's `PlatformTransactionManager`; a saga's unit of work joins the surrounding transaction | `TransactionBoundary` |
| Transport | the in-memory transport over a durable message store in the same database (survives restarts, at least once, per-aggregate order) | `InMemoryTransport`, `MessageStore` |
| Saga objects | a prototype-scoped bean of the saga class when there is one (constructor injection works), otherwise its no-argument constructor | `SagaObjectProvider` |
| Command handler, loader and saver hosts | their beans | `HandlerInstanceProvider` |
| Extra handler parameters | resolved from the context like an injection point (`@Qualifier` honoured) | `HandlerParameterResolver` |
| Aggregates without `@AggregateLoader`/`@AggregateSaver` | their Spring Data repository (`findById`/`save`) | `AggregateResolver` |
| Domain events as Spring application events | every recorded event published inside the unit of work's transaction (`application-events.mode=in-transaction`) | set `saga-forge.application-events.mode` |
| Lifecycle | the runtime starts after the context refreshed, then the transport; on shutdown the transport stops first | — |
| Spring Modulith | with its event publication registry present, the transport stores its messages there: one outbox | `saga-forge.transport.modulith-interop=false` |
| Actuator | with Actuator present, a read-only `sagaforge` endpoint and a `sagaforge` health indicator (`DOWN` while messages are set aside) | Spring Boot's `management.*` properties |

Every bean backs off when the application defines one of the same type. Two `SagaRuntime` beans fail startup.

## Common setups

### Spring Boot, Postgres and the in-memory transport (baseline)

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
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jdbc</artifactId>  <!-- or spring-boot-starter-jdbc -->
</dependency>
```

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/orders
spring.datasource.username=orders
spring.datasource.password=orders
# development only: create Saga Forge's tables in an empty database
saga-forge.storage.postgres.initialize-schema=always
```

Publish the event that starts a saga through Saga Forge's `EventPublisher`. Inside a `@Transactional` method, the event
is stored with your own changes and delivered after the commit:

```java
@Service
class OrderPlacement {
    private final OrderRepository orders;
    private final EventPublisher events;

    OrderPlacement(OrderRepository orders, EventPublisher events) {
        this.orders = orders;
        this.events = events;
    }

    @Transactional
    void place(String orderId, BigDecimal amount) {
        orders.save(Order.create(orderId));
        events.publish(new OrderCreatedEvent(orderId, amount));
    }
}
```

The complete application is `saga-forge-example-spring`.

### With Spring Modulith

Add Spring Modulith's JDBC event publication registry. Nothing else changes:

```xml
<dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-jdbc</artifactId>
</dependency>
```

```properties
spring.modulith.events.jdbc.schema-initialization.enabled=true
```

The transport now keeps its messages in Modulith's registry, so the application has one outbox. A saga's domain events
reach other modules' `@ApplicationModuleListener`s with Modulith's guarantees: after the commit, in the listener's own
transaction, never after a rollback, and resubmittable through `IncompleteEventPublications` when the listener failed.

```java
@Component
class PaymentNotifier {
    @ApplicationModuleListener
    void on(PaymentProcessedEvent event) { ... }
}
```

`ApplicationModules.of(App.class).verify()` keeps passing: the starter adds no type to your modules.

### With Actuator

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

```properties
management.endpoints.web.exposure.include=health,sagaforge
```

`GET /actuator/sagaforge` returns the message counts per status (capped at 1,000 each) and the age of the oldest
unconsumed message:

```json
{ "counts": { "CONSUMED": 120, "SET_ASIDE": 1, "UNCONSUMED": 3 }, "oldestUnconsumed": "PT2.5S" }
```

The `sagaforge` health contributor is `DOWN` with a `setAside` detail while any message waits for an operator. Switch
them off with `management.endpoint.sagaforge.enabled=false` and `management.health.sagaforge.enabled=false`.

## Properties

| Property | Default | Meaning |
|---|---|---|
| `saga-forge.enabled` | `true` | Whether Saga Forge's auto-configuration is active. |
| `saga-forge.scan-packages` | none | Packages scanned for saga model classes in addition to the application's packages. |
| `saga-forge.storage.type` | detected | Which storage module to use when several are on the class path (`postgres`). |
| `saga-forge.storage.postgres.initialize-schema` | `never` | `always` creates Saga Forge's tables in an empty database; `never` fails startup if they are missing. |
| `saga-forge.runtime.max-total-retry-backoff` | `30s` | Longest total wait a step's `@RetryPolicy` may add up to; a longer policy fails startup. |
| `saga-forge.application-events.mode` | `in-transaction` | `in-transaction`: recorded events are published as Spring application events inside the unit of work. `after-delivery`: published when the transport delivers them, after the commit. `none`: not published. |
| `saga-forge.transport.workers` | `4` | Delivery threads; messages of one aggregate always use the same one. |
| `saga-forge.transport.max-attempts` | `10` | Failed deliveries before a message is set aside for an operator. |
| `saga-forge.transport.initial-backoff` | `1s` | Wait after a message's first failed delivery, doubled per further failure. |
| `saga-forge.transport.max-backoff` | `1m` | Longest wait between delivery attempts. |
| `saga-forge.transport.lease-duration` | `5m` | How long a leased message is reserved for this instance before another may take it. |
| `saga-forge.transport.poll-interval` | `500ms` | Longest wait between polls when nothing signals new messages. |
| `saga-forge.transport.lease-batch-size` | `100` | Most messages leased at once. |
| `saga-forge.transport.retention` | `7d` | How long consumed messages are kept. |
| `saga-forge.transport.retention-sweep-interval` | `1h` | How often consumed messages older than the retention are removed. |
| `saga-forge.transport.heartbeat-interval` | `30s` | How often this instance refreshes its heartbeat in the message store. |
| `saga-forge.transport.modulith-interop` | `true` | Whether the transport keeps its messages in Spring Modulith's registry when it is present. |

Your IDE completes all of them from the starter's configuration metadata.

## Overrides

**Your own runtime.** The lifecycles still start it, and the transport around it:

```java
@Bean
SagaRuntime sagaRuntime(StorageProvider storage, SagaContextStore contexts, TransactionBoundary transactions,
        EventDispatcher dispatcher, Outbox outbox) {
    return SagaRuntime.builder().register(OrderProcessingSaga.class, PaymentService.class, Order.class)
            .storage(storage).contextStore(contexts).transactions(transactions)
            .dispatcher(dispatcher).outbox(outbox).build();
}
```

**Aggregates without Spring Data.** A client loader and saver win over a repository for their aggregate type:

```java
@Component
class OrderStore {
    @AggregateLoader Order load(String orderId) { ... }
    @AggregateSaver void save(Order order) { ... }
}
```

Or replace the Spring Data resolver for every aggregate:

```java
@Bean
AggregateResolver aggregateResolver(EntityManager entityManager) {
    return new JpaAggregateResolver(entityManager);   // your AggregateResolver
}
```

**Tracing or metrics around every delivery.** Wrap the runtime's receiver:

```java
@Bean
EventReceiverCustomizer tracing(Tracer tracer) {
    return receiver -> event -> {
        try (var span = tracer.start(event.getClass().getSimpleName())) {
            return receiver.receive(event);
        }
    };
}
```

**Model classes outside the application's packages**, such as a shared library:

```java
@Bean
SagaModelContributor billingModel() {
    return () -> List.of(BillingSaga.class, InvoiceService.class, Invoice.class);
}
```

The same works by property: `saga-forge.scan-packages=com.example.billing`.

**Switches:**

```properties
saga-forge.application-events.mode=none
saga-forge.storage.postgres.initialize-schema=never
saga-forge.enabled=false
```

## Gotchas

- **Saga beans are prototype-scoped.** A saga object is created for every unit of work. Declare a saga as a bean only
  to inject collaborators, and give it `@Scope("prototype")`. A singleton saga bean fails startup.
- **A plain `@EventListener` runs inside the saga's unit of work.** It sees the event before the commit, and its
  failure rolls the unit of work back. Listeners in other modules should use `@ApplicationModuleListener` or
  `@TransactionalEventListener`.
- **`initialize-schema=always` is for development.** It creates the tables in an empty database and never alters
  existing ones. In production, apply `saga-forge-storage-postgres`'s `schema.sql` and its `schema-upgrade-NNN.sql`
  scripts with Flyway or Liquibase.
- **One runtime per application.**
- **The `@AggregateIdentifier` type must convert to the repository's id type** through the application's
  `ConversionService`. Startup fails naming both types otherwise.
- **`SagaContext` values are JSON scalars on Postgres:** `String`, `Boolean`, `Integer`, `Long`, `Double`, lists and
  maps. Keep a `BigDecimal` as its plain string.
- **Spring Data maps constructor parameters by name.** Compile with `-parameters`; Spring Boot's parent POM does this
  for you.
- **An event a step of its own saga runs is not passed on to other saga types.** Give the second saga an event of its
  own (memory.md G-ENG-24).

## Testing

Test a saga without a context, database or transport with the saga test fixture (`saga-forge-test`, test scope).
Hand it the beans it needs, built by hand with test doubles or taken from a context:

```java
SagaFixture fixture = SagaFixture.of(OrderProcessingSaga.class, Order.class)
        .handlers(new PaymentService(provider))
        .build();
fixture.givenAggregates(Order.create("o-1", true))
        .when(new OrderCreatedEvent("o-1", amount))
        .expectEndedWith("completeOrder");
```

`OrderSagaFixtureTest` in `saga-forge-example-spring` is the complete test. A helper that builds the fixture from an
application context is not available yet (memory.md G-009-01).

## When startup fails

Every misconfiguration stops the application with Spring Boot's failure analysis, naming the class, bean, property or
module and what to do. For example, without a storage module:

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

No Saga Forge storage module is on the class path; a saga needs durable storage.

Action:

Add saga-forge-storage-postgres (the supported storage module), or define your own StorageProvider and SagaContextStore beans.
```

An invalid saga model lists every error at once, such as a step whose event type no other step accepts or a handler
parameter no bean supplies.
