---
name: saga-forge
description: Write, change, test and debug Saga Forge sagas (io.github.gkosharovdev:saga-forge-*) in a Java/Spring Boot application. Use when code involves @Saga, @SagaStep, @StartSaga, @EndSaga, @StartCompensation, @SagaCompensation, @RetryPolicy, @CommandHandler, @Aggregate, @DomainEvent, @Command, CommandGateway, DomainEvents.record, EventPublisher, SagaContext, CommandId or SagaFixture, or when a "Saga engine refused to start" error must be fixed.
---

# Saga Forge

Saga Forge 0.1.0: Java 21, Spring Boot 4.1, Postgres storage, in-memory transport (no broker). Annotations are in
`io.github.gkosharovdev.sagaforge.core.annotation`; `CommandGateway`/`CommandId` in `core.command`; `DomainEvents`/
`EventPublisher` in `core.event`; `SagaContext` in `core.lifecycle`; `CompensationTrigger` in `core.compensation`;
`TransientFailure`/`FinalFailure` in `core.failure`; `SagaFixture` in `io.github.gkosharovdev.sagaforge.test`.

Guide: https://github.com/gkosharovdev/saga-forge/tree/master/docs/guide (single file:
https://github.com/gkosharovdev/saga-forge/blob/master/llms-full.txt). Javadoc:
https://javadoc.io/doc/io.github.gkosharovdev/saga-forge-core.

## Shape of a saga

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
        context.putState("paymentId", event.paymentId());
        commandGateway.send(new ReserveStockCommand(event.orderId()));
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void completeOrder(OrderCompletedEvent event) {
    }

    @StartCompensation
    @SagaStep(associationProperty = "orderId")
    public void rejectOrder(StockUnavailableEvent event) {
    }

    @SagaCompensation(forStep = "processPayment")
    public void compensatePayment(CompensationTrigger trigger) {
        String paymentId = context.getState("paymentId", String.class).orElseThrow();
        commandGateway.send(new RefundPaymentCommand(sagaId, paymentId,
                new BigDecimal(context.getState("orderAmount", String.class).orElseThrow())));
    }
}
```

(Adapted from `saga-forge-example-spring/.../OrderProcessingSaga.java`.)

## Do

- Put `@Saga` on a concrete class with **exactly one** `@SagaId private String sagaId;`.
- Declare **at most one** `SagaContext` field and **at most one** `CommandGateway` field, recognized by type, no
  annotation; the runtime sets them. Keep no other state in saga fields: a new saga object is made per unit of work.
- Give every step `@SagaStep(associationProperty = "<event field name>")`, a `void` return, and exactly one parameter
  whose type is `@DomainEvent`. One step per event type per saga.
- Mark exactly one step `@StartSaga` and at least one `@EndSaga`; both always together with `@SagaStep`.
- Dispatch commands with `commandGateway.send(new XCommand(...))`.
- Make events records annotated `@DomainEvent(aggregate = X.class)` where `X` is `@Aggregate`, with exactly one
  `@AggregateIdentifier` field. The association property may be a different field.
- Make commands records annotated `@Command`. When an `@Aggregate` instance method handles it, give it exactly one
  `@AggregateIdentifier` field (the aggregate to load).
- Put `@CommandHandler` on an `@Aggregate` instance method (loaded by the command's identifier through its Spring Data
  repository or an `@AggregateLoader`, then saved), an `@Aggregate` constructor/static factory (creation), or a
  `@Service`/`@Component` method. Exactly one `@Command` parameter; one handler per command type.
- Record resulting events in handlers and aggregates with `DomainEvents.record(new XEvent(...))`.
- Start a saga from application code with the injected Saga Forge `EventPublisher.publish(event)`, inside your
  `@Transactional` method if you also change state.
- Store `SagaContext` values as JSON scalars: `String`, `Boolean`, `Integer`, `Long`, finite `Double`, `List`,
  `Map<String, ?>`. **`BigDecimal` → `amount.toPlainString()`**, read back with `new BigDecimal(...)`; `Instant` →
  ISO string; enum → `name()`.
- Give every handler that calls an outside system a `CommandId commandId` parameter and pass `commandId.value()` as the
  idempotency key. Compensation commands too: compensations can run again after a crash.
- Model business failures as events (`StockUnavailableEvent`) handled by a `@StartCompensation` `@SagaStep`; write
  what compensations need into `SagaContext` in the steps.
- Undo a step with `@SagaCompensation(forStep = "<exact step method name>")` returning `void`, taking nothing or a
  `CompensationTrigger`.
- Retry with `@SagaStep(retry = @RetryPolicy(...))` and mark retryable exceptions `implements TransientFailure` (or list
  them in `retryOn`); mark "do not retry, compensate" exceptions `implements FinalFailure`. Keep total backoff under
  30 s.
- Use `@Command(delivery = CommandDelivery.OUTBOX)` when the handler must run after the sending step commits and its
  failure must not roll the step back.
- With the starter, a saga that needs collaborators is a `@Scope("prototype")` bean; handler hosts are beans.
- Test with `SagaFixture.of(Saga.class).build().given(...).when(...).expectCommands(...)`; use `.handlers(...)` to run
  real handlers and `givenAggregates(...)` to seed aggregates.

## Don't

- Don't return a command (or anything) from a step; don't make a step take a command.
- Don't put `@CommandHandler` on a `@Saga` class.
- Don't make aggregates extend or implement a Saga Forge type, and don't call repositories for them from a saga.
- Don't publish saga events with Spring's `ApplicationEventPublisher`: they never reach sagas.
- Don't use a plain `@EventListener` for saga events in another module: it runs inside the saga's transaction. Use
  `@ApplicationModuleListener` or `@TransactionalEventListener`.
- Don't store `BigDecimal`, `Instant`, `LocalDate`, records, POJOs or `null` in `SagaContext`.
- Don't put `@StartCompensation` with `@StartSaga` or `@EndSaga`; don't map a `@SagaCompensation` to a
  `@StartCompensation` step; at most one compensation per step.
- Don't declare two steps for the same event type, two `@StartSaga` steps, or two handlers for one command.
- Don't rely on long retry waits, deadlines or timeouts (not supported); use a scheduled job that publishes a timeout
  event.
- Don't expect one event that continues a saga to also start a different saga type; record a separate event.
- Don't add `SagaRuntime` configuration classes in a Spring Boot app: the starter assembles everything.
- Don't use classes in packages named `internal`.

## When startup fails

The message is `Saga engine refused to start (N errors):` followed by one error per line. Look each line up in
https://github.com/gkosharovdev/saga-forge/blob/master/docs/guide/validation-rules.md. Most common:

| Error starts with | Fix |
|---|---|
| `@SagaStep method m in S must return void` | return `void`, `commandGateway.send(...)` |
| `... parameter type T must be annotated with @DomainEvent` | annotate the event `@DomainEvent(aggregate = X.class)` |
| `T must declare exactly one @AggregateIdentifier field` | one `@AggregateIdentifier` on each aggregate and event |
| `@Command type C is handled by @Aggregate instance method ... must declare an @AggregateIdentifier field` | add `@AggregateIdentifier` to the command |
| `@Aggregate A has instance-method @CommandHandler methods but no @AggregateLoader and no AggregateResolver` | add a Spring Data repository or `@AggregateLoader` |
| `@CommandHandler H.m parameter #n T cannot be resolved` | make `T` a bean, or inject it into the constructor |
| `Command C is delivered through the outbox but has no @CommandHandler` | add the handler (in a scanned package) |
| `@SagaCompensation on m: forStep 'x' does not reference an existing method` | use the exact step method name |
| `total retry backoff n ms exceeds the engine maximum` | shorten the `@RetryPolicy` |
| `Event E has no value for association property 'p'` (at runtime) | the event needs a non-null field named `p` |
