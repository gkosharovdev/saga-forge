# Process metadata

A saga often runs long after the request that started it, on other threads and after restarts. Process metadata is a
small map of strings (a correlation id, a tenant, a request id) that Saga Forge takes from the code that starts a saga
or publishes an event, keeps with the saga instance and its messages, and gives back to the saga's code wherever it
runs: in its steps, its command handlers (in the same unit of work or delivered through the outbox later), its
compensations and its timeout steps.

Saga Forge does not interpret the map. You choose the keys; Saga Forge stores them, carries them and puts them back.

## How it travels

- **When a saga starts**, its instance takes the metadata of the event that started it, and keeps it for its whole
  life. A later event with different metadata does not change it.
- **Every message a saga's unit of work stores** (an event it records, an outbox command, a deadline, a scheduled
  command) carries the instance's metadata. An event published outside any saga carries what was captured from the
  publishing thread.
- **Each history entry** records the metadata of the delivery that caused it: the step a later event triggered keeps
  that event's metadata, so the history shows which request moved the saga on. Entries of operator repairs, recovery
  sweeps and command-failure reactions keep the instance's.
- **While saga code runs**, the instance's metadata is put back on the thread (for example in the MDC) for the whole
  unit of work, and the thread's previous state is restored afterwards, also when the unit of work fails.

Metadata is not added to outcome events (`SagaCompleted`, `SagaFailed`, `SagaCompensated`); a listener that needs it
reads the instance through `SagaOperations`.

## Spring Boot: SLF4J's MDC

Name the MDC keys to carry:

```properties
saga-forge.metadata.mdc-keys=correlationId
```

Source: `saga-forge-example-spring/src/main/resources/application.properties`.

With the property set and SLF4J on the class path, the starter captures those keys from the MDC when an order is
placed, stores them with the saga, and puts them back in the MDC while the saga's steps, handlers and compensations
run, on the relay's threads too and after a restart. Only the configured keys are read or written; other MDC keys are
left alone. Without SLF4J the property is ignored with a startup warning. An application bean of type
`MetadataAccessor` replaces the MDC accessor.

The `sagas` endpoint shows each instance's `metadata` and each history entry's; the `sagaforge` endpoint's `metadata`
object names the accessor in use (`none`, `mdc` with its `mdcKeys`, or the class of your own).

## Plain Java: your own accessor

A `MetadataAccessor` reads the thread's current values (`capture`) and makes metadata current for a while (`restore`,
whose `Scope` puts back what was there when closed). This one wraps an application's own request context:

```java
  static final class RequestContext implements MetadataAccessor {

    private static final ThreadLocal<Map<String, String>> CURRENT =
        ThreadLocal.withInitial(Map::of);

    static void set(Map<String, String> values) {
      CURRENT.set(Map.copyOf(values));
    }

    static Map<String, String> current() {
      return CURRENT.get();
    }

    @Override
    public Map<String, String> capture() {
      return CURRENT.get();
    }

    @Override
    public Scope restore(SagaMetadata metadata) {
      if (metadata == null) {
        throw new IllegalArgumentException("metadata must not be null");
      }
      Map<String, String> previous = CURRENT.get();
      CURRENT.set(metadata.asMap());
      return () -> CURRENT.set(previous);
    }
  }
```

Give the same accessor to the runtime and to the transport:

```java
  private final InMemoryTransport transport =
      InMemoryTransport.builder()
          .messageStore(new InMemoryMessageStore())
          .transactions(boundary)
          .metadataAccessor(requestContext)
          .build();
```

and `SagaRuntime.builder() ... .metadataAccessor(requestContext)`.

Source: `saga-forge-example/src/test/java/io/github/gkosharovdev/sagaforge/example/checkout/CheckoutMetadataTest.java`.

Without an accessor (`MetadataAccessor.NONE`, the default) nothing is captured, but metadata a transport delivers is
still stored and handed to saga code.

## Reading it in saga code

A command handler takes a `SagaMetadata` parameter, beside its command and an optional `CommandId`. Logistics books a
shipment with the checkout's correlation id, so its support can trace the booking back to the request:

```java
  @CommandHandler
  public void handle(RequestShipmentCommand command, CommandId commandId, SagaMetadata metadata) {
    bookings.putIfAbsent(commandId.value(), command);
    metadata
        .get("correlationId")
        .ifPresent(correlationId -> correlationIds.putIfAbsent(commandId.value(), correlationId));
  }
```

Source: `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/checkout/LogisticsService.java`.

A saga reads it through a field of type `SagaMetadata`, recognised by its type like the `SagaContext` field:

```java
  @Saga
  public static final class ParcelSaga {

    @SagaId private String sagaId;
    private CommandGateway commandGateway;
    private SagaMetadata metadata;
```

Source: `saga-forge-runtime/src/test/java/io/github/gkosharovdev/sagaforge/runtime/fixtures/metadata/MetadataModel.java`.

A saga declares at most one such field (rule R32) and a handler at most one such parameter (rule R33); see
[Validation rules](validation-rules.md). `SagaMetadata` is immutable: a step cannot add or change entries.

## Limits and failures

- At most 32 entries; keys up to 128 characters, values up to 1,024. An entry over a bound, or with a blank key or
  `null` value, is dropped and logged as `saga.metadata.entry.dropped key=...`, never with its value.
- An accessor that throws never fails a saga: a failing capture starts the saga with empty metadata
  (`saga.metadata.capture.failed`); a failing restore or scope close lets the unit of work run and commit
  (`saga.metadata.restore.failed`).
- Metadata is stored as JSON in `saga_instance`, `saga_lifecycle_history` and `saga_message_store` (schema V6; see
  [Database schema](schema.md)) and shown unredacted. Don't put secrets or personal data in it.

## Testing

`SagaFixture` takes metadata with the event, `when(event, metadata)` or `given(metadata, events...)`, or an accessor
with `Builder.metadataAccessor(...)`; `expectMetadata(...)`, `InstanceView.metadata()` and `RecordedCommand.metadata()`
check what the instance kept and what each command saw:

```java
  void correlationIdStaysWithTheSaga() {
    SagaMetadata request = SagaMetadata.of(Map.of("correlationId", "c-1"));

    SagaFixture.of(OrderProcessingSaga.class)
        .build()
        .given(request, new OrderCreatedEvent("o-1", AMOUNT))
        .when(new PaymentProcessedEvent("payment-1", "o-1", AMOUNT))
        .expectCommands(new ReserveStockCommand("o-1"))
        .expectMetadata(request);
  }
```

Source: `saga-forge-example/src/test/java/io/github/gkosharovdev/sagaforge/example/OrderProcessingSagaFixtureTest.java`.
