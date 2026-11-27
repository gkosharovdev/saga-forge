# Implementation Plan: Publish Once: Spring Events Reach Sagas, Saga Events Reach Spring

**Branch**: `014-publish-once` (work committed on the session branch `claude/confident-brahmagupta-lcx2qb`) |
**Date**: 2026-11-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/014-publish-once/spec.md`

## Summary

One publication reaches every saga step and every Spring listener, whatever the route, with Saga Forge's own message
log beside Spring Modulith's registry. Underneath:

1. **Core**: `EventReceiver` gains the default method `receive(Object domainEvent, String sourceSagaId)` (research R6).
   Nothing else; no Spring type.
2. **Runtime**: `UnitOfWork` hands on a recorded event its own instance consumed when another saga type has a step for
   it (`EventSubscriptions`, R6); `EventRouter` gives the source instance no result for such an event when its step is
   already `COMPLETED`; `SagaRuntime.eventTypes()` (R4). Property 47.
3. **Transport and fixture**: the relay and `LoopBackTransport` deliver with the stored `sourceSagaId`.
4. **Starter**: `InboundSagaEventListener` stores Spring-published saga events (R1), records them inside a unit of work
   without publishing them to Spring again (`AlreadyPublishedEvents`, R2), ignores Saga Forge's own publications
   (`PublishingGuard`, R3); `ApplicationEventPublishingEventPublisher` makes `EventPublisher` publish to Spring (R5);
   `saga-forge.application-events.inbound.enabled` (default `true`); `after-delivery` deprecated and refused together
   with the route (R7); deadlines pick the own log when interop is not set (R8).
5. **Example and docs**: the Spring example publishes through `ApplicationEventPublisher`; guide, README, agent skill,
   snippet, `llms-full.txt`, architecture, `.kiro` Property 47, memory.md (close G-008-03, G-ENG-24; add G-014-04,
   G-014-05).

## Technical Context

**Language/Version**: Java, `maven.compiler.release` 21 (CI also on 25)

**Primary Dependencies**: none new. Core stays dependency-free; runtime keeps `opentelemetry-api`; the starter keeps
Spring Boot 4.1 (`ApplicationListener`, `PayloadApplicationEvent`, `TransactionSynchronizationManager`).

**Storage**: no schema change. The fan-out uses the existing `saga_message_store.source_saga_id`.

**Testing**: JUnit 5 + AssertJ. Runtime: `RecordedEventFanOutTest`, `EventRouterSourceTest`, `SagaRuntimeEventTypesTest`
over the runtime's in-memory doubles; `UnitOfWorkBoundaryTest` unchanged. Transport: a fan-out scenario in
`TransportAcceptanceSuite` (in-memory store, Postgres, Modulith interop). Fixture: `FixtureParityTest` fan-out case.
Starter, with Postgres containers (`ApplicationContextRunner` / `SpringApplicationBuilder`, G-008-04):
`PublishOnceContainerTest`, `InboundRouteInUnitOfWorkTest`, `InboundRouteDisabledTest`, `AfterDeliveryConflictTest`,
`DeadlineInteropDefaultTest`, `ReadmePropertiesTest`; unit tests for `PublishingGuard`, `AlreadyPublishedEvents`,
`InboundSagaEventListener`, `ApplicationEventPublishingEventPublisher`. Spring example container test.

**Target Platform**: JVM library | **Project Type**: multi-module Maven library

**Performance Goals**: the inbound listener costs one set lookup per Spring event that is not a saga event; a saga
event costs one message-store append (the same as `EventPublisher`). The fan-out adds one stored message per recorded
event that another saga type handles, and one storage read per redelivery to its source instance.

**Constraints**: Principle I (Spring only in the starter; core gains one default method). Principle II (no new saga
author API; publishing stays Spring's or `DomainEvents.record`). Principle IV (no storage or transport added).
Release-build Javadoc rules (no `<h3>`, no `@throws` on record comments).

**Scale/Scope**: about 4 new starter classes, 1 new runtime class, edits in ~10 existing classes, ~10 test classes,
docs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Zero-dependency core | Core gains `EventReceiver.receive(Object, String)` (default method, JDK types only). Every Spring part is in the starter. The runtime and transport gain no dependency. | Pass |
| II. Annotation-driven | No new annotation and no imperative API for saga authors. Publishing uses Spring's publisher or the existing `DomainEvents.record`. | Pass |
| III. Formal spec conformance | Fan-out becomes Property 47 in `.kiro/specs/saga-forge-framework/design.md` with a property-style test; Speckit flow followed. | Pass |
| IV. Narrow scope | No storage or transport added. The Kafka question is recorded, not decided (G-014-02). | Pass |
| V. Documentation hierarchy | `docs/architecture.md` (event flow, fan-out rule) and `docs/use-cases.md` updated before guides; vocabulary unchanged (Domain Event, Saga, Step). | Pass |
| Workflow: composition | Both new Spring types are decorators or listeners holding collaborators; no class hierarchy. | Pass |
| Workflow: test abstractions first | The one new SPI method has two implementations (default delegation; `SagaRuntime`) and two callers (relay, fixture), each tested. No other new interface. | Pass |
| Workflow: recognisable names | Rated below and in research R1 to R6. | Pass |

**Post-design re-check**: pass (no change from Phase 1).

### Abstractions and invocation chains

Names rated by how widely known they are (high: a well-known library's own type or a standard pattern; medium: common
engineering vocabulary; low: boutique).

| Type | Module | Shape | Rating |
|---|---|---|---|
| `EventReceiver.receive(Object, String sourceSagaId)` | core SPI | default method on an existing interface | high (existing interface; field name reused from `NewMessage`) |
| `EventSubscriptions` | runtime, package-private | lookup "which other saga types handle this event type" | medium-high (publish/subscribe vocabulary) |
| `SagaRuntime.eventTypes()` | runtime, public | accessor | high |
| `InboundSagaEventListener` | starter | Spring `ApplicationListener<PayloadApplicationEvent<?>>` | high (Spring interface; "inbound adapter" is Spring Integration's term) |
| `ApplicationEventPublishingEventPublisher` | starter | decorator of `EventPublisher` | medium (mirrors the existing `ApplicationEventPublishingOutbox`) |
| `PublishingGuard` | starter, package-private | thread-bound re-entry flag | medium (re-entry guard idiom; the runtime has `ReentrancyGuard`) |
| `AlreadyPublishedEvents` | starter, package-private | thread-bound identity set, cleared after transaction completion | medium (says what it holds) |

Invocation chains:

- **Spring publication, no unit of work**: `ApplicationEventPublisher.publishEvent(e)` → `InboundSagaEventListener`
  (saga event type? not guarded?) → `EventDispatcher.dispatch(e)` → `Relay.publish` → `MessageStore.append` in the joined
  transaction → after commit, relay → `SagaRuntime.receive(e)` → saga step. Modulith/`@TransactionalEventListener`s get
  `e` from Spring directly.
- **Spring publication inside a unit of work** (handler saves a Spring Data aggregate): Spring Data →
  `publishEvent(e)` → listeners (synchronous ones now; transactional ones after commit) and
  `InboundSagaEventListener` → `AlreadyPublishedEvents.add(e)`, `DomainEvents.record(e)` → the unit of work runs a
  waiting step in process or stores `e` → `ApplicationEventPublishingOutbox.store` publishes the other recorded events,
  skipping `e`.
- **`EventPublisher.publish(e)` outside a unit of work**: `ApplicationEventPublishingEventPublisher` → runtime
  publisher (stored) → `PublishingGuard.run(() -> publishEvent(e))` → listeners; `InboundSagaEventListener` ignores it.
- **Fan-out**: unit of work of instance X (type A) records `e`, a step of X runs it → `buffered()` adds `e` to `events`
  because `EventSubscriptions.otherSagaTypesHandle(e.class, A)` → stored with `sourceSagaId = X` → relay →
  `SagaRuntime.receive(e, X)` → `EventRouter`: type B starts or continues; type A's reaction correlates X, whose step for
  `e` is `COMPLETED` → no result.

## Project Structure

### Documentation (this feature)

```text
specs/014-publish-once/
├── plan.md              # this file
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   ├── event-receiver-source.md   # core SPI method, runtime fan-out and source rule (Property 47)
│   ├── inbound-route.md           # starter: Spring → sagas, no duplicates
│   └── starter-configuration.md   # properties, after-delivery deprecation, deadline default
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
saga-forge-core/src/main/java/.../core/spi/EventReceiver.java                 # + default receive(Object, String)
saga-forge-runtime/src/main/java/.../runtime/SagaRuntime.java                 # + receive(Object, String), eventTypes()
saga-forge-runtime/src/main/java/.../runtime/internal/EventSubscriptions.java # new
saga-forge-runtime/src/main/java/.../runtime/internal/UnitOfWork.java         # buffered(): fan-out
saga-forge-runtime/src/main/java/.../runtime/internal/EventRouter.java        # source instance rule
saga-forge-runtime/src/main/java/.../runtime/internal/EngineRuntime.java      # wiring
saga-forge-transport-inmemory/src/main/java/.../internal/Relay.java           # deliver with sourceSagaId
saga-forge-test/src/main/java/.../test/LoopBackTransport.java                 # deliver with source
saga-forge-spring-boot-starter/src/main/java/.../spring/events/
    InboundSagaEventListener.java, ApplicationEventPublishingEventPublisher.java,
    PublishingGuard.java, AlreadyPublishedEvents.java                         # new
    ApplicationEventPublishingOutbox.java, ApplicationEventsConfiguration.java, ApplicationEventsMode.java  # edits
saga-forge-spring-boot-starter/src/main/java/.../spring/SagaForgeProperties.java              # inbound.enabled
saga-forge-spring-boot-starter/src/main/java/.../spring/modulith/ModulithInteropConfiguration.java  # deadline default
saga-forge-spring-boot-starter/src/main/java/.../spring/diagnostics/DeadlinesRequireOwnLogFailureAnalyzer.java
saga-forge-example-spring/src/main/java/.../OrderPlacement.java              # ApplicationEventPublisher
```

**Structure Decision**: existing multi-module layout; no new module.

## Phases (for `/speckit-tasks`)

1. Core method and runtime fan-out with its tests (Property 47), `eventTypes()`. Independent of Spring.
2. Transport and fixture deliver with the source; acceptance suite and parity tests.
3. Starter inbound route, guard and identity set, `EventPublisher` decorator, property; unit tests; container tests.
4. `after-delivery` deprecation and conflict; deadline default.
5. Spring example, docs (`docs/architecture.md`, `docs/use-cases.md`, guide pages, README, agent skill and snippet,
   `llms-full.txt`), `.kiro` Property 47, memory.md, AGENTS.md status, whole build with container suites.

## Complexity Tracking

No constitution violation to justify.
