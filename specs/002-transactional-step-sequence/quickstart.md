# Quickstart: validating Saga Units of Work, Command Gateway, and Command Handlers

**Feature**: [spec.md](./spec.md) | **Contracts**: [contracts/](./contracts/) | **Data model**:
[data-model.md](./data-model.md)

This guide lists the runnable checks that prove the feature end to end. There is no production engine; the
behavioral checks run against the test-only `SagaTestHarness` (research §10).

## Prerequisites

- JDK 21 and Maven 3.9+.
- Repository root: `saga-forge`, branch `002-transactional-step-sequence`.

## Commands

```bash
mvn -B -pl saga-forge-core test                  # contracts, scanner rules, harness scenarios
mvn -B -pl saga-forge-example -am test           # canonical example passes validateAll
```

Both must end in `BUILD SUCCESS` with zero failures.

## The saga shape being validated

Signatures only. The real code is the test fixture
`saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/execution/fixtures/booking/`;
`saga-forge-example` shows the same model on its own `OrderProcessingSaga`.

```java
@Saga
public class CreateBookingSaga {
    @SagaId private String sagaId;
    private SagaContext context;          // recognized by type
    private CommandGateway commandGateway; // recognized by type

    @StartSaga @SagaStep(associationProperty = "bookingId")
    public void onBookingRequested(BookingRequestedEvent event) { /* putState + send(ClaimAvailability) */ }

    @SagaStep(associationProperty = "bookingId")
    public void onAvailabilityClaimed(AvailabilityClaimedEvent event) { /* getState + send(InitiatePayment) */ }

    @SagaStep(associationProperty = "bookingId")
    public void onPaymentConfirmed(PaymentConfirmedEvent event) { /* send(ConfirmBooking) */ }

    @EndSaga @SagaStep(associationProperty = "bookingId")
    public void onBookingConfirmed(BookingConfirmedEvent event) { }            // outcome "confirmed"

    @EndSaga @SagaStep(associationProperty = "bookingId")
    public void onPaymentDeclined(PaymentDeclinedEvent event) { /* send(ReleaseAvailability) */ } // outcome "declined"
}
```

Handlers: `Inventory` (plain `@Aggregate` loaded through a client `@AggregateLoader`, records
`AvailabilityClaimedEvent` with `DomainEvents.record`), `PaymentService` (service, records `PaymentInitiatedEvent`),
`Booking` (`@Aggregate` created by a constructor `@CommandHandler`, records `BookingConfirmedEvent`). Every event
is `@DomainEvent(aggregate = ...)` with one `@AggregateIdentifier`. All three live in the same `fixtures/booking/`
package as the saga.

## Validation scenarios

| # | Scenario | Expected outcome | Spec |
|---|---|---|---|
| 1 | `validateAll` over a model containing every invalid fixture in contracts/validation.md | One report listing every error, each with its documented message | SC-001, FR-026 |
| 2 | Three-step in-process saga, happy path | All three aggregates changed, `COMPLETED`, outcome recorded, all steps `COMPLETED` | US1-1 |
| 3 | Same saga, failure injected at step 1, 2, and 3 | Zero aggregate changes each time; `FAILED` with the failing step; context written before the failure still readable | SC-002, US1-2/3 |
| 4 | Steps declared out of event order | Steps run in event order | US1-5 |
| 5 | Service-hosted handler recording with `DomainEvents.record` | Next step triggered exactly as with an event recorded inside an aggregate | US2-7 |
| 6 | Step catches a failing handler's exception | Unit of work still rolled back | US2-9, FR-012 |
| 7 | `CreateBookingSaga` first unit of work | `RUNNING`; two steps `COMPLETED`, others `NOT_RUN`; *payment initiated* dispatched after commit | US3-1 |
| 8 | Resume from `persistedState()` in a fresh harness with *payment confirmed* / *payment declined* | Outcome `onBookingConfirmed` / `onPaymentDeclined`; untaken steps `NOT_RUN` | SC-003, SC-004 |
| 9 | Second unit of work fails | First unit of work's effects kept; `FAILED`; context from both units of work readable | US3-4/5 |
| 10 | Event for a `COMPLETED` saga, or for no instance | Reported as unexpected / unmatched; no effect | US3-6, FR-003 |
| 11 | Repository search for steps returning a command (README, `docs/`, `specs/`, `AGENTS.md`, example) | Zero matches | SC-005 |
| 12 | `Inventory` has no Saga Forge supertype or interface; loaded by its `@AggregateLoader`, saved by its `@AggregateSaver` | Handler runs on the loaded instance; saver called once; missing id → unit of work fails | US2-1, US2-11, FR-013 |
| 13 | `Booking` created by a constructor handler | New aggregate saved inside the unit of work | US2-10 |
| 14 | `DomainEvents.record` with no unit of work; `DomainEvents.capture` around an aggregate call | `IllegalStateException`; capture returns the recorded events | US2-13, FR-015 |
| 15 | Completed steps of `CreateBookingSaga` | Each step's record lists the `(aggregate type, id)` of the events it recorded | FR-004 |
