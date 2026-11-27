# saga-forge-jmolecules

Lets a domain marked only with [jMolecules](https://github.com/xmolecules/jmolecules) drive Saga Forge sagas: no
Saga Forge annotation on aggregates, events, commands or command handlers. The saga itself keeps Saga Forge's
annotations (`@Saga`, `@SagaStep`, `@StartSaga`, `@EndSaga`, ...).

Supported jMolecules: the versions of `org.jmolecules:jmolecules-bom` 2025.0.2 (`jmolecules-ddd`, `jmolecules-events`,
`jmolecules-cqrs-architecture` 2.0.1).

## Enable it

Spring Boot starter: put the module on the class path. The starter adds the vocabulary unless
`saga-forge.jmolecules.enabled=false`.

```xml
<dependency>
    <groupId>io.github.gkosharovdev</groupId>
    <artifactId>saga-forge-jmolecules</artifactId>
</dependency>
```

Hand-assembled runtime: pass one `DomainTypes` to the runtime and the transport.

```java
DomainTypes domainTypes = DomainTypes.of(new JMoleculesDomainTypeResolver());
SagaRuntime runtime = SagaRuntime.builder().domainTypes(domainTypes) /* ... */ .build();
InMemoryTransport transport = InMemoryTransport.builder().domainTypes(domainTypes) /* ... */ .build();
```

Saga Forge's own annotations stay recognised; a model may mix both.

## What is recognised

| Role | Markers | Identifier or link |
|---|---|---|
| aggregate | `@AggregateRoot`, `AggregateRoot<T, ID>` | the `@Identity` field or method; for `AggregateRoot<T, ID>` without one, `getId()` |
| domain event | `@DomainEvent`, `DomainEvent` (`jmolecules-events`) | a link member, below |
| command | `@Command` (`jmolecules-cqrs-architecture`, also the deprecated `cqrs.annotation.Command`) | a link member, below |
| command handler | `@CommandHandler` on a method or constructor | - |

Link members are the declared fields (record components count once):

| Member | Aggregate | Identity read |
|---|---|---|
| `Association<A, ID>` with concrete type arguments | `A` | `getId()` |
| `@Association(aggregateType = A.class)` | `A` | the value |
| `@Association` without `aggregateType` | the aggregate whose identifier has the member's type | the value |
| a subtype of `org.jmolecules.ddd.types.Identifier` | the aggregate whose identifier has the member's type | the value |

A member of any other type, such as a plain `UUID` or `String`, is never a link. When two aggregates share an
identifier type, validation names both: name the aggregate on the link member, or give each aggregate its own
identifier type. Without `jmolecules-events` or `jmolecules-cqrs-architecture` on the class path, no event or command
respectively is recognised.

Not recognised: saga-side annotations, `@DomainEventHandler`, `@Repository`, `@Service`.

## Typed identifiers

Associations and ordering keys use an identifier's text form (`String.valueOf`). Keep it stable and identical across
the events and commands of one saga, for example a record that overrides `toString()` to return its value. Loaders
and resolvers receive the identifier value as it is.

## Outbox commands

jMolecules has no delivery attribute. A command handled after the sender commits keeps Saga Forge's annotation beside
jMolecules' one:

```java
@org.jmolecules.architecture.cqrs.Command
@io.github.gkosharovdev.sagaforge.core.annotation.Command(delivery = CommandDelivery.OUTBOX)
record NotifyCustomer(OrderId orderId) {}
```

## Events held on the aggregate

jMolecules defines no convention for holding events, so this module ships no `AggregateEventSource`. An application
with its own convention registers one, for example for aggregates with `domainEvents()` and `clearDomainEvents()`:

```java
final class PendingEvents implements AggregateEventSource {
  @Override public boolean supports(Class<?> aggregateType) { return HasEvents.class.isAssignableFrom(aggregateType); }
  @Override public List<Object> takeEvents(Object aggregate) {
    HasEvents holder = (HasEvents) aggregate;
    List<Object> events = List.copyOf(holder.domainEvents());
    holder.clearDomainEvents();
    return events;
  }
}
```

With the Spring Boot starter, define it as a bean; by hand, pass it to `SagaRuntime.Builder.aggregateEventSource`.
