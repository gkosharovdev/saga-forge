# Contract: Host Configuration

## Hand-assembled runtime and transport

```java
DomainTypes domainTypes = DomainTypes.of(new JMoleculesDomainTypeResolver());

InMemoryTransport transport = InMemoryTransport.builder()
    .domainTypes(domainTypes)                 // new; default DomainTypes.builtIn()
    // messageStore, transactions, ... as today
    .build();

SagaRuntime runtime = SagaRuntime.builder()
    .domainTypes(domainTypes)                 // new; default DomainTypes.builtIn()
    .aggregateEventSource(new OrderEventSource())   // new, repeatable
    .register(OrderProcessingSaga.class, Order.class, PaymentService.class, OrderRepository.class)
    // storage, outbox, dispatcher, ... as today
    .build();
```

- `domainTypes(null)`/`aggregateEventSource(null)`: `IllegalArgumentException`, like the other builder methods.
- The scanner run by `SagaRuntime.Builder.build()` (`new ReflectiveAnnotationScanner(domainTypes).validateAll(...)`)
  and every runtime component use the same instance.
- A transport and runtime given different `DomainTypes`: not detected (documented; the starter always shares one).

## Scanner

`new ReflectiveAnnotationScanner()` = `new ReflectiveAnnotationScanner(DomainTypes.builtIn())`. `AnnotationScanner` is
unchanged.

## `DomainEvents`

- `record(Object)`: null check here; the marker check is the bound recorder's (the runtime's uses its `DomainTypes`).
  Built-in text unchanged: `"{Class} is not annotated @DomainEvent"`; with more vocabularies:
  `"{Class} is not a domain event (accepted: {describe})"`.
- `capture(Runnable)`: unchanged (built-in only). New `capture(DomainTypes, Runnable)`.

## Test fixture

`SagaFixture.Builder.domainTypes(DomainTypes)` and `.aggregateEventSource(AggregateEventSource)`; record-and-answer and
`handlers(...)` modes both honour them; the fixture's `Model` asks `DomainTypes`.

## Spring Boot starter

| Bean / property | Behaviour |
|---|---|
| `DomainTypes` bean (`SagaForgeDomainTypesConfiguration`) | built from every `DomainTypeResolver` bean in `@Order` order, plus `JMoleculesDomainTypeResolver` when `saga-forge-jmolecules` is on the class path and the property below is not `false`; backs off for an application `DomainTypes` bean |
| `saga-forge.jmolecules.enabled` | `true` by default; `false` leaves jMolecules markers unrecognised (no error mentions them) |
| consumers | `SagaRuntime.Builder`, `InMemoryTransport.Builder`, `SagaModelCollector` (bean and package scanning find vocabulary aggregates and handler hosts), `SpringDataAggregateResolver` (identifier type check through `DomainTypes`), the fixture-free starter checks |
| `AggregateEventSource` beans | each passed to `Builder.aggregateEventSource`, `@Order` order; the starter defines none |
| `sagaforge` Actuator endpoint | adds `vocabularies` (names) and `eventSources` (class names) |
| docs | starter README table + `additional-spring-configuration-metadata.json` (`ReadmePropertiesTest`) |

014 interplay: `SagaRuntime.eventTypes()` returns the step event types whatever their vocabulary, so
`InboundSagaEventListener` and the fan-out cover jMolecules events with no change (FR-036, `JMoleculesInboundRouteTest`).

## Startup and validation errors (new or changed)

| Source | Text |
|---|---|
| R30 | `"{Type} is marked {marker1} ({role1}) and {marker2} ({role2}); a type has one role"` |
| R30 | `"{Type} names different identifiers: {marker1} {member1}, {marker2} {member2}"` |
| R31 | `"{Type} ({marker}) concerns no known aggregate: {reason}. Add an Association<A, ID> member or @Association(aggregateType = A.class), or give the aggregate its own identifier type"` |
| missing marker (any rule, more than one vocabulary) | the role's `describe`, e.g. `"... must be a domain event (@DomainEvent, or jMolecules @DomainEvent / DomainEvent)"` |
| `DomainTypes.of` | `"Vocabulary 'jMolecules' is registered twice"` |
| harvest | see [aggregate-event-source.md](aggregate-event-source.md) |

Every built-in-only text is unchanged (`ScannerErrorTextsTest`).
