# Saga Forge test fixture

`saga-forge-test` tests sagas the way they run in production, in milliseconds: a test states the history a saga has
seen, publishes the next event, and states what the saga must do. The fixture builds and starts the shipped
`SagaRuntime` over in-memory parts of its own, so routing by association, the saga-scoped unit of work, duplicates,
retries and compensation behave as they do on Postgres and a transport. It needs no database, transport or Spring
context, and no JUnit extension: a failed expectation throws a `SagaExpectationError`, which is an `AssertionError`.

```xml
<dependency>
    <groupId>io.github.gkosharovdev</groupId>
    <artifactId>saga-forge-test</artifactId>
    <version>0.4.0</version>
    <scope>test</scope>
</dependency>
```

It depends on `saga-forge-core` and `saga-forge-runtime` only.

## A first test

The canonical `OrderProcessingSaga` (`saga-forge-example`) associates its events by `orderId`: `handleOrderCreated`
(`@StartSaga`) asks for payment, `processPayment` reserves stock, `completeOrder` (`@EndSaga`) ends the saga, and
`rejectOrder` (`@StartCompensation`) triggers the refund in `compensatePayment`.

```java
class OrderProcessingSagaFixtureTest {

    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    @Test
    void paymentReservesStock() {
        SagaFixture.of(OrderProcessingSaga.class).build()
                .given(new OrderCreatedEvent("o-1", AMOUNT))
                .when(new PaymentProcessedEvent("payment-1", "o-1", AMOUNT))
                .expectCommands(new ReserveStockCommand("o-1"))
                .expectContext("paymentId", "payment-1")
                .expectCompletedStep("processPayment")
                .expectStatus(SagaStatus.RUNNING);
    }
}
```

Given events run exactly like the event under test, but only what the event under test causes counts for the
expectations. `fixture.when(...)` works without a `given`, and `fixture.given(...).then()` checks the last given event.
A fixture is one scenario: a later `when` continues from the state the earlier ones reached.

## Expectations

Every method returns the same `Then`, so calls chain.

| Method | Checks |
|---|---|
| `expectCommands(Object...)` | exactly these commands, in order, compared with `equals` |
| `expectCommandsMatching(Predicate...)` | one command per predicate, in order |
| `expectNoCommands()` | none |
| `expectCommand(Class<T>, Predicate<T>)` | at least one command of that type matches |
| `expectHandled()` / `expectUnhandled()` | some / no saga instance handled the event |
| `expectOutcome(HandlingOutcome)` | the runtime's outcome: `WAITING`, `COMPLETED`, `DUPLICATE`, `DUPLICATE_START`, `UNMATCHED`, ... |
| `expectStarted()` | the event started the instance |
| `expectCompletedStep(String)` | the step completed in response to the event |
| `expectCompensations(String...)` | these compensation methods ran, in order; a failed one reads `name (failed)` |
| `expectAttempts(String, int)` | how often the step's unit of work ran |
| `expectStatus(SagaStatus)` | the instance's status afterwards |
| `expectEndedWith(String)` | `COMPLETED` with that `@EndSaga` step as its outcome |
| `expectCompensatedAfter(String)` | `COMPENSATED`, with that step recorded as the trigger |
| `expectFailure(Class, FailureClassification)` | the recorded failure: the instance's, a failed compensation's, or the one that triggered compensation |
| `expectContext(String, Object)` / `expectNoContext(String)` | a `SagaContext` value, or its absence |
| `expectOutcomeEvent(Class<T>, Predicate<T>)` | exactly one outcome event was committed, of that type, matching |
| `expectOutcomeEvent(Class<?>)` | exactly one outcome event was committed, of that type |
| `expectNoOutcomeEvent()` | no outcome event was committed: no instance ended |
| `expectOpenDeadline(String)` / `expectNoOpenDeadline(String)` | the deadline is open (started, neither expired nor cancelled), or not |
| `expectDeadlineExpired(String)` | the deadline came due and its `@DeadlineExpired` step ran |
| `expectDeadlineIgnored(String)` | the deadline came due but the instance no longer waited on it |
| `instance(Class<?>, String)` | switches the subject to the instance with that association value |
| `result()` | the `EventResult`, for your own assertions (AssertJ, Hamcrest, ...) |

Instance expectations address the one instance the event touched. When an event reaches several (two saga types
reacting to it, say), choose one with `instance(OrderProcessingSaga.class, "o-1")`. Outside a `Then`,
`fixture.instance(type, associationValue)` returns an `InstanceView` with the stored instance and its context values.

A failure names everything needed to find the bug, and carries the exception that failed the saga as its cause:

```text
OrderProcessingSaga[orderId=o-1] (saga-1), when PaymentProcessedEvent[paymentId=payment-1, orderId=o-1, amount=10.00]:
  step processPayment completed (attempt 1), status RUNNING, outcome WAITING
  expected commands: [ReserveStockCommand[orderId=o-2]]
  dispatched:        [ReserveStockCommand[orderId=o-1]]
```

Outcome events are the `SagaCompleted`, `SagaFailed` and `SagaCompensated` events the runtime records when a saga
ends. `EventResult.outcomeEvents()` lists the ones the event under test caused; with a chosen instance, the outcome
expectations look at that instance's only. `SagaFixture.Builder.outcomeEvents(false)` switches them off, like the
runtime's builder:

```java
    @Test
    @DisplayName("completion: the order's completion ends the saga and announces SagaCompleted")
    void completionIsAnnounced() {
        SagaFixture.of(OrderProcessingSaga.class).build()
                .given(new OrderCreatedEvent("o-4", AMOUNT), new PaymentProcessedEvent("payment-4", "o-4", AMOUNT))
                .when(new OrderCompletedEvent("o-4"))
                .expectEndedWith("completeOrder")
                .expectOutcomeEvent(SagaCompleted.class, completed -> completed.outcomeStep().equals("completeOrder")
                        && completed.associations().get("orderId").equals("o-4"));
    }
```

Source: `saga-forge-example/src/test/java/io/github/gkosharovdev/sagaforge/example/OrderProcessingSagaFixtureTest.java`.

An invalid saga fails `build()` with the runtime's `SagaRuntimeStartupException`, listing every model error.

## Record and answer, or handler mode

By default no command handler runs. Each command a step sends is recorded, counts as handled, and the test answers it
by publishing the event the handler would have recorded. That tests the saga's own logic.

Register handler hosts to run them as in production:

```java
PaymentProvider provider = new PaymentProvider();
SagaFixture fixture = SagaFixture.of(OrderProcessingSaga.class, Order.class)
        .handlers(new PaymentService(provider))
        .build();

fixture.givenAggregates(new Order("o-5", true))
        .when(new OrderCreatedEvent("o-5", AMOUNT))
        .expectEndedWith("completeOrder");
assertThat(fixture.aggregate(Order.class, "o-5")).hasValueSatisfying(order ->
        assertThat(order.status()).isEqualTo(Order.Status.COMPLETED));
```

- `handlers(...)` runs the `@CommandHandler`s, `@AggregateLoader`s and `@AggregateSaver`s of the objects you pass, and
  the handlers of the registered `@Aggregate` classes. `handleAggregates()` turns on the aggregates alone.
- Aggregates without a client loader and saver live in an in-memory store: seed it with `givenAggregates(...)`, read it
  with `fixture.aggregate(type, id)`. A client `@AggregateLoader`/`@AggregateSaver` keeps precedence.
- A command whose handler you did not register is still only recorded.
- `@Command(delivery = OUTBOX)` commands are handled after the sending unit of work commits, before `when` returns; a
  failure does not roll the step back and is listed in `result().outboxFailures()`.

`RecordedCommand.handled()` says which commands a handler ran.

## Failures, retries and time

```java
SagaFixture fixture = SagaFixture.of(OrderProcessingSaga.class)
        .handlers(new PaymentService(provider))
        .failCommand(ProcessPaymentCommand.class, () -> new PaymentGatewayTimeoutException("slow"), 2)
        .failCommand(RefundPaymentCommand.class, refund -> refund.amount().signum() > 0,
                () -> new IllegalStateException("refund refused"))
        .build();
```

- `failCommand(type, failure)` fails every handling; add a count to fail only the next n, and a predicate to match
  content. The first matching rule wins. Failures are classified by the runtime's own rules (`TransientFailure`,
  `FinalFailure`, `retryOn`), so retries, `EXHAUSTED` and compensation follow as in production.
- Retry waits advance `fixture.clock()` instead of sleeping: a policy with 30 seconds of backoff takes milliseconds.
  The clock starts at `2026-01-01T00:00:00Z` (`startingAt(...)` changes it), and a test may `set` or `advance` it.
- Deadlines and scheduled commands wait until the clock reaches their due time.
  `whenTimeElapses(Duration)` and `whenTimeReaches(Instant)` move the clock, then deliver everything due by then,
  earliest first, until nothing is due. `given(...).givenTimeElapses(...)` lets time pass in the history. Moving
  `fixture.clock()` yourself delivers nothing until the next `whenTime...`, and neither does `when(event)`:

  ```java
  fixture.given(new BookingRequested("b-1", "PT30M"))
          .whenTimeElapses(Duration.ofMinutes(30))
          .expectDeadlineExpired("hold")
          .expectCompensatedAfter("holdExpired")
          .expectCommands(new ReleaseSeat("b-1"));
  ```

  `InstanceView.openDeadlines()` lists the open deadlines with their due times, and `property(key, value)` on the
  builder sets what a `${key:default}` duration reads.
- Saga ids are `saga-1`, `saga-2`, ... and unit-of-work ids `uow-1`, ..., so `CommandId`s are predictable.
  `result().commands()` holds the commands of the attempts that committed; `result().allAttempts()` holds every
  attempt, for example to check that a retry kept the same idempotency key.
- `maxTotalRetryBackoff(...)` matches the runtime's setting.

## Sagas and handlers that need collaborators

Pass instances or factories, built by hand or taken from a Spring context; the fixture itself knows nothing about
Spring:

```java
SagaFixture.of(ShippingSaga.class)
        .sagaObjects(ShippingSaga.class, () -> new ShippingSaga(clock))   // a fresh object per unit of work
        .handlers(new CarrierService(stubCarrierClient))
        .build();
```

Other sagas and hosts are created with their no-argument constructor. `saga-forge-example-spring`'s
`OrderSagaFixtureTest` tests the Spring application's saga this way.

## `SagaContext` values

A value durable storage cannot persist fails the test at the step that wrote it, before any database is involved.
Supported: `String`, `Boolean`, `Integer`, `Long`, finite `Double`, `Map` with `String` keys, `List`, nested.

```text
OrderProcessingSaga[orderId=o-1] (saga-1), when OrderCreatedEvent[orderId=o-1, amount=10.00]:
  step handleOrderCreated stored SagaContext value 'orderAmount' of type java.math.BigDecimal,
  which durable storage cannot persist (supported: String, Boolean, Integer, Long, finite Double, Map with String keys, List).
  Store it as a String (for example amount.toPlainString()), or build the fixture with checkContextValues(false).
```

`DurableContextValues.isDurable(value)` applies the same rule.

## Gotchas

- **One event at a time.** The fixture runs units of work one after another on the test thread; it does not model
  concurrent units of work, transport redelivery, leases or ordering across aggregates.
- **An event a step of its own instance runs is not passed on to other saga types** (memory.md G-ENG-24), in the
  fixture as in production.
- **Outbox commands are reported when they are delivered**, after the commands handled in the sending unit of work.
  A record-and-answer test of a saga that sends an outbox command must register the handler's class (`register(...)`),
  as the runtime refuses to store an outbox command nobody handles (memory.md G-009-04).
- **A `Long` stays a `Long` in the fixture**, while Postgres reads a small one back as an `Integer` (memory.md
  G-003-07, G-009-06).
- **Aggregates without a no-argument constructor are not copied** by the in-memory store, so a handler's change to one
  stays visible even if its unit of work rolled back (memory.md G-009-05).
