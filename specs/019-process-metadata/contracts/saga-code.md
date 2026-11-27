# Contract: Reading Metadata in Saga Code and Handlers

**Modules**: `saga-forge-core` (scanner), `saga-forge-runtime` | **Spec**: FR-013 to FR-013b, D7

## Saga field

```java
@Saga
public class OrderProcessingSaga {
  @SagaId private String sagaId;
  private SagaContext context;
  private CommandGateway commandGateway;
  private SagaMetadata metadata;        // optional, recognised by declared type, read-only

  @StartSaga
  @SagaStep(associationProperty = "orderId")
  public void handleOrderCreated(OrderCreatedEvent event) {
    String tenant = metadata.get("tenant").orElse("default");
    ...
  }
}
```

- At most one field of declared type `SagaMetadata` (R32). Injected before every step, compensation and deadline step
  of a unit of work with the instance's metadata; never persisted.
- Assigning to the field has no effect on the instance or on outgoing messages (the value is replaced on the next
  saga object; documented as read-only).

## Handler parameter

```java
@CommandHandler
public void handle(ProcessPaymentCommand command, CommandId commandId, SagaMetadata metadata) { ... }
```

- At most one `SagaMetadata` parameter (R33, `validateAll`). Supplied by the runtime, never by a
  `HandlerParameterResolver` (`supports` is not asked for it).
- Value: the handling unit of work's metadata (Property 50 (c)).

## Scanner texts

- R32: `"@Saga class {S} must declare at most one SagaMetadata field, found {n}"` (as implemented, in the style of
  R7/R8).
- R33: `"@CommandHandler method {m} in {H} must declare at most one SagaMetadata parameter, found {n}"`.

Both go into the user guide's error table and `docs/agents/saga-forge/SKILL.md` (FR-025).
