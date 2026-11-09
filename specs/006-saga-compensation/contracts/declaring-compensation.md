# Contract: Declaring Compensation

**Feature**: `006-saga-compensation` | Spec FR-001 to FR-002, FR-007 to FR-010 | Research §1, §2, §4

## A saga with a business failure and a compensation

```java
@Saga
public class OrderProcessingSaga {

    @SagaId private String sagaId;
    private SagaContext context;
    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void handleOrderCreated(OrderCreatedEvent event) { ... }

    @SagaStep(associationProperty = "orderId")
    public void processPayment(PaymentProcessedEvent event) {
        context.putState("paymentId", event.paymentId());           // read later by the compensation
        ...
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void completeOrder(OrderCompletedEvent event) { }

    @StartCompensation                                            // failure outcome "rejectDelivery"
    @SagaStep(associationProperty = "orderId")
    public void rejectDelivery(DeliveryRejectedEvent event) {
        context.putState("rejectionReason", event.reason());       // commits with the start of compensation
    }

    @SagaCompensation(forStep = "processPayment")
    public void compensatePayment(CompensationTrigger trigger) {
        String paymentId = context.getState("paymentId", String.class).orElseThrow();
        BigDecimal amount = context.getState("orderAmount", BigDecimal.class).orElseThrow();
        commandGateway.send(new RefundPaymentCommand(sagaId, paymentId, amount));
    }
}
```

## Scanner rules (`.kiro/specs/saga-forge-framework/design.md`)

| Rule | Error message (`{m}` method, `{c}` class) |
|---|---|
| R20 | `Method {m} in {c} is annotated with @StartCompensation but missing @SagaStep` (the R4 wording) |
| R20 | `@StartCompensation method {m} in {c} cannot also be annotated with @StartSaga` / `@EndSaga` |
| R21 | `@SagaCompensation method {m} in {c} must return void` |
| R21 | `@SagaCompensation method {m} in {c} must declare no parameter or one CompensationTrigger parameter` |
| R21 | `@SagaCompensation method {m} in {c} cannot also be annotated with @SagaStep` |
| R22 | `@SagaCompensation methods {m1}, {m2} in {c} both compensate step {s}` |
| R22 | `@SagaCompensation method {m} in {c} compensates step {s}, which is a @StartCompensation step` |

All rules run in `validate` and `validateAll`, in the same single pass (Property 24), and are therefore reported by
`SagaEngine` startup.

## What a compensation may use

| Facility | Available | Note |
|---|---|---|
| `CommandGateway` field | yes | commands get 005 identities scoped to the compensation method's name |
| `SagaContext` field | yes | values written by forward steps survive their rollbacks |
| `@SagaId` field | yes | |
| `CompensationTrigger` parameter | optional | kind, step, event type or failure (`SagaFailure`), time |
| The triggering event object | no | not persisted; copy what is needed into `SagaContext` in the failure-outcome step |
| Returning a command | no | the method returns `void` |

A failure thrown by the method, or reported by the gateway, fails the compensation (contracts/compensation-run.md).
