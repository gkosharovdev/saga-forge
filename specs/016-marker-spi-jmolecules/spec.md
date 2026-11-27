# Feature Specification: Marker SPI and jMolecules Adapter

**Feature Branch**: `016-marker-spi-jmolecules`

**Created**: 2026-11-27

**Status**: Implemented (2026-11-27)

**Input**: User description: "016 Marker SPI and jMolecules adapter (roadmap `.specify/memory/roadmap.md` brief 016,
booking-service feedback SF-3, workarounds W5 and W6) — closes G-ADOPT-04 and G-002-04. Let applications whose domain
depends only on jMolecules (or another marker vocabulary) use Saga Forge without double-annotating: a core SPI telling
the scanner which types and members count as aggregate, aggregate identifier and domain event (default: today's
annotations), a separate `saga-forge-jmolecules` module, and an `AggregateEventSource` SPI that harvests events held on
aggregates after a handler's aggregate is saved (the starter supports Spring Data `@DomainEvents`/
`@AfterDomainEventPublication`). Scanner error texts name the marker used. Out of scope: fluent model registration,
handlers returning events." Revised 2026-11-27 after 014 (publish once) merged: Spring-published events, Spring Data
`@DomainEvents` included, already reach sagas through 014's inbound route, so harvesting narrows to hosts without
Spring (roadmap brief 014, "interplay with 016").

## Context

Saga Forge recognises a domain only through its own annotations. An aggregate must carry `@Aggregate`, its identity
`@AggregateIdentifier`, an event `@DomainEvent(aggregate = X.class)`, a command `@Command` and a handler
`@CommandHandler`; and a handler records an event only by calling the static `DomainEvents.record(...)`. A team that
keeps framework types out of its domain model, typically by depending only on jMolecules, has two bad choices today:
annotate every domain type twice (jMolecules plus Saga Forge), or translate its domain events into Saga Forge event
types in the application layer (booking-service W5, W6). Aggregates that hold their events until they are saved (the
pattern of Spring Data's `@DomainEvents`, and a common hand-written one in jMolecules domains) cannot hand those events to sagas at all
without a call to `DomainEvents.record` from inside the domain, unless Spring delivers them: since 014 (publish once)
the starter's inbound route records an event published through Spring's `ApplicationEventPublisher`, Spring Data
`@DomainEvents` on save included, as a recorded event of the running unit of work, or stores it in the publishing
transaction. Without Spring (a hand-assembled runtime, the test fixture `SagaFixture`) there is no such route.

The marker check is not in one place. The scanner, the runtime (routing, command dispatch, startup checks, the unit of
work, publishing), the in-memory transport's ordering keys, the starter's model collection and aggregate resolver, and
the test fixture each test for Saga Forge's annotations on their own. 014's additions do not: its inbound route and its
fan-out (Property 47) match on the event types the model's steps take (`SagaRuntime.eventTypes()`), so they follow
whatever the scanner accepts as a step's event. Recognising another vocabulary therefore means
giving all of them one source of truth first.

`docs/architecture.md` "jMolecules consideration" records the intent (optional interop, no hard dependency) and still
presents a `@ReadModel` annotation that does not exist (G-002-04).

This feature introduces a **model vocabulary**: the set of markers that tell Saga Forge which types and members play
which domain role. Saga Forge's annotations stay the built-in vocabulary and stay recognised everywhere. A separate
`saga-forge-jmolecules` module adds jMolecules' markers. An **aggregate event source** lets the engine collect the
events an aggregate holds after a command handler ran, as if the handler had recorded them, so a pure domain never
calls Saga Forge, also where no Spring route exists. In a Spring application 014's route remains the way Spring Data
aggregates' events reach sagas; the starter adds no event source of its own.

## Decisions Taken In This Spec

These settle the questions in the roadmap brief; each is reflected in the requirements below.

- **D1 — Additive vocabularies.** Saga Forge's annotations are always recognised. A vocabulary adds markers, it never
  replaces the built-in ones, so a model can mix both (useful while migrating) and existing applications change
  nothing. A type may carry markers of more than one vocabulary as long as they agree on its role.
- **D2 — What the vocabulary covers.** Every role the model reads from a *domain* type: aggregate, aggregate identifier,
  domain event (and the aggregate it is about), command (and the aggregate it targets), and command handler. Saga-side
  annotations (`@Saga`, `@SagaId`, `@SagaStep`, `@StartSaga`, `@EndSaga`, `@StartCompensation`, `@SagaCompensation`,
  `@StartDeadline`, `@DeadlineExpired`, `@RetryPolicy`) and application-layer annotations (`@AggregateLoader`,
  `@AggregateSaver`) stay Saga Forge's own: sagas and repositories are application code. Commands and command handlers
  are included because the canonical saga's stock reservation is handled by an aggregate instance method, so a domain
  that depends only on jMolecules needs them.
- **D3 — One source of truth.** Every module that asks "is this an aggregate / event / command / handler, and where is
  its identifier" asks the vocabulary, not the annotation directly. No module keeps its own marker check.
- **D4 — An identifier may be a field or an accessor.** jMolecules aggregate roots expose their identity through
  `getId()` and `@Identity` may sit on a method. The model accepts an identifier member that is a field or a
  no-argument accessor, when the vocabulary marks it so. Saga Forge's own `@AggregateIdentifier` stays field-only.
- **D5 — Harvesting is a separate SPI from the vocabulary.** Which types are aggregates and how an aggregate hands over
  its pending events are different questions with different implementers: a vocabulary answers the first; an
  application (or a later integration) answers the second for the way its aggregates hold events. jMolecules defines
  no event-holding convention (its DDD types have none; `@DomainEventPublisher` only documents a publishing method), so
  the jMolecules module ships a vocabulary and no event source. Harvested events are recorded exactly like `DomainEvents.record`:
  same unit of work, same outbox, same routing, same ordering.
- **D6 — Harvesting covers aggregates the engine handled.** The engine harvests from aggregates it loaded or created
  for a `@CommandHandler` (and its vocabulary equivalent), after the handler returned and before the aggregate's save,
  if one runs. Taking the events before the save means a save that would publish them itself (Spring Data's
  repository `save`) finds none, so the same event never enters the unit of work twice, once harvested and once
  through 014's inbound route (D10).
  An aggregate that application code saves outside a command handler is not harvested here; publishing those events to
  sagas is roadmap feature 014's inbound route.
- **D7 — Error texts name the marker used.** A scanner or startup error about a type marked with jMolecules names the
  jMolecules marker, so an application never reads about an annotation it does not use.
- **D8 — Formal properties restated, not weakened.** Properties 16 and 17 (an event names an aggregate; aggregates and
  events have exactly one identifier) are restated over "aggregate marker" and "identifier marker". New rules and
  properties are numbered after the existing ones: R30 onward and Property 48 onward (014 took Property 47).
- **D9 — Integration placement.** jMolecules is a dependency of `saga-forge-jmolecules` only; core and runtime gain no
  dependency (Principle I). The starter enables the jMolecules vocabulary when the module is on the classpath, with a
  property to turn it off, and hands application-defined vocabulary and event source beans to the runtime. It ships no
  Spring Data event source: 014's inbound route already covers Spring Data `@DomainEvents` (014 D3, FR-005, FR-006).

- **D10 — Harvesting and 014's route never both take an event.** A harvested event is a recorded event, so it reaches
  Spring listeners through the unit of work's existing publication (`saga-forge.application-events.mode`, 014's
  `AlreadyPublishedEvents` guard keeps the inbound route from storing it again). An event taken by an event source is
  cleared from the aggregate before the save, so a Spring Data save cannot publish it as well. Where no event source
  covers an aggregate, 014's route alone applies, unchanged.
- **D11 — Explicit aggregate link through jMolecules associations.** A jMolecules event or command finds its aggregate
  from the type of its identifier; when that type is shared by several aggregates, a jMolecules
  `Association<Aggregate, Id>` member (or an `@Association`-annotated member) names the aggregate explicitly. No
  application-layer mapping.
- **D12 — Outbox delivery stays on Saga Forge's `@Command`.** A command type that needs `CommandDelivery.OUTBOX`
  carries Saga Forge's `@Command(delivery = OUTBOX)`, also when it is otherwise marked with jMolecules. Delivery is a
  transport policy, not a domain role; accepting this one double annotation avoids a second delivery mechanism.
  Recorded as gap G-016-01.

## Clarifications

### Session 2026-11-27

- Q: When two aggregates share an identifier type, how does a jMolecules event or command name its aggregate? → A: a
  jMolecules `Association<Aggregate, Id>` (or `@Association`) member names it; typed identifiers resolve by type
  otherwise (D11, FR-016).
- Q: Which route publishes harvested Spring Data events to Spring listeners? → A: Saga Forge only: the engine harvests
  and clears before Spring Data's save, so Spring Data publishes nothing for them. **Superseded the same day** by 014
  (publish once, merged before this spec's plan): Spring Data `@DomainEvents` reach sagas through 014's inbound route,
  so the starter ships no Spring Data event source; the answer survives only as the rule that an event source clears
  before the save (D6, D10, FR-024).
- Q: How does a jMolecules-only command get outbox delivery? → A: it keeps Saga Forge's `@Command(delivery = OUTBOX)` on
  its type; double annotation accepted for this policy and recorded as a gap (D12, FR-028).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A jMolecules-only domain drives a saga (Priority: P1)

A team models its domain with jMolecules only: `@AggregateRoot` (or `AggregateRoot<T, ID>`) aggregates with an
`@Identity`, jMolecules `@DomainEvent` (or `DomainEvent`) events and jMolecules CQRS `@Command`/`@CommandHandler`
types. It adds `saga-forge-jmolecules` and writes a saga with Saga Forge's saga annotations. The saga starts on a
jMolecules event, sends commands handled by a jMolecules aggregate and ends, without a single Saga Forge annotation in
the domain package.

**Why this priority**: it is the adopter's blocking workaround (W5) and the feature's acceptance test.

**Independent Test**: the canonical order saga, with its domain package rewritten to depend only on jMolecules, passes
the same scenarios as the Saga Forge-annotated version, and an architecture test proves the domain package references
no Saga Forge type.

**Acceptance Scenarios**:

1. **Given** a domain whose aggregate, events and commands carry only jMolecules markers and an application with the
   jMolecules vocabulary enabled, **When** the model is validated, **Then** it is valid with the same rules applied as
   to the Saga Forge-annotated equivalent.
2. **Given** that model running, **When** the start event is published, **Then** the saga starts, its commands are
   handled by the jMolecules aggregate (loaded and saved as today), and it completes with the same step records,
   outcome and outcome event as the Saga Forge-annotated equivalent.
3. **Given** a jMolecules event whose aggregate cannot be determined, **When** the model is validated, **Then**
   validation fails with an error that names the event type, the jMolecules marker and what is missing.
4. **Given** the jMolecules vocabulary is not enabled, **When** a step takes a jMolecules-only event, **Then**
   validation fails exactly as today, naming Saga Forge's `@DomainEvent`.

---

### User Story 2 - Events held on an aggregate reach sagas without `DomainEvents.record` (Priority: P1)

An aggregate registers events on itself while handling a command (for example a jMolecules aggregate root that keeps
a list of pending events). The application provides an event source for that convention. After the command handler
returns, before the aggregate is saved, the engine collects those events and treats them exactly like recorded events:
they reach saga steps in order, inside the same unit of work, and through the same outbox, in a hand-assembled runtime,
in the test fixture and in a Spring application alike.

**Why this priority**: without Spring, a pure domain still has to call a Saga Forge static (W6), and the fixture, which
has no Spring, cannot run such a domain's real handlers at all; with it the domain is framework-free in every host.

**Independent Test**: a fixture test and a hand-assembled runtime test run a saga whose aggregate never calls
`DomainEvents.record`; the next step runs on the harvested event, and the aggregate's pending events are cleared
exactly once.

**Acceptance Scenarios**:

1. **Given** an aggregate handler that registers two events on the aggregate, **When** the handler returns,
   **Then** both events are recorded in registration order in that unit of work, and the aggregate's pending events are
   cleared.
2. **Given** a handler that both calls `DomainEvents.record` and registers an event on the aggregate, **When** the
   unit of work commits, **Then** both events are delivered once each, the explicitly recorded events first, then the
   harvested ones.
3. **Given** a handler whose unit of work rolls back after harvesting, **When** the attempt is retried, **Then** no
   harvested event of the failed attempt is delivered.
4. **Given** a Spring application with an event source registered for an aggregate that is also a Spring Data
   aggregate with `@DomainEvents`, **When** a handler changes it and the unit of work commits, **Then** each event
   reaches saga steps once and each Spring application listener once (harvested, not also caught by 014's route)
   (FR-024).
5. **Given** a Spring application with no event source for a Spring Data aggregate, **When** a handler saves it,
   **Then** its `@DomainEvents` reach sagas through 014's inbound route exactly as before this feature.

---

### User Story 3 - Errors speak the vocabulary the application uses (Priority: P2)

A developer misplaces an identifier or forgets a marker on a jMolecules-modelled type. The scanner and startup errors
name the jMolecules marker on the type and explain the fix in those terms.

**Why this priority**: a correct vocabulary with Saga Forge-only error texts would still read as "add `@Aggregate`",
pushing teams back to double annotation.

**Independent Test**: each existing aggregate/event/command/handler rule is triggered once with Saga Forge markers and
once with jMolecules markers; each error names the marker actually present and the rule's id.

**Acceptance Scenarios**:

1. **Given** a jMolecules aggregate with two `@Identity` members, **When** validated, **Then** the error names
   `@Identity` and the aggregate type, not `@AggregateIdentifier`.
2. **Given** a type marked as an aggregate by one vocabulary and as a domain event by another, **When** validated,
   **Then** validation fails naming both markers.

---

### User Story 4 - A third vocabulary can be plugged in (Priority: P3)

A team with its own in-house DDD annotations (or another library) provides a vocabulary of its own, the same way the
jMolecules module does, and gets the same validation, runtime behaviour and error texts.

**Why this priority**: proves the SPI is not jMolecules-shaped, but no adopter needs it yet.

**Independent Test**: a test-only vocabulary over a handful of custom annotations runs the canonical saga.

**Acceptance Scenarios**:

1. **Given** a test-only vocabulary with custom aggregate, identifier and event annotations, **When** the canonical
   saga runs over a domain using them, **Then** it behaves as the Saga Forge-annotated version.

---

### Edge Cases

- A type carries both a Saga Forge and a jMolecules marker for the same role: accepted; Saga Forge's attributes (for
  example `@DomainEvent.aggregate`, `@Command.delivery`) win where both say something, and the two must not contradict
  each other (FR-007).
- Two aggregates share an identifier type (`UUID`, `String`, `Long`), so an event's aggregate is not determined by type
  (FR-016).
- An aggregate identifier is a typed value (a record such as `OrderId`): associations compare its textual form, so
  events and commands of one saga must carry the same identifier type or one that renders the same text (FR-018).
- An identifier accessor throws or returns null: treated like a null identifier field today (the event is rejected
  when routed, the command when sent).
- An aggregate holds events but no saver runs (the client's own unit of work persists it): harvesting still runs after
  the handler returns (D6).
- A Spring Data aggregate saved by application code outside a command handler: 014's inbound route stores its saga
  events in the publishing transaction, unchanged; no event source is asked (D6).
- A jMolecules event published through Spring (014's inbound route) or fanned out to another saga type (Property 47):
  recognised as a saga event because a step takes its type; no change to 014's code is needed (FR-036).
- A fixture test of a Spring Data aggregate whose events are published by Spring on save: the fixture has no Spring,
  so those events are not seen unless the test registers an event source for the aggregate (G-016-02).
- A jMolecules event or command with both a typed identifier and an `Association` member naming different aggregates:
  validation fails (R31).
- An aggregate created by a factory handler holds events from construction: they are harvested after the creation save.
- A harvested event's type is not a domain event in any enabled vocabulary: the unit of work fails with an error naming
  the aggregate and the event type, like recording an unmarked event today.
- An aggregate's pending events are cleared by the event source, never by the engine poking at fields; an aggregate
  that cannot be cleared is harvested once per unit of work and the source is responsible for not reporting it again.
- An aggregate is handled twice in one unit of work (two commands to the same aggregate): each handling harvests only
  the events registered since the previous harvest.
- An event source is registered for a type the vocabulary does not see as an aggregate: ignored for that type.
- The jMolecules module is on the classpath but the property disables it: jMolecules markers are not recognised and no
  error mentions them.
- The fixture is built without a vocabulary for a jMolecules model: it fails with the same validation error a runtime
  would give.
- A jMolecules-only command that should go through the outbox: it needs Saga Forge's `@Command(delivery = OUTBOX)`
  (D12); without it, it is delivered in the sender's unit of work.
- An outcome event (011) or deadline message (017) is not a domain event of any vocabulary and is unaffected.

## Requirements *(mandatory)*

### Functional Requirements

#### Model vocabulary (core SPI)

- **FR-001**: Core MUST define a model vocabulary SPI that answers, for a type or member, whether it is an aggregate, an
  aggregate identifier, a domain event, a command or a command handler, and for an event or command, which aggregate it
  concerns and which member holds that aggregate's identity.
- **FR-002**: Saga Forge's own annotations MUST be the built-in vocabulary and MUST be recognised in every application,
  with or without other vocabularies (D1). An application that adds no vocabulary MUST see no behaviour change, error
  text change or new configuration.
- **FR-003**: A runtime, the starter and the test fixture MUST accept additional vocabularies; recognition MUST be the
  union of the built-in vocabulary and every added one.
- **FR-004**: Every module that today tests for `@Aggregate`, `@AggregateIdentifier`, `@DomainEvent`, `@Command` or
  `@CommandHandler` (scanner, runtime routing, command dispatch, startup checks, unit of work, event publishing,
  in-memory transport ordering keys, starter model collection and aggregate resolver, fixture) MUST obtain the answer
  from the vocabulary (D3), including the checks 014 added to `EventRouter` and `UnitOfWork`. An architecture test MUST fail when such a direct check reappears outside the built-in
  vocabulary.
- **FR-005**: The vocabulary MUST describe each answer with the marker that produced it (a display name such as
  `@org.jmolecules.ddd.annotation.AggregateRoot` or `AggregateRoot<T, ID>`), so errors can name it (D7).
- **FR-006**: An identifier member MAY be a field or a no-argument, non-void accessor method, as the vocabulary marks it
  (D4). Saga Forge's `@AggregateIdentifier` remains field-only.
- **FR-007**: A type that two vocabularies place in different roles (for example aggregate and domain event), or for
  which they name different aggregates or identifiers, MUST fail validation, naming both markers (new scanner rule
  R30).
- **FR-008**: Properties 16 and 17 in `.kiro/specs/saga-forge-framework/design.md` MUST be restated over "aggregate
  marker" and "identifier marker" with unchanged strength; a new Property 48 MUST state vocabulary equivalence: a model
  whose domain types are marked through any enabled vocabulary validates, routes, orders and handles exactly as the
  same model marked with Saga Forge's annotations.
- **FR-009**: The vocabulary SPI MUST stay in core with no third-party dependency (Principle I) and MUST be exercised
  by a test-only vocabulary as well as by the jMolecules one before it is relied on (constitution, Development
  Workflow).

#### Error texts

- **FR-010**: Every scanner and startup error about an aggregate, identifier, event, command or handler MUST name the
  marker actually present on the type (or, when a marker is missing, the markers the enabled vocabularies accept) and
  keep its rule id (D7).
- **FR-011**: Existing error texts for models that use only Saga Forge annotations MUST stay unchanged, so existing
  documentation and tests remain valid.

#### jMolecules module

- **FR-012**: A new module `saga-forge-jmolecules` MUST provide a vocabulary over jMolecules' DDD markers
  (`@AggregateRoot`, `AggregateRoot<T, ID>`, `@Identity`, `Identifiable<ID>`), event markers (`@DomainEvent`,
  `DomainEvent`) and CQRS markers (`@Command`, `@CommandHandler`, and their type equivalents), annotations and type
  interfaces alike.
- **FR-013**: The module MUST depend only on `saga-forge-core` and jMolecules' artifacts (no Spring, no runtime, no
  storage or transport) and MUST NOT make core, the runtime or the starter depend on jMolecules.
- **FR-014**: The module's supported jMolecules version line MUST be stated in its README and in the root dependency
  management.
- **FR-015**: A jMolecules aggregate's identity MUST be its `@Identity` member, or for `AggregateRoot<T, ID>` /
  `Identifiable<ID>` types, `getId()`.
- **FR-016**: The aggregate a jMolecules event or command concerns MUST be resolved, in this order: from a jMolecules
  `Association<Aggregate, Id>` member or an `@Association`-annotated member, which names the aggregate and holds its
  identity; otherwise from the type of its identifier members, when exactly one known aggregate has that identifier
  type (D11). Identifier members of a jMolecules event or command are `Association` members, `@Association` members
  and members whose type implements jMolecules `Identifier`; a plain-typed member (`UUID`, `String`) counts only when
  annotated `@Association` (plan research R4, G-016-05). An unresolved, ambiguous or contradictory mapping MUST fail validation naming the type, its candidate
  aggregates and how to disambiguate (add an association member or a distinct identifier type) (new scanner rule
  R31).
- **FR-017**: A jMolecules event or command with more than one candidate identifier member for its aggregate MUST fail
  validation, as Property 17 requires for Saga Forge's markers.
- **FR-018**: Associations, aggregate loading and ordering keys MUST work with typed identifiers: loading passes the
  identifier value as it is to the loader or resolver; associations and ordering keys use its text form, as today. The
  guide MUST state that a typed identifier's text form must be stable and identical across the events and commands of
  one saga.
- **FR-019**: The module MUST NOT ship an aggregate event source, since jMolecules defines no convention for holding
  events (D5); its README MUST show how an application provides one for its own convention, and memory.md MUST record
  that a convention-based source can be added if jMolecules or its integrations gain one (G-016-03).

#### Aggregate event source (harvesting)

- **FR-020**: Core MUST define an aggregate event source SPI that, for an aggregate instance, returns the events it
  holds in registration order and clears them once they are taken (D5).
- **FR-021**: After a `@CommandHandler` (or vocabulary equivalent) on or creating an aggregate returns, and before the
  aggregate's save if one runs (D6), the runtime MUST ask every applicable event source for the aggregate's events and
  record each as if `DomainEvents.record` had been called at that point: same unit of work, same outbox and recorded
  events, same routing, same fan-out to other saga types (Property 47), same at-least-once and per-aggregate ordering
  guarantees. Events recorded explicitly during the handler come first. A new Property 49 MUST state that a harvested
  event is indistinguishable downstream from the same event recorded at that point.
- **FR-022**: Harvested events MUST be discarded with a rolled-back unit of work and MUST NOT be delivered by a later
  attempt unless that attempt registers them again. Each event MUST be harvested at most once per unit of work.
- **FR-023**: The starter MUST NOT ship an event source for Spring Data aggregates; their `@DomainEvents` keep reaching
  sagas through 014's inbound route (D9). The starter MUST pass application-defined event source beans to the runtime.
- **FR-024**: In a Spring application, an event an event source took MUST reach each saga step and each Spring
  application listener (including Spring Modulith `@ApplicationModuleListener`s) exactly once: through the unit of
  work's existing publication to Spring, never also through 014's inbound route or Spring Data's own publication on
  save (D10). A Spring test MUST prove it for an aggregate that is both covered by an event source and a Spring Data
  aggregate with `@DomainEvents`, in the default mode and under Modulith interop.
- **FR-025**: A harvested event whose type is not a domain event in any enabled vocabulary MUST fail the unit of work
  with an error naming the aggregate type, the event type and the event source.
- **FR-026**: The test fixture MUST harvest exactly as the runtime does (record-and-answer and real-handler modes), and
  a parity test MUST compare the fixture with a hand-assembled runtime on a harvesting model.
- **FR-027**: Harvesting MUST be traced like recording today: harvested events carry the trace context of the unit of
  work that recorded them (010), with no new span required.

#### Commands

- **FR-028**: A jMolecules-only command MUST be delivered in the sender's unit of work. Outbox delivery MUST be declared
  with Saga Forge's `@Command(delivery = OUTBOX)` on the type, also when the type is otherwise marked with jMolecules
  (D12); the two markers together MUST validate (FR-007), and rule R23 and Property 35 hold unchanged. The guide MUST
  say so, and memory.md MUST record it as G-016-01.

#### 014 interplay

- **FR-036**: `SagaRuntime.eventTypes()`, and through it 014's inbound route and fan-out (Property 47), MUST include
  event types recognised by any enabled vocabulary, with no change to the starter's inbound listener; a Spring test MUST
  show a jMolecules event published through `ApplicationEventPublisher` starting a saga.
- **FR-037**: The ordering key of a stored event (014 D10) MUST be its aggregate identifier as the vocabulary reports it
  (field or accessor), in text form (FR-018), whichever route stored it.

#### Spring Boot starter

- **FR-029**: The starter MUST enable the jMolecules vocabulary when `saga-forge-jmolecules` is on the classpath, with
  a property to disable it, and MUST pass every vocabulary and event source bean to the runtime and to its own model
  collection and aggregate resolver. Application-defined vocabulary and event source beans MUST be picked up as well.
- **FR-030**: The starter README and configuration metadata MUST document the new property (kept in step by
  `ReadmePropertiesTest`), and the `sagaforge` Actuator endpoint SHOULD list the enabled vocabularies and event sources.

#### Example and documentation

- **FR-031**: An example MUST run the canonical order saga over a domain package that depends only on jMolecules, with
  its events harvested from the aggregate, over the starter and Postgres; an ArchUnit test MUST prove the domain package
  references no Saga Forge type; fixture tests MUST cover it.
- **FR-032**: `docs/architecture.md` "jMolecules consideration" MUST be replaced by this design, and its mentions of a
  `@ReadModel` annotation (including Vocabulary/Projection) MUST be reworded as future, closing G-002-04. The
  constitution's Principle II list mentions `@ReadModel` and MUST be corrected by a PATCH amendment through
  `/speckit-constitution`.
- **FR-033**: `docs/guide/` MUST gain a page on vocabularies and harvesting (snippets copied from compiling code), and
  `docs/agents/saga-forge/SKILL.md`, `docs/agents/AGENTS-snippet.md` and `llms-full.txt` MUST be updated; the
  validation-rules page MUST list R30 and R31.
- **FR-034**: `AGENTS.md`, `README.md` (modules table), the roadmap and `.specify/memory/memory.md` MUST be updated:
  G-ADOPT-04 and G-002-04 closed, new gaps recorded with ids (G-016-01 outbox delivery needs Saga Forge's `@Command`,
  G-016-02 the fixture sees no Spring-published events, G-016-03 no jMolecules event source, and any others), the
  roadmap's 016 brief narrowed to match (no starter Spring Data source), and the G-002-05 dependency decision recorded
  for jMolecules (the constitution amendment Principle I may still need, see memory.md).
- **FR-035**: The release profile MUST publish `saga-forge-jmolecules`, and the release build with Javadoc MUST pass.

### Key Entities

- **Model vocabulary**: a named set of markers that classify domain types and members into roles (aggregate,
  identifier, domain event, command, command handler) and link events and commands to their aggregate and identifier.
  Built-in: Saga Forge's annotations. Added: jMolecules, or an application's own.
- **Marker**: an annotation or type interface a vocabulary recognises, with a display name used in error texts.
- **Domain role**: what a type or member is to the model; exactly one role per type across all enabled vocabularies.
- **Aggregate event source**: takes the events an aggregate holds, in order, and clears them; asked by the engine after
  a command handler ran and the aggregate was saved.
- **Harvested event**: an event obtained from an event source, indistinguishable downstream from one recorded with
  `DomainEvents.record`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The canonical order saga runs end to end, through every existing scenario (completion, stock-unavailable
  compensation, payment failure), with a domain package that has zero references to Saga Forge types, verified by an
  architecture test.
- **SC-002**: The domain in that example (its commands use in-process delivery, D12) calls Saga Forge zero times: no `DomainEvents.record`, no Saga Forge
  annotation, no Saga Forge type.
- **SC-003**: Every existing test of the whole build, container suites included, passes unchanged: an application that
  adds no vocabulary sees no behaviour or error-text change.
- **SC-004**: 100% of the aggregate, identifier, event, command and handler validation rules have a test with Saga
  Forge markers and one with jMolecules markers, and every jMolecules-triggered error names the jMolecules marker.
- **SC-005**: No module outside the built-in vocabulary checks for a Saga Forge domain annotation directly (architecture
  test), so adding a vocabulary needs no change in the scanner, runtime, transport, starter or fixture.
- **SC-006**: Every harvested event is delivered exactly once to each saga step and each Spring listener that takes it
  (zero duplicates and zero losses across commit, rollback-and-retry and restart scenarios), including when 014's
  inbound route is on.
- **SC-008**: The jMolecules example has no Spring Data type in its domain package and its fixture tests run the
  aggregate's real handlers with harvested events.
- **SC-007**: A test-only third vocabulary of five or fewer annotations runs the canonical saga with no change to Saga
  Forge's modules.

## Assumptions

- jMolecules 2.x is the supported line (the version the release build pins; the local cache holds 2.0.x and 2.1.x);
  the plan fixes the exact version in the root dependency management.
- jMolecules' annotations are retained at runtime and readable by reflection, like Saga Forge's own.
- jMolecules CQRS markers are optional for an application: a domain without commands needs only the DDD and event
  artifacts on its classpath, and the vocabulary recognises whatever is present.
- Events and commands still have to be encodable by the message store's codec (Jackson in Postgres); typed identifiers
  and associations are the application's concern there (for example jMolecules' Jackson integration). This feature does
  not change the codec.
- Typed identifiers render a stable text form (`toString`) that is the same across events and commands of one saga;
  this is documented, not enforced.
- Saga classes, `@AggregateLoader`/`@AggregateSaver` methods and the configuration of vocabularies are application code
  and keep Saga Forge's annotations and builder methods (no fluent model registration).
- Events published outside a command handler (application services saving aggregates themselves, Spring's
  `ApplicationEventPublisher`) reach sagas through 014's inbound route (merged 2026-11-26), which this feature reuses
  unchanged.
- The jMolecules example's aggregate keeps its pending events in a plain list behind an accessor and a clear method,
  and its application layer provides the event source for it; the domain depends on neither Spring Data nor Saga
  Forge.
- Out of scope: fluent model registration (Principle II), command handlers returning events (a second recording
  style), a vocabulary for saga-side annotations, jMolecules architecture verification beyond the example's ArchUnit
  check, Spring Modulith event externalization (G-008-01), and a `@ReadModel` annotation.
