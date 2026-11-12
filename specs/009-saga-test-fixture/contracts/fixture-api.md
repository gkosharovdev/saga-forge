# Contract: Fixture API (`io.github.gkosharovdev.sagaforge.test`)

Artifact `io.github.gkosharovdev:saga-forge-test`, test scope. Depends on `saga-forge-core` and `saga-forge-runtime`.

## Building

```java
SagaFixture fixture = SagaFixture.of(OrderProcessingSaga.class)      // saga types and any other model classes
        .register(Payment.class, Order.class)                        // further model classes (aggregates, ...)
        .handlers(new PaymentService(provider))                      // run these hosts' @CommandHandlers (FR-012)
        .sagaObjects(OrderProcessingSaga.class, () -> new OrderProcessingSaga(clock))   // FR-013
        .failCommand(ProcessPaymentCommand.class, () -> new PaymentGatewayTimeoutException("slow"), 2)   // FR-008
        .failCommand(RefundPaymentCommand.class, refund -> refund.amount().signum() > 0,
                () -> new IllegalStateException("refund refused"))  // always, matching content
        .startingAt(Instant.parse("2026-11-12T09:00:00Z"))           // FR-009
        .maxTotalRetryBackoff(Duration.ofMinutes(5))                 // as the runtime property
        .checkContextValues(true)                                    // FR-019, default true
        .build();                                                    // validates and starts (FR-003)
```

- `of(Class<?>...)`, `register(Class<?>...)`: model classes, as `SagaRuntime.Builder.register`.
- `handlers(Object...)`: each object's class is registered and its handlers, loaders and savers run on it. Without any
  `handlers`, no command is handled (record-and-answer, FR-011). Aggregate instance handlers run when their aggregate
  class is registered and at least one handler host or `handleAggregates()` is set; the aggregate comes from the
  `AggregateStore` unless a registered loader/saver host covers it.
- `sagaObjects(type, Supplier)`: a fresh object per unit of work.
- `failCommand(type, failure)`, `failCommand(type, failure, times)`, `failCommand(type, predicate, failure)`,
  `failCommand(type, predicate, failure, times)`.

## Scenario

```java
fixture.givenAggregates(Order.create("o-1", true))       // handler mode only
       .given(new OrderCreatedEvent("o-1", amount))      // varargs; history, not asserted
       .when(new PaymentProcessedEvent("payment-1", "o-1", amount))
       .expectCommands(new ReserveStockCommand("o-1"))
       .expectContext("paymentId", "payment-1")
       .expectCompletedStep("processPayment")
       .expectStatus(SagaStatus.RUNNING);
```

`given(...)` and `givenAggregates(...)` return a `Given`; `when(event)` returns a `Then`. `fixture.when(...)` without
`given` is allowed; `fixture.given(...)` with no `when` exposes `.then()` over the last given event.

## Then (expectations over the event under test)

Every method returns `Then`, so calls chain; each throws `SagaExpectationError` on mismatch.

| Method | Checks |
|---|---|
| `expectCommands(Object... commands)` | exactly these commands, in order, compared with `equals` |
| `expectCommandsMatching(Predicate<Object>...)` | one command per predicate, in order |
| `expectNoCommands()` | none |
| `expectCommand(Class<T>, Predicate<T>)` | at least one command of that type matches |
| `expectHandled()` / `expectUnhandled()` | some / no saga type handled the event |
| `expectOutcome(HandlingOutcome)` | the instance's handling outcome (`WAITING`, `COMPLETED`, `DUPLICATE`, …) |
| `expectStarted()` | the event started the instance |
| `expectCompletedStep(String step)` | the step completed in response to the event |
| `expectCompensations(String... steps)` | these compensations ran, in order |
| `expectStatus(SagaStatus)` | the instance's status afterwards |
| `expectEndedWith(String outcomeStep)` | `COMPLETED` with that `@EndSaga` step |
| `expectCompensatedAfter(String triggerStep)` | `COMPENSATED`, trigger step recorded |
| `expectFailure(Class<? extends Throwable>, FailureClassification)` | the recorded failure |
| `expectAttempts(String step, int attempts)` | attempts of a step |
| `expectContext(String key, Object value)` / `expectNoContext(String key)` | a `SagaContext` value / its absence |
| `instance(Class<?> sagaType, String associationValue)` | switch the subject to a named instance |
| `result()` | the `EventResult` for custom assertions |

## Reading state

`fixture.instance(type, associationValue)` → `InstanceView`; `fixture.aggregate(type, id)` → `Optional`;
`fixture.clock()` → `FixtureClock` (`set`, `advance`, `instant`); `fixture.commands()` → every `RecordedCommand` so far.

## Failure messages (FR-014)

```text
OrderProcessingSaga[orderId=o-1] (saga-1), when PaymentProcessedEvent[paymentId=payment-1, orderId=o-1, amount=10]:
  step processPayment completed (attempt 1), status RUNNING
  expected commands: [ReserveStockCommand[orderId=o-1]]
  dispatched:        []
```

An unexpected failure inside the saga is attached as the error's cause.

## Errors

- `SagaRuntimeStartupException` (from the runtime) at `build()` for an invalid model.
- `SagaExpectationError extends AssertionError` for every failed expectation and for a context value outside the
  durable value types.
- `IllegalStateException` for misuse (for example `givenAggregates` without handler mode).
