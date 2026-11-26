# Feature Specification: Publish Once: Spring Events Reach Sagas, Saga Events Reach Spring

**Feature Branch**: `014-publish-once`

**Created**: 2026-11-26

**Status**: Draft

**Input**: User description: "014 publish once: Spring events reach sagas, saga events reach Spring. Follow the rescoped
brief '### 014: Publish once' in `.specify/memory/roadmap.md` (2026-11-26): two logs (Saga Forge's own message log,
Modulith's registry; interop unchanged), (1) inbound route from Spring's ApplicationEventPublisher for event types a
saga has a step for, stored in Saga Forge's log in the publishing transaction; (2) EventPublisher.publish inside a
Spring transaction also publishes to Spring; (3) G-ENG-24 fan-out of recorded events consumed in process to other saga
types; (4) no duplicate storage/delivery, ApplicationEventBridge decision; (5) starter keeps own log when deadlines are
declared. Keep options open for a later Kafka transport (G-014-01, G-014-02). Closes G-008-03 and G-ENG-24." —
booking-service feedback SF-4, workarounds W7 and W8.

## Context

A Spring Boot application that uses Saga Forge with Saga Forge's own message log (`saga-forge.transport.modulith-interop=false`,
required once a saga declares a deadline) has two logs: Saga Forge's message log, which delivers events to sagas, and
Spring Modulith's event publication registry, which delivers events to `@ApplicationModuleListener`s. Both live in the
application's database and are written in the same transaction.

Today an event reaches both kinds of consumer only when it is published one particular way:

| Published by | Module listeners | Sagas |
|---|---|---|
| A saga step or command handler (`DomainEvents.record`) | yes, inside the unit of work's transaction | yes |
| Application code through Spring's `ApplicationEventPublisher` (also Spring Data `@DomainEvents` on save) | yes | **no** (G-008-03) |
| Application code through Saga Forge's `EventPublisher` | only in `application-events.mode=after-delivery`, outside a transaction | yes |
| A recorded event that a step of its own saga instance consumes in process | yes | **not by other saga types** (G-ENG-24) |

Application code outside sagas therefore publishes every event twice, once for listeners and once for sagas, and has
to know which events start or continue a saga. A second saga type that waits for an event another saga consumes in
process never sees it, without any error.

This feature makes **one publication enough**: an event published by any route reaches every saga step and every
module listener that wants it, exactly once each, durably, after the publishing transaction commits.

## Decisions Taken In This Spec

- **D1 — Two logs, interop unchanged.** Saga Forge's log stays the saga transport and Modulith's registry stays the
  listeners' outbox. A common outbox is not a goal (user decision, 2026-11-26; G-017-01 accepted). The interop mode keeps
  its current behaviour; everything this feature adds works with it too, storing into whichever log the transport uses.
- **D2 — Only saga event types enter Saga Forge's log.** The inbound route stores an event published through Spring
  only if its type is a domain event that some registered saga has a step for. Other events pass by untouched. Saga
  Forge's log does not become the application's general event bus; whether a later Kafka transport publishes
  integration events stays open (G-014-02).
- **D3 — Inside a unit of work, a Spring-published saga event is a recorded event.** When code running inside a Saga
  Forge unit of work (a step, a command handler, an aggregate saver) publishes a saga event through Spring, for example
  by saving a Spring Data aggregate that holds `@DomainEvents`, the event is treated exactly as if
  `DomainEvents.record` had been called: it continues the instance in process when a step of it waits for it, it is
  stored with the unit of work, and it is discarded when the unit of work rolls back. Since Spring already delivered it
  to listeners, Saga Forge does not publish it to Spring a second time.
- **D4 — Outside a unit of work, the publishing transaction decides.** A saga event published through Spring while a
  transaction is active is stored in that transaction, so it is stored if and only if the transaction commits. Published
  with no active transaction, it is stored on its own at once, which is what Saga Forge's `EventPublisher` does today.
- **D5 — `EventPublisher` publishes to Spring like a recorded event.** Outside a unit of work, `EventPublisher.publish`
  stores the event for sagas (unchanged) and, under `application-events.mode=in-transaction`, also publishes it through
  Spring at once, inside the caller's transaction when there is one. Inside a unit of work it stays exactly
  `DomainEvents.record`, whose events the unit of work already publishes to Spring.
- **D6 — Saga Forge's own publications are never caught by the inbound route.** Events Saga Forge publishes to Spring
  (the unit of work's recorded events, D5's `EventPublisher` publications, outcome events) are not stored a second time.
- **D7 — Recorded events fan out to other saga types.** A recorded event that a step of its own instance consumes in
  process is also handed on for every *other* saga type that has a step for its type, in the same unit of work, so it
  is stored if and only if the unit of work commits. The owning saga type does not receive it a second time. An event
  no other saga type has a step for is not handed on (unchanged). This revises 004's fan-out rule into a numbered
  correctness property (Property 47).
- **D8 — `after-delivery` mode is deprecated.** `application-events.mode=after-delivery` (the `ApplicationEventBridge`,
  007) re-publishes delivered messages to Spring outside a transaction. With the inbound route it would hand listeners
  events Spring already delivered to them. It stays available, deprecated, only while the inbound route is off; the
  starter refuses to start with both, and the failure analysis names the two properties.
- **D9 — Deadlines pick the own log.** When the model declares a deadline and `saga-forge.transport.modulith-interop`
  is not set explicitly, the starter uses Saga Forge's own log (and says so at startup) instead of failing. An explicit
  `true` still fails startup with today's failure analysis.
- **D10 — The ordering key does not depend on the route.** An event stored through the inbound route is keyed by its
  `@AggregateIdentifier`, exactly like an event recorded in a unit of work or published through `EventPublisher`, so a
  later transport can partition on the same key (G-014-01).

## Clarifications

### Session 2026-11-26

- Q: Is the inbound route on by default or opt-in? → A: [NEEDS CLARIFICATION: on by default (with a property to
  switch it off), or off until the application sets a property? An existing application that today publishes the same
  event both through Spring and through `EventPublisher` would have it stored twice once the route is on.]

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A Spring-published event starts and continues sagas (Priority: P1)

A module of the application changes an aggregate and publishes the resulting domain event the usual Spring way, through
`ApplicationEventPublisher` or by saving a Spring Data aggregate that holds its events. A saga whose start step takes
that event type starts; a running saga whose next step takes it continues. Module listeners receive the event as
before. The module never references Saga Forge.

**Why this priority**: this is the most frequent path in a modular application, and today it reaches no saga (G-008-03),
forcing every such module to publish twice.

**Independent Test**: a Spring application with one saga whose start step takes `OrderPlaced`; a service publishes
`OrderPlaced` through Spring inside a transaction; the saga instance exists and its start step ran, and an
`@ApplicationModuleListener` received the event once.

**Acceptance Scenarios**:

1. **Given** a saga with a start step for an event type, **When** application code publishes that event through
   Spring inside a transaction that commits, **Then** one saga instance starts, after the commit, and the module listener
   receives the event exactly once.
2. **Given** the same setup, **When** the publishing transaction rolls back, **Then** no saga starts and the event is
   not in Saga Forge's log.
3. **Given** a running saga waiting for an event, **When** that event is published through Spring, **Then** the saga's
   step runs once.
4. **Given** an event type no saga has a step for, **When** it is published through Spring, **Then** nothing is stored
   in Saga Forge's log and listeners behave as before.
5. **Given** a saga event published through Spring with no active transaction, **When** the call returns, **Then** the
   event is stored and is delivered to the saga.
6. **Given** a Spring Data aggregate whose `@DomainEvents` hold a saga event, **When** the repository saves it, **Then**
   the saga receives the event once.

---

### User Story 2 - An event recorded by one saga reaches another saga type (Priority: P1)

`OrderProcessingSaga`'s handler records `OrderCompleted`, which ends `OrderProcessingSaga` in process. A second saga
type, `FulfilmentSaga`, starts on `OrderCompleted`. Today it never starts. With this feature it starts once, after the
recording unit of work commits.

**Why this priority**: the current behaviour loses messages without any error, and it gets more likely as an
application adds saga types; it is a correctness fix (G-ENG-24).

**Independent Test**: two saga types with steps for the same event type; a handler of the first records it while a
step of the first consumes it in process; the second saga type gets exactly one instance, the first saga's step ran
exactly once.

**Acceptance Scenarios**:

1. **Given** saga types A and B both with a step for event E, **When** a unit of work of an A instance records E and a
   step of that instance consumes it in process, **Then** after the commit a B instance receives E once, and the A
   instance's step ran once.
2. **Given** the same, **When** the unit of work rolls back, **Then** B receives nothing.
3. **Given** only saga type A has a step for E, **When** an A instance records and consumes E in process, **Then** E is
   not handed on (no stored message, as today).
4. **Given** the in-process step of A and the fan-out to B, **When** the delivery to B fails and is retried, **Then**
   the A instance is not affected.

---

### User Story 3 - Saga Forge's EventPublisher reaches module listeners (Priority: P2)

Application code that publishes through Saga Forge's `EventPublisher` (for instance, code written before this feature)
has its event delivered to module listeners too, in the caller's transaction, under the default
`application-events.mode`.

**Why this priority**: it makes the two publishing APIs equivalent, so neither choice silently loses a consumer; most
new code will use Spring's publisher (User Story 1).

**Independent Test**: a service calls `EventPublisher.publish(OrderPlaced)` in a transaction; a saga starts and an
`@ApplicationModuleListener` receives the event once, after the commit.

**Acceptance Scenarios**:

1. **Given** `application-events.mode=in-transaction`, **When** `EventPublisher.publish` is called outside a unit of
   work in a transaction that commits, **Then** listeners receive the event once and the saga receives it once.
2. **Given** the same, **When** the transaction rolls back, **Then** neither receives it.
3. **Given** `application-events.mode=none`, **When** `EventPublisher.publish` is called, **Then** only sagas receive it
   (unchanged).
4. **Given** `EventPublisher.publish` called inside a unit of work, **Then** behaviour is unchanged (`DomainEvents.record`).

---

### User Story 4 - Each consumer receives each event once, whatever the route (Priority: P1)

An application mixes routes: steps record events, a handler saves a Spring Data aggregate inside a unit of work, a
service publishes through Spring, older code calls `EventPublisher`. Every saga step and every module listener receives
each event exactly once.

**Why this priority**: the inbound route and the existing outbound publication would otherwise loop into each other and
duplicate events; without this guarantee User Stories 1 to 3 are unsafe.

**Independent Test**: one test per route combination counts deliveries per saga step and per listener; every count is
one.

**Acceptance Scenarios**:

1. **Given** a step records event E, **When** the unit of work publishes E to Spring, **Then** E is stored in Saga
   Forge's log once (not again through the inbound route).
2. **Given** a command handler inside a unit of work saves a Spring Data aggregate holding E, **When** Spring publishes
   E, **Then** E is recorded in the unit of work once, listeners receive E once (Saga Forge does not publish it to Spring
   again), and a step of the same instance waiting for E runs in process.
3. **Given** `EventPublisher.publish(E)` outside a unit of work, **When** it publishes E to Spring, **Then** E is stored
   once.
4. **Given** an outcome event published to Spring, **Then** it is not stored again through the inbound route.
5. **Given** the inbound route is on and `application-events.mode=after-delivery`, **When** the application starts,
   **Then** startup fails and the failure analysis names both properties.

---

### User Story 5 - A model with deadlines starts under Modulith without extra configuration (Priority: P3)

An application with Spring Modulith's registry on the class path declares a saga deadline and sets no interop property.
It starts on Saga Forge's own log and logs that it did so.

**Why this priority**: removes a confusing startup failure for the setup booking-service needs; the explicit property
already works around it.

**Independent Test**: context with Modulith's JDBC registry, a model with `@StartDeadline`, no interop property: the
context starts, the transport uses the own log, the startup log names the reason.

**Acceptance Scenarios**:

1. **Given** a model with a deadline and no interop property, **When** the context starts, **Then** it uses Saga Forge's
   own log.
2. **Given** a model with a deadline and `modulith-interop=true`, **When** the context starts, **Then** startup fails
   with today's failure analysis.
3. **Given** a model without deadlines and no interop property, **Then** behaviour is unchanged (interop on when the
   registry is present).

---

### Edge Cases

- A Spring-published event is wrapped in Spring's `PayloadApplicationEvent`, or is a subclass of a saga event type: the
  route matches on the event's own type exactly as the runtime routes it (a step takes the exact type).
- The same event object is published twice through Spring: it is stored twice, like two `EventPublisher` calls; saga
  steps rely on the runtime's existing duplicate handling, and module listeners see it twice (unchanged Spring
  behaviour).
- An event is published through Spring from a module listener running in its own transaction after another commit: D4
  applies to that listener's transaction.
- A saga event is published through Spring on a thread with a unit of work of another saga instance active: D3 applies;
  it is recorded in that unit of work.
- Under interop on, the inbound route stores into Modulith's registry as Saga Forge's transport does today.
- The runtime is not started yet, or stopped, when a saga event is published through Spring: storing it does not need
  the runtime; it is delivered when the transport runs.
- A recorded event fans out to saga type B, and a step of B also records an event that A has a step for: each unit of
  work fans out on its own rules; no loop is introduced since each fan-out is a stored message delivered once.
- Two saga types other than the owner both have a step for the event: one stored message reaches both, as for any
  delivered event.
- A hand-assembled runtime with the default after-commit outbox: D7's fan-out goes through that outbox (at most once,
  G-ENG-07, unchanged).
- The fixture (`SagaFixture`) behaves like the runtime for D7: an event consumed in process that another saga type in
  the fixture's model has a step for reaches it.

## Requirements *(mandatory)*

### Functional Requirements

**Inbound route (Spring to sagas)**

- **FR-001**: The starter MUST store an event published through Spring's application event publisher in Saga Forge's
  message log when its type is a domain event type for which at least one registered saga has a step (D2).
- **FR-002**: Events whose type no registered saga has a step for MUST NOT be stored, and their Spring delivery MUST be
  unchanged.
- **FR-003**: When a transaction is active, the event MUST be stored in that transaction, so it is stored if and only
  if the transaction commits (D4).
- **FR-004**: When no transaction is active, the event MUST be stored at once in its own transaction (D4).
- **FR-005**: When a Saga Forge unit of work is active on the publishing thread, the event MUST be recorded in that unit
  of work with the same effects as `DomainEvents.record` (in-process continuation, storage with the unit of work,
  discarded on rollback), and Saga Forge MUST NOT publish it to Spring again (D3).
- **FR-006**: Events published through Spring by Spring Data aggregates' `@DomainEvents` on save MUST be covered by
  FR-001 to FR-005.
- **FR-007**: The inbound route's default state MUST follow the clarification above; it MUST be switchable by a
  documented property.
- **FR-008**: The stored event's ordering key MUST be its `@AggregateIdentifier` value, the same key as for events
  stored by the other routes (D10).

**Outbound (EventPublisher to Spring)**

- **FR-009**: Under `application-events.mode=in-transaction`, `EventPublisher.publish` called outside a unit of work
  MUST also publish the event through Spring, inside the caller's transaction when there is one (D5).
- **FR-010**: Under `application-events.mode=none`, `EventPublisher.publish` MUST NOT publish to Spring (unchanged).
- **FR-011**: `EventPublisher.publish` inside a unit of work MUST behave as today (`DomainEvents.record`).

**No duplicates**

- **FR-012**: An event Saga Forge itself publishes to Spring (recorded events, outcome events, FR-009 publications) MUST
  NOT be stored again by the inbound route (D6).
- **FR-013**: For every route, each saga step waiting for an event and each module listener MUST receive one publication
  of it exactly once (at least once at delivery, as the transport already guarantees, with no duplicate introduced by
  Saga Forge's own routing).
- **FR-014**: `application-events.mode=after-delivery` MUST be marked deprecated in the configuration metadata and docs;
  with the inbound route on, startup MUST fail with a failure analysis naming both properties (D8).

**Fan-out of recorded events (runtime)**

- **FR-015**: A recorded event that a step of its own instance consumed in process MUST be handed to the outbox, in the
  same unit of work, when at least one other saga type has a step for its type (D7).
- **FR-016**: Delivering such an event MUST NOT run a step of the owning saga type for it, and MUST NOT report it as a
  failure or duplicate for the owning instance.
- **FR-017**: A recorded event consumed in process that no other saga type has a step for MUST NOT be handed on
  (unchanged).
- **FR-018**: FR-015 to FR-017 MUST hold for every outbox (the transport's in-transaction outbox and the default
  after-commit outbox) and for the saga test fixture (`SagaFixture`), which MUST stay in parity with the runtime.
- **FR-019**: The rule MUST be stated as a new correctness property (Property 47) in
  `.kiro/specs/saga-forge-framework/design.md` and tested as such.

**Starter defaults**

- **FR-020**: When the model declares a deadline and `saga-forge.transport.modulith-interop` is not set, the starter
  MUST use Saga Forge's own log and log the reason at startup (D9).
- **FR-021**: When the model declares a deadline and the property is explicitly `true`, startup MUST fail as today.

**Boundaries and documentation**

- **FR-022**: Core and runtime MUST gain no Spring dependency; every Spring-facing part of this feature lives in the
  starter (Constitution Principle I).
- **FR-023**: The interop mode's behaviour MUST stay unchanged apart from the inbound route storing into its log (D1).
- **FR-024**: The user guide, the starter's README (`ReadmePropertiesTest`), `docs/agents/saga-forge/SKILL.md`,
  `docs/agents/AGENTS-snippet.md` and `llms-full.txt` MUST describe the publishing routes, the deprecation and the
  deadline default; `docs/guide/limitations.md` MUST state the ordering limits of two logs (G-014-01).
- **FR-025**: `.specify/memory/memory.md` MUST close G-008-03 and G-ENG-24 and record any gap this feature leaves.

### Key Entities

- **Saga event type**: a domain event type that at least one registered saga has a step for; the set the inbound route
  and the fan-out rule test against.
- **Publication route**: where an event enters: recorded in a unit of work, published through Spring, or published
  through `EventPublisher`; each route ends in the same two places (Saga Forge's log for sagas, Spring for listeners).
- **Stored message**: the existing message in Saga Forge's log, keyed by aggregate identifier; this feature adds no
  field to it.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For each of the three publication routes, an end-to-end test shows one saga step execution and one module
  listener invocation per published event, after commit, across an application restart between publication and
  delivery.
- **SC-002**: A rolled-back publishing transaction leads to zero saga step executions and zero listener invocations, for
  every route.
- **SC-003**: The Spring example application publishes each of its domain events once, with no call to Saga Forge's
  `EventPublisher` left in module code that is not a saga.
- **SC-004**: With two saga types reacting to the same recorded event, the second type gets exactly one instance in
  100% of runs of the fan-out test, including runs where the first delivery attempt fails.
- **SC-005**: An application with Spring Modulith's registry and a deadline starts with no interop property set.
- **SC-006**: The whole build, container suites included, passes; existing tests change only where this spec changes
  behaviour (fan-out, `EventPublisher` to Spring, deadline default).

## Assumptions

- The application uses the starter with the in-memory transport over Postgres; the hand-assembled runtime gets only
  the fan-out change (FR-015 to FR-019).
- Spring Modulith is optional: the inbound route works with plain Spring application events and `@TransactionalEventListener`s.
- Only `@DomainEvent`-annotated types count as saga events; 016 widens this to other markers (jMolecules) through the
  same check.
- Duplicate deliveries remain possible at least once, as the transport already defines; this feature adds no
  deduplication of its own beyond FR-012.
- Ordering between Saga Forge's log and Modulith's registry is not guaranteed and is documented, not fixed (G-014-01).
- No schema change is needed.
