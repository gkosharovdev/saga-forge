# Recipes

Each recipe is copied from compiling code in this repository; the source file is named under each snippet (Javadoc
and imports trimmed, code unchanged). Paths are relative to the repository root. Annotations are in
`io.github.gkosharovdev.sagaforge.core.annotation`.

- [1. Several aggregates in one saga-scoped unit of work](#1-several-aggregates-in-one-saga-scoped-unit-of-work)
- [2. Compensation](#2-compensation)
- [3. Retry and idempotent handlers](#3-retry-and-idempotent-handlers)
- [4. Outbox commands](#4-outbox-commands)
- [5. Bounded-context modules in one Spring Boot monolith](#5-bounded-context-modules-in-one-spring-boot-monolith)

## 1. Several aggregates in one saga-scoped unit of work

**Problem**: an order's payment and its stock reservation must commit together or not at all.

**Pattern**: chain steps through events. A step sends a command; its handler records an event; when that event triggers
a further step of the *same* instance, that step runs in the *same* unit of work. The unit of work commits at the first
`@EndSaga` step, at a `@StartCompensation` step, or when no queued event is left for the instance. Any exception on the
way rolls everything back: both aggregates, the step records and the recorded events.

The saga (`OrderProcessingSaga`, shown in full in [Getting started](getting-started.md#3-a-saga)):
`handleOrderCreated` sends `ProcessPaymentCommand`, `processPayment` reacts to `PaymentProcessedEvent` and sends
`ReserveStockCommand`, `completeOrder` (`@EndSaga`) reacts to `OrderCompletedEvent`.

The first handler is a service bean; it records the event that continues the saga:

```java
@Service
public class PaymentService {

    private final PaymentProvider provider;

    public PaymentService(PaymentProvider provider) {
        this.provider = provider;
    }

    @CommandHandler
    public void handle(ProcessPaymentCommand command, CommandId commandId) {
        String paymentId = provider.charge(commandId.value(), command.amount());
        DomainEvents.record(new PaymentProcessedEvent(paymentId, command.orderId(), command.amount()));
    }

    @CommandHandler
    public void handle(RefundPaymentCommand command) {
        provider.refund(command.paymentId(), command.amount());
    }
}
```

Source: `saga-forge-example-spring/src/main/java/io/github/gkosharovdev/sagaforge/example/spring/PaymentService.java`.

The event belongs to the `Payment` aggregate but correlates by `orderId`; the aggregate can be a marker class when its
state lives elsewhere:

```java
@DomainEvent(aggregate = Payment.class)
public record PaymentProcessedEvent(@AggregateIdentifier String paymentId, String orderId, BigDecimal amount) {
}

@Aggregate
public class Payment {

    @AggregateIdentifier
    private String paymentId;
}
```

Source: `saga-forge-example-spring/src/main/java/io/github/gkosharovdev/sagaforge/example/spring/events/PaymentProcessedEvent.java`,
`Payment.java`.

The second handler is an instance method of the `Order` aggregate (see [Getting started](getting-started.md#3-a-saga));
the command carries the aggregate's identity so the runtime can load it:

```java
@Command
public record ReserveStockCommand(@AggregateIdentifier String orderId) {
}
```

Source: `saga-forge-example-spring/src/main/java/io/github/gkosharovdev/sagaforge/example/spring/commands/ReserveStockCommand.java`.

A check that throws in a later step rolls back the whole chain, here including the payment:

```java
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
```

Source: `saga-forge-example-spring/.../OrderProcessingSaga.java`.

Notes:

- An unmarked exception ends the saga `FAILED` with nothing committed in that unit of work. Effects outside the
  database (a call to a payment provider) are not rolled back: make them idempotent (recipe 3).
- `SagaContext` values written before the failure stay readable.

## 2. Compensation

**Problem**: after the payment committed, the stock turns out to be unavailable; the payment must be refunded.

**Pattern**: model the business failure as an **event** recorded by the aggregate, handle it in a `@StartCompensation`
step, and map an undo method to each step that must be reversed with `@SagaCompensation(forStep = "<step method
name>")`. Compensations run after the failure step commits, most recently completed step first, each in its own unit
of work. They get no event: keep what they need in `SagaContext`.

The aggregate records the failure instead of throwing:

```java
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
```

Source: `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/Order.java`.

```java
@DomainEvent(aggregate = Order.class)
public record StockUnavailableEvent(@AggregateIdentifier String orderId) {
}
```

Source: `saga-forge-example-spring/src/main/java/io/github/gkosharovdev/sagaforge/example/spring/events/StockUnavailableEvent.java`.

The saga's failure step and the compensation of `processPayment`:

```java
@StartCompensation
@SagaStep(associationProperty = "orderId")
public void rejectOrder(StockUnavailableEvent event) {
    // The trigger records why; the compensation reads what it needs from the SagaContext.
}

@SagaCompensation(forStep = "processPayment")
public void compensatePayment(CompensationTrigger trigger) {
    String paymentId = context.getState("paymentId", String.class).orElseThrow();
    commandGateway.send(new RefundPaymentCommand(sagaId, paymentId, amount()));
}
```

Source: `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/OrderProcessingSaga.java`.

The refund handler passes its `CommandId` on, because a compensation interrupted by a crash runs again:

```java
@CommandHandler
public void handle(RefundPaymentCommand command, CommandId commandId) {
    provider.refund(commandId.value(), command.paymentId(), command.amount());
}
```

Source: `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/PaymentService.java`.

Outcome: `rejectOrder` commits, the instance is `COMPENSATING`, `compensatePayment` runs, the instance ends
`COMPENSATED` with `rejectOrder` as its trigger.

What starts compensation, and what does not:

| Situation | Result |
|---|---|
| A `@StartCompensation` step committed | compensate every other completed step → `COMPENSATED` |
| A step throws an exception implementing `FinalFailure`, after an earlier unit of work committed | compensate → `COMPENSATED` |
| A transient failure runs out of attempts (`EXHAUSTED`), after an earlier unit of work committed | compensate → `COMPENSATED` |
| Any failure while nothing committed yet | nothing to undo → `FAILED` |
| An unmarked exception (`UNCLASSIFIED`) | no compensation → `FAILED`, committed effects stay |
| A compensation throws | never retried → `REQUIRES_INTERVENTION` |

A `FinalFailure` is a marker interface on your exception class:

```java
public final class CourierRejected extends RuntimeException implements FinalFailure {
    public CourierRejected(String message) {
        super(message);
    }
}
```

Source: `saga-forge-runtime/src/test/java/io/github/gkosharovdev/sagaforge/runtime/fixtures/retry/CourierRejected.java`.

## 3. Retry and idempotent handlers

**Problem**: the payment provider sometimes times out; retrying must never charge twice.

**Pattern**: classify the failure as transient (exception implements `TransientFailure`, or is listed in
`retryOn`), give the step a `@RetryPolicy`, and have every handler that calls an outside system take a `CommandId`
parameter and pass `commandId.value()` on as the idempotency key. A retry re-runs the whole unit of work; every command
keeps its identity across retries and redeliveries.

```java
public class PaymentGatewayTimeoutException extends RuntimeException implements TransientFailure {

    public PaymentGatewayTimeoutException(String message) {
        super(message);
    }
}
```

Source: `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/PaymentGatewayTimeoutException.java`.

```java
@StartSaga
@SagaStep(associationProperty = "orderId", retry = @RetryPolicy(maxAttempts = 3, initialBackoffMillis = 200,
        multiplier = 2.0, maxBackoffMillis = 1_000))
public void handleOrderCreated(OrderCreatedEvent event) {
    context.putState("orderAmount", event.amount().toPlainString());
    commandGateway.send(new ProcessPaymentCommand(event.orderId(), event.amount()));
}
```

Source: `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/OrderProcessingSaga.java`
(up to 3 attempts, 200 ms then 400 ms apart).

```java
@CommandHandler
public void handle(ProcessPaymentCommand command, CommandId commandId) {
    String paymentId = provider.charge(commandId.value(), command.amount());
    DomainEvents.record(new PaymentProcessedEvent(paymentId, command.orderId(), command.amount()));
}
```

Source: `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/PaymentService.java`.

The provider honours the key: charging again with a key it has seen returns the first payment:

```java
public String charge(String idempotencyKey, BigDecimal amount) {
    String paymentId = paymentsByKey.computeIfAbsent(idempotencyKey, key -> "payment-" + key);
    amounts.putIfAbsent(paymentId, amount);
    if (timeoutsLeft > 0) {
        timeoutsLeft--;
        throw new PaymentGatewayTimeoutException("payment provider did not answer for key " + idempotencyKey);
    }
    return paymentId;
}
```

Source: `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/PaymentProvider.java`.

A third-party exception you cannot mark goes into `retryOn`:

```java
@SagaStep(associationProperty = "bookingId", retry = @RetryPolicy(maxAttempts = 3, initialBackoffMillis = 200,
        multiplier = 2.0, maxBackoffMillis = 1_000, retryOn = CourierTimeout.class))
public void onCharged(DeliveryCharged event) {
    int tries = context.getState(CONTEXT_TRIES, Integer.class).orElse(0);
    context.putState(CONTEXT_TRIES, tries + 1);
    commandGateway.send(new BookCourier(event.bookingId()));
}
```

Source: `saga-forge-runtime/src/test/java/io/github/gkosharovdev/sagaforge/runtime/fixtures/retry/RetrySaga.java`.

Notes:

- The policy of the **failing step's** unit of work applies (the step whose unit of work was running).
- Waits are in-process and block the delivering thread; the total wait is capped by
  `saga-forge.runtime.max-total-retry-backoff` (30 s by default). No minutes-long or scheduled retries (see
  [Limitations](limitations.md)).
- `SagaContext` writes are not rolled back between attempts (the counter above sees earlier attempts).
- A redelivered event for a step that already completed is reported `DUPLICATE` and runs nothing.

## 4. Outbox commands

**Problem**: a command's handler must not run inside the sending step's transaction (a slow card charge), and its
failure must not roll the step back.

**Pattern**: mark the command type `@Command(delivery = CommandDelivery.OUTBOX)`. `send` stores the command with the
sender's unit of work, with its `CommandId` assigned at send time; it is handled after that unit of work commits, in a
unit of work of its own, at least once (retried with backoff by the in-memory transport, set aside after
`saga-forge.transport.max-attempts`). It is never handled if the sender rolls back. The handler should pass the
`CommandId` on, since it can run more than once.

```java
@Command(delivery = CommandDelivery.OUTBOX)
public record ChargeCard(@AggregateIdentifier String invoiceId) {
}
```

Source: `saga-forge-transport-inmemory/src/test/java/io/github/gkosharovdev/sagaforge/transport/inmemory/fixtures/ChargeCard.java`.

```java
@Saga
public final class BillingSaga {

    @SagaId
    private String sagaId;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "invoiceId")
    public void onInvoiceIssued(InvoiceIssued event) {
        commandGateway.send(new ChargeCard(event.invoiceId()));
    }

    @EndSaga
    @SagaStep(associationProperty = "invoiceId")
    public void onCardCharged(CardCharged event) {
        // Outcome step.
    }
}
```

Source: `saga-forge-transport-inmemory/src/test/java/io/github/gkosharovdev/sagaforge/transport/inmemory/fixtures/BillingSaga.java`.

```java
@CommandHandler
public void handle(ChargeCard command, CommandId commandId) {
    charges.add(new Charge(command.invoiceId(), commandId, Thread.currentThread().getName()));
    if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
        throw new IllegalStateException("card provider unavailable");
    }
    DomainEvents.record(new CardCharged(command.invoiceId()));
}
```

Source: `saga-forge-transport-inmemory/src/test/java/io/github/gkosharovdev/sagaforge/transport/inmemory/fixtures/CardService.java`.

`onInvoiceIssued` commits with the stored `ChargeCard` (saga `RUNNING`); the transport then runs `CardService.handle`,
whose `CardCharged` event starts the next unit of work and ends the saga.

Notes:

- The handler must be among the registered classes (rule R23); with the starter, a bean in the scanned packages.
- The default `IN_UNIT_OF_WORK` delivery is what you want when the handler's change must commit atomically with the
  step (recipe 1).

## 5. Bounded-context modules in one Spring Boot monolith

**Problem**: one Spring Boot application, one Java package (module) per bounded context, integrated through events
only, no broker.

**Pattern**: each module exposes its commands and events as its API; the saga lives in the module that owns the
process; handlers record events with `DomainEvents.record`; application code publishes start events through Saga
Forge's `EventPublisher`; other modules listen to saga events with Spring Modulith's `@ApplicationModuleListener`.
The starter scans the packages of the `@SpringBootApplication`, so nothing is registered by hand. Events are delivered
through the in-memory transport's message store in your Postgres database (at least once, per-aggregate order,
survives restarts).

Application (three modules: `orders`, `payments`, `notifications`):

```java
@SpringBootApplication(proxyBeanMethods = false)
public class ModulithApp {

    @Bean
    DataSource dataSource() {
        return StarterPostgres.dataSource();
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
```

Source: `saga-forge-spring-boot-starter/src/test/java/io/github/gkosharovdev/sagaforge/spring/modulith/app/ModulithApp.java`
(the test supplies its `DataSource`; a real application uses `spring.datasource.*`).

`payments` module API: its command, its event and the aggregate the event belongs to:

```java
public final class PaymentEvents {

    private PaymentEvents() {
    }

    @Command
    public record TakePayment(String orderId, BigDecimal amount) {
    }

    @DomainEvent(aggregate = Payment.class)
    public record PaymentTaken(@AggregateIdentifier String paymentId, String orderId, BigDecimal amount) {
    }

    @Aggregate
    public static class Payment {

        @AggregateIdentifier
        private String paymentId;
    }
}
```

Source: `.../spring/modulith/app/payments/PaymentEvents.java`.

`payments` handler, a Spring bean:

```java
@Component
public class PaymentHandler {

    @CommandHandler
    public void handle(TakePayment command, CommandId commandId) {
        DomainEvents.record(new PaymentTaken("payment-" + commandId.value(), command.orderId(), command.amount()));
    }
}
```

Source: `.../spring/modulith/app/payments/PaymentHandler.java`.

`orders` module: its event and the saga that drives the process:

```java
@DomainEvent(aggregate = OrderPlaced.Order.class)
public record OrderPlaced(@AggregateIdentifier String orderId, BigDecimal amount) {

    @Aggregate
    public static class Order {

        @AggregateIdentifier
        private String orderId;
    }
}

@Saga
public class PaymentSaga {

    @SagaId
    private String sagaId;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void onPlaced(OrderPlaced event) {
        commandGateway.send(new TakePayment(event.orderId(), event.amount()));
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void onPaid(PaymentTaken event) {
        if (event.amount().signum() < 0) {
            throw new IllegalStateException("negative payment for " + event.orderId());
        }
    }
}
```

Source: `.../spring/modulith/app/orders/OrderPlaced.java`, `.../orders/PaymentSaga.java`.

`notifications` module: reacts to the saga's event after the commit, in its own transaction:

```java
@Component
public class PaymentNotifier {

    @ApplicationModuleListener
    public void on(PaymentTaken event) {
        inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
        if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new IllegalStateException("notification failed for " + event.orderId());
        }
        notified.add(event.orderId());
    }
}
```

Source: `.../spring/modulith/app/notifications/PaymentNotifier.java` (test bookkeeping fields omitted).

Starting the process from application code, in the same transaction as your own change (from
`saga-forge-example-spring`):

```java
@Transactional
public void place(String orderId, BigDecimal amount, boolean inStock) {
    orders.save(Order.create(orderId, inStock));
    events.publish(new OrderCreatedEvent(orderId, amount));
}
```

Source: `saga-forge-example-spring/src/main/java/io/github/gkosharovdev/sagaforge/example/spring/OrderPlacement.java`
(`events` is the injected `io.github.gkosharovdev.sagaforge.core.event.EventPublisher`).

Rules for this setup:

- **Publish saga events with `EventPublisher` or `DomainEvents.record`, never with Spring's
  `ApplicationEventPublisher`**: events published through Spring do not reach sagas.
- Saga events are also published as Spring application events inside the unit of work
  (`saga-forge.application-events.mode=in-transaction`). A plain `@EventListener` therefore runs **inside** the saga's
  transaction and its failure rolls the saga back; other modules should use `@ApplicationModuleListener` or
  `@TransactionalEventListener`.
- With Spring Modulith's JDBC event publication registry on the classpath (`spring-modulith-starter-jdbc`), the
  transport keeps its messages there: one outbox for the application, and
  `ApplicationModules.of(App.class).verify()` keeps passing.
- An event that continues a saga instance is not handed to a *different* saga type that also reacts to it; give the
  second saga an event of its own.
- Run one application instance per database for now (see [Limitations](limitations.md)).
