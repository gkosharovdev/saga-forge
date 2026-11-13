# Testing with `SagaFixture`

`saga-forge-test` (test scope) runs a saga's given/when/then tests on the shipped `SagaRuntime` over in-memory parts:
routing by association, the unit of work, duplicates, retries and compensation behave as in production. No database,
transport or Spring context, no JUnit extension: a failed expectation throws `SagaExpectationError` (an
`AssertionError`). Public API: package `io.github.gkosharovdev.sagaforge.test`.

```xml
<dependency>
    <groupId>io.github.gkosharovdev</groupId>
    <artifactId>saga-forge-test</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

## Record and answer (the saga's own logic)

By default no command handler runs. Each command a step sends is recorded and counts as handled; the test answers it by
publishing the event the handler would have recorded.

```java
class OrderProcessingSagaFixtureTest {

    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    @Test
    @DisplayName("happy path: the payment reserves the stock")
    void paymentReservesStock() {
        SagaFixture.of(OrderProcessingSaga.class).build()
                .given(new OrderCreatedEvent("o-1", AMOUNT))
                .when(new PaymentProcessedEvent("payment-1", "o-1", AMOUNT))
                .expectCommands(new ReserveStockCommand("o-1"))
                .expectContext("paymentId", "payment-1")
                .expectCompletedStep("processPayment")
                .expectStatus(SagaStatus.RUNNING);
    }

    @Test
    @DisplayName("compensation path: missing stock refunds the payment")
    void missingStockRefundsThePayment() {
        SagaFixture.of(OrderProcessingSaga.class).build()
                .given(new OrderCreatedEvent("o-2", AMOUNT), new PaymentProcessedEvent("payment-2", "o-2", AMOUNT))
                .when(new StockUnavailableEvent("o-2"))
                .expectCompensations("compensatePayment")
                .expectCommands(new RefundPaymentCommand("saga-1", "payment-2", AMOUNT))
                .expectCompensatedAfter("rejectOrder");
    }
}
```

Source: `saga-forge-example/src/test/java/io/github/gkosharovdev/sagaforge/example/OrderProcessingSagaFixtureTest.java`.

Given events run exactly like the event under test, but only what the event under test causes counts for the
expectations. A fixture is one scenario: a later `when` continues from the state the earlier ones reached. Saga ids
are `saga-1`, `saga-2`, ..., so command identities and commands carrying the saga id are predictable.

## Handler mode (handlers and aggregates run)

```java
class OrderSagaFixtureTest {

    private final PaymentProvider provider = new PaymentProvider();
    private final SagaFixture fixture = SagaFixture.of(OrderProcessingSaga.class, Order.class)
            .handlers(new PaymentService(provider))
            .build();

    @Test
    @DisplayName("an in-stock order is paid for and completed in one call")
    void completes() {
        BigDecimal amount = new BigDecimal("25.00");

        fixture.givenAggregates(Order.create("o-1", true))
                .when(new OrderCreatedEvent("o-1", amount))
                .expectCommands(new ProcessPaymentCommand("o-1", amount), new ReserveStockCommand("o-1"))
                .expectContext("orderAmount", "25.00")
                .expectEndedWith("completeOrder");

        assertThat(fixture.aggregate(Order.class, "o-1").orElseThrow().status()).isEqualTo(Order.Status.COMPLETED);
        assertThat(provider.charges()).containsValue(amount);
    }

    @Test
    @DisplayName("an out-of-stock order is refunded")
    void refunds() {
        fixture.givenAggregates(Order.create("o-2", false))
                .when(new OrderCreatedEvent("o-2", new BigDecimal("9.50")))
                .expectCompensations("compensatePayment")
                .expectCompensatedAfter("rejectOrder")
                .expectStatus(SagaStatus.COMPENSATED);

        assertThat(provider.refunds()).containsValue(new BigDecimal("9.50"));
    }
}
```

Source: `saga-forge-example-spring/src/test/java/io/github/gkosharovdev/sagaforge/example/spring/OrderSagaFixtureTest.java`.
This tests the Spring application's saga with its own `@Service` built by hand: no Spring context.

- `SagaFixture.of(SagaClass.class, AggregateClass.class, ...)` registers the model classes.
- `handlers(...)` runs the `@CommandHandler`, `@AggregateLoader` and `@AggregateSaver` methods of the objects passed,
  and the handlers of registered `@Aggregate` classes. A command whose handler is not registered is only recorded.
- Aggregates without a loader/saver live in an in-memory store: seed with `givenAggregates(...)`, read with
  `fixture.aggregate(type, id)`.
- Sagas with constructor dependencies: `sagaObjects(MySaga.class, () -> new MySaga(collaborator))`.

## Retries and failures

```java
@Test
@DisplayName("retried payment: two provider timeouts, one charge, the same idempotency key")
void retriedPayment() {
    PaymentProvider provider = new PaymentProvider();
    provider.timeOutNext(2);
    SagaFixture fixture = SagaFixture.of(OrderProcessingSaga.class).handlers(new PaymentService(provider)).build();

    Then then = fixture.when(new OrderCreatedEvent("o-3", AMOUNT))
            .expectAttempts("handleOrderCreated", 3)
            .expectCommands(new ProcessPaymentCommand("o-3", AMOUNT), new ReserveStockCommand("o-3"));

    assertThat(then.result().allAttempts()).filteredOn(c -> c.command() instanceof ProcessPaymentCommand)
            .extracting(RecordedCommand::commandId).hasSize(3).containsOnly(then.result().commands().get(0)
                    .commandId());
    assertThat(provider.charges()).hasSize(1);
}
```

Source: `saga-forge-example/src/test/java/io/github/gkosharovdev/sagaforge/example/OrderProcessingSagaFixtureTest.java`.

- Retry waits advance `fixture.clock()` instead of sleeping.
- `failCommand(type, () -> exception)` fails every handling of a command type; add a count to fail the next n, or a
  predicate to match content. Failures are classified by the runtime's rules (`TransientFailure`, `FinalFailure`,
  `retryOn`).
- `result().commands()` holds the commands of the attempts that committed; `result().allAttempts()` every attempt.

## Expectations

| Method | Checks |
|---|---|
| `expectCommands(Object...)` | exactly these commands, in order (`equals`) |
| `expectCommandsMatching(Predicate...)` / `expectCommand(Class, Predicate)` / `expectNoCommands()` | commands by predicate / at least one match / none |
| `expectHandled()` / `expectUnhandled()` / `expectStarted()` | some / no instance handled the event / it started the instance |
| `expectOutcome(HandlingOutcome)` | `WAITING`, `COMPLETED`, `DUPLICATE`, `DUPLICATE_START`, `UNMATCHED`, ... |
| `expectCompletedStep(String)` / `expectAttempts(String, int)` | the step completed / how often its unit of work ran |
| `expectStatus(SagaStatus)` / `expectEndedWith(String)` / `expectCompensatedAfter(String)` | final status / `COMPLETED` at that `@EndSaga` / `COMPENSATED` after that trigger |
| `expectCompensations(String...)` | these compensations ran, in order |
| `expectFailure(Class, FailureClassification)` | the recorded failure |
| `expectContext(String, Object)` / `expectNoContext(String)` | a `SagaContext` value or its absence |
| `instance(Class, String)` | switch to the instance with that association value (several sagas reacting) |
| `result()` | the `EventResult`, for your own assertions |

## What the fixture catches before production

- An invalid model fails `build()` with the same `SagaRuntimeStartupException` as production
  ([Validation rules](validation-rules.md)).
- A `SagaContext` value Postgres cannot store (a `BigDecimal`, say) fails the test at the step that wrote it, with
  `Store it as a String (for example amount.toPlainString())`.

## Gotchas

- One event at a time on the test thread: no concurrency, redelivery, leases or cross-aggregate ordering.
- In record mode, a saga that sends an `@Command(delivery = OUTBOX)` command needs the handler's class registered
  (`register(HandlerClass.class)`), or `build()` fails rule R23.
- A `Long` stays a `Long` in the fixture, while Postgres reads a small one back as `Integer`.
- Aggregates without a no-argument constructor are not copied by the in-memory store, so a rolled-back change to one
  stays visible.

The full API is in the [fixture README](../../saga-forge-test/README.md) and the
[Javadoc](https://javadoc.io/doc/io.github.gkosharovdev/saga-forge-test).
