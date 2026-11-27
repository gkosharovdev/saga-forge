# Contract: `saga-forge-jmolecules`

Artifact `io.github.gkosharovdev:saga-forge-jmolecules`. Dependencies: `saga-forge-core`; `org.jmolecules:jmolecules-ddd`
(compile); `jmolecules-events`, `jmolecules-cqrs-architecture` (compile, `optional`). Versions from
`org.jmolecules:jmolecules-bom` 2025.0.2. No Spring, runtime, storage or transport dependency.

```java
package io.github.gkosharovdev.sagaforge.jmolecules;

/** Recognises jMolecules' DDD, event and CQRS markers as Saga Forge domain roles. Stateless, thread-safe. */
public final class JMoleculesDomainTypeResolver implements DomainTypeResolver {
  public JMoleculesDomainTypeResolver();
  @Override public String name();   // "jMolecules"
  // role, identifiers, commandHandler, describe: as tabled below
}
```

## Mapping

| Role | Markers (display) | Identifier / link members |
|---|---|---|
| `AGGREGATE` | `jMolecules @AggregateRoot`, `jMolecules AggregateRoot<T, ID>` | the `@Identity` field or method (`jMolecules @Identity`); for `AggregateRoot<T, ID>` without `@Identity`, `getId()` (`jMolecules AggregateRoot<T, ID>`) |
| `DOMAIN_EVENT` | `jMolecules @DomainEvent`, `jMolecules DomainEvent` | link members, below |
| `COMMAND` | `jMolecules @Command`, `jMolecules Command` | link members, below |
| command handler | `jMolecules @CommandHandler` on a method or constructor | — |

Link members (declared fields and no-argument accessors of records):

| Member | Named aggregate | Value |
|---|---|---|
| typed `Association<A, ID>` (concrete type arguments) | `A` | `getId()`, type `ID` |
| annotated `@Association(aggregateType = A.class)` | `A` | the member value |
| annotated `@Association` (no `aggregateType`) | resolved by member type | the member value |
| typed with a subtype of `org.jmolecules.ddd.types.Identifier` | resolved by member type | the member value |

A record component counts once (its field), not again through its accessor. Members of other types are never links
(G-016-05). An `Association` member with wildcard or raw type arguments is a link with an unreadable aggregate, which
R31 reports.

## Optional artifacts

When `jmolecules-events` or `jmolecules-cqrs-architecture` is not on the class path, the resolver answers nothing for
those roles; it never fails to load (classes are probed once with `Class.forName(name, false, loader)`).

## Not provided

- No `AggregateEventSource` (jMolecules has no event-holding convention; G-016-03). The README shows an application's
  source for a `domainEvents()`/`clearDomainEvents()` convention.
- No delivery attribute: an outbox command keeps Saga Forge's `@Command(delivery = OUTBOX)` (D12, G-016-01).
- Saga-side annotations, `@DomainEventHandler`, `@Repository`, `@Service`: not recognised (D2).

## Tests that bind this contract

`JMoleculesDomainTypeResolverTest` (each row of both tables, record components, optional artifacts absent via a filtering
class loader), `JMoleculesScannerRulesTest` (each identifier/role rule and R30/R31 over jMolecules markers, texts name the
jMolecules marker: SC-004), `JMoleculesCanonicalSagaTest` (the canonical saga over a hand-assembled runtime: Property 48).
