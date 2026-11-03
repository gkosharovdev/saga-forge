# Quickstart: Saga Lifecycle and Context Abstractions

**Feature**: [spec.md](./spec.md) | **Data model**: [data-model.md](./data-model.md) |
**Contracts**: [contracts/saga-lifecycle.md](./contracts/saga-lifecycle.md),
[contracts/saga-context.md](./contracts/saga-context.md)

> Feature 002 ([specs/002-transactional-step-sequence](../002-transactional-step-sequence/spec.md)) supersedes
> the step shape shown in earlier versions of this quickstart: steps return `void` and dispatch commands through
> `CommandGateway`.

This walks through the two independent test shapes named in the spec (User Story 1 and User Story 2),
shaped as JUnit 5 + AssertJ scenarios against the shipped contracts in
`io.github.gkosharovdev.sagaforge.core.lifecycle`. The contracts are interfaces only: no production
`SagaContext`/`SagaLifecycle` implementation (and no engine) ships in `saga-forge-core`, so Scenario B runs
against a test double such as the test-only `InMemorySagaLifecycle`.

## Scenario A — step author reads/writes business state (User Story 1)

Extends the existing canonical example style (`saga-forge-example`'s `OrderProcessingSaga`): a saga class
declares a `SagaContext`-typed field (no annotation — recognized by type per `research.md` §1), alongside
the existing `@SagaId` field and `@SagaStep` methods. Since feature 002, the values a step writes to its
`SagaContext` are persisted as a separate `SagaContextRecord` (through `SagaContextStore`) rather than inside
`SagaInstance.state`.

```java
import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import java.math.BigDecimal;

// @Aggregate / @DomainEvent / @Command are TYPE-level annotations: they go on the aggregate/event/command
// types, not on step parameters. Every @Aggregate and @DomainEvent declares exactly one @AggregateIdentifier,
// and @DomainEvent names the aggregate that records it.
@Aggregate record Order(@AggregateIdentifier String orderId) {}
@DomainEvent(aggregate = Order.class) record OrderCreated(@AggregateIdentifier String orderId, BigDecimal amount) {}
@DomainEvent(aggregate = Order.class) record PaymentProcessed(@AggregateIdentifier String orderId) {}
@DomainEvent(aggregate = Order.class) record OrderCompleted(@AggregateIdentifier String orderId) {}
@Command record ChargePayment(String orderId, BigDecimal amount) {}
@Command record CompleteOrder(String orderId, BigDecimal amount) {}

@Saga
class OrderProcessingSaga {
    @SagaId
    private String orderId;

    private SagaContext context;           // no annotation: recognized by type; populated by the engine before each step call
    private CommandGateway commandGateway; // no annotation: recognized by type; populated by the engine

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    void handleOrderCreated(OrderCreated event) {
        context.putState("paymentAmount", event.amount());        // whole-value write (FR-002); null → IllegalArgumentException
        commandGateway.send(new ChargePayment(event.orderId(), event.amount()));
    }

    @SagaStep(associationProperty = "orderId")
    void processPayment(PaymentProcessed event) {
        // read a value a prior step wrote, by name and expected type (FR-001, FR-003)
        BigDecimal amount = context.getState("paymentAmount", BigDecimal.class)
            .orElseThrow(); // never null/default-confused with absent, per FR-004

        context.updateStateAttribute("shipment", "carrier", "UPS");     // FR-002a: field-level write
        context.updateStateAttribute("shipment", "trackingReady", true); // same structured value, 2nd field

        commandGateway.send(new CompleteOrder(event.orderId(), amount));
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
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance; // existing SPI record, extended by feature 002
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;   // existing SPI enum, reused as-is
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

SagaLifecycle lifecycle = ...; // test double per contracts/saga-lifecycle.md (e.g. test-only InMemorySagaLifecycle)

// 1. start — every @SagaStep starts NOT_RUN
SagaInstance created = lifecycle.start(OrderProcessingSaga.class, "orderId", "order-123");
assertThat(created.status()).isEqualTo(SagaStatus.CREATED);

// 2. record a completed step (feature 002) — changes only steps[stepName], never the status
SagaInstance recorded = lifecycle.recordStepCompleted(created.sagaId(), "handleOrderCreated",
    List.of(AggregateReference.of(Order.class, "order-123")));
assertThat(recorded.steps().get("handleOrderCreated").status()).isEqualTo(StepStatus.COMPLETED);
assertThat(recorded.status()).isEqualTo(SagaStatus.CREATED);

// 3. advance (a unit of work committed at a wait point)
SagaInstance running = lifecycle.advance(created.sagaId());
assertThat(running.status()).isEqualTo(SagaStatus.RUNNING);

// 4. complete — names the reached @EndSaga step as the outcome; the engine (caller) has already
//    verified it is @EndSaga, since SagaLifecycle holds no saga metadata
SagaInstance completed = lifecycle.complete(running.sagaId(), "completeOrder");
assertThat(completed.status()).isEqualTo(SagaStatus.COMPLETED);
assertThat(completed.outcome()).isEqualTo("completeOrder");

// 5. reject further transitions on a terminal instance (InvalidSagaTransitionException.terminal)
assertThatThrownBy(() -> lifecycle.advance(completed.sagaId()))
    .isInstanceOfSatisfying(InvalidSagaTransitionException.class, ex -> {
        assertThat(ex.isNotFound()).isFalse();
        assertThat(ex.getCurrentStatus()).contains(SagaStatus.COMPLETED); // Optional<SagaStatus>
    });
assertThatThrownBy(() -> lifecycle.complete(completed.sagaId(), "completeOrder"))
    .isInstanceOf(InvalidSagaTransitionException.class);

// fail (feature 002) — the only way into FAILED; records the failure, also terminal
SagaInstance other = lifecycle.start(OrderProcessingSaga.class, "orderId", "order-789");
SagaInstance failed = lifecycle.fail(other.sagaId(),
    SagaFailure.of("processPayment", new IllegalStateException("amount mismatch"), Instant.now()));
assertThat(failed.status()).isEqualTo(SagaStatus.FAILED);
assertThat(failed.failure().stepName()).isEqualTo("processPayment");

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

// null arguments → IllegalArgumentException (start/recordStepCompleted/advance/complete/fail)
assertThatThrownBy(() -> lifecycle.advance(null)).isInstanceOf(IllegalArgumentException.class);
```

## Scenario C — documentation accuracy (User Story 3)

Not a runtime test — a delivery checklist item for `/speckit-implement`:
- `AGENTS.md` "Current Status" and `docs/architecture.md` must list `SagaLifecycle`/`SagaContext` as
  implemented **only after** the types above exist in `saga-forge-core` with passing tests (FR-011).
- Until then, both documents must not claim they are implemented — verified by this feature's own
  completion, not by this quickstart (SC-003 is checked by re-reading the docs post-delivery).
