# Use Cases

Worked examples showing how the `@Saga` annotation model maps onto real multi-step, multi-aggregate flows —
including the failure, retry, and compensation scenarios that motivated several of the gaps flagged in
[architecture.md](architecture.md). These are drawn from production experience, not hypotheticals.

## 1. Happy path: `OrderProcessingSaga`

The simplest shape: three steps, one failure outcome and one compensation, living in `saga-forge-example` as a
continuously compiled reference. Every step is triggered by a Domain Event and returns `void`. A step dispatches Commands through the
`CommandGateway` field; a `@CommandHandler` on an Aggregate or a service handles each Command and records the
next Domain Event with `DomainEvents.record`. The `SagaContext` and `CommandGateway` fields are recognized by
type and populated by the engine.

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
        context.putState("orderAmount", event.amount());
        commandGateway.send(new ProcessPaymentCommand(event.orderId(), event.amount()));
    }

    @SagaStep(associationProperty = "orderId")
    public void processPayment(PaymentProcessedEvent event) {
        BigDecimal orderAmount = context.getState("orderAmount", BigDecimal.class).orElseThrow();
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
        BigDecimal amount = context.getState("orderAmount", BigDecimal.class).orElseThrow();
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
transaction, so it has to be undone: the engine then runs `compensatePayment` in its own unit of work, the refund is
handled with its own stable `CommandId` as the provider's idempotency key, and the saga ends `COMPENSATED` with
`rejectOrder` and `StockUnavailableEvent` recorded as its trigger. A repeated `StockUnavailableEvent` is `DUPLICATE`
and refunds nothing.

## 2. `CreateBookingSaga`: claim, pay, then one of two outcomes

A booking claims availability on an `Inventory` Aggregate, initiates payment, then waits for the payment
provider's callback. It mirrors the reference saga in `saga-forge-core`'s test fixtures and the README:

```java
@Saga
public class CreateBookingSaga {

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "bookingId")
    public void onBookingRequested(BookingRequestedEvent event) {
        context.putState("inventoryId", event.inventoryId());
        context.putState("amount", event.amount());
        commandGateway.send(new ClaimAvailability(
                event.inventoryId(), event.bookingId(), event.checkIn(), event.checkOut()));
    }

    @SagaStep(associationProperty = "bookingId")
    public void onAvailabilityClaimed(AvailabilityClaimedEvent event) {
        context.putState("claimId", event.claimId());
        long amount = context.getState("amount", Long.class).orElseThrow();
        commandGateway.send(new InitiatePayment(event.bookingId(), amount));
    }

    // No step reacts to PaymentInitiatedEvent: wait point, the first unit of work commits.

    @SagaStep(associationProperty = "bookingId")
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        context.putState("paymentId", event.paymentId());
        String claimId = context.getState("claimId", String.class).orElseThrow();
        commandGateway.send(new ConfirmBooking(event.bookingId(), claimId, event.paymentId()));
    }

    @EndSaga // outcome "confirmed"
    @SagaStep(associationProperty = "bookingId")
    public void onBookingConfirmed(BookingConfirmedEvent event) {
        // Outcome step: nothing left to dispatch.
    }

    @EndSaga // outcome "declined"
    @SagaStep(associationProperty = "bookingId")
    public void onPaymentDeclined(PaymentDeclinedEvent event) {
        String inventoryId = context.getState("inventoryId", String.class).orElseThrow();
        String claimId = context.getState("claimId", String.class).orElseThrow();
        commandGateway.send(new ReleaseAvailability(inventoryId, event.bookingId(), claimId));
    }
}
```

Handlers: `Inventory` handles `ClaimAvailability` and `ReleaseAvailability` with instance methods, loaded and
saved through `InventoryRepository`'s `@AggregateLoader`/`@AggregateSaver`; `PaymentService` handles
`InitiatePayment`; `Booking` is created by its `@CommandHandler` constructor taking `ConfirmBooking`. One
excerpt:

```java
@Aggregate
public class Inventory {
    @AggregateIdentifier
    private final String inventoryId;
    private final Map<String, Claim> claims = new LinkedHashMap<>();

    @CommandHandler
    public void handle(ClaimAvailability command) { // ClaimAvailability carries @AggregateIdentifier inventoryId
        String claimId = claimIdFor(command.bookingId());
        if (claims.containsKey(claimId)) {
            throw new IllegalStateException("Booking " + command.bookingId() + " already holds claim " + claimId);
        }
        claims.put(claimId, new Claim(claimId, command.bookingId(), command.checkIn(), command.checkOut()));
        DomainEvents.record(new AvailabilityClaimedEvent(inventoryId, command.bookingId(), claimId));
    }
}
```

The saga runs in two saga-scoped units of work:

- **First unit of work**: `BookingRequestedEvent` (published by application code through the event publisher)
  runs `onBookingRequested` and, in-process, `onAvailabilityClaimed`. `PaymentInitiatedEvent` triggers no Step,
  so the chain stops at a wait point: the claim and the payment commit, and the saga is persisted `RUNNING`.
- **Second unit of work**: the provider's callback arrives through the transport (possibly in another
  application instance), is correlated by `bookingId`, and runs from the persisted saga state. On
  `PaymentConfirmedEvent` it runs `onPaymentConfirmed` then `onBookingConfirmed`; on `PaymentDeclinedEvent` it
  runs `onPaymentDeclined`, which releases the claim. Either way the saga is `COMPLETED` with the reached
  `@EndSaga` step as its outcome, and the other path's Steps stay not run.

If the second unit of work fails (for example the `Booking` constructor throws), it is rolled back in full,
the first unit of work's claim and payment stay committed, and the saga ends `FAILED` with the failing Step and
cause recorded. `SagaContext` (`inventoryId`, `amount`, `claimId`, `paymentId`) is stored separately from the
unit of work and stays readable.

Undoing the first unit of work's effects is compensation ([006](../specs/006-saga-compensation/spec.md)). The
availability compensation targets `onBookingRequested`, the Step whose `ClaimAvailability` Command caused the effect
(an earlier draft of this document wrongly mapped it to `onAvailabilityClaimed`). With it, the decline becomes a
failure outcome instead of an `@EndSaga` step that releases the claim by hand, as in the README:

```java
    @StartCompensation // failure outcome "declined"
    @SagaStep(associationProperty = "bookingId")
    public void onPaymentDeclined(PaymentDeclinedEvent event) {
        context.putState("declineReason", event.reason());
    }

    @SagaCompensation(forStep = "onBookingRequested")
    public void releaseClaim(CompensationTrigger trigger) {
        commandGateway.send(new ReleaseAvailability(inventoryId(), bookingId(), claimId())); // read from SagaContext
    }
```

The decline then ends the saga `COMPENSATED`. A final failure of the second unit of work (the `Booking` constructor
throwing an exception marked `FinalFailure`) releases the claim the same way, with the failing step and cause as the
trigger; an unmarked failure still ends `FAILED`. If releasing the claim itself fails, the saga ends
`REQUIRES_INTERVENTION`, and nothing retries it.

Production lessons this example carries (see architecture.md's feature gaps):

- **Idempotency** ([005](../specs/005-step-retry/spec.md)). If `AvailabilityClaimedEvent` is delivered twice
  (at-least-once transport) after `onAvailabilityClaimed` completed, the engine reports `DUPLICATE` and does not
  dispatch `InitiatePayment` again. If the unit of work is run again before it committed, `InitiatePayment` keeps its
  `CommandId`, and a payment handler that passes it to the gateway as the idempotency key charges once.
- **Retry** ([005](../specs/005-step-retry/spec.md)). A transient failure (a timeout calling the payment gateway,
  marked `TransientFailure` or listed in the step's `retryOn`) is retried with backoff under the step's
  `@RetryPolicy`. Running out of attempts after the claim committed compensates it, with a `RETRIES_EXHAUSTED`
  trigger carrying the attempt count and last cause.
- **Domain rejection leads to compensation** ([006](../specs/006-saga-compensation/spec.md)). A domain rejection
  (card declined) is not retried: it is a `@StartCompensation` step, and the availability claim is compensated.

## 3. Deferred booking — five related processes, one shared policy family

This is the richest real-world example, generalized from a deferred-booking hardening effort. A single
booking's lifecycle is actually five related orchestrated processes, each independently triggerable and each
needing its own failure handling. They are presented here as the kind of multi-process family a `@Saga`
class per process is expected to model — not as a single saga, since each has a distinct start/end lifecycle
and distinct association properties.

### Process A — Create deferred booking request

| Step | Triggered by | Dispatches | On transient failure | On domain failure |
|---|---|---|---|---|
| Validate and record request | `DeferredBookingRequestedEvent` | `RecordBookingRequestCommand` | Retry with backoff | Fail terminally — no compensation needed, nothing committed yet |
| Notify owner | `BookingRequestRecordedEvent` | `NotifyOwnerCommand` | Retry with backoff | Requires intervention — booking exists but owner was never notified |

### Process B — Owner accepts

| Step | Triggered by | Dispatches | On transient failure | On domain failure |
|---|---|---|---|---|
| Record acceptance | `OwnerAcceptedEvent` | `ConfirmBookingCommand` | Retry with backoff | Requires intervention |
| Trigger payment initiation | `BookingConfirmedEvent` | `InitiateDeferredPaymentCommand` | Retry with backoff | Compensate: `CancelBookingConfirmationCommand` |

### Process C — Owner declines

| Step | Triggered by | Dispatches | On transient failure | On domain failure |
|---|---|---|---|---|
| Record decline and release hold | `OwnerDeclinedEvent` | `ReleaseBookingHoldCommand` | Retry with backoff | Requires intervention — hold may be stuck |

### Process D — Initiate deferred payment

| Step | Triggered by | Dispatches | On transient failure | On domain failure |
|---|---|---|---|---|
| Attempt payment | `DeferredPaymentDueEvent` | `ChargePaymentCommand`, idempotency keyed on a stable payment attempt identity | Retry with backoff, same payment attempt identity — never a new charge attempt | Compensate: `CancelBookingConfirmationCommand` (from Process B) |

The payment attempt identity (not just the saga ID) is what makes retries safe here: retrying the step must
reuse the same idempotency key at the payment gateway, or a transient failure followed by a retry risks a
double charge. This is a concrete argument for `@SagaStep` eventually supporting a declared idempotency key
expression, not just relying on the saga/step name pair.

### Process E — Expire deferred booking

| Step | Triggered by | Dispatches | On transient failure | On domain failure |
|---|---|---|---|---|
| Detect expiry | Scheduled work claim reaching its deadline | `ExpireBookingCommand` | Retry with backoff | Requires intervention |
| Release hold | `BookingExpiredEvent` | `ReleaseBookingHoldCommand` | Retry with backoff | Requires intervention |

Process E is triggered by a scheduled work claim rather than a domain event from another aggregate. The thin
scheduling adapter that detects the deadline should invoke exactly one complete use case
(`ExpireBookingCommand`) rather than sequencing the hold release itself — the sequencing belongs to the saga,
not the scheduler.

## What this family of processes demonstrates

- **One association property per process, not one per saga family.** Process A/B/C/D/E would each be their
  own `@Saga` class, each keyed by `bookingId` (or, for Process D, additionally by a payment attempt
  identity) — not a single mega-saga handling every booking state transition, because each process has its
  own distinct start and end.
- **Compensation targets a specific prior step, not "the saga."** `@SagaCompensation(forStep = "...")` always
  names the step whose effect needs undoing — e.g. Process D's compensation undoes Process B's confirmation,
  even though they're different `@Saga` classes. Cross-process compensation like this must dispatch a command
  aimed at the other process's aggregate; it cannot reach into the other saga instance directly.
- **Every domain failure resolves to compensate-or-intervene, never silent retry-forever.** This is the
  failure categorization from architecture.md's "Failure categorization" section, applied consistently across
  all five processes above.
- **Transient vs. domain failure must be distinguishable by the step author**, because the correct response
  (retry vs. compensate/intervene) is opposite. This is the strongest argument in this document for adding
  retry/backoff and failure-classification attributes to `@SagaStep` in a future iteration.

## 4. A restart in the middle of a saga (in-memory transport, 007)

A modulith runs the order, payment and shipping modules in one process. `OrderPlaced` starts the fulfilment saga,
which asks the payment module to charge the customer and waits. The payment module confirms with
`PaymentConfirmed`, recorded in its own unit of work.

- The confirmation is appended to the message log in the same transaction as the payment module's changes. If that
  transaction rolls back, there is nothing to deliver.
- The process is redeployed after the commit but before the relay delivered the confirmation. On start, the new
  process finds the confirmation unconsumed and delivers it; the saga completes as if nothing happened.
- If the process stopped after the saga's step committed but before the message was marked consumed, the
  confirmation is delivered again and the engine reports the step as a `DUPLICATE` — no second shipment is booked.
- A command marked `@Command(delivery = LOGGED)`, such as `ChargeCard`, is stored with the step that sent it and
  handled after that step commits; its handler receives the same `CommandId` on every delivery and passes it to the
  payment provider as the idempotency key.
- A message that keeps failing is set aside after ten attempts, without holding up other orders, and an operator can
  deliver it again once the cause is fixed.

