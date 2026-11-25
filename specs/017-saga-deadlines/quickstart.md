# Quickstart: Saga Deadlines (017)

Validation guide plus the example sagas inline. The example code lands in `saga-forge-example`'s `checkout` package,
the README and `docs/use-cases.md` §1 through the example and docs tasks in `tasks.md`.

## Example: payment window in `CheckoutSaga`

`OrderPlacedEvent` gains `boolean express`; the window is per order, so it comes from the context.

```java
@StartSaga
@SagaStep(associationProperty = "orderId")
public void onOrderPlaced(OrderPlacedEvent event) {
    context.putState("orderId", event.orderId());
    context.putState("amount", event.amount().toPlainString());
    context.putState("paymentWindow", event.express() ? "PT10M" : "PT30M");
    commandGateway.send(new ReserveStockCommand(event.orderId(), event.lines()));
}

/** Stock held: authorise the payment; the customer now has the payment window to pass the card challenge. */
@SagaStep(associationProperty = "orderId",
        retry = @RetryPolicy(maxAttempts = 3, initialBackoffMillis = 200, multiplier = 2.0))
@StartDeadline(name = "paymentWindow", durationFrom = "paymentWindow")
public void onStockReserved(StockReservedEvent event) {
    context.putState("reservationId", event.reservationId());
    commandGateway.send(new AuthorizePaymentCommand(event.orderId(), amount()));
}

/** The customer abandoned the card challenge: give the stock back and reject the order. */
@StartCompensation
@DeadlineExpired("paymentWindow")
@SagaStep
public void onPaymentWindowExpired(DeadlineReached deadline) {
    context.putState("timedOutAt", deadline.dueAt().toString());
}

@SagaCompensation(forStep = "onOrderPlaced")
public void cancelCheckout(CompensationTrigger trigger) {
    Order.RejectionReason reason = switch (trigger.kind()) {
        case DEADLINE_EXPIRED -> Order.RejectionReason.PAYMENT_TIMED_OUT;
        default -> "onStockUnavailable".equals(trigger.stepName())
                ? Order.RejectionReason.OUT_OF_STOCK
                : Order.RejectionReason.PAYMENT_NOT_AUTHORIZED;
    };
    commandGateway.send(new ReleaseStockCommand(orderId()));
    commandGateway.send(new RejectOrderCommand(orderId(), reason));
}
```

`onPaymentAuthorized` (`@EndSaga`) needs no `cancelledBy`: leaving `RUNNING` cancels every open deadline.

## Example: courier-assignment deadline in `FulfilmentSaga`

```java
@SagaStep(associationProperty = "orderId")
@StartDeadline(name = "courierAssignment", duration = "${checkout.courier-assignment:PT2H}",
        cancelledBy = "onShipmentDispatched")
public void onPaymentCaptured(PaymentCapturedEvent event) {          // the step that sends RequestShipmentCommand
    commandGateway.send(new RequestShipmentCommand(orderId(), address()));
}

@SagaStep(associationProperty = "orderId")
public void onShipmentDispatched(ShipmentDispatchedEvent event) {   // courier found; delivery may take days
    context.putState("trackingId", event.trackingId());
}

@StartCompensation
@DeadlineExpired("courierAssignment")
@SagaStep
public void onCourierNotAssigned() {
}
```

(Step and event names follow the current `FulfilmentSaga`; adjust to the real step that sends `RequestShipmentCommand`.)

## Example fixture tests

```java
@Test
void abandonedCardChallengeRejectsTheOrder() {
    fixture.given().event(orderPlaced("o-1", /* express */ false))
           .when().event(new StockReservedEvent("o-1", "r-1"))
           .then().expectOpenDeadline("paymentWindow");

    fixture.when().timeElapses(Duration.ofMinutes(30))
           .then().expectDeadlineExpired("paymentWindow")
                  .expectStatus(SagaStatus.COMPENSATED)
                  .expectCommand(new RejectOrderCommand("o-1", Order.RejectionReason.PAYMENT_TIMED_OUT))
                  .expectOutcomeEvent(SagaCompensated.class);
}

@Test
void paymentInTimeNeverTimesOut() {
    fixture.given().event(orderPlaced("o-2", true)).event(new StockReservedEvent("o-2", "r-2"))
           .when().event(new PaymentAuthorizedEvent("o-2", "p-2"))
           .then().expectNoOpenDeadline("paymentWindow");
    fixture.when().timeElapses(Duration.ofHours(1)).then().expectNoCommands();
}

@Test
void courierAssignedStopsTheDeadlineWhileDeliveryContinues() { /* advance 3h after ShipmentDispatched: still RUNNING */ }
```

## Validation scenarios

| # | Scenario | Command | Expected |
|---|----------|---------|----------|
| 1 | Store contract (in-memory) | `mvn -pl saga-forge-transport-inmemory test` | scheduling cases of `MessageStoreContract` pass |
| 2 | Store contract + 10k overdue restart (Postgres) | `mvn -pl saga-forge-storage-postgres -Pcontainer-tests verify` | pass (SC-004) |
| 3 | Scanner R24–R29 | `mvn -pl saga-forge-core test` | one failing-model test per rule, valid example models pass |
| 4 | Runtime: fire once, cancel, ignore when not RUNNING, randomized interleavings | `mvn -pl saga-forge-runtime test` | pass (SC-001, SC-002) |
| 5 | Startup rejection with `ImmediateDispatchOutbox` / Modulith interop | runtime + starter tests | `SagaRuntimeStartupException`; failure analyzer text |
| 6 | Example fixture tests | `mvn -pl saga-forge-example test` | checkout and fulfilment deadline tests pass |
| 7 | Restart before due (Postgres, Spring example) | `mvn -pl saga-forge-example-spring -Pcontainer-tests verify` | timeout step runs once after restart (SC-001) |
| 8 | Whole build + Javadoc | `mvn -Pcontainer-tests verify` then `mvn -P release -Dgpg.skip -DskipTests install` | green (SC-006) |
| 9 | Docs | `docs/guide/build-llms-full.sh` and `mkdocs build --strict` | no diff in CI, site builds |
