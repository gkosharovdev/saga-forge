# Quickstart: Saga Lifecycle and Context Abstractions

**Feature**: [spec.md](./spec.md) | **Data model**: [data-model.md](./data-model.md) |
**Contracts**: [contracts/saga-lifecycle.md](./contracts/saga-lifecycle.md),
[contracts/saga-context.md](./contracts/saga-context.md)

This walks through the two independent test shapes named in the spec (User Story 1 and User Story 2),
shaped as JUnit 5 + AssertJ scenarios against the shipped contracts in
`io.github.gkosharovdev.sagaforge.core.lifecycle`. The contracts are interfaces only: no production
`SagaContext`/`SagaLifecycle` implementation (and no engine) ships in `saga-forge-core`, so Scenario B runs
against a test double such as the test-only `InMemorySagaLifecycle`.

## Scenario A — step author reads/writes business state (User Story 1)

Extends the existing canonical example style (`saga-forge-example`'s `OrderProcessingSaga`): a saga class
declares a `SagaContext`-typed field (no annotation — recognized by type per `research.md` §1), alongside
the existing `@SagaId` field and `@SagaStep` methods.

```java
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import java.math.BigDecimal;

// @DomainEvent / @Command are TYPE-level annotations: they go on the event/command types,
// not on step parameters or return positions.
@DomainEvent record OrderCreated(String orderId, BigDecimal amount) {}
@DomainEvent record PaymentProcessed(String orderId) {}
@DomainEvent record OrderCompleted(String orderId) {}
@Command record ChargePayment(String orderId, BigDecimal amount) {}
@Command record CompleteOrder(String orderId, BigDecimal amount) {}

@Saga
class OrderProcessingSaga {
    @SagaId
    private String orderId;

    private SagaContext context; // no annotation: recognized by type; populated by the engine before each step call

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    ChargePayment handleOrderCreated(OrderCreated event) {
        context.putState("paymentAmount", event.amount());        // whole-value write (FR-002); null → IllegalArgumentException
        return new ChargePayment(event.orderId(), event.amount());
    }

    @SagaStep(associationProperty = "orderId")
    CompleteOrder processPayment(PaymentProcessed event) {
        // read a value a prior step wrote, by name and expected type (FR-001, FR-003)
        BigDecimal amount = context.getState("paymentAmount", BigDecimal.class)
            .orElseThrow(); // never null/default-confused with absent, per FR-004

        context.updateStateAttribute("shipment", "carrier", "UPS");     // FR-002a: field-level write
        context.updateStateAttribute("shipment", "trackingReady", true); // same structured value, 2nd field

        return new CompleteOrder(event.orderId(), amount);
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    void completeOrder(OrderCompleted event) {
        // "shipment" now holds {"carrier": "UPS", "trackingReady": true} — both fields present (SC-005)
    }
}
```

**Validation checklist** (items 1–7 map to spec Acceptance Scenarios 1–7; item 8 is the null-rejection
decision recorded in `contracts/saga-context.md`):
1. `processPayment` reads back exactly the `BigDecimal` `handleOrderCreated` wrote under `"paymentAmount"`.
2. `context.getState("doesNotExist", String.class)` → `Optional.empty()`, not `null`/`""`.
3. `context.getState("paymentAmount", Instant.class)` → `SagaStateTypeMismatchException`, not a cast error.
4. A second, concurrently-processed order's `SagaContext` never observes this order's `"paymentAmount"`.
5. After both `updateStateAttribute` calls, `context.getState("shipment", Map.class)` has both fields.
6. The first `updateStateAttribute("shipment", "carrier", "UPS")` call, when nothing was stored under
   `"shipment"` yet, creates `{"carrier": "UPS"}` rather than failing.
7. If `"shipment"` had instead been `putState("shipment", "already-shipped")` (a scalar), the following
   `updateStateAttribute("shipment", "carrier", "UPS")` → `NonStructuredStateValueException`.
8. `null` is never stored: `putState(name, null)` and `updateStateAttribute(name, field, null)` throw
   `IllegalArgumentException` (as do `null`/blank `name`/`field` and a `null` `type` on `getState`), so
   `Optional.empty()` from `getState` always means "never written".

## Scenario B — engine governs instance lifecycle (User Story 2)

Exercised directly against a `SagaLifecycle` test double/in-memory implementation — no real engine,
storage, or transport required.

```java
import io.github.gkosharovdev.sagaforge.core.lifecycle.DuplicateSagaInstanceException;
import io.github.gkosharovdev.sagaforge.core.lifecycle.InvalidSagaTransitionException;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaLifecycle;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance; // existing SPI record, reused as-is
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;   // existing SPI enum, reused as-is
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

SagaLifecycle lifecycle = ...; // test double per contracts/saga-lifecycle.md (e.g. test-only InMemorySagaLifecycle)

// 1. start
SagaInstance created = lifecycle.start(OrderProcessingSaga.class, "orderId", "order-123");
assertThat(created.status()).isEqualTo(SagaStatus.CREATED);

// 2. advance
SagaInstance running = lifecycle.advance(created.sagaId());
assertThat(running.status()).isEqualTo(SagaStatus.RUNNING);

// 3. complete — the engine (caller) has already verified the current step is @EndSaga;
//    SagaInstance has no current-step field, so SagaLifecycle does not check it
SagaInstance completed = lifecycle.complete(running.sagaId());
assertThat(completed.status()).isEqualTo(SagaStatus.COMPLETED);

// 4. reject further transitions on a terminal instance (InvalidSagaTransitionException.terminal)
assertThatThrownBy(() -> lifecycle.advance(completed.sagaId()))
    .isInstanceOfSatisfying(InvalidSagaTransitionException.class, ex -> {
        assertThat(ex.isNotFound()).isFalse();
        assertThat(ex.getCurrentStatus()).contains(SagaStatus.COMPLETED); // Optional<SagaStatus>
    });
assertThatThrownBy(() -> lifecycle.complete(completed.sagaId()))
    .isInstanceOf(InvalidSagaTransitionException.class);

// unknown sagaId is rejected too (InvalidSagaTransitionException.notFound)
assertThatThrownBy(() -> lifecycle.advance("does-not-exist"))
    .isInstanceOfSatisfying(InvalidSagaTransitionException.class, ex -> {
        assertThat(ex.isNotFound()).isTrue();
        assertThat(ex.getCurrentStatus()).isEmpty();
    });

// duplicate start on an already-active correlation is rejected
lifecycle.start(OrderProcessingSaga.class, "orderId", "order-456");
assertThatThrownBy(() -> lifecycle.start(OrderProcessingSaga.class, "orderId", "order-456"))
    .isInstanceOfSatisfying(DuplicateSagaInstanceException.class, ex -> {
        assertThat(ex.getSagaType()).isEqualTo(OrderProcessingSaga.class);
        assertThat(ex.getAssociationProperty()).isEqualTo("orderId");
        assertThat(ex.getAssociationValue()).isEqualTo("order-456");
    });

// null arguments → IllegalArgumentException (start/advance/complete)
assertThatThrownBy(() -> lifecycle.advance(null)).isInstanceOf(IllegalArgumentException.class);
```

## Scenario C — documentation accuracy (User Story 3)

Not a runtime test — a delivery checklist item for `/speckit-implement`:
- `AGENTS.md` "Current Status" and `docs/architecture.md` must list `SagaLifecycle`/`SagaContext` as
  implemented **only after** the types above exist in `saga-forge-core` with passing tests (FR-011).
- Until then, both documents must not claim they are implemented — verified by this feature's own
  completion, not by this quickstart (SC-003 is checked by re-reading the docs post-delivery).
