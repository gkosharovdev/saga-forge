# Quickstart: Saga Test Fixture

Validation scenarios proving the feature end to end. API: [contracts/fixture-api.md](contracts/fixture-api.md).

## Prerequisites

- Java 21, Maven. No database or container is needed for any scenario.
- Build: `mvn -B -pl saga-forge-test -am clean verify`.

## Scenarios

1. **One step, record-and-answer** (US1, SC-001): in `saga-forge-example`, a test over `OrderProcessingSaga` given
   `OrderCreatedEvent(o-1, 10)` when `PaymentProcessedEvent(payment-1, o-1, 10)` expects
   `ReserveStockCommand(o-1)`, context `paymentId = payment-1`, step `processPayment`, status `RUNNING`. The test body
   is at most 15 lines and needs no database.
2. **Compensation** (US2): given `OrderCreatedEvent(o-2)` and `PaymentProcessedEvent(..., o-2, ...)`, when
   `StockUnavailableEvent(o-2)`: expect compensation `compensatePayment` dispatching `RefundPaymentCommand` with the
   stored payment id and amount, status `COMPENSATED`, trigger `rejectOrder`.
3. **Retry without waiting** (US2, SC-005): `failCommand(ProcessPaymentCommand, timeout, 2)` in handler mode with
   `PaymentService`; `when OrderCreatedEvent(o-3)`: step `handleOrderCreated` completes after 3 attempts; every attempt
   used the same `CommandId`; the fixture clock advanced by the backoff (200 ms + 400 ms); wall time < 1 s.
4. **Whole flow with handlers** (US4): `handlers(paymentService, orderRepository)` (or the aggregate store with
   `givenAggregates(Order(o-5, inStock))`): `when OrderCreatedEvent(o-5)` → `COMPLETED`, order `o-5` `COMPLETED`.
5. **Correlation and duplicates** (US3): two orders started; an event for `o-4` advances only `o-4`; the same event
   again → `DUPLICATE`, no command.
6. **Failure message** (US6, SC-004): a deliberately wrong expectation fails with the saga type, association value,
   event, steps and the expected/actual commands.
7. **Context value rule** (FR-019, SC-007): a saga storing a `BigDecimal` fails its fixture test naming the key and
   type; `DurableContextValueContractTest` in `saga-forge-storage-postgres` passes in the default build (it checks the rule against `JsonCodec`).
8. **Parity** (SC-003): `FixtureParityTest` reports no differences between the fixture and a hand-assembled runtime.
9. **Spring application** (US5, SC-006): in `saga-forge-example-spring`, a fixture test over its saga and
   `PaymentService` bean, built by hand with its `PaymentProvider` and handed to the fixture, runs with no database.

## Expected outcome

All scenarios pass in `mvn -B clean verify`; the runtime's,
storage's and starter's existing suites pass unchanged.
