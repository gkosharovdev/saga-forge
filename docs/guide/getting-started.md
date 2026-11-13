# Getting started

Saga Forge 0.1.0 requires **Java 21** and, with the starter, **Spring Boot 4.1**. All artifacts are on Maven Central
under the group `io.github.gkosharovdev`.

| Artifact | Use it for | Scope |
|---|---|---|
| `saga-forge-spring-boot-starter` | auto-configuration: builds and starts the runtime from your beans; brings `saga-forge-core`, `saga-forge-runtime` and `saga-forge-transport-inmemory` | compile |
| `saga-forge-storage-postgres` | durable saga storage, `SagaContext` values and the message store on Postgres | compile |
| `saga-forge-test` | `SagaFixture`, in-memory given/when/then tests | test |
| `saga-forge-core` | annotations and contracts only, zero dependencies (for a module that declares commands and events but runs no saga) | compile |

## 1. Dependencies

Maven:

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
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jdbc</artifactId>  <!-- or spring-boot-starter-jdbc -->
</dependency>
<dependency>
    <groupId>io.github.gkosharovdev</groupId>
    <artifactId>saga-forge-test</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("io.github.gkosharovdev:saga-forge-spring-boot-starter:0.1.0")
    implementation("io.github.gkosharovdev:saga-forge-storage-postgres:0.1.0")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    testImplementation("io.github.gkosharovdev:saga-forge-test:0.1.0")
}
```

Compile with `-parameters` (Spring Boot's parent POM and Gradle plugin do this): Spring Data maps constructor
parameters by name.

## 2. Database and schema

Point Spring Boot at Postgres as usual:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/orders
spring.datasource.username=orders
spring.datasource.password=orders
```

Saga Forge's tables (`saga_instance`, `saga_association`, `saga_context`, `saga_lifecycle_history`,
`saga_message_store`, `saga_message_store_consumer`) are defined in the classpath resource
`io/github/gkosharovdev/sagaforge/storage/postgres/schema.sql` inside `saga-forge-storage-postgres`.

- **Development**: let the starter create them in an empty database:
  `saga-forge.storage.postgres.initialize-schema=always`. It never alters existing tables.
- **Production**: copy `schema.sql` into your Flyway or Liquibase migrations and keep
  `initialize-schema=never` (the default). With `never`, startup fails if the tables are missing:
  `Saga Forge's tables [...] do not exist in the database.`

A database created from the 0.1.0 `schema.sql` needs none of the `schema-upgrade-NNN.sql` scripts; see
[Operations](operations.md#schema-and-upgrades) for when they apply.

## 3. A saga

The starter finds `@Saga`, `@Aggregate` and handler classes in the packages of your `@SpringBootApplication` and starts
the runtime; there is no configuration class to write. This is the complete canonical saga of
`saga-forge-example-spring`: pay for an order, reserve its stock, complete it; or refund the payment when the stock is
unavailable.

```java
@Saga
public class OrderProcessingSaga {

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "orderId", retry = @RetryPolicy(maxAttempts = 3, initialBackoffMillis = 200,
            multiplier = 2.0, maxBackoffMillis = 1_000))
    public void handleOrderCreated(OrderCreatedEvent event) {
        context.putState("orderAmount", event.amount().toPlainString());
        commandGateway.send(new ProcessPaymentCommand(event.orderId(), event.amount()));
    }

    @SagaStep(associationProperty = "orderId")
    public void processPayment(PaymentProcessedEvent event) {
        BigDecimal orderAmount = amount();
        if (orderAmount.compareTo(event.amount()) != 0) {
            throw new IllegalStateException("Payment " + event.paymentId() + " of " + event.amount()
                    + " does not match order amount " + orderAmount);
        }
        context.putState("paymentId", event.paymentId());
        commandGateway.send(new ReserveStockCommand(event.orderId()));
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void completeOrder(OrderCompletedEvent event) {
        // Terminal step: the unit of work commits and the saga is COMPLETED.
    }

    @StartCompensation
    @SagaStep(associationProperty = "orderId")
    public void rejectOrder(StockUnavailableEvent event) {
        // The trigger records why; compensatePayment follows.
    }

    @SagaCompensation(forStep = "processPayment")
    public void compensatePayment(CompensationTrigger trigger) {
        String paymentId = context.getState("paymentId", String.class).orElseThrow();
        commandGateway.send(new RefundPaymentCommand(sagaId, paymentId, amount()));
    }

    private BigDecimal amount() {
        return new BigDecimal(context.getState("orderAmount", String.class).orElseThrow());
    }
}
```

Source: `saga-forge-example-spring/src/main/java/io/github/gkosharovdev/sagaforge/example/spring/OrderProcessingSaga.java`
(imports: `core.annotation.*`, `core.command.CommandGateway`, `core.compensation.CompensationTrigger`,
`core.lifecycle.SagaContext`, all under `io.github.gkosharovdev.sagaforge`).

Events and commands are plain records:

```java
@DomainEvent(aggregate = Order.class)
public record OrderCreatedEvent(@AggregateIdentifier String orderId, BigDecimal amount) {
}

@Command
public record ReserveStockCommand(@AggregateIdentifier String orderId) {
}
```

Source: `saga-forge-example-spring/src/main/java/io/github/gkosharovdev/sagaforge/example/spring/events/OrderCreatedEvent.java`,
`.../commands/ReserveStockCommand.java`.

The aggregate, a Spring Data JDBC entity that extends nothing from Saga Forge, handles a command and records the next
event. The starter loads it through its `CrudRepository` by the command's `@AggregateIdentifier` and saves it after
the handler:

```java
@Aggregate
@Table("orders")
public class Order {

    public enum Status { CREATED, COMPLETED, OUT_OF_STOCK }

    @Id
    @AggregateIdentifier
    private final String orderId;
    private final boolean inStock;
    private Status status;
    @Version
    private Long version;

    @PersistenceCreator
    Order(String orderId, boolean inStock, Status status, Long version) {
        this.orderId = orderId;
        this.inStock = inStock;
        this.status = status;
        this.version = version;
    }

    public static Order create(String orderId, boolean inStock) {
        return new Order(orderId, inStock, Status.CREATED, null);
    }

    @CommandHandler
    public void handle(ReserveStockCommand command) {
        if (status != Status.CREATED) {
            throw new IllegalStateException("Order " + orderId + " is already " + status);
        }
        if (!inStock) {
            status = Status.OUT_OF_STOCK;
            DomainEvents.record(new StockUnavailableEvent(orderId));
            return;
        }
        status = Status.COMPLETED;
        DomainEvents.record(new OrderCompletedEvent(orderId));
    }
    // accessors omitted
}

public interface OrderRepository extends CrudRepository<Order, String> {
}
```

Source: `saga-forge-example-spring/src/main/java/io/github/gkosharovdev/sagaforge/example/spring/Order.java`,
`OrderRepository.java`.

## 4. Start a saga

Publish the start event through Saga Forge's `EventPublisher` bean (never Spring's `ApplicationEventPublisher`). Inside
a `@Transactional` method the event is stored with your own changes and delivered after the commit:

```java
@Service
public class OrderPlacement {

    private final OrderRepository orders;
    private final EventPublisher events;

    public OrderPlacement(OrderRepository orders, EventPublisher events) {
        this.orders = orders;
        this.events = events;
    }

    @Transactional
    public void place(String orderId, BigDecimal amount, boolean inStock) {
        orders.save(Order.create(orderId, inStock));
        events.publish(new OrderCreatedEvent(orderId, amount));
    }
}
```

Source: `saga-forge-example-spring/src/main/java/io/github/gkosharovdev/sagaforge/example/spring/OrderPlacement.java`.

## 5. What happens

1. `OrderCreatedEvent` reaches `handleOrderCreated` (`@StartSaga`): a saga instance is created for `orderId`, and a
   unit of work begins.
2. `ProcessPaymentCommand` runs `PaymentService.handle` in the same unit of work; it records `PaymentProcessedEvent`,
   which triggers `processPayment` of the same instance, still in the same unit of work, and so on.
3. The unit of work commits at the first `@EndSaga` step (`completeOrder`, saga `COMPLETED`), at a
   `@StartCompensation` step (`rejectOrder`, saga `COMPENSATING`, then `COMPENSATED` after `compensatePayment`), or
   when no queued event is left for the instance (saga `RUNNING`, waiting for an event from outside).
4. Any exception rolls the whole unit of work back. See [Recipes](recipes.md) for retry and compensation.

Next: [Model reference](model-reference.md), [Testing](testing.md).

## Without Spring

The runtime can be assembled by hand in plain Java (no starter); see the
[root README](../../README.md#without-spring-plain-java) and `OrderProcessingSagaTransportTest` in
`saga-forge-example`.
