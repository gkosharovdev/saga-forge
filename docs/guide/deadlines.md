# Deadlines and scheduled commands

A saga waits for its next event. A deadline gives that wait a time limit: a step starts a named deadline, and if the
awaited event does not arrive in time, a timeout step runs instead. A step can also schedule a command to be handled
later. Both are stored in the transport's message store with a due time, in the step's own transaction, so they survive
a restart and are cancelled with the rest of the unit of work when it rolls back.

## Starting a deadline

`@StartDeadline` goes on a `@SagaStep`. The deadline starts when that step's unit of work commits and is due at the
step's completion time plus the duration. The checkout's payment window reads its length from the `SagaContext`:

```java
    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void onOrderPlaced(OrderPlacedEvent event) {
        context.putState("orderId", event.orderId());
        context.putState("amount", event.amount().toPlainString());
        context.putState("paymentWindow", event.express() ? "PT10M" : "PT30M");
        commandGateway.send(new ReserveStockCommand(event.orderId(), event.lines()));
    }

    @SagaStep(associationProperty = "orderId",
            retry = @RetryPolicy(maxAttempts = 3, initialBackoffMillis = 200, multiplier = 2.0))
    @StartDeadline(name = "paymentWindow", durationFrom = "paymentWindow")
    public void onStockReserved(StockReservedEvent event) {
        context.putState("reservationId", event.reservationId());
        commandGateway.send(new AuthorizePaymentCommand(event.orderId(), amount()));
    }
```

Source: `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/checkout/CheckoutSaga.java`.

The fulfilment saga's courier deadline has a fixed length, taken from a property with a default:

```java
    @StartSaga
    @SagaStep(associationProperty = "orderId")
    @StartDeadline(name = "courierAssignment", duration = "${checkout.courier-assignment:PT2H}",
            cancelledBy = "onShipmentDispatched")
    public void onOrderConfirmed(OrderConfirmedEvent event) {
```

Source: `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/checkout/FulfilmentSaga.java`.

| Attribute | Meaning |
|---|---|
| `name` | The deadline's name, unique within the saga. Several steps may start the same name. |
| `duration` | An ISO-8601 duration (`"PT15M"`) or a placeholder `"${key}"` / `"${key:PT15M}"`, resolved once at startup. In Spring Boot the key is an ordinary property; in plain Java it comes from `SagaRuntime.Builder.propertyResolver`. |
| `durationFrom` | The `SagaContext` attribute holding the duration as an ISO-8601 `String`, read after the step's method returned. |
| `cancelledBy` | Steps whose completion closes the deadline. |

Give exactly one of `duration` and `durationFrom`. A zero or negative duration from the context is due at once; a
missing, non-`String` or unparsable context value fails the step with `DeadlineDurationException`, an unmarked
failure. A step may carry several `@StartDeadline`s with different names.

## When a deadline closes

An open deadline closes, and its stored message is cancelled in the same transaction, when:

- a step named in `cancelledBy` completes;
- a step starts the same name again: the new deadline replaces the open one, and its duration counts from then;
- the instance leaves `RUNNING`: an `@EndSaga` step, a `@StartCompensation` step, a `FAILED` write, or compensation
  after a failure.

So a deadline that only guards the path to the end of the saga needs no `cancelledBy`. The payment window has none:
both answers from Payments end the checkout.

## The timeout step

Each deadline has exactly one `@DeadlineExpired` step. It is a `@SagaStep` with no `associationProperty` (the deadline
is addressed to its instance directly) and takes no parameter or one `DeadlineReached`:

```java
    @StartCompensation
    @DeadlineExpired("paymentWindow")
    @SagaStep
    public void onPaymentWindowExpired(DeadlineReached deadline) {
        context.putState("timedOutAt", deadline.dueAt().toString());
    }
```

Source: `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/checkout/CheckoutSaga.java`.

- With `@StartCompensation`, the usual case, the instance compensates. The `CompensationTrigger` and the outcome
  event's `OutcomeTrigger` have kind `DEADLINE_EXPIRED` and carry `deadlineName()`, so a compensation can tell a
  timeout from a business failure (`cancelCheckout` rejects the order `PAYMENT_TIMED_OUT`).
- With `@EndSaga`, the timeout is a success outcome of its own.
- As a plain step, the instance keeps running, for example a reminder that starts its own deadline again.

A timeout step is never `@StartSaga`. It runs only while the instance is `RUNNING` and the deadline is still open under
the same start. A deadline that arrives later (the instance ended or compensates, the deadline was cancelled or
replaced) runs no step: the delivery is reported `IGNORED` and logged as `saga.deadline.ignored` with a reason
`NOT_RUNNING`, `NOT_OPEN` or `REPLACED`.

## Scheduled commands

`CommandGateway.schedule(command, at)` stores an outbox command to be handled at `at` or soon after, and returns a
`ScheduledCommandId`; `cancel(id)` withdraws it if it has not been handed over yet:

```java
    @StartSaga
    @SagaStep(associationProperty = "subscriptionId")
    public void onStarted(SubscriptionStarted event) {
        ScheduledCommandId renewal = commandGateway.schedule(new ChargeCard(event.subscriptionId()),
                Instant.now().plus(Duration.parse(event.chargeIn())));
        context.putState("renewal", renewal.value());
    }

    @EndSaga
    @SagaStep(associationProperty = "subscriptionId")
    public void onCancelled(SubscriptionCancelled event) {
        context.putState("renewalCancelled", String.valueOf(commandGateway.cancel(
                new ScheduledCommandId(context.getState("renewal", String.class).orElseThrow()))));
    }
```

Source: `saga-forge-transport-inmemory/src/test/java/io/github/gkosharovdev/sagaforge/transport/inmemory/fixtures/RenewalSaga.java`
(shortened).

- The command's type must be `@Command(delivery = CommandDelivery.OUTBOX)`.
- It is stored only if the sending unit of work commits; a time in the past is due at once.
- `cancel` returns `false` when the command is already being delivered, was handled, was cancelled, or is unknown.
- Unlike a deadline, a scheduled command is not cancelled when the saga ends: cancel it yourself if it must not run.
- From a saga step, its `CommandId` is saga-scoped, and a final failure is reported to the saga like any outbox
  command's.

## What it needs

- **An `IN_TRANSACTION` outbox that can schedule messages**: the in-memory transport over the Postgres message store
  (the Spring Boot starter's default setup). With the default `ImmediateDispatchOutbox`, a saga that declares a deadline
  fails startup, and `schedule` throws `UnsupportedOperationException`.
- **Not with Spring Modulith interop**: Modulith's publication registry has no due time, so the starter refuses
  deadlines while `saga-forge.transport.modulith-interop` is on. Set it to `false` to keep Saga Forge's own log.
- **One clock**: the runtime and the transport read the same `java.time.Clock` (in Spring Boot, a `Clock` bean, or
  the UTC system clock).
- **Postgres**: an existing database applies `schema-upgrade-017.sql` (see [Operations](operations.md#schema-and-upgrades)).

## Timing

- A due message is picked up on the relay's next poll, so a deadline runs up to `saga-forge.transport.poll-interval`
  after its due time, later if the relay is busy.
- Deadlines that came due while the application was down run once it is back. Each runs at most once; overdue
  messages are delivered in the order they were stored, not strictly in due order.
- A deadline the relay has already leased when the awaited event cancels it is still delivered, and ignored.

## Testing

`SagaFixture` moves its clock with `whenTimeElapses(Duration)` or `whenTimeReaches(Instant)` and then delivers
everything due, earliest first. `when(event)` never delivers merely-due messages:

```java
    @Test
    @DisplayName("an abandoned card challenge: the window closes, the stock is released and the order rejected")
    void abandonedChallengeTimesOut() {
        fixture.given(PLACED, RESERVED)
                .whenTimeElapses(Duration.ofMinutes(29))
                .expectNoCommands();

        fixture.whenTimeElapses(Duration.ofMinutes(1))
                .expectDeadlineExpired("paymentWindow")
                .expectCompensations("cancelCheckout")
                .expectCommands(new RejectOrderCommand("o-1", Order.RejectionReason.PAYMENT_TIMED_OUT),
                        new ReleaseStockCommand("o-1"))
                .expectCompensatedAfter("onPaymentWindowExpired")
                .expectStatus(SagaStatus.COMPENSATED)
                .expectOutcomeEvent(SagaCompensated.class, compensated ->
                        compensated.trigger().kind() == CompensationTriggerKind.DEADLINE_EXPIRED
                                && "paymentWindow".equals(compensated.trigger().deadlineName()));
    }
```

Source: `saga-forge-example/src/test/java/io/github/gkosharovdev/sagaforge/example/checkout/CheckoutSagaFixtureTest.java`.

More in [Testing](testing.md#deadlines): `expectOpenDeadline`, `expectNoOpenDeadline`, `expectDeadlineIgnored`,
`InstanceView.openDeadlines()` and `Builder.property(key, value)` for `${key}` durations.
