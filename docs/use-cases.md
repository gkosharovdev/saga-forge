# Use Cases

Worked examples showing how the `@Saga` annotation model maps onto real multi-step, multi-aggregate flows —
including the failure, retry, and compensation scenarios that motivated several of the gaps flagged in
[architecture.md](architecture.md). These are drawn from production experience, not hypotheticals.

## 1. Happy path: `OrderProcessingSaga`

The simplest possible shape — three steps, one compensation — living in `saga-forge-example` as a continuously
compiled reference:

```java
@Saga
public class OrderProcessingSaga {

    @SagaId
    private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public ProcessPaymentCommand handleOrderCreated(OrderCreatedEvent event) {
        return new ProcessPaymentCommand(event.orderId());
    }

    @SagaStep(associationProperty = "orderId")
    public ReserveStockCommand processPayment(PaymentProcessedEvent event) {
        return new ReserveStockCommand(event.orderId());
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void completeOrder(OrderCompletedEvent event) {
        // terminal step — saga completes
    }

    @SagaCompensation(forStep = "processPayment")
    public void compensatePayment() {
        // undo the payment side effect if a later step fails
    }
}
```

Use this as the baseline for understanding the annotation contract before reading the richer examples below.

## 2. `CreateBookingSaga` — claim, then pay

A booking needs availability claimed on an Inventory aggregate before payment is attempted, matching the
README's canonical example:

```java
@Saga
public class CreateBookingSaga {

    @SagaId
    private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "bookingId")
    public ClaimAvailabilityCommand onBookingRequested(BookingRequestedEvent event) {
        return new ClaimAvailabilityCommand(event.bookingId(), event.dates());
    }

    @SagaStep(associationProperty = "bookingId")
    public InitiatePaymentCommand onAvailabilityClaimed(AvailabilityClaimedEvent event) {
        return new InitiatePaymentCommand(event.bookingId());
    }

    @EndSaga
    @SagaStep(associationProperty = "bookingId")
    public void onPaymentInitiated(PaymentInitiatedEvent event) {
        // terminal step — saga completes
    }

    @SagaCompensation(forStep = "onAvailabilityClaimed")
    public ReleaseAvailabilityCommand compensateAvailability(AvailabilityClaimedEvent event) {
        return new ReleaseAvailabilityCommand(event.bookingId());
    }
}
```

Two production lessons this example carries, even though neither is enforced by `saga-forge-core` yet (see
architecture.md's "Idempotency and retry" gap):

- If `AvailabilityClaimedEvent` is delivered twice (at-least-once transport), `onAvailabilityClaimed` must not
  re-issue `InitiatePaymentCommand` a second time. This requires a uniqueness check on `(sagaId, stepName)`
  at the storage layer before the step runs — not yet modeled by `@SagaStep`.
- If payment initiation fails with a transient error (timeout calling the payment gateway), the step should
  retry with backoff; if it fails with a domain rejection (card declined), it should not retry — it should
  compensate the availability claim instead. `@SagaStep` has no attributes to distinguish these yet.

## 3. Deferred booking — five related processes, one shared policy family

This is the richest real-world example, generalized from a deferred-booking hardening effort. A single
booking's lifecycle is actually five related orchestrated processes, each independently triggerable and each
needing its own failure handling. They are presented here as the kind of multi-process family a `@Saga`
class per process is expected to model — not as a single saga, since each has a distinct start/end lifecycle
and distinct association properties.

### Process A — Create deferred booking request

| Step | Triggered by | Emits | On transient failure | On domain failure |
|---|---|---|---|---|
| Validate and record request | `DeferredBookingRequestedEvent` | `RecordBookingRequestCommand` | Retry with backoff | Fail terminally — no compensation needed, nothing committed yet |
| Notify owner | `BookingRequestRecordedEvent` | `NotifyOwnerCommand` | Retry with backoff | Requires intervention — booking exists but owner was never notified |

### Process B — Owner accepts

| Step | Triggered by | Emits | On transient failure | On domain failure |
|---|---|---|---|---|
| Record acceptance | `OwnerAcceptedEvent` | `ConfirmBookingCommand` | Retry with backoff | Requires intervention |
| Trigger payment initiation | `BookingConfirmedEvent` | `InitiateDeferredPaymentCommand` | Retry with backoff | Compensate: `CancelBookingConfirmationCommand` |

### Process C — Owner declines

| Step | Triggered by | Emits | On transient failure | On domain failure |
|---|---|---|---|---|
| Record decline and release hold | `OwnerDeclinedEvent` | `ReleaseBookingHoldCommand` | Retry with backoff | Requires intervention — hold may be stuck |

### Process D — Initiate deferred payment

| Step | Triggered by | Emits | On transient failure | On domain failure |
|---|---|---|---|---|
| Attempt payment | `DeferredPaymentDueEvent` | `ChargePaymentCommand`, idempotency keyed on a stable payment attempt identity | Retry with backoff, same payment attempt identity — never a new charge attempt | Compensate: `CancelBookingConfirmationCommand` (from Process B) |

The payment attempt identity (not just the saga ID) is what makes retries safe here: retrying the step must
reuse the same idempotency key at the payment gateway, or a transient failure followed by a retry risks a
double charge. This is a concrete argument for `@SagaStep` eventually supporting a declared idempotency key
expression, not just relying on the saga/step name pair.

### Process E — Expire deferred booking

| Step | Triggered by | Emits | On transient failure | On domain failure |
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
  even though they're different `@Saga` classes. Cross-process compensation like this must emit a command
  aimed at the other process's aggregate; it cannot reach into the other saga instance directly.
- **Every domain failure resolves to compensate-or-intervene, never silent retry-forever.** This is the
  failure categorization from architecture.md's "Failure categorization" section, applied consistently across
  all five processes above.
- **Transient vs. domain failure must be distinguishable by the step author**, because the correct response
  (retry vs. compensate/intervene) is opposite. This is the strongest argument in this document for adding
  retry/backoff and failure-classification attributes to `@SagaStep` in a future iteration.
