# Vocabularies: a jMolecules domain

Saga Forge reads the domain side of a model (aggregates, their identifiers, domain events, commands and command
handlers) through *vocabularies* of markers. Its own annotations are always one. The `saga-forge-jmolecules` module
adds a second, so a domain marked only with [jMolecules](https://github.com/xmolecules/jmolecules) drives sagas with no
Saga Forge annotation on it. The saga class itself keeps Saga Forge's annotations (`@Saga`, `@SagaStep`,
`@StartSaga`, `@EndSaga`, `@StartCompensation`, `@SagaCompensation`).

## Enable it

Add the module. With the Spring Boot starter nothing else is needed: the starter adds the vocabulary while
`saga-forge.jmolecules.enabled` is not `false`.

```xml
        <dependency>
            <groupId>io.github.gkosharovdev</groupId>
            <artifactId>saga-forge-jmolecules</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.jmolecules</groupId>
            <artifactId>jmolecules-events</artifactId>
        </dependency>
        <dependency>
            <groupId>org.jmolecules</groupId>
            <artifactId>jmolecules-cqrs-architecture</artifactId>
        </dependency>
```

Source: `saga-forge-example-jmolecules/pom.xml`.

`jmolecules-events` is needed for events, `jmolecules-cqrs-architecture` for commands and command handlers. Keep the
domain package in the scanned packages:

```properties
# The domain lives beside the application package; the starter scans it for jMolecules aggregates and handlers.
saga-forge.scan-packages=io.github.gkosharovdev.sagaforge.example.jmolecules.domain
```

Source: `saga-forge-example-jmolecules/src/main/resources/application.properties`.

A hand-assembled runtime passes one `DomainTypes` to the runtime and the transport (`SagaRuntime.Builder.domainTypes`,
`InMemoryTransport.Builder.domainTypes`); a fixture test passes it to `SagaFixture.Builder.domainTypes` (see
[Testing](testing.md#a-jmolecules-domain)). Saga Forge's annotations stay recognised, and a model may mix both.

## The domain

The aggregate implements `AggregateRoot<T, ID>`, so its identifier is `getId()`, and marks its handler with jMolecules'
`@CommandHandler`. Events and commands carry the order's typed identifier, which links them to the aggregate:

```java
public final class Order implements AggregateRoot<Order, OrderId> {
  // ...
  @CommandHandler
  public void handle(ReserveStock command) {
    if (inStock) {
      status = Status.COMPLETED;
      events.add(new OrderCompleted(id));
    } else {
      status = Status.OUT_OF_STOCK;
      events.add(new StockUnavailable(id));
    }
  }
  // ...
  /** The events recorded since the repository last took them, oldest first. */
  public List<Object> domainEvents() {
    return List.copyOf(events);
  }

  public void clearDomainEvents() {
    events.clear();
  }
}
```

Source: `saga-forge-example-jmolecules/src/main/java/io/github/gkosharovdev/sagaforge/example/jmolecules/domain/Order.java`.

```java
public record PaymentProcessed(OrderId orderId, String paymentId, BigDecimal amount)
    implements DomainEvent {}
```

```java
@Command
public record ReserveStock(OrderId orderId) {}
```

Source: `PaymentProcessed.java` and `ReserveStock.java` in the same package.

The saga is the canonical one, unchanged: `@SagaStep(associationProperty = "orderId")` reads the event's `orderId`
member, whatever its type.

## What is recognised

| Role | jMolecules marker | Identifier or link |
|---|---|---|
| aggregate | `@AggregateRoot`, `AggregateRoot<T, ID>` | the `@Identity` field or method; for `AggregateRoot<T, ID>` without one, `getId()` |
| domain event | `@DomainEvent`, `DomainEvent` | one link member, below |
| command | `@Command` (also the deprecated `cqrs.annotation.Command`) | at most one link member |
| command handler | `@CommandHandler` on a method or constructor | - |

A link member is a declared field or record component of type `Association<A, ID>`, one marked
`@Association(aggregateType = A.class)` or `@Association`, or one whose type implements jMolecules' `Identifier`.
Without a named aggregate, the link resolves to the one aggregate whose identifier has the member's type. A plain
`String` or `UUID` member is never a link. When two aggregates share an identifier type, validation names both: name
the aggregate on the link, or give each aggregate its own identifier type.

Not recognised: `@DomainEventHandler`, `@Repository`, `@Service`, and any saga-side marker.

### Typed identifiers

Associations, the in-memory transport's ordering keys and aggregate references use an identifier's text form
(`String.valueOf`). Keep it the plain value and the same in every event and command of a saga:

```java
public record OrderId(String value) implements Identifier {

  public OrderId {
    Objects.requireNonNull(value, "value");
  }

  @Override
  public String toString() {
    return value;
  }
}
```

Source: `saga-forge-example-jmolecules/src/main/java/io/github/gkosharovdev/sagaforge/example/jmolecules/domain/OrderId.java`.

Loaders, savers and Spring Data repositories receive the identifier value itself, not its text.

### Outbox commands

jMolecules has no delivery attribute. A command handled after the sender commits keeps Saga Forge's `@Command` beside
jMolecules' one:

```java
@org.jmolecules.architecture.cqrs.Command
@io.github.gkosharovdev.sagaforge.core.annotation.Command(delivery = CommandDelivery.OUTBOX)
record NotifyCustomer(OrderId orderId) {}
```

## Events held on the aggregate

A jMolecules aggregate usually holds its events instead of calling `DomainEvents.record`. Register an
`AggregateEventSource`: after each aggregate command handler, and before the aggregate is saved, the runtime asks the
first source that supports the aggregate's type for its events and records them, in order, as if the handler had
recorded them. They continue the saga in the same unit of work and roll back with it.

```java
@Component
public class OrderEventSource implements AggregateEventSource {

  @Override
  public boolean supports(Class<?> aggregateType) {
    return Order.class.isAssignableFrom(aggregateType);
  }

  @Override
  public List<Object> takeEvents(Object aggregate) {
    Order order = (Order) aggregate;
    List<Object> events = order.domainEvents();
    order.clearDomainEvents();
    return events;
  }
}
```

Source: `saga-forge-example-jmolecules/src/main/java/io/github/gkosharovdev/sagaforge/example/jmolecules/application/OrderEventSource.java`.

With the starter, define it as a bean (several are asked in `@Order` order); by hand, pass it to
`SagaRuntime.Builder.aggregateEventSource`; in a fixture test, to `SagaFixture.Builder.aggregateEventSource`. A source
must clear what it returns, or the next handler call on the same aggregate returns the events again. Every returned
object must be a domain event of an enabled vocabulary, otherwise the handling fails with
`Event source {S} returned {T} for aggregate {A}, which is not a domain event (accepted: ...)`.

A Spring Data aggregate with `@DomainEvents` and `@AfterDomainEventPublication` needs no source in a Spring Boot
application: its events are published when the repository saves it and reach sagas through the starter's inbound
route, like any event published through `ApplicationEventPublisher`. If you register a source for such an aggregate
anyway, it takes and clears the events before the save, so Spring Data publishes nothing for them and each event
reaches the saga once.

## Another vocabulary

A team with its own markers implements `DomainTypeResolver` (`core.spi.model`): it names the vocabulary, answers which
role a type has (`role`, or `roles` when one class could carry two), which members identify or link it
(`identifiers`), whether an executable is a command handler (`commandHandler`), and how its markers read in error
texts (`describe`, `describeIdentifier`). An empty answer means "this vocabulary does not mark it". With the starter,
define the resolver as a bean; by hand, pass `DomainTypes.of(resolver)` to the runtime, the transport and the fixture.
`saga-forge-jmolecules`' `JMoleculesDomainTypeResolver` is the worked example.

## Checking what is enabled

The `sagaforge` Actuator endpoint lists `vocabularies` (Saga Forge's first) and `eventSources` (class names, in the
order the runtime asks them). Error texts name the marker the model used, for example
`jMolecules @CommandHandler method handle in NoCommandHandler must declare exactly one command parameter (@Command, or jMolecules @Command), found 0`;
see [Validation rules](validation-rules.md#vocabularies-of-markers).
