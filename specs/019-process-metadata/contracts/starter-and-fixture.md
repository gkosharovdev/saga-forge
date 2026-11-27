# Contract: Starter Property and Fixture API

**Modules**: `saga-forge-spring-boot-starter`, `saga-forge-test` | **Spec**: FR-019 to FR-022, D9

## Property

| Property | Type | Default | Meaning |
|---|---|---|---|
| `saga-forge.metadata.mdc-keys` | list of strings | empty | MDC keys captured into, and restored from, saga metadata. Empty: no accessor, metadata stays empty unless an application `MetadataAccessor` bean exists |

- Registered with configuration metadata; documented in the starter README (`ReadmePropertiesTest`).
- An application bean of type `MetadataAccessor` replaces the MDC accessor; the property is then ignored, and the
  `sagaforge` endpoint names the bean's class.
- Requires `org.slf4j.MDC` on the class path (always true with `spring-boot-starter-logging`); otherwise the property
  is ignored with a startup warning.

## `MdcMetadataAccessor` behaviour

| Call | Effect |
|---|---|
| `capture()` | `{k: MDC.get(k)}` for each configured `k` with a non-null value |
| `restore(m)` | For each configured `k`: remember `MDC.get(k)`, then `MDC.put(k, m.get(k))` if present, else `MDC.remove(k)` |
| `close()` | For each configured `k`: put back the remembered value or remove it |

Keys of `m` that are not configured are not written to MDC.

## Fixture

```java
fixture.when(new OrderCreatedEvent("order-1", "42.00"),
             SagaMetadata.of(Map.of("correlationId", "c-1")))
    .instance(OrderProcessingSaga.class, "order-1")
    .expectMetadata(SagaMetadata.of(Map.of("correlationId", "c-1")));

fixture.given(SagaMetadata.of(Map.of("correlationId", "c-1")), new OrderCreatedEvent(...))
    .when(new PaymentProcessedEvent(...));

SagaFixture.of(OrderProcessingSaga.class, ...).metadataAccessor(accessor)  // Builder, optional, default NONE
fixture.instance(OrderProcessingSaga.class, "order-1").metadata();          // InstanceView
recordedCommand.metadata();                                                 // RecordedCommand
```

- `when(event)` without metadata captures through the fixture's accessor (empty by default).
- `expectMetadata` fails with `SagaExpectationError` listing expected and actual entries.
