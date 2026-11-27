# Contract: Domain Type SPI (`core.spi.model`)

Public API of `saga-forge-core`. Implemented by vocabulary modules (`saga-forge-jmolecules`) and by applications with
their own markers; consumed by Saga Forge's own modules through `DomainTypes` only.

```java
package io.github.gkosharovdev.sagaforge.core.spi.model;

/** The role a domain type plays in the model. */
public enum DomainRole { AGGREGATE, DOMAIN_EVENT, COMMAND }

/** The marker that produced an answer, named in error texts. */
public record Marker(String vocabulary, String display) {
  // both non-blank, else IllegalArgumentException
}

/**
 * One vocabulary of domain markers. Answers per type and member, without knowledge of the model.
 * An empty answer means "this vocabulary does not mark it". Implementations are stateless or immutable
 * and thread-safe; Saga Forge caches every answer per class.
 */
public interface DomainTypeResolver {

  /** Unique, human-readable vocabulary name, e.g. "jMolecules". */
  String name();

  /** A role and the marker that gives it. */
  record RoleMarker(DomainRole role, Marker marker) {}

  /** The role this vocabulary gives {@code type}, with the marker that gives it. */
  Optional<RoleMarker> role(Class<?> type);

  /**
   * For a type this vocabulary marks: an aggregate's identity members, or an event's or command's link members
   * (members naming or identifying the aggregate it concerns). Declared members only, declaration order.
   */
  List<IdentifierMember> identifiers(Class<?> type);

  /** The marker making {@code member} (method or constructor) a command handler, if this vocabulary marks it. */
  Optional<Marker> commandHandler(Executable member);

  /** The markers this vocabulary accepts for {@code role}, for "missing marker" texts, e.g. "jMolecules @DomainEvent / DomainEvent". */
  String describe(DomainRole role);
}
```

```java
/** An identifier or link member: a field or a no-argument accessor. */
public final class IdentifierMember {
  public static IdentifierMember ofField(Field field, Marker marker, Optional<Class<?>> namedAggregate);
  public static IdentifierMember ofAccessor(Method accessor, Marker marker, Optional<Class<?>> namedAggregate);
  /** A member whose value is reached through {@code reader} (e.g. Association::getId), typed {@code valueType}. */
  public static IdentifierMember ofReader(Member member, Marker marker, Class<?> valueType,
      Optional<Class<?>> namedAggregate, Function<Object, Object> reader);

  public Marker marker();
  public Member member();
  public String name();
  public Class<?> valueType();
  public Optional<Class<?>> namedAggregate();
  /** @throws IllegalStateException naming member and type if the value is null or the accessor throws */
  public Object read(Object instance);
}
```

```java
/** All enabled vocabularies, Saga Forge's annotations first. Immutable, thread-safe. */
public final class DomainTypes {
  public static DomainTypes builtIn();
  /** @throws IllegalArgumentException on a null resolver or a vocabulary name used twice (including "Saga Forge") */
  public static DomainTypes of(DomainTypeResolver... added);

  public List<String> vocabularies();                       // ["Saga Forge", "jMolecules"]
  public Optional<DomainRole> role(Class<?> type);
  public boolean isAggregate(Class<?> type);
  public boolean isDomainEvent(Class<?> type);
  public boolean isCommand(Class<?> type);
  public List<Marker> markers(Class<?> type);
  public Optional<Marker> commandHandler(Executable member);
  public List<IdentifierMember> identifiers(Class<?> type);
  /** @throws IllegalStateException if the object's type (or a superclass) has no single identifier, or it is null */
  public Object identifierValue(Object eventOrCommand);
  public AggregateResolution aggregateOf(Class<?> eventOrCommandType, Collection<Class<?>> aggregates);
  public CommandDelivery delivery(Class<?> commandType);
  public List<String> conflicts(Class<?> type);             // R30
  public String describe(DomainRole role);

  public sealed interface AggregateResolution {
    record Resolved(Class<?> aggregate, IdentifierMember link) implements AggregateResolution {}
    record Unresolved(String reason, List<Class<?>> candidates) implements AggregateResolution {}
  }
}
```

## Behavioural contract

- `builtIn()` answers exactly as today's direct annotation reads (Saga Forge `@Aggregate`, `@AggregateIdentifier`
  field-only, `@DomainEvent(aggregate)`, `@Command`, `@CommandHandler`); `describe` returns the simple annotation name.
- `role` reports a role only if every resolver that answers agrees; disagreement is reported by `conflicts` and the type
  has no role at runtime.
- Caching: answers are computed once per class; a resolver must return the same answer for the same class.
- A resolver throwing is a programming error: `DomainTypes` lets it propagate (startup fails with it).
- `IdentifierMember.read` makes no copy and no conversion; text forms are `String.valueOf` of the value, by the caller.

## Tests that bind this contract

`DomainTypesTest` (composition, precedence, conflicts, caching, `describe`), `BuiltInResolverTest` (built-in answers equal
today's reads on every fixture type in core's tests), `TestDomainTypeResolver` (R12) and `JMoleculesDomainTypeResolverTest`.
