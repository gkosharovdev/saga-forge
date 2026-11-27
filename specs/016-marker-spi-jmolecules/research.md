# Research: Marker SPI and jMolecules Adapter

**Feature**: `specs/016-marker-spi-jmolecules` | **Date**: 2026-11-27 | **Spec**: [spec.md](spec.md)

Each section records a decision, why it was taken and what else was weighed. Abstractions are rated for community
familiarity (AGENTS.md): **high** = established pattern or name most Java developers know, **medium** = known in the
DDD / Spring community, **low** = boutique.

## R1. Where the marker checks live today

**Finding.** Saga Forge's domain annotations are read directly in 40 places across six modules (`grep isAnnotationPresent
| getAnnotation(` on the main sources, 2026-11-27, after 014):

| Module | Class | What it reads |
|---|---|---|
| core | `ReflectiveAnnotationScanner` | every role; identifier fields; `@DomainEvent.aggregate`; `@Command.delivery`; error texts |
| core | `DomainEvents.record`/`capture` | `@DomainEvent` on the recorded object |
| runtime | `EventRouter` (014), `UnitOfWork` (014's `recorded`), `RunCompensation`, `EngineEventPublisher` | `@DomainEvent` on a routed/recorded/published event |
| runtime | `RunSteps.touchedAggregateOf` | `@DomainEvent.aggregate` + identifier → `AggregateReference` stored in `StepRecord` |
| runtime | `EngineCommandGateway` | `@Command`, `@Command.delivery`, `@CommandHandler`, `@Aggregate` host |
| runtime | `StartupChecks`, `EngineRuntime.needsHostInstance` | `@Aggregate`, `@CommandHandler`, `@Command.delivery` |
| runtime | `Reflection.aggregateIdentifier` | `@AggregateIdentifier` field (`ClassValue` cache) |
| transport | `OrderingKeys` | `@DomainEvent.aggregate`, `@AggregateIdentifier` |
| starter | `SagaModelCollector`, `SagaRuntimeConfiguration.verify`, `SpringDataAggregateResolver` | `@Aggregate`, `@CommandHandler`, `@AggregateIdentifier` |
| test | `Model` | `@CommandHandler`, `@Command`, `@Aggregate`, `@AggregateIdentifier` |

014's inbound route (`InboundSagaEventListener`) and fan-out (`EventSubscriptions`) match on `SagaRuntime.eventTypes()`,
the parameter types of the model's steps, so they need no change once the scanner and the router accept vocabulary
events (spec FR-036).

**Decision.** One core type answers every one of these questions (R2), every call site above is rewritten to ask it, and
an ArchUnit rule (`DomainAnnotationAccessTest`, runtime/transport/starter/test test scope; a plain reflection test in core)
forbids `isAnnotationPresent`/`getAnnotation` of the five domain annotations outside the built-in resolver (FR-004,
SC-005). Saga-side annotations (`@Saga`, `@SagaStep`, ...) and `@AggregateLoader`/`@AggregateSaver` keep their direct
reads (spec D2).

## R2. The SPI: `DomainTypeResolver`, composed by `DomainTypes`

**Decision.** Core `core.spi.model` gains:

- `DomainTypeResolver` (SPI, interface): answers for **one vocabulary**, per type and member, with no knowledge of the
  model: is this type an aggregate / domain event / command, is this executable a command handler, which members of this
  type are its identifier (aggregate: its identity; event or command: the link to the aggregate it concerns). Every
  answer carries the `Marker` that produced it.
- `DomainTypes` (final class): the **composition** of the built-in resolver (Saga Forge's annotations, always first) and
  the added ones. It answers the same questions for the whole application, detects contradictions (R30), produces the
  role descriptions error texts need, and resolves an event's aggregate against a set of model aggregates (R5). Every
  module in R1 holds a `DomainTypes`, never a single resolver.
- Value types `DomainRole` (enum `AGGREGATE`, `DOMAIN_EVENT`, `COMMAND`), `Marker` (record: vocabulary name + display
  text), `IdentifierMember` (final class: marker, member, value type, optional named aggregate, `read(Object)`).

**Rationale.** A resolver per vocabulary keeps each adapter small and model-free (the transport has no model, R6);
composition in one place gives every module the same union, the same precedence and the same conflict rule, and keeps
`DomainTypes` the only thing a caller sees (DIP: callers depend on the composed abstraction, never on Saga Forge's
annotations). Composition, not inheritance: the built-in vocabulary is one more resolver held by `DomainTypes`.

**Names and familiarity.**

| Name | Rating | Why |
|---|---|---|
| `DomainTypeResolver` | high | "Resolver" is the codebase's own SPI word (`AggregateResolver`, `HandlerParameterResolver`) and Spring's (`HandlerMethodArgumentResolver`); "domain type" is plain DDD vocabulary. |
| `DomainTypes` | high | plural-noun facade like JDK `Files`/`Types`, Spring Modulith's `Types`. |
| `Marker` | high | Effective Java Item 41 "marker interfaces"; the spec's own term. |
| `IdentifierMember` | medium | `java.lang.reflect.Member` + DDD "identifier". |

**Alternatives considered.** `ModelVocabulary` (the roadmap's placeholder): boutique, rejected; the spec's prose keeps
"vocabulary" for the concept, the type is a resolver. One interface with a method per annotation
(`isAggregate`, `isDomainEvent`, ...) and no `Marker`: cannot name the marker in errors (FR-005). A pluggable
replacement of the built-in vocabulary: contradicts D1. `ServiceLoader` discovery of resolvers: invisible
configuration, and the starter would need a second switch to turn it off; rejected for explicit builder methods (R8).

## R3. Identifier members: field or accessor, and how they are read

**Decision.** `IdentifierMember` wraps either a `Field` or a no-argument, non-void `Method` and reads the value through
a resolver-supplied reader, so an `Association<A, ID>` member reads `association.getId()` and a jMolecules aggregate
root reads `getId()`. Readers are made accessible once and cached per class in `DomainTypes` (`ClassValue`, as
`Reflection.IDENTIFIER_FIELDS` does today). The built-in resolver keeps `@AggregateIdentifier` field-only, searched on
the declared fields as today (the scanner counts declared fields; the transport and the Spring resolver walk
superclasses; that difference is kept per caller through `DomainTypes.identifiers(type)` returning declared members and
`DomainTypes.identifierValue(object)` walking the hierarchy, matching today's behaviour of each caller).

**Rationale.** FR-006, D4. A null value or a throwing accessor surfaces exactly like a null `@AggregateIdentifier`
field today (`IllegalStateException` naming the member).

## R4. The jMolecules mapping

**Decision.** `saga-forge-jmolecules` ships `JMoleculesDomainTypeResolver`:

| Role | Recognised (annotation form) | Recognised (type form) | Identifier |
|---|---|---|---|
| Aggregate | `org.jmolecules.ddd.annotation.AggregateRoot` | `org.jmolecules.ddd.types.AggregateRoot<T, ID>` | the `@Identity` field or method; for the type form, `getId()` |
| Domain event | `org.jmolecules.event.annotation.DomainEvent` | `org.jmolecules.event.types.DomainEvent` | link members (below) |
| Command | `org.jmolecules.architecture.cqrs.annotation.Command` | `org.jmolecules.architecture.cqrs.Command` | link members (below) |
| Command handler | `org.jmolecules.architecture.cqrs.annotation.CommandHandler` on a method or constructor | — | — |

Link members of an event or command, the members that say which aggregate it concerns:

1. a member typed `org.jmolecules.ddd.types.Association<A, ID>`: names `A` (from the generic type argument), value
   `getId()`;
2. a member annotated `org.jmolecules.ddd.annotation.Association`: names `aggregateType()` when it is not `void.class`,
   otherwise resolves by the member's type; value is the member's value;
3. a member whose type implements `org.jmolecules.ddd.types.Identifier`: resolves by its type.

A member of a plain type (`UUID`, `String`, `Long`) is not a link unless annotated `@Association`, since an event
commonly carries other values of those types (an event id, an amount). The jMolecules CQRS and events artifacts are
`optional` dependencies of the module: the resolver checks with `Class.forName` once whether each is present and skips
the roles of an absent one (spec Assumptions).

**Rationale.** D11 and FR-012 to FR-017. Rules 1 to 3 need no model, so the resolver stays model-free (R2); only "which
aggregate has identifier type X" needs the model (R5). `@Association(aggregateType = ...)` is jMolecules' own way to
name a target aggregate, so the domain stays jMolecules-only for plain identifier types too.

**Version.** `org.jmolecules:jmolecules-bom` **2025.0.2** (ddd, events and cqrs-architecture 2.0.1) in the root
`dependencyManagement`; Spring Boot 4.1.2 does not manage jMolecules, so the starter does not either. Build-time
check: the module's tests run against exactly this BOM.

**Alternatives considered.** Recognising `@Identity` on event fields: jMolecules defines it as the identity of the
annotated object itself, not of another aggregate; rejected. Recognising `jmolecules-events`' `@DomainEventHandler`
as a saga step: saga steps stay Saga Forge's (D2).

## R5. Resolving an event's aggregate (R31)

**Decision.** `DomainTypes.aggregateOf(type, aggregates)` returns the aggregate an event or command concerns, given the
model's aggregate types: a link that names an aggregate wins; otherwise the link's value type must be the identifier
type of exactly one aggregate in `aggregates`. Zero links, several links, a link naming a type that is not an aggregate,
links that disagree, and an identifier type shared by several aggregates all fail with rule **R31**, naming the type,
the candidates and the fix ("add an `Association<A, ID>` member or `@Association(aggregateType = A.class)`, or give
`A` its own identifier type"). The model's aggregates are the aggregate-role types among the passed-in classes plus
those reached through loaders, savers and handler hosts (the scanner's `reachedTypes`, extended to vocabulary roles).

The runtime resolves each step event type's aggregate once at `start()` (`SagaModel`) and uses it for
`AggregateReference` (`RunSteps.touchedAggregateOf`). Saga Forge's `@DomainEvent(aggregate = X.class)` is a named link,
so built-in models resolve exactly as today, including the existing rule that `X` must be an aggregate (Property 16).

**Rationale.** Property 16 restated over "aggregate marker" (FR-008); the scanner is the only place with the whole model,
the runtime reuses its answer.

## R6. Ordering keys in the transport

**Decision.** `InMemoryTransport.Builder.domainTypes(DomainTypes)` (default `DomainTypes.builtIn()`); `OrderingKeys`
keys an event `<aggregate>#<id>` when its link names the aggregate (all built-in events, `Association` members) and
`<identifier type>#<id>` otherwise, and a command `<command type>#<id>` as today. The id text is `String.valueOf` of
the identifier value (FR-018, FR-037).

**Rationale.** The transport has no model (it is built before the runtime, which takes it as dispatcher and outbox), so
it cannot run R5; it holds the same `DomainTypes` value as the runtime (R8), not a model. Keying a by-type event by its identifier type keeps per-aggregate order: within a model R31
guarantees that type belongs to exactly one aggregate. Built-in keys do not change, so stored messages of existing
databases keep their order across the upgrade.

**Alternatives considered.** Computing the key in the runtime and carrying it on `OutgoingMessages`/`NewMessage`:
cleaner, but events also enter through `EventDispatcher.dispatch(Object)` (`EventPublisher`, 014's inbound route),
which carries no key; widening `EventDispatcher` for this is a larger SPI change than the feature needs. A lazy
`Supplier<DomainTypes>` from the runtime: the starter could do it (G-014-07's pattern), hand-assembly could not.
Recorded as G-016-06.

## R7. Harvesting: `AggregateEventSource` and the hook point

**Decision.** Core `core.spi.AggregateEventSource`:

```java
boolean supports(Class<?> aggregateType);
List<Object> takeEvents(Object aggregate);   // registration order; clears what it returns
```

`EngineCommandGateway` calls the first supporting source (registration order, like `AggregateResolver`) in its
aggregate routes, right after the handler returns and before `AggregateAccess.save`: in `aggregateInstance` after
`Reflection.invoke(method, aggregate, ...)`, in `creation` after the factory returned. Each taken event goes to the
recorder bound by `DomainEventScope` (the unit of work's), so it follows exactly the path of `DomainEvents.record`:
validation through `DomainTypes` (FR-025, with the aggregate and source named), in-process continuation, outbox,
recorded events, fan-out (Property 47), rollback discard. Service handlers (non-aggregate hosts) are not harvested.

**Rationale.** D5, D6, FR-020 to FR-022. "Before the save" makes a Spring Data save find nothing to publish, so 014's
inbound route and harvesting never both take an event (D10). One supporting source per aggregate type keeps the
"at most once per unit of work" rule simple: a source returns and clears.

**Names and familiarity.** `AggregateEventSource` (spec and roadmap name): medium; "event source" also evokes event
sourcing and the browser `EventSource`, but `supports`/`takeEvents` read unambiguously and mirror `AggregateResolver`.
`takeEvents` follows `BlockingQueue.take`/`drainTo` semantics (returns and removes). Alternatives `DomainEventCollector`
and `PendingEventsExtractor`: no more familiar; rejected to keep the name the adopter's feedback and roadmap use.

**Alternatives considered.** Harvesting after the save (the brief's wording): makes Spring Data publish the same events
through 014's route as well (duplicates); rejected by the clarification. Asking every supporting source: two sources
for one type would harvest twice; rejected. Reflection on a conventional `domainEvents()` method in core: a boutique
convention baked into core; rejected (the application provides its source, G-016-03).

## R8. Configuration surfaces

**Decision.**

| Host | Vocabulary | Event sources |
|---|---|---|
| `DomainTypes` | `DomainTypes.builtIn()`, `DomainTypes.of(DomainTypeResolver...)` (built-in always first, added in order) | — |
| `SagaRuntime.Builder` | `domainTypes(DomainTypes)` (default `builtIn()`) | `aggregateEventSource(AggregateEventSource)` (repeatable, in order) |
| `InMemoryTransport.Builder` | `domainTypes(DomainTypes)` (default `builtIn()`); the host passes the same instance as to the runtime | — |
| `ReflectiveAnnotationScanner` | new constructor `(DomainTypes)`; no-argument constructor = built-in | — |
| `SagaFixture.Builder` | `domainTypes(DomainTypes)` | `aggregateEventSource(...)` |
| starter | every `DomainTypeResolver` bean, plus `JMoleculesDomainTypeResolver` when `saga-forge-jmolecules` is on the classpath and `saga-forge.jmolecules.enabled` is not `false` (default `true`) | every `AggregateEventSource` bean |

The starter builds one `DomainTypes` bean (backs off for an application bean, like every starter bean) and passes it to
the runtime builder, the transport, `SagaModelCollector` and `SpringDataAggregateResolver`. The `sagaforge` Actuator
endpoint lists vocabularies and event sources (FR-030).

**Rationale.** One immutable `DomainTypes` value built by the host and handed to every component avoids an ordering
problem (the transport is built before the runtime) and guarantees runtime and transport see the same vocabularies.
`aggregateEventSource(...)` has the shape of the existing `aggregateResolver(...)` (familiar to current users). No
fluent model registration (Principle II): a host adds vocabularies, it never declares a type's role.

## R9. Static entry points: `DomainEvents.record` and `capture`

**Decision.** `DomainEvents.record` keeps its null check and moves the marker check to the bound recorder: the
runtime's recorder validates through its `DomainTypes` (the existing `UnitOfWork.Work.recorded` check, rewritten), so an
application-layer handler can record a jMolecules event. The exception type and the built-in text stay unchanged
(`"... is not annotated @DomainEvent"` when only the built-in vocabulary is enabled). `capture(Runnable)` has no runtime;
it keeps checking the built-in vocabulary only, and gains `capture(DomainTypes, Runnable)` for tests of jMolecules
aggregates that still record explicitly. `EngineEventPublisher` and `RunCompensation` use `DomainTypes`.

**Rationale.** A static facade cannot hold configuration; the recorder is the configured object on the thread. Pure
domains never call `DomainEvents`, so the overload is a convenience, not a dependency.

## R10. Error texts

**Decision.** Error texts are built from `DomainTypes.describe(role)` (the accepted markers, for "missing marker" errors)
and the `Marker` of the member or type at fault (for "wrong shape" errors). With only the built-in vocabulary,
`describe(DOMAIN_EVENT)` is `@DomainEvent`, so every existing text is byte-identical (FR-011); a golden test
(`ScannerErrorTextsTest`) pins the current texts of all rules before the refactor. With jMolecules enabled a missing
marker reads e.g. "must be a domain event (@DomainEvent, or jMolecules @DomainEvent / DomainEvent)", and a wrong shape
names the marker present, e.g. "Order must declare exactly one identifier (jMolecules @Identity), found 2". New rules:

- **R30** (model rule): a type placed in different roles, or given different identifiers, by two vocabularies.
- **R31** (model rule): an event or command whose aggregate cannot be resolved (R5).

`docs/guide/validation-rules.md` lists both with their texts; G-DOC-02's drift risk grows with the parametrised texts,
so `ScannerErrorTextsTest` also checks that each text in the guide is produced by some rule (closes G-DOC-02 for the
scanner part).

## R11. The jMolecules example

**Decision.** New module `saga-forge-example-jmolecules` (Spring Boot, starter, Postgres, in-memory transport):

- `...example.jmolecules.domain`: `Order` (`AggregateRoot<Order, OrderId>`, keeps pending events in a list behind
  `domainEvents()`/`clearDomainEvents()`, jMolecules `@CommandHandler` for stock reservation), `OrderId`/`PaymentId`
  (`Identifier` records), events (`DomainEvent` records with typed ids or `Association` members), commands
  (`Command` records), a payment domain service with a jMolecules `@CommandHandler`.
- `...example.jmolecules.application`: `OrderProcessingSaga` (Saga Forge's saga annotations), `OrderRepository`
  (`@AggregateLoader`/`@AggregateSaver` over `JdbcClient`, mapping the aggregate to a row), `OrderEventSource`
  (`AggregateEventSource` over `domainEvents()`/`clearDomainEvents()`), an order placement service publishing the start
  event through Spring (014).
- Tests: `DomainDependsOnlyOnJMoleculesTest` (ArchUnit: `..domain..` depends on `java..` and `org.jmolecules..` only;
  SC-001/SC-002/SC-008), fixture tests over the three canonical scenarios in real-handler mode (harvested events),
  one Postgres container test end to end.

**Rationale.** Persisting through Spring Data would put `@Id`/repositories' needs into the domain (or require
jMolecules' ByteBuddy plugin, a build-time dependency the example does not need); an application-layer repository is
the hexagonal shape jMolecules users expect, and it exercises `@AggregateLoader`/`@AggregateSaver` with typed ids. The
existing Spring example stays as it is.

**Alternatives considered.** A package inside `saga-forge-example-spring`: two `Order` aggregates and two sagas for one
event type in one context would collide (step uniqueness, 014 fan-out); rejected.

## R12. Test-only third vocabulary

**Decision.** `saga-forge-core`'s test sources gain `TestDomainTypeResolver` over four test annotations
(`@TestAggregate`, `@TestId`, `@TestEvent(of = ...)`, `@TestCommand`) and handler detection through a fifth
(`@TestHandles`); `saga-forge-runtime`'s tests run the canonical saga over it (`ThirdVocabularyTest`, SC-007), and core's
scanner tests run each identifier/role rule over it. It is the abstraction's first concrete caller and test double
(constitution, Development Workflow).

## Gaps to record (memory.md)

| Id | Gap |
|---|---|
| G-016-01 | Outbox delivery needs Saga Forge's `@Command(delivery = OUTBOX)` on a jMolecules command (spec D12). |
| G-016-02 | The fixture sees no Spring-published events (Spring Data `@DomainEvents`); tests register an event source. |
| G-016-03 | jMolecules defines no event-holding convention, so the module ships no event source. |
| G-016-04 | `DomainEvents.capture(Runnable)` knows only Saga Forge's markers; `capture(DomainTypes, Runnable)` is the workaround. |
| G-016-05 | Plain-typed identifiers on jMolecules events and commands need `@Association`; they are never inferred. |
| G-016-06 | The transport keys a by-type jMolecules event by its identifier type, not its aggregate (R6). |
