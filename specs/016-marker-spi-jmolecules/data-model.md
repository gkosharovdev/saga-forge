# Data Model: Marker SPI and jMolecules Adapter

**Feature**: `specs/016-marker-spi-jmolecules` | **Date**: 2026-11-27

No persisted data changes: no table, column or JSON field is added. This document describes the new in-memory types,
their invariants, and the rules and properties they introduce. Signatures are in [contracts/](contracts/).

## Types (core, package `io.github.gkosharovdev.sagaforge.core.spi.model` unless noted)

### `DomainRole` (enum)

`AGGREGATE`, `DOMAIN_EVENT`, `COMMAND`. A type has at most one role across all enabled vocabularies (R30). Command
handlers are a property of executables, not a type role.

### `Marker` (record)

| Component | Meaning |
|---|---|
| `vocabulary` | the resolver's name: `Saga Forge`, `jMolecules`, or an application's own |
| `display` | how errors name it: `@Aggregate`, `@DomainEvent`, `jMolecules @AggregateRoot`, `jMolecules AggregateRoot<T, ID>` |

Invariants: both non-blank. Built-in displays are exactly the simple annotation names used in today's error texts, which
is what keeps built-in texts unchanged (FR-011).

### `IdentifierMember` (final class)

| Property | Meaning |
|---|---|
| `marker()` | the `Marker` that made this member an identifier or a link |
| `member()` | the `Field` or no-argument `Method` |
| `name()` | the member name, for errors (`orderId`, `getId()`) |
| `valueType()` | the type of the value `read` returns (for an `Association<A, ID>` member: `ID`) |
| `namedAggregate()` | `Optional<Class<?>>`: the aggregate the member names (`@DomainEvent.aggregate`, `Association<A, ID>`, `@Association(aggregateType = A.class)`), empty when it resolves by `valueType` |
| `read(Object)` | the value; `IllegalStateException` naming member and type when it is null or the accessor throws |

Invariants: a method member takes no parameters and is non-void; the member is made accessible once at construction.
Equality is by member and marker.

### `DomainTypeResolver` (interface, SPI)

One vocabulary, model-free. For a type: `role` (with its `Marker`), `identifiers` (aggregate: its identity members;
event or command: its link members); for an executable: `commandHandler` (with its `Marker`); `name()`;
`describe(DomainRole)` for "missing marker" texts. An empty answer means "this vocabulary says nothing", never "no".

### `DomainTypes` (final class)

The composition used everywhere. State: the ordered resolvers (built-in first), per-class caches (`ClassValue`).
Immutable and thread-safe.

| Operation | Result |
|---|---|
| `builtIn()`, `of(DomainTypeResolver...)` | factories; `of` always prepends the built-in resolver and rejects a duplicate vocabulary name |
| `role(type)` | `Optional<DomainRole>`: the role every resolver that answers agrees on |
| `markers(type)` | all markers present on the type, for errors |
| `isAggregate/isDomainEvent/isCommand(type)` | shorthands |
| `commandHandler(executable)` | `Optional<Marker>` |
| `identifiers(type)` | declared identifier/link members, all vocabularies, in declaration order |
| `identifierValue(object)` | the single identifier's value, searching superclasses (callers that did so before) |
| `aggregateOf(type, aggregates)` | `AggregateResolution` (below) |
| `delivery(commandType)` | `CommandDelivery` from Saga Forge's `@Command`, else `IN_UNIT_OF_WORK` (D12) |
| `conflicts(type)` | R30 messages, empty when consistent |
| `describe(role)` | accepted markers: `@DomainEvent` (built-in only) or `@DomainEvent, or jMolecules @DomainEvent / DomainEvent` |

### `AggregateResolution` (sealed interface, nested in `DomainTypes`)

`Resolved(Class<?> aggregate, IdentifierMember link)` or `Unresolved(String reason, List<Class<?>> candidates)`. The
scanner turns `Unresolved` into an R31 error; the runtime calls it only for validated step event types.

### `AggregateEventSource` (interface, SPI, package `core.spi`)

`supports(aggregateType)`, `takeEvents(aggregate)` returning the pending events in registration order and clearing
them. Contract: after `takeEvents` returned a list, the same events are never returned again for that instance.

## Resolution rules

### Role (per type)

1. Ask every resolver in order; collect the roles they report.
2. None: no role. One distinct role: that role. Two distinct roles: R30 (validation error; at runtime the type is
   treated as having no role, which a validated model never reaches).

### Identifier of an aggregate

The union of identity members of all resolvers that report `AGGREGATE`. Exactly one member, or one per vocabulary
naming the same member (Saga Forge `@AggregateIdentifier` and jMolecules `@Identity` on one field): valid. Otherwise
Property 17's existing error with the markers present.

### Link of an event or command

The union of link members of all resolvers that report the type's role. Events: exactly one link (Property 17);
commands: at most one, and one when handled by an aggregate instance method (existing rule). A built-in
`@AggregateIdentifier` field on a `@DomainEvent` is a link that names `@DomainEvent.aggregate`.

### Aggregate of an event or command (R31)

1. The link names an aggregate: it must have role `AGGREGATE` (Property 16, existing text for the built-in case).
2. Otherwise: exactly one type in the model's aggregates whose identifier `valueType` equals the link's `valueType`.
3. Otherwise R31.

### Ordering key (transport)

Event: `<named aggregate>#<id>` when the link names one, else `<link valueType>#<id>`; outcome events and commands
unchanged. `id` is `String.valueOf(read(...))`.

## Scanner rules (added to `.kiro/specs/saga-forge-framework/design.md`)

| Rule | Property | Check | Error text |
|---|---|---|---|
| R30 | 48 | No type has two roles, and no type has identifier members from two vocabularies naming different members or aggregates | `"{Type} is marked {marker1} ({role1}) and {marker2} ({role2}); a type has one role"` / `"{Type} names different identifiers: {marker1} {member1}, {marker2} {member2}"` |
| R31 | 16 | Every event and command reached in the model resolves its aggregate | `"{Type} ({marker}) concerns no known aggregate: {reason}. Add an Association<A, ID> member or @Association(aggregateType = A.class), or give the aggregate its own identifier type"`; with candidates: `"... identifier type {IdType} belongs to {A}, {B}"` |

Existing rules whose texts become parametrised (byte-identical for built-in models): the identifier-count rules
(Property 17), event aggregate (Property 16), command handler parameter count, loader/saver shape, instance-handler
command identifier, step parameter "Non-@DomainEvent param", no `@CommandHandler` on a saga, one handler per command.

## Properties

- **Property 16 (restated)**: every domain event, under any aggregate marker or link, concerns exactly one type that has
  an aggregate marker.
- **Property 17 (restated)**: every aggregate has exactly one identifier member, every domain event exactly one link,
  every command at most one, under any identifier marker.
- **Property 48 (vocabulary equivalence)**: for any model *M* marked with Saga Forge's annotations and any model *M'*
  obtained by replacing each domain marker with an enabled vocabulary's equivalent (same roles, same identifiers and
  links, same aggregates), validation of *M* and *M'* yields the same rule violations, and for any event sequence the
  two runtimes produce the same step records, statuses, outcomes, outbox contents (types and ordering keys) and outcome
  events. Tested by `VocabularyEquivalencePropertyTest` (jqwik, over the test vocabulary) and the jMolecules module's
  canonical saga test.
- **Property 49 (harvested = recorded)**: for any aggregate command handler that registers events *e1..en* on the
  aggregate, with an event source supporting the aggregate's type, the unit of work's recorded events, in-process
  continuations, outbox contents and fan-out (Property 47) equal those of the same handler calling
  `DomainEvents.record(e1..en)` as its last statements, and *e1..en* are delivered by no other route and never by a
  later attempt of a rolled-back unit of work. Tested by `HarvestPropertyTest`.

## State transitions

None new. Saga, step, message and compensation state machines are unchanged.
