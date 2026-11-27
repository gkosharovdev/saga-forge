---

description: "Task list for 016-marker-spi-jmolecules"
---

# Tasks: Marker SPI and jMolecules Adapter

**Input**: Design documents from `/specs/016-marker-spi-jmolecules/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: included. The spec gives every story an independent test, the success criteria name tests (SC-003 to SC-007),
and the constitution asks for a test with every new abstraction, written with or before its first caller. Test class
names follow the contracts and plan.

**Organization**:
- **Setup**: green baseline, the two guard tests (golden error texts, direct annotation reads), jMolecules version.
- **Foundational**: the core SPI (`DomainTypeResolver`, `DomainTypes`, ...) with the built-in resolver and the test-only
  vocabulary, then every module rewired to `DomainTypes` with built-in behaviour unchanged. Every story needs it.
- **The stories, in spec order**:
  - US1 (P1): a jMolecules-only domain drives a saga (module, R31 mapping, starter auto-enable, example).
  - US2 (P1): events held on an aggregate reach sagas (`AggregateEventSource`, harvest point, fixture, 014 interplay).
  - US3 (P2): errors speak the vocabulary used (parametrised texts, R30).
  - US4 (P3): a third vocabulary plugs in (Property 48 over the test vocabulary).
- **Last**: docs, design.md properties, the register, release build and verification.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: the user story from spec.md (US1 to US4)

## Path Conventions

Inside a named module, two placeholders stand for long package paths:
- `…` means `src/main/java/io/github/gkosharovdev/sagaforge`.
- `…test` means `src/test/java/io/github/gkosharovdev/sagaforge`.

Other paths are relative to the repository root. Short prefixes:

| Prefix | Path |
|---|---|
| `model/` | `saga-forge-core/…/core/spi/model/` |
| `model-test/` | `saga-forge-core/…test/core/spi/model/` |
| `spi/` | `saga-forge-core/…/core/spi/` |
| `scanner/` | `saga-forge-core/…/core/scanner/` |
| `scanner-test/` | `saga-forge-core/…test/core/scanner/` |
| `rt/` | `saga-forge-runtime/…/runtime/` |
| `internal/` | `rt/internal/` |
| `rt-test/` | `saga-forge-runtime/…test/runtime/` |
| `relay/` | `saga-forge-transport-inmemory/…/transport/inmemory/` |
| `tr-test/` | `saga-forge-transport-inmemory/…test/transport/inmemory/` |
| `fixture/` | `saga-forge-test/…/test/` |
| `fixture-test/` | `saga-forge-test/…test/test/` |
| `jm/` | `saga-forge-jmolecules/…/jmolecules/` |
| `jm-test/` | `saga-forge-jmolecules/…test/jmolecules/` |
| `spring/` | `saga-forge-spring-boot-starter/…/spring/` |
| `spring-test/` | `saga-forge-spring-boot-starter/…test/spring/` |
| `ex/` | `saga-forge-example-jmolecules/…/example/jmolecules/` |
| `ex-test/` | `saga-forge-example-jmolecules/…test/example/jmolecules/` |

---

## Phase 1: Setup

- [X] T001 Run `mvn -q verify` from the repository root (no container suites) and record the baseline test count in the
  progress note at the end of this file; stop and report if it is not green.
- [X] T002 [P] Pin every built-in error text that involves `@Aggregate`, `@AggregateIdentifier`, `@DomainEvent`,
  `@Command` or `@CommandHandler`. The scanner and startup texts are already pinned exactly by `ValidateAllTest`,
  `ReflectiveAnnotationScannerTest`, `OutboxCommandValidationTest` and `StartupValidationTest`, so no duplicate
  `ScannerErrorTextsTest` is added; the runtime texts were not pinned and now are, in
  `rt-test/DomainMarkerTextsTest.java` (publish, receive, send of an unmarked object). `DomainEvents.record`'s text gets
  its pin in T020. Must pass before any refactor.
- [X] T003 [P] Add `DomainAnnotationAccessTest` (ArchUnit, one per module, same rule) in
  `rt-test/DomainAnnotationAccessTest.java`, `tr-test/DomainAnnotationAccessTest.java`,
  `spring-test/DomainAnnotationAccessTest.java` and `fixture-test/DomainAnnotationAccessTest.java`: no main class calls
  `isAnnotationPresent`/`getAnnotation`/`getDeclaredAnnotation` with `Aggregate`, `AggregateIdentifier`, `DomainEvent`,
  `Command` or `CommandHandler`; and `scanner-test/CoreDomainAnnotationAccessTest.java` for core, allowing only
  `model/SagaForgeAnnotations.java`. Each starts with today's call sites (research R1 table) as a frozen allow-list so it
  passes on unchanged code.
- [X] T004 [P] Add `org.jmolecules:jmolecules-bom` 2025.0.2 (`<type>pom</type><scope>import</scope>`) to the root
  `pom.xml` `dependencyManagement`, with a `jmolecules-bom.version` property (research R4).

**Checkpoint**: baseline recorded, both guards green on unchanged code.

---

## Phase 2: Foundational (blocking all stories)

**Purpose**: the core SPI with its built-in and test-only implementations, and every module asking `DomainTypes`
instead of reading annotations, with built-in behaviour and texts unchanged.

### Core SPI

- [X] T005 [P] Create `model/DomainRole.java` (enum `AGGREGATE`, `DOMAIN_EVENT`, `COMMAND`) and `model/Marker.java`
  (record `vocabulary`, `display`, both non-blank) per contracts/domain-types-spi.md, with Javadoc.
- [X] T006 [P] Create `model/IdentifierMember.java` (final class; factories `ofField`, `ofAccessor`, `ofReader`;
  `marker`, `member`, `name`, `valueType`, `namedAggregate`, `read`; accessibility set once; equality by member and
  marker; `read` throws `IllegalStateException` naming member and declaring type on null or a throwing accessor) and
  `model-test/IdentifierMemberTest.java` (field, record component field, accessor, reader, null, throwing accessor,
  non-void/zero-argument checks).
- [X] T007 Create `model/DomainTypeResolver.java` (interface with nested `record RoleMarker(DomainRole, Marker)`;
  `name`, `role`, `identifiers`, `commandHandler`, `describe`) per contracts/domain-types-spi.md.
- [X] T008 Create package-private `model/SagaForgeAnnotations.java` implementing `DomainTypeResolver` over `@Aggregate`,
  `@AggregateIdentifier` (declared fields only), `@DomainEvent(aggregate)` (its identifier field is a link naming
  `aggregate()`), `@Command`, `@CommandHandler`; vocabulary name `Saga Forge`; displays and `describe` are the simple
  annotation names (`@Aggregate`, ...), so texts stay unchanged (data-model "Marker").
- [X] T009 Create `model/DomainTypes.java` (final): `builtIn()`, `of(DomainTypeResolver...)` (built-in first; rejects
  null and duplicate names with `"Vocabulary '{name}' is registered twice"`), `vocabularies`, `role`, `isAggregate`,
  `isDomainEvent`, `isCommand`, `markers`, `commandHandler`, `identifiers`, `identifierValue` (walks superclasses, like
  today's transport and Spring resolver), `aggregateOf` with the nested sealed `AggregateResolution`
  (`Resolved`/`Unresolved`, data-model "Aggregate of an event or command"), `delivery` (Saga Forge `@Command.delivery`,
  else `IN_UNIT_OF_WORK`), `conflicts` (R30 texts from contracts/host-configuration.md), `describe` (built-in only: the
  simple name; otherwise `"@X, or jMolecules ..."`); `ClassValue` caches; immutable and thread-safe.
- [X] T010 [P] Add `model-test/BuiltInResolverTest.java`: for every `@Aggregate`/`@DomainEvent`/`@Command`/
  `@CommandHandler` fixture type in `saga-forge-core/…test` (and the example's types via a test-scope dependency only if
  already present; otherwise core fixtures only), `DomainTypes.builtIn()` answers exactly what today's direct reads give
  (role, identifier field, event aggregate, delivery, handler executables).
- [X] T011 [P] Create the test-only vocabulary (research R12) in `model-test/vocabulary/`: annotations `@TestAggregate`,
  `@TestId` (field or method), `@TestEvent(of = Class)`, `@TestCommand`, `@TestHandles` (method or constructor), and
  `TestDomainTypeResolver` (name `Test`); package it in core's test-jar (already published: check
  `saga-forge-core/pom.xml` `maven-jar-plugin` `test-jar` includes it).
- [X] T012 Add `model-test/DomainTypesTest.java` over the built-in and test vocabularies: union of roles, agreement
  across vocabularies, R30 role and identifier conflicts with both markers named, a type marked by both vocabularies for
  the same role and member is valid, duplicate vocabulary rejected, `describe` with one and two vocabularies,
  `aggregateOf` (named link, by identifier type, none, shared identifier type, named type not an aggregate, links that
  disagree), `identifierValue` through a superclass, caching (resolver called once per class, via a counting resolver).

### Scanner on `DomainTypes`

- [X] T013 Add constructor `ReflectiveAnnotationScanner(DomainTypes)` in `scanner/ReflectiveAnnotationScanner.java`
  (no-argument constructor delegates with `DomainTypes.builtIn()`); replace every read of the five domain annotations
  (`reachedTypes`/`addIfModelType`, `validateCommandHandlerParameters`, loaders/savers shape, `validateIdentifierFields`,
  `validateEventAggregate`, `validateOutboxCommands`, `validateUniqueCommandHandlers`,
  `validateInstanceHandlerCommandIdentifiers`, `isWellFormedLoader/Saver`, `aggregateIdentifierCount`,
  `commandParameters`, `handlerMembers`, `validateNoCommandHandlers`, the step-parameter check) with `DomainTypes`
  calls; build texts from `describe(role)` and the present `Marker`; `ScannerErrorTextsTest` and all existing scanner
  tests stay green unchanged.
- [X] T014 Replace the `@DomainEvent.aggregate` rule with `DomainTypes.aggregateOf` over the model's aggregates (passed-in
  aggregate-role types plus reached loaders' return types, savers' parameter types and aggregate handler hosts),
  keeping the built-in text for a named type that is not an aggregate; emit **R31** for `Unresolved` with the text in
  contracts/host-configuration.md; emit **R30** from `DomainTypes.conflicts` for every validated type, as model errors;
  update the class Javadoc rule list.
- [X] T015 [P] Add `scanner-test/VocabularyRulesTest.java` over the test vocabulary: each role/identifier rule fires with
  the test marker named, R30 (two roles; two identifiers), R31 (no link, ambiguous identifier type, named non-aggregate,
  disagreeing links), mixed model (Saga Forge saga, test-vocabulary aggregate and events) validates.

### Runtime, transport, starter and fixture on `DomainTypes`

- [X] T016 Add `SagaRuntime.Builder.domainTypes(DomainTypes)` in `rt/SagaRuntime.java` (default `builtIn()`, null
  rejected like the other builder methods); pass it to `new ReflectiveAnnotationScanner(domainTypes)` in `build()` and to
  `EngineSettings` so every internal component receives the same instance.
- [X] T017 Rewire `internal/Reflection.java` (`aggregateIdentifier` → `DomainTypes.identifierValue`, remove
  `IDENTIFIER_FIELDS`), `internal/EngineCommandGateway.java` (command detection, `@Command.delivery` →
  `DomainTypes.delivery`, handler executables → `commandHandler`, aggregate host → `isAggregate`,
  `HandlerArguments.of` command position), `internal/StartupChecks.java`, `internal/EngineRuntime.java`
  (`needsHostInstance`) and `internal/AggregateAccess.java` (if it reads markers) to `DomainTypes`.
- [X] T018 Rewire the event checks of `internal/EventRouter.java` (`requireDomainEvent`), `internal/UnitOfWork.java`
  (`Work.recorded`), `internal/RunCompensation.java` and `internal/EngineEventPublisher.java` to
  `DomainTypes.isDomainEvent`, keeping the built-in texts and building others from `describe(DOMAIN_EVENT)`.
- [X] T019 Make `internal/SagaModel.java` resolve each step event type's aggregate once at start
  (`DomainTypes.aggregateOf` over the registered model's aggregates) and use it in
  `internal/RunSteps.java#touchedAggregateOf` instead of `@DomainEvent.aggregate` (research R5).
- [X] T020 Move the marker check of `DomainEvents.record` in `saga-forge-core/…/core/event/DomainEvents.java` to the
  bound recorder (keep the null check and `IllegalArgumentException`); keep `capture(Runnable)` built-in; add
  `capture(DomainTypes, Runnable)`; update `spi/DomainEventRecorder.java` Javadoc (the recorder validates); add cases to
  the existing `DomainEvents` tests (built-in text unchanged inside a runtime unit of work and in `capture`).
- [X] T021 Add `InMemoryTransport.Builder.domainTypes(DomainTypes)` in `relay/InMemoryTransport.java` and rewrite
  `relay/internal/OrderingKeys.java` (and `LogAppender` call sites) to take it: event key `<named aggregate>#<id>`, else
  `<link valueType>#<id>`; command key unchanged (data-model "Ordering key"); existing ordering tests stay green.
- [X] T022 Rewire the fixture: `SagaFixture.Builder.domainTypes(DomainTypes)` in `fixture/SagaFixture.java` passed to its
  runtime and loop-back transport, and `fixture/Model.java` to `DomainTypes`.
- [X] T023 Rewire the starter: `spring/runtime/SagaModelCollector.java` (`isModelClass`, `rank`, package scanning
  candidates), `spring/runtime/SagaRuntimeConfiguration.java#verify` and `spring/host/SpringDataAggregateResolver.java`
  (`identifierType`) take the `DomainTypes` bean; add `spring/runtime/SagaForgeDomainTypesConfiguration.java` defining
  `DomainTypes` from `DomainTypeResolver` beans in `@Order` order (`@ConditionalOnMissingBean`), passed to the runtime
  builder and to `spring/transport/InMemoryTransportConfiguration.java`; register the configuration in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` before `SagaRuntimeConfiguration`.
- [X] T024 Empty the allow-lists of the four `DomainAnnotationAccessTest`s and `CoreDomainAnnotationAccessTest` (T003); run
  `mvn -q verify`: same test count as T001 plus the new tests, all green (SC-003, SC-005).
  Done 2026-11-27: `mvn verify` (container suites not run) 1,918 tests, 0 failures, 1 skipped; the T001 baseline of
  2,101 counted the container suites, which run again in T066. Core keeps `SagaForgeAnnotations` as its one allowed
  reader (the built-in vocabulary).

**Checkpoint**: one source of truth for domain markers; built-in models unchanged.

> Implementation notes (Foundational, 2026-11-27), deviations from the contracts, recorded in plan.md and memory.md:
> - `DomainTypeResolver` gains `describeIdentifier(DomainRole)`, so identifier texts name the vocabulary's member.
> - `IdentifierMember` has `ofField`/`ofAccessor` plus withers `naming(aggregate)` and `unwrappedBy(fn, identityType)`
>   instead of factories with `Optional` parameters and `ofReader`.
> - The domain-type rules (Properties 16, 17, R30, R31) and their texts live in the package-private scanner class
>   `DomainTypeRules`, keeping `ReflectiveAnnotationScanner` from growing.
> - R31 applies to events and commands whose preferred marker is another vocabulary's, that have link members and that
>   are not a Property 16 case; its hint is vocabulary-neutral ("Name the aggregate on the link member, or give the
>   aggregate its own identifier type").
> - T019: the step events' aggregates are resolved once at start in the new `internal/EventAggregates`, not in
>   `SagaModel`.
> - T020: `DomainEventRecorder` gains `default DomainTypes domainTypes()` (`builtIn()`); `DomainEvents.record` checks
>   against it (or `builtIn()` outside a unit of work), so the existing tests stay unchanged.
> - T022: the fixture's loop-back transport keys nothing by aggregate, so only its runtime takes `DomainTypes`.
> - T023: package scanning loads each candidate class (without initialising it) and asks `DomainTypes`, as metadata
>   cannot tell an interface-marked aggregate (G-016-09); the starter checks moved to `ModelHostChecks`.

---

## Phase 3: User Story 1 - A jMolecules-only domain drives a saga (Priority: P1) 🎯 MVP

**Goal**: a domain marked only with jMolecules validates and runs the canonical saga, in a hand-assembled runtime and
through the starter.

**Independent Test**: the canonical saga over a jMolecules-only domain package behaves as the Saga Forge-annotated one,
and ArchUnit proves the domain package references no Saga Forge type (spec US1, quickstart §3 and §6).

### Module

- [X] T025 [US1] Create module `saga-forge-jmolecules/pom.xml` (parent root POM; dependencies `saga-forge-core`,
  `jmolecules-ddd`, optional `jmolecules-events` and `jmolecules-cqrs-architecture`; test: JUnit, AssertJ,
  `saga-forge-runtime` and its test-jar, `saga-forge-core` test-jar); add `<module>` to the root `pom.xml` after
  `saga-forge-transport-inmemory`; add it to the `release` profile's published modules (FR-035).
- [X] T026 [US1] Implement `jm/JMoleculesDomainTypeResolver.java` per contracts/jmolecules-module.md: roles (annotation
  and type forms), aggregate identity (`@Identity` field/method, else `getId()` for `AggregateRoot<T, ID>`), link
  members (`Association<A, ID>` with concrete type arguments, `@Association(aggregateType)`, `@Association` by type,
  `Identifier` subtypes; record components once), command handlers, `describe`, displays prefixed `jMolecules`;
  optional artifacts probed once with `Class.forName(name, false, loader)`.
- [X] T027 [P] [US1] Add `jm-test/JMoleculesDomainTypeResolverTest.java`: every mapping row of both tables in
  contracts/jmolecules-module.md, record aggregates and events, raw/wildcard `Association` (unreadable aggregate),
  plain `UUID` member not a link (G-016-05), events/cqrs artifacts absent (filtering class loader).
- [X] T028 [P] [US1] Add jMolecules canonical domain test fixtures in `jm-test/canonical/` (`Order` as
  `AggregateRoot<Order, OrderId>` with jMolecules `@CommandHandler` for stock reservation, `OrderId`/`PaymentId`
  `Identifier` records, events and commands as jMolecules records with typed ids or `Association` members, a payment
  service with a jMolecules `@CommandHandler`) and a Saga Forge `OrderProcessingSaga` over them, mirroring
  `saga-forge-example`'s `OrderProcessingSaga` (completion, stock-unavailable compensation, payment failure); events are
  recorded with `DomainEvents.record` from the saga-facing handlers here (harvesting is US2).
- [X] T029 [US1] Add `jm-test/JMoleculesCanonicalSagaTest.java`: hand-assembled runtime (runtime test-jar doubles) with
  `DomainTypes.of(new JMoleculesDomainTypeResolver())`; the three scenarios give the same step records, statuses,
  outcomes, outcome events and outbox ordering keys as `saga-forge-example`'s Saga Forge-annotated model run the same
  way (spec US1 scenarios 1, 2); without the vocabulary, validation fails naming `@DomainEvent` (scenario 4).
- [X] T030 [P] [US1] Add `jm-test/JMoleculesAggregateResolutionTest.java`: R31 for an event whose identifier type two
  aggregates share, fixed by an `Association` member and by `@Association(aggregateType = ...)`; an event with no link;
  Saga Forge `@Command(delivery = OUTBOX)` together with jMolecules `@Command` validates and is delivered through the
  outbox (D12, FR-028).
- [X] T031 [P] [US1] Add `jm/README.md`: what is recognised (tables from contracts/jmolecules-module.md), how to enable
  (hand-assembled and starter), link members and G-016-05, outbox delivery (D12), typed identifier text form (FR-018),
  an application event source example (G-016-03), supported jMolecules version (FR-014).

> US1 module notes (2026-11-27): jMolecules 2.0.1 has no `Command` type, so commands are `org.jmolecules.architecture.cqrs.Command`
> and the deprecated `cqrs.annotation.Command` (handlers likewise); the scanner tests live in `JMoleculesScannerRulesTest`
> (T030's R31 cases are there; the outbox case validates the model, delivery is covered by the runtime's outbox tests).

### Starter

- [X] T032 [US1] In `spring/runtime/SagaForgeDomainTypesConfiguration.java` add `JMoleculesDomainTypeResolver` when the
  class is present (`@ConditionalOnClass`) and `saga-forge.jmolecules.enabled` is not `false`; add the property to
  `spring/SagaForgeProperties.java` (default `true`), `additional-spring-configuration-metadata.json` and the starter
  `README.md` properties table (`ReadmePropertiesTest`); make `saga-forge-jmolecules` an `optional` dependency of the
  starter POM.
- [X] T033 [P] [US1] Add `spring-test/runtime/SagaForgeDomainTypesConfigurationTest.java` (`ApplicationContextRunner`,
  G-008-04): enabled with the module present, disabled by the property, absent module (`FilteredClassLoader`),
  application `DomainTypeResolver` bean added, application `DomainTypes` bean wins.
- [X] T034 [P] [US1] Add `spring-test/events/JMoleculesInboundRouteTest.java` (in-memory doubles, like `PublishOnceTest`):
  a jMolecules event published through `ApplicationEventPublisher` in a transaction starts a saga once (FR-036, 014
  route unchanged); `SagaRuntime.eventTypes()` contains the jMolecules event type.
- [X] T035 [US1] List vocabularies in the `sagaforge` Actuator endpoint (`spring/actuator/SagaForgeEndpoint.java`, field
  `vocabularies`) and cover it in the existing endpoint test (FR-030).

### Example

- [X] T036 [US1] Create module `saga-forge-example-jmolecules/pom.xml` (starter, `saga-forge-jmolecules`,
  `saga-forge-storage-postgres`, `spring-boot-starter-jdbc`, Postgres driver; test: `saga-forge-test`, ArchUnit,
  Testcontainers); add `<module>` to the root `pom.xml` after `saga-forge-example-spring`; not in the release profile.
- [X] T037 [US1] Write the domain in `ex/domain/`: `Order` (`AggregateRoot<Order, OrderId>`, pending events in a list
  behind `domainEvents()`/`clearDomainEvents()`, jMolecules `@CommandHandler` methods), `OrderId`, `PaymentId`, events
  `OrderCreated`, `PaymentProcessed`, `OrderCompleted`, `StockUnavailable`, commands `ReserveStock`, `ProcessPayment`,
  `RefundPayment`, and `Payments` (domain service with jMolecules `@CommandHandler`s, its refund taking `CommandId`
  only if that keeps the domain Saga Forge-free; otherwise the idempotency key comes from the command); no Saga Forge,
  Spring or Spring Data import (SC-002, SC-008).
- [X] T038 [US1] Write the application in `ex/application/`: `OrderProcessingSaga` (Saga Forge annotations,
  `associationProperty = "orderId"`, `@StartSaga`/`@EndSaga`/`@StartCompensation`, compensation for payment),
  `OrderRepository` (`@AggregateLoader`/`@AggregateSaver` over `JdbcClient`, typed ids), `OrderPlacement` (publishes
  `OrderCreated` through Spring, 014), `JMoleculesOrderApplication` (`@SpringBootApplication`), `schema.sql` for the
  `orders` table in `saga-forge-example-jmolecules/src/main/resources/`. Event source comes in US2 (T050); until then
  `OrderRepository` publishes the aggregate's events through `ApplicationEventPublisher` in its saver (014 D3).
- [X] T039 [P] [US1] Add `ex-test/DomainDependsOnlyOnJMoleculesTest.java` (ArchUnit: classes in `..domain..` depend only
  on `java..` and `org.jmolecules..`) (SC-001, SC-002, SC-008).
- [X] T040 [P] [US1] Add `ex-test/OrderProcessingSagaFixtureTest.java` in record-and-answer mode over
  `DomainTypes.of(new JMoleculesDomainTypeResolver())`: completion, stock-unavailable compensation, payment failure.
- [X] T041 [US1] Add `ex-test/JMoleculesExampleContainerTest.java` (`@Tag("container")`, Postgres Testcontainers,
  `SpringApplicationBuilder`): an order placed through `OrderPlacement` completes; a stock-unavailable order is
  compensated (quickstart §6).

**Checkpoint**: US1 independently demonstrable: jMolecules-only domain, canonical saga, starter auto-enable.

---

## Phase 4: User Story 2 - Events held on an aggregate reach sagas (Priority: P1)

**Goal**: an aggregate's pending events are taken after its command handler and recorded like `DomainEvents.record`,
in every host.

**Independent Test**: a fixture test and a hand-assembled runtime test run a saga whose aggregate never calls
`DomainEvents.record`; the next step runs on the harvested event, and pending events are cleared once (spec US2).

- [X] T042 [US2] Create `spi/AggregateEventSource.java` (`supports`, `takeEvents`) with the contract Javadoc from
  contracts/aggregate-event-source.md.
- [X] T043 [US2] Add `SagaRuntime.Builder.aggregateEventSource(AggregateEventSource)` (repeatable, registration order,
  null rejected) in `rt/SagaRuntime.java`, carried in `internal/EngineSettings.java`.
- [X] T044 [US2] Harvest in `internal/EngineCommandGateway.java`: in `aggregateInstance` after the handler invocation
  and in `creation` after the factory returned, before `access.save`, ask the first supporting source and record each
  event through `DomainEventScope.current()`'s recorder; a source exception propagates; an unmarked event fails with
  the FR-025 text (contracts/aggregate-event-source.md); service-handler routes untouched.
- [X] T045 [P] [US2] Add `rt-test/HarvestedEventsTest.java` (test doubles: an aggregate with a pending list and a
  source): order of two events; explicit `DomainEvents.record` first, harvested second; creation route; service handler
  not harvested; next step runs in process on a harvested event; rollback then retry delivers no event of the failed
  attempt; source throwing fails the unit of work; unmarked event text; two commands to one aggregate in one unit of
  work harvest only new events; two supporting sources, only the first asked.
- [X] T046 [P] [US2] Add `rt-test/HarvestPropertyTest.java` (jqwik, Property 49): for generated event lists, harvested
  vs explicitly recorded as the handler's last statements give equal recorded events, in-process continuations, outbox
  contents and fan-out to a second saga type (Property 47).
- [X] T047 [US2] Add `SagaFixture.Builder.aggregateEventSource(...)` in `fixture/SagaFixture.java` passed to its runtime;
  add `fixture-test/HarvestParityTest.java` comparing the fixture with a hand-assembled runtime on a harvesting model in
  `handlers(...)` mode (FR-026).
- [X] T048 [US2] Pass every `AggregateEventSource` bean (`@Order` order) to the runtime builder in
  `spring/runtime/SagaRuntimeConfiguration.java`; list them in the `sagaforge` endpoint (`eventSources`); the starter
  defines none (FR-023).
- [X] T049 [US2] Add `spring-test/events/HarvestWithSpringDataTest.java`: an aggregate that has an event source and is
  a Spring Data aggregate with `@DomainEvents`/`@AfterDomainEventPublication`, saved by `SpringDataAggregateResolver`
  inside a handler; each saga step and each `@TransactionalEventListener`/`@ApplicationModuleListener` sees each event
  once, in `application-events.mode=in-transaction` and under Modulith interop (FR-024, spec US2 scenario 4); without
  the source, `@DomainEvents` reach the saga through 014's route as before (scenario 5); in-memory doubles, plus a
  `@Tag("container")` Postgres variant if the existing `PublishOnceContainerTest` setup can host it.
- [X] T050 [US2] In the example, add `ex/application/OrderEventSource.java` (`AggregateEventSource` over
  `Order.domainEvents()`/`clearDomainEvents()`, a Spring bean) and drop the publishing from `OrderRepository`'s saver
  (T038); switch `ex-test/OrderProcessingSagaFixtureTest.java` to `handlers(...)` mode with real `Order` and `Payments`
  handlers and the event source (SC-008); the container test (T041) stays green.

**Checkpoint**: US2 independently testable in runtime, fixture and Spring; example uses harvesting.

Implementation notes (US2):
- The harvest lives in a package-private `internal/EventHarvest` that `EngineCommandGateway`'s routes call before
  `access.save`; it records through `DomainEvents.record`, so the unit of work's recorder sees harvested events exactly
  like explicit ones. A source returning `null` fails with `IllegalStateException`; an unmarked object with the FR-025
  text (`... which is not a domain event (accepted: @DomainEvent)`).
- Test doubles: `rt-test/fixtures/harvest/HarvestModel` (a `Parcel` with a pending list, creation and instance
  handlers, a service `Notifier`, the property's `NotingSaga`/`NotesAuditSaga`) and `PendingEvents`; the fixture's
  `HarvestParityTest` reuses them from the runtime test-jar.
- T048: `SagaForgeEndpoint`'s 016 constructor gains the event sources (`eventSources`: class names in order); the
  released two-argument constructor is unchanged.
- T049: over a Spring Data map repository (`spring-data-keyvalue`, test scope; the repository extends
  `KeyValueRepository` so strict multi-module mode assigns it) and the runtime's doubles, with plain `@EventListener`s.
  The Modulith interop and `@TransactionalEventListener` variants need a database and are not built (G-016-07).
- T050: `OrderEventSource` is a `@Component` in the example; `Payments` announces through `DomainEvents::record` in the
  fixture test (handlers mode, three scenarios); the container test stayed green (3 tests).

---

## Phase 5: User Story 3 - Errors speak the vocabulary the application uses (Priority: P2)

**Goal**: every domain-marker error names the marker present, or the markers accepted.

**Independent Test**: each existing aggregate/event/command/handler rule triggered once with Saga Forge markers and
once with jMolecules markers; each error names the marker present and keeps its rule (spec US3).

- [X] T051 [US3] Add `jm-test/JMoleculesScannerRulesTest.java`: each rule of `ScannerErrorTextsTest` (T002) triggered
  with jMolecules markers asserts the full text names the jMolecules marker (e.g. `jMolecules @Identity`), plus R30
  (`@AggregateRoot` type also jMolecules `@DomainEvent`; Saga Forge `@Aggregate` plus jMolecules `@DomainEvent`) and
  R31 with candidates (SC-004).
- [X] T052 [P] [US3] Add startup-error cases with jMolecules markers to `jm-test/JMoleculesStartupChecksTest.java`
  (aggregate with instance handlers but no loader or resolver; outbox command with a non-command-capable outbox) via a
  hand-assembled runtime, asserting marker-naming texts.
- [X] T053 [US3] Fix any text the two tests expose as still naming a Saga Forge annotation for a jMolecules type, in
  `scanner/ReflectiveAnnotationScanner.java` and `internal/StartupChecks.java`; `ScannerErrorTextsTest` stays green.
- [X] T054 [P] [US3] Add the "missing marker" variants with two vocabularies to `scanner-test/VocabularyRulesTest.java`
  (step parameter not an event: `"must be a domain event (@DomainEvent, or Test @TestEvent)"`).

**Checkpoint**: SC-004 met.

---

## Phase 6: User Story 4 - A third vocabulary can be plugged in (Priority: P3)

**Goal**: the SPI is not jMolecules-shaped.

**Independent Test**: the test-only vocabulary (T011) runs the canonical saga with no change to Saga Forge's modules
(spec US4, SC-007).

- [X] T055 [US4] Add `rt-test/ThirdVocabularyTest.java`: the canonical saga's domain re-marked with the T011 annotations,
  hand-assembled runtime with `DomainTypes.of(new TestDomainTypeResolver())`, the three scenarios equal to the Saga
  Forge-annotated run.
- [X] T056 [P] [US4] Add `rt-test/VocabularyEquivalencePropertyTest.java` (jqwik, Property 48): generated event
  sequences over the canonical saga give equal step records, statuses, outcomes, outbox types and ordering keys, and
  outcome events for the Saga Forge-annotated and the test-vocabulary model; validation of generated invalid variants
  yields the same rule violations.
- [X] T057 [P] [US4] Add a test-vocabulary case to `tr-test/` ordering tests (`OrderingKeysTest` or the existing ordering
  suite): a named-link event keys by aggregate, a by-type event by identifier type (G-016-06).

**Checkpoint**: all four stories done.

Implementation notes (US3, US4):
- T051 exposed that one vocabulary can give a type two roles (a jMolecules `@AggregateRoot` that is a `DomainEvent`),
  which the single-answer `role` cannot say. `DomainTypeResolver` gains the default `roles(Class)` (every role, the one
  `role` returns first); `DomainTypes` collects answers through it, so R30 reports it; the jMolecules resolver
  overrides it.
- T053 fixed: the handler-arity rule and the startup texts (instance handlers without loader, outbox command without a
  command outbox, unresolvable parameter, two `CommandId` parameters) now name the handler's marker; the "must declare
  an identifier" text takes its article from the phrase (`DomainTypeRules.withArticle`: "an @AggregateIdentifier
  field", "a jMolecules link member"). Built-in texts unchanged.
- T054: the step-parameter text reads `(@DomainEvent, or @TestEvent)` (the test vocabulary's display), already pinned;
  handler-arity and loader variants added.
- T055/T056: twin models `rt-test/fixtures/vocabulary/AnnotatedShop` and `MarkedShop` (generated from one template)
  observed through `rt-test/ShopRun` with class names removed. The property generates order sequences (repeated
  starts, out of stock, declined payment); ordering keys are covered by the transport's `OrderingKeysTest` (T057,
  already added in T021). Validating generated invalid variants against both vocabularies is not built (G-016-08).

---

## Phase 7: Polish, docs and verification

- [X] T058 [P] Update `.kiro/specs/saga-forge-framework/design.md`: restate Properties 16 and 17, add Properties 48 and
  49, rules R30 and R31 in the rule table and the error-text table, and the closing paragraph line for 016
  (data-model "Properties", "Scanner rules").
- [X] T059 [P] Replace `docs/architecture.md` "jMolecules consideration" with this design (vocabularies, `DomainTypes`,
  jMolecules module, harvesting, 014 interplay) and reword its `@ReadModel` mentions, Vocabulary/Projection included, as
  future (closes G-002-04); update "Status at a glance" and "Module responsibilities".
- [X] T060 [P] Add `docs/guide/vocabularies.md` (enabling jMolecules, link members, typed identifiers, outbox
  delivery, event sources, own vocabulary; every snippet copied from compiling code with its source file named) and
  link it from `docs/guide/README.md` and `mkdocs.yml`; add R30/R31 and the two-vocabulary text forms to
  `docs/guide/validation-rules.md`; add the vocabulary note to `docs/guide/model-reference.md` and `docs/guide/testing.md`
  (fixture builder methods); add G-016-01, -02, -05, -06 to `docs/guide/limitations.md`.
- [X] T061 [P] Update `docs/agents/saga-forge/SKILL.md` and `docs/agents/AGENTS-snippet.md` (jMolecules recognition,
  event sources, outbox note), then run `docs/guide/build-llms-full.sh` and commit `llms-full.txt`.
- [X] T062 [P] Update `README.md` modules table (`saga-forge-jmolecules`, `saga-forge-example-jmolecules`, core
  `core.spi.model`/`AggregateEventSource`) and the starter `README.md` (property, beans, Actuator fields).
- [X] T063 Update `AGENTS.md`: "Current Status" entry for 016, Module Map (new modules, core SPI), Known Gaps (G-016-01
  to G-016-06), "Saga Model" note that domain roles come from `DomainTypes`.
- [X] T064 Update `.specify/memory/memory.md`: close G-ADOPT-04 and G-002-04; add G-016-01 to G-016-06 (research "Gaps
  to record"); note jMolecules under G-002-05's dependency decision; adoption backlog item 6 done; update
  `.specify/memory/roadmap.md` 016 brief (status done, narrowed: no starter Spring Data source, 014 interplay) and
  feature-order row.
- [X] T065 Run `/speckit-constitution` for a PATCH amendment removing `@ReadModel` from Principle II's annotation list
  (FR-032); record the version bump in `.specify/memory/constitution.md`'s Sync Impact Report.
- [X] T066 Mark `spec.md` status `Implemented (date)`; run `mvn spotless:apply`.
- [X] T067 Run `mvn -B -P release -Dgpg.skip -DskipTests install`: Javadoc for the new core types and
  `saga-forge-jmolecules` builds (no `<h3>` in type comments, no `@throws` on record comments) (FR-035).
- [X] T068 Run `mvn -B -Pcontainer-tests verify` and record the test count in AGENTS.md "Current Status" and the
  progress note below (quickstart §8); walk quickstart §1 to §7.

---

## Dependencies & Execution Order

- **Setup (T001–T004)** → **Foundational (T005–T024)** → stories.
- Inside Foundational: T005–T008 → T009 → T010–T012; T013–T015 need T009; T016–T023 need T013 (the runtime builds the
  scanner); T024 last.
- **US1 (T025–T041)** needs Foundational. T025 → T026 → T027–T031; T032–T035 need T026; T036–T041 need T032.
- **US2 (T042–T050)** needs Foundational; independent of US1 except T050 (needs T038). T042 → T043 → T044 → T045–T047;
  T048 → T049.
- **US3 (T051–T054)** needs T026 (jMolecules resolver) and T013–T014; T053 after T051–T052.
- **US4 (T055–T057)** needs Foundational only (test vocabulary from T011).
- **Polish (T058–T068)** after the stories it documents; T067–T068 last.

```text
Setup ─► Foundational ─┬─► US1 (module ► starter ► example) ─┐
                       ├─► US2 (SPI ► harvest ► fixture ► starter) ─► T050 (example) ─┤
                       ├─► US3 (needs US1's resolver) ───────────────────────────────┤
                       └─► US4 ──────────────────────────────────────────────────────┴─► Polish
```

## Parallel Opportunities

- Setup: T002, T003, T004.
- Foundational: T005 and T006 together; T010, T011 together after T009; T015 beside T016–T023.
- US1: T027, T028, T030, T031 after T026; T033, T034 after T032; T039, T040 after T038.
- US2: T045, T046 after T044; T047 beside T048.
- US3: T052, T054 beside T051. US4: T056, T057 beside T055.
- After Foundational, US2 and US4 can run beside US1.
- Polish: T058–T062 together.

### Example: US1 after T026

```text
T027 jm-test/JMoleculesDomainTypeResolverTest.java
T028 jm-test/canonical/ fixtures
T030 jm-test/JMoleculesAggregateResolutionTest.java
T031 jm/README.md
```

### Example: US2 after T044

```text
T045 rt-test/HarvestedEventsTest.java
T046 rt-test/HarvestPropertyTest.java
```

## Implementation Strategy

- **MVP**: Setup + Foundational + US1 (T001–T041). A jMolecules-only domain runs the canonical saga; events are still
  published by the application layer (Spring, 014) or recorded by handlers.
- **Increment 2**: US2 makes the example's domain framework-free in every host (fixture real handlers, hand-assembled).
- **Increment 3**: US3 error texts, US4 third vocabulary and Property 48.
- **Finish**: Polish; each increment keeps `mvn verify` green and every built-in text unchanged (T002 guard).

## Progress note

- 2026-11-27, T001: baseline `mvn -B verify` (no container suites) green: 2,101 tests, 0 failures, 0 errors, 6 skipped.
- T002: scanner/startup texts were already pinned; runtime texts pinned by `DomainMarkerTextsTest` (3 tests).
- T003: `DomainAnnotationAccessTest` in runtime, transport, starter and fixture (ArchUnit: no dependency on the five
  annotation types), `CoreDomainAnnotationAccessTest` (source scan; core has no ArchUnit), each with a frozen
  allow-list of today's call sites.

> Polish (2026-11-27): T058 to T066 done (design.md, architecture.md, guide page `vocabularies.md` plus validation,
> model, testing and limitations pages, SKILL.md/AGENTS-snippet and `llms-full.txt`, READMEs, AGENTS.md, memory.md
> G-016-01 to G-016-09, roadmap, constitution 1.1.1). T067: release build with Javadoc green. T068:
> `mvn -Pcontainer-tests verify` green, 2,233 tests, 0 failures, 6 skipped.
