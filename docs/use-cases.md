# Use Cases

Worked examples of the annotation model on multi-step, multi-aggregate flows, with their failure, retry and
compensation paths. Every saga shown here compiles in `saga-forge-example` and is covered by its tests.

## 1. Ordering and checkout: `CheckoutSaga` and `FulfilmentSaga`

A web shop takes an order, holds the stock, authorises the card, ships the parcel, and gives the money back when the
parcel cannot be delivered. Two sagas run the process, handing over at `OrderConfirmedEvent`:

```text
CheckoutSaga    OrderPlaced ─► StockReserved ─► PaymentAuthorized ─► ConfirmOrder ─┐
                     └─ StockUnavailable / PaymentDeclined ─► cancelCheckout       │ OrderConfirmedEvent
                     └─ payment window expires ─► cancelCheckout                   │
FulfilmentSaga  OrderConfirmed ─► ShipmentDispatched ─► ShipmentDelivered  ◄───────┘
                     └─ DeliveryImpossible ─► cancelOrder (refund or void, restock, cancel)
                     └─ courier assignment expires ─► cancelOrder
```

Both sagas also wait against the clock. Checkout gives the customer a payment window to pass the card challenge, and
fulfilment gives Logistics a time to find a courier. Each is a named saga deadline: a step starts it with
`@StartDeadline`, a `@DeadlineExpired` timeout step runs if it comes due, and it closes when the awaited step runs or
the instance leaves `RUNNING`.

| Context | Owns | Talks to the sagas through |
|---|---|---|
| Ordering | the `Order` aggregate | in-process `@CommandHandler`s, in the saga's unit of work |
| Inventory | stock and reservations | outbox commands in, `StockReservedEvent` / `StockUnavailableEvent` out |
| Payments | authorisation, capture, void, refund at the card provider | `AuthorizePaymentCommand` in the unit of work, the rest outbox commands |
| Logistics | shipments, couriers, delivery attempts | outbox commands in, `ShipmentDispatchedEvent` / `ShipmentDeliveredEvent` / `DeliveryImpossibleEvent` out |

The code is in `saga-forge-example`, package `io.github.gkosharovdev.sagaforge.example.checkout`. `InventoryService`,
`PaymentService` and `LogisticsService` stand in for the other services and record the events those services would
publish.

### `CheckoutSaga`

```java
@Saga
public class CheckoutSaga {

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

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

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void onPaymentAuthorized(PaymentAuthorizedEvent event) {
        String reservationId = context.getState("reservationId", String.class).orElseThrow();
        commandGateway.send(new ConfirmOrderCommand(event.orderId(), reservationId, event.paymentId()));
    }

    @StartCompensation
    @SagaStep(associationProperty = "orderId")
    public void onStockUnavailable(StockUnavailableEvent event) {
        context.putState("unavailableSku", event.sku());
    }

    @StartCompensation
    @SagaStep(associationProperty = "orderId")
    public void onPaymentDeclined(PaymentDeclinedEvent event) {
        context.putState("declineReason", event.reason());
    }

    @StartCompensation
    @DeadlineExpired("paymentWindow")
    @SagaStep
    public void onPaymentWindowExpired(DeadlineReached deadline) {
        context.putState("timedOutAt", deadline.dueAt().toString());
    }

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

    // orderId() and amount() read the SagaContext.
}
```

Source: `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/checkout/CheckoutSaga.java`.

What Saga Forge does with it:

- **Correlation.** Every event carries `orderId`, and `associationProperty = "orderId"` routes it to the right
  instance. No lookup code.
- **Outbox delivery.** `ReserveStockCommand` is `@Command(delivery = CommandDelivery.OUTBOX)`: it is stored with
  `onOrderPlaced`'s unit of work and sent once that commits. The saga then waits `RUNNING` for Inventory, holding no
  thread and no transaction.
- **One unit of work per wait.** Inventory's `StockReservedEvent` runs `onStockReserved`. The payment provider answers
  within the call, so `PaymentAuthorizedEvent` runs the `@EndSaga` step `onPaymentAuthorized` in the same transaction,
  and the order's confirmation commits with it.
- **Retry with idempotency.** A provider timeout implements `TransientFailure`, so the whole unit of work runs again
  under `@RetryPolicy`. `PaymentService` passes the `CommandId` to the provider as its idempotency key, so three
  attempts authorise once.
- **Compensation.** A `@StartCompensation` step names the failure outcome. The runtime then runs the compensation of
  every completed step, here `cancelCheckout` for `onOrderPlaced`, in its own unit of work. The `CompensationTrigger`
  says which outcome it was, and the order is rejected with the matching reason.
- **Payment window.** `onStockReserved` starts the `paymentWindow` deadline, its length read from the `SagaContext`
  value `onOrderPlaced` wrote: 30 minutes, or 10 at express checkout. The deadline is stored as a message with a due
  time in the same transaction. If neither `PaymentAuthorizedEvent` nor `PaymentDeclinedEvent` arrives in time, the
  timeout step `onPaymentWindowExpired` runs with a `DeadlineReached` and starts compensation; the trigger's kind is
  `DEADLINE_EXPIRED`, and the order is rejected `PAYMENT_TIMED_OUT`. The deadline needs no `cancelledBy`: both answers
  end the saga, and leaving `RUNNING` closes every open deadline.
- **Hand-over.** The `Order` aggregate records `OrderConfirmedEvent` while it handles `ConfirmOrderCommand`. No
  checkout step takes that event, so it leaves through the outbox and starts `FulfilmentSaga`.

### `FulfilmentSaga`

```java
@Saga
public class FulfilmentSaga {

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    @StartDeadline(name = "courierAssignment", duration = "${checkout.courier-assignment:PT2H}",
            cancelledBy = "onShipmentDispatched")
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        context.putState("orderId", event.orderId());
        context.putState("reservationId", event.reservationId());
        context.putState("paymentId", event.paymentId());
        commandGateway.send(new RequestShipmentCommand(event.orderId(), event.lines(), event.deliveryAddress()));
    }

    @SagaStep(associationProperty = "orderId")
    public void onShipmentDispatched(ShipmentDispatchedEvent event) {
        context.putState("shipmentId", event.shipmentId());
        commandGateway.send(new CapturePaymentCommand(event.orderId(), paymentId()));
        commandGateway.send(new CommitStockCommand(event.orderId(), reservationId()));
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void onShipmentDelivered(ShipmentDeliveredEvent event) {
        // Nothing left to do: the money and the stock were taken at dispatch.
    }

    @StartCompensation
    @SagaStep(associationProperty = "orderId")
    public void onDeliveryImpossible(DeliveryImpossibleEvent event) {
        context.putState("cancellationReason", event.reason());
    }

    @StartCompensation
    @DeadlineExpired("courierAssignment")
    @SagaStep
    public void onCourierNotAssigned() {
        context.putState("cancellationReason", "no courier assigned");
    }

    @SagaCompensation(forStep = "onOrderConfirmed")
    public void cancelOrder() {
        String orderId = context.getState("orderId", String.class).orElseThrow();
        String reason = context.getState("cancellationReason", String.class).orElse("not deliverable");
        commandGateway.send(new ReleasePaymentCommand(orderId, paymentId()));
        commandGateway.send(new ReleaseStockCommand(orderId));
        commandGateway.send(new CancelOrderCommand(orderId, reason));
    }
}
```

Source: `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/checkout/FulfilmentSaga.java`.

The saga waits between Logistics' events for as long as the delivery takes; its state is in the database, not in
memory. When Logistics reports `DeliveryImpossibleEvent`, `cancelOrder` gives everything back. The handlers keep the
business rules: Payments refunds a captured payment and voids one that was only authorised.

Logistics must find a courier within `checkout.courier-assignment`, two hours unless the property says otherwise.
`onShipmentDispatched` is named in `cancelledBy`, so a dispatch in time closes the deadline while the saga goes on
waiting for the delivery. Otherwise `onCourierNotAssigned` runs and `cancelOrder` voids the payment and releases the
stock. The timeout step takes no parameter: it needs nothing from the `DeadlineReached`. A deadline that comes due
while the application is down runs once it is back, in due order.

```java
    @CommandHandler
    public void handle(ReleasePaymentCommand command) {
        if (provider.state(command.paymentId()) == PaymentProvider.State.CAPTURED) {
            provider.refund(command.paymentId());
        } else {
            provider.voidAuthorization(command.paymentId());
        }
    }
```

Source: `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/checkout/PaymentService.java`.

### Every path

| Scenario | Compensations | Money | Stock | Ends |
|---|---|---|---|---|
| Delivered | - | captured | committed | both `COMPLETED` |
| Out of stock | `cancelCheckout` | untouched | never held | checkout `COMPENSATED` |
| Card declined | `cancelCheckout` | never authorised | released | checkout `COMPENSATED` |
| Provider times out twice | - | authorised once | held | as delivered |
| Provider down past the retries | `cancelCheckout` | never authorised | released | checkout `COMPENSATED` |
| Card challenge abandoned, payment window closes | `cancelCheckout` | never authorised | released | checkout `COMPENSATED` |
| No courier within the assignment time | `cancelOrder` | voided | released | fulfilment `COMPENSATED` |
| No courier for the address | `cancelOrder` | voided | released | fulfilment `COMPENSATED` |
| Returned after failed attempts | `cancelOrder` | refunded | booked back in | fulfilment `COMPENSATED` |
| Inventory's event delivered twice | - | - | - | reported `DUPLICATE`, nothing runs |

Each ending also publishes an outcome event (`SagaCompleted`, `SagaCompensated`, `SagaFailed`) that listeners use,
for example to e-mail the customer.

### Testing it

The saga test fixture plays the other services. In record-and-answer mode the test publishes the events Inventory or
Payments would send and checks what the saga does next, with no database or transport:

```java
    @Test
    @DisplayName("declined card: the held stock is released and the order rejected")
    void declinedPaymentReleasesStock() {
        fixture.given(PLACED, new StockReservedEvent("reservation-o-1", "o-1"))
                .when(new PaymentDeclinedEvent("declined-1", "o-1", "insufficient funds"))
                .expectCompensations("cancelCheckout")
                .expectCommands(new RejectOrderCommand("o-1", Order.RejectionReason.PAYMENT_NOT_AUTHORIZED),
                        new ReleaseStockCommand("o-1"))
                .expectCompensatedAfter("onPaymentDeclined")
                .expectOutcomeEvent(SagaCompensated.class);
    }
```

Source: `saga-forge-example/src/test/java/io/github/gkosharovdev/sagaforge/example/checkout/CheckoutSagaFixtureTest.java`.

A deadline test moves the fixture's clock on with `whenTimeElapses`. Nothing happens a minute before the window
closes; at 30 minutes the timeout step runs:

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

`FulfilmentSagaFixtureTest` does the same for fulfilment, including the courier deadline. `CheckoutAndFulfilmentTest` runs both sagas with the real
handlers, from a placed order to a refund.

## 2. The smallest shape: `OrderProcessingSaga`

Three steps, one failure outcome and one compensation, all in one unit of work. Every step is triggered by a Domain
Event and returns `void`. A step dispatches Commands through the `CommandGateway` field; a `@CommandHandler` on an
Aggregate or a service handles each Command and records the next Domain Event with `DomainEvents.record`. The
`SagaContext` and `CommandGateway` fields are recognized by type and populated by the runtime.

```java
@Saga
public class OrderProcessingSaga {

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "orderId", retry = @RetryPolicy(maxAttempts = 3, initialBackoffMillis = 200,
            multiplier = 2.0, maxBackoffMillis = 1_000)) // a payment-provider timeout is retried
    public void handleOrderCreated(OrderCreatedEvent event) {
        context.putState("orderAmount", event.amount().toPlainString());   // a JSON scalar, as storage needs
        commandGateway.send(new ProcessPaymentCommand(event.orderId(), event.amount()));
    }

    @SagaStep(associationProperty = "orderId")
    public void processPayment(PaymentProcessedEvent event) {
        BigDecimal orderAmount = new BigDecimal(context.getState("orderAmount", String.class).orElseThrow());
        if (orderAmount.compareTo(event.amount()) != 0) {
            throw new IllegalStateException("Payment does not match order amount"); // rolls back the payment too
        }
        context.putState("paymentId", event.paymentId());
        commandGateway.send(new ReserveStockCommand(event.orderId()));
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void completeOrder(OrderCompletedEvent event) {
        // Outcome step: nothing left to dispatch.
    }

    @StartCompensation // failure outcome "rejectOrder": the stock is not there after all
    @SagaStep(associationProperty = "orderId")
    public void rejectOrder(StockUnavailableEvent event) {
        // The trigger records why; the compensation reads what it needs from the SagaContext.
    }

    @SagaCompensation(forStep = "processPayment") // runs in its own unit of work once the saga compensates
    public void compensatePayment(CompensationTrigger trigger) {
        String paymentId = context.getState("paymentId", String.class).orElseThrow();
        BigDecimal amount = new BigDecimal(context.getState("orderAmount", String.class).orElseThrow());
        commandGateway.send(new RefundPaymentCommand(sagaId, paymentId, amount)); // keyed by its CommandId
    }
}
```

The two Command Handlers, abbreviated. `PaymentService` is a service-hosted handler; `Order` is an
`@Aggregate` with an instance-method handler, loaded and saved by the client's own `OrderRepository`:

```java
public class PaymentService {
    @CommandHandler
    public void handle(ProcessPaymentCommand command, CommandId commandId) {
        // The identity is the same on every retry and redelivery: the provider charges once per key. A timeout
        // throws PaymentGatewayTimeoutException, which implements TransientFailure, so the step is retried.
        String paymentId = provider.charge(commandId.value(), command.amount());
        DomainEvents.record(new PaymentProcessedEvent(paymentId, command.orderId(), command.amount()));
    }
}

@Aggregate
public class Order {
    @AggregateIdentifier
    private final String orderId;
    private Status status = Status.CREATED;

    @CommandHandler
    public void handle(ReserveStockCommand command) { // ReserveStockCommand carries @AggregateIdentifier orderId
        if (status != Status.CREATED) {
            throw new IllegalStateException("Order " + orderId + " is already " + status);
        }
        if (!inStock) {                          // a business failure, recorded rather than thrown
            status = Status.OUT_OF_STOCK;
            DomainEvents.record(new StockUnavailableEvent(orderId));
            return;
        }
        status = Status.COMPLETED;
        DomainEvents.record(new OrderCompletedEvent(orderId));
    }
}

public class OrderRepository {
    @AggregateLoader
    public Order load(String orderId) { ... }  // null -> AggregateNotFoundException, unit of work fails

    @AggregateSaver
    public void save(Order order) { ... }
}
```

Every Domain Event in this chain triggers a Step of the same saga instance, so the whole chain runs in one
saga-scoped unit of work that commits when the `@EndSaga` step `completeOrder` completes: the payment and the
order change commit together or not at all. A failure anywhere rolls the unit of work back and the saga ends
`FAILED`; values already written to `SagaContext` stay readable.

If the order turns out to be out of stock, `Order` records `StockUnavailableEvent` instead, and the `@StartCompensation`
step `rejectOrder` handles it in the same unit of work. That unit of work commits with `processPayment` completed and
the instance `COMPENSATING` ([006](../specs/006-saga-compensation/spec.md)). The provider's charge happened outside the
transaction, so it has to be undone: the runtime then runs `compensatePayment` in its own unit of work, the refund is
handled with its own stable `CommandId` as the provider's idempotency key, and the saga ends `COMPENSATED` with
`rejectOrder` and `StockUnavailableEvent` recorded as its trigger. A repeated `StockUnavailableEvent` is `DUPLICATE`
and refunds nothing.

### Testing it

The saga test fixture (`saga-forge-test`) runs the saga with no database or transport. The compensation path, in
record-and-answer mode, where the test publishes the events the handlers would have recorded:

```java
    @Test
    @DisplayName("compensation path: missing stock refunds the payment")
    void missingStockRefundsThePayment() {
        SagaFixture.of(OrderProcessingSaga.class).build()
                .given(new OrderCreatedEvent("o-2", AMOUNT), new PaymentProcessedEvent("payment-2", "o-2", AMOUNT))
                .when(new StockUnavailableEvent("o-2"))
                .expectCompensations("compensatePayment")
                .expectCommands(new RefundPaymentCommand("saga-1", "payment-2", AMOUNT))
                .expectCompensatedAfter("rejectOrder")
                .expectOutcomeEvent(SagaCompensated.class, compensated ->
                        compensated.trigger().kind() == CompensationTriggerKind.FAILURE_OUTCOME
                                && compensated.trigger().stepName().equals("rejectOrder"));
    }
```

Source: `saga-forge-example/src/test/java/io/github/gkosharovdev/sagaforge/example/OrderProcessingSagaFixtureTest.java`.

The retried payment runs the real `PaymentService` with `.handlers(new PaymentService(provider))`: two provider
timeouts, three attempts of `handleOrderCreated`, one charge, and the same `CommandId` on every attempt. The retry
waits advance the fixture's clock instead of sleeping. The same test class covers the happy path and the outcome
events of each ending.

## 3. A restart in the middle of a checkout

`CheckoutSaga` runs over the in-memory transport, with its messages in Postgres (`saga_message_store`):

- `onOrderPlaced` commits with `ReserveStockCommand` appended to the message store in the same transaction. If the
  step rolls back, nothing is sent.
- The process is redeployed after that commit but before the relay delivered the command. On start, the new process
  finds it undelivered and delivers it; Inventory reserves the stock and the checkout carries on as if nothing
  happened.
- `StockReservedEvent` is appended in the same transaction as Inventory's reservation. If the process stops after
  `onStockReserved` committed but before the event was marked consumed, the event is delivered again and the runtime
  reports `DUPLICATE`: the card is not authorised twice.
- Every delivery of an outbox command carries the same `CommandId`, which the handler passes on as the idempotency key,
  so a redelivered `RequestShipmentCommand` books one shipment.
- A message that keeps failing is set aside after ten attempts (`saga-forge.transport.max-attempts`), without holding up
  other orders, and an operator can deliver it again once the cause is fixed.
