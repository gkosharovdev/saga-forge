---

description: "Task list for 014-publish-once"
---

# Tasks: Publish Once: Spring Events Reach Sagas, Saga Events Reach Spring

**Input**: Design documents from `/specs/014-publish-once/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: included. Every story in the spec has an independent test, and the constitution asks for a test with every
new abstraction, written with or before its first caller. The contracts number their cases: ER, FO, SR in
`event-receiver-source.md`; IR, PO, EP in `inbound-route.md`; AD, DI in `starter-configuration.md`.

**Organization**:
- **Setup**: a green baseline and the design rules.
- **Foundational**: `SagaRuntime.eventTypes()` and the `inbound.enabled` property, which US1, US3 and US4 use.
- **The stories, in spec order**:
  - US1 (P1): Spring-published events reach sagas.
  - US2 (P1): a recorded event reaches other saga types (runtime, transport, fixture; no Spring).
  - US3 (P2): `EventPublisher` reaches Spring listeners.
  - US4 (P1): once per consumer on every route (guard, already-published set, `after-delivery` refusal).
  - US5 (P3): deadlines pick the own log.
- **Last**: the Spring example, docs and the register, then verification.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: the user story from spec.md (US1 to US5)

## Path Conventions

Inside a named module, two placeholders stand for long package paths:
- `…` means `src/main/java/io/github/gkosharovdev/sagaforge`.
- `…test` means `src/test/java/io/github/gkosharovdev/sagaforge`.

Other paths are relative to the repository root. Short prefixes:

| Prefix | Path |
|---|---|
| `spi/` | `saga-forge-core/…/core/spi/` |
| `rt/` | `saga-forge-runtime/…/runtime/` |
| `internal/` | `rt/internal/` |
| `rt-test/` | `saga-forge-runtime/…test/runtime/` |
| `relay/` | `saga-forge-transport-inmemory/…/transport/inmemory/internal/` |
| `tr-test/` | `saga-forge-transport-inmemory/…test/transport/inmemory/` |
| `fixture/` | `saga-forge-test/…/test/` |
| `fixture-test/` | `saga-forge-test/…test/test/` |
| `spring/` | `saga-forge-spring-boot-starter/…/spring/` |
| `spring-test/` | `saga-forge-spring-boot-starter/…test/spring/` |
| `example/` | `saga-forge-example-spring/…/example/spring/` |

---

## Phase 1: Setup

- [X] T001 Run `mvn -q verify` from the repository root (no container suites) and record the baseline test count in the
  progress note at the end of this file; stop and report if it is not green.
- [X] T002 Before the first class, propose loading the `effective-java`, `clean-code-java` and `solid-design-review`
  skills (AGENTS.md); if they are not installed, apply research.md R12 by hand and note it in the progress note.

---

## Phase 2: Foundational (blocking)

- [X] T003 [P] Add `SagaRuntimeEventTypesTest` in `rt-test/SagaRuntimeEventTypesTest.java`: two registered sagas →
  `eventTypes()` is the union of their step event classes, excludes `@DeadlineExpired` steps and outcome events, is the
  same before and after `start()`, and is unmodifiable (data-model.md "Saga event types").
- [X] T004 Implement `public Set<Class<?>> eventTypes()` in `rt/SagaRuntime.java`, computed in `build()` from the
  registered classes' `@SagaStep` event parameters with the router's exact-class rule (reuse `internal/SagaModel` via a
  package-private factory in `internal/EngineRuntime.java` if needed); Javadoc without `<h3>`.
- [X] T005 [P] Add `Inbound` (field `enabled`, default `true`) to `SagaForgeProperties.ApplicationEvents` in
  `spring/SagaForgeProperties.java`, with the configuration-metadata description; extend
  `spring-test/SagaForgePropertiesTest.java`.

**Checkpoint**: `mvn -q -pl saga-forge-runtime,saga-forge-spring-boot-starter -am verify` green.

---

## Phase 3: User Story 1 - A Spring-published event starts and continues sagas (P1) 🎯 MVP

**Goal**: an event published through Spring's `ApplicationEventPublisher` (also Spring Data `@DomainEvents`) whose type
a saga handles is stored in Saga Forge's log in the publishing transaction and reaches the saga.

**Independent test**: `PublishOnceContainerTest#springPublishStartsSaga` and `#springPublishRolledBack`.

### Tests

- [X] T006 [P] [US1] Add `InboundSagaEventListenerTest` in `spring-test/events/InboundSagaEventListenerTest.java` over a
  recording `EventDispatcher` and `DomainEvents.capture`: IR2 (non-saga type ignored, subclass ignored), IR3 (inside a
  scope: recorded, not dispatched), IR4 (outside: dispatched), IR5 (no runtime start needed).
- [X] T007 [P] [US1] Add `PublishOnceContainerTest` in `spring-test/events/PublishOnceContainerTest.java`
  (`@Tag("container")`, `StarterPostgres`, `SpringApplicationBuilder`, interop off): a saga starting on `OrderPlaced`
  and continuing on `OrderPaid`, plus an `@ApplicationModuleListener` (or `@TransactionalEventListener`) counting calls.
  Cases: publish in a committed transaction → one instance, step once, listener once; rolled back → nothing stored,
  nothing delivered; publish with no transaction → delivered; continuing event → step once; non-saga event → nothing
  stored; restart between publication and delivery → delivered once.
- [X] T008 [P] [US1] Add `InboundRouteInUnitOfWorkTest` in `spring-test/events/InboundRouteInUnitOfWorkTest.java`
  (container): a command handler saves a Spring Data aggregate whose `@DomainEvents` hold a saga event the same instance
  waits for → the step runs in process in the same unit of work; on a forced rollback nothing is stored.
- [X] T009 [P] [US1] Add `InboundRouteDisabledTest` in `spring-test/events/InboundRouteDisabledTest.java`
  (`ApplicationContextRunner`): `saga-forge.application-events.inbound.enabled=false` → no `InboundSagaEventListener`
  bean; default → one.

### Implementation

- [X] T010 [P] [US1] Create `PublishingGuard` (package-private; `run(Runnable)` restoring the previous value in
  `finally`, `active()`) in `spring/events/PublishingGuard.java` with `spring-test/events/PublishingGuardTest.java`
  (nesting, exception path). Needed by IR1; full use in US4.
- [X] T011 [P] [US1] Create `AlreadyPublishedEvents` (package-private identity set; `add` registers a
  `TransactionSynchronization` clearing it after completion when synchronization is active; `remove` returns presence)
  in `spring/events/AlreadyPublishedEvents.java` with `spring-test/events/AlreadyPublishedEventsTest.java` (identity
  not equality, cleared after commit and after rollback).
- [X] T012 [US1] Implement `InboundSagaEventListener` (public, `ApplicationListener<PayloadApplicationEvent<?>>`;
  collaborators `Set<Class<?>>` and `EventDispatcher`) per contracts/inbound-route.md IR1 to IR5 in
  `spring/events/InboundSagaEventListener.java`.
- [X] T013 [US1] Register it in `spring/events/ApplicationEventsConfiguration.java`: `@ConditionalOnProperty`
  `saga-forge.application-events.inbound.enabled` (match if missing), `@ConditionalOnBean({SagaRuntime.class,
  EventDispatcher.class})`, `@ConditionalOnMissingBean`, built from `SagaRuntime.eventTypes()`.
- [X] T014 [US1] Make T006 to T009 pass; check the Modulith interop acceptance
  (`spring-test/modulith/ModulithInteropAcceptanceContainerTest.java`) stays green with the route on (research R10).

**Checkpoint**: US1 works with interop off and on.

---

## Phase 4: User Story 2 - An event recorded by one saga reaches another saga type (P1)

**Goal**: Property 47. A recorded event its own instance consumed in process also reaches every other saga type with
a step for it; the source instance gets no result for it.

**Independent test**: `RecordedEventFanOutTest` and the fan-out scenario of `TransportAcceptanceSuite`.

### Tests

- [X] T015 [P] [US2] Add `RecordedEventFanOutTest` in `rt-test/RecordedEventFanOutTest.java` over the runtime's
  in-memory doubles (`TestTransport`, `TransactionalInMemoryStorageProvider`): FO1 (type B gets E once after commit, A's
  step once), FO2 (unchanged), FO3 (rollback → nothing), no other type → no stored message; a property-style case over
  generated pairs of saga types (jqwik if the module uses it, else parameterised).
- [X] T016 [P] [US2] Add `EventRouterSourceTest` in `rt-test/EventRouterSourceTest.java`: SR2 (source with `COMPLETED`
  step → no result, no `saga.event.duplicate` log), SR3 (source `COMPENSATING` → compensation resumes; source terminal →
  `UNEXPECTED` as before; other instance unaffected), ER1 (blank source → `IllegalArgumentException`).
- [X] T017 [P] [US2] Add a fan-out scenario to `tr-test/support/TransportAcceptanceSuite.java` (runs on the in-memory
  store, Postgres and Modulith interop): two saga types on one recorded event, a first delivery attempt to B that fails
  transiently → B has one instance, A unaffected.
- [X] T018 [P] [US2] Add the fan-out case to `fixture-test/FixtureParityTest.java` and a fixture test with two saga
  types on one event in `fixture-test/RecordedEventFanOutFixtureTest.java`.

### Implementation

- [X] T019 [US2] Add the default method `receive(Object domainEvent, String sourceSagaId)` to `spi/EventReceiver.java`
  (ER1, ER2; Javadoc on the source's meaning, no `@throws` misuse).
- [X] T020 [US2] Create `EventSubscriptions` (package-private, immutable; `otherSagaTypesHandle(Class<?>, String)`) in
  `internal/EventSubscriptions.java`, built from the registered `SagaModel`s in `internal/EngineRuntime.java`, with
  `rt-test/internal/EventSubscriptionsTest.java` if the runtime's tests reach package-private types, else covered by T015.
- [X] T021 [US2] Change `buffered()` in `internal/UnitOfWork.java` per data-model.md "Hand-on rule" (FO1, FO4), passing
  `EventSubscriptions` through the existing settings/collaborators; keep recording order; `UnitOfWorkBoundaryTest`
  stays green.
- [X] T022 [US2] Implement the source rule in `internal/EventRouter.java` (SR1 to SR3): carry `sourceSagaId` in the
  reaction; for the correlated source instance read its `StepRecord` for the reacting step and skip it when `COMPLETED`;
  override `receive(Object, String)` in `rt/SagaRuntime.java` and route through `internal/EngineRuntime.java`.
- [X] T023 [P] [US2] Deliver with the source in `relay/Relay.java` (`receive(payload, sourceSagaId)` when
  `StoredMessage.sourceSagaId()` is non-null) and in the runtime test-jar's `TestTransport`
  (`saga-forge-runtime/…test/runtime/TestTransport.java` or its actual location).
- [X] T024 [P] [US2] Keep the batch's `sagaId` with each queued event in `fixture/LoopBackTransport.java` and deliver
  with it.
- [X] T025 [US2] Make T015 to T018 pass; add Property 47 to `.kiro/specs/saga-forge-framework/design.md` (text from
  contracts/event-receiver-source.md) and reference it from the tests' Javadoc.

**Checkpoint**: `mvn -q -pl saga-forge-core,saga-forge-runtime,saga-forge-transport-inmemory,saga-forge-test -am verify`
green.

---

## Phase 5: User Story 3 - Saga Forge's EventPublisher reaches module listeners (P2)

**Goal**: under `application-events.mode=in-transaction`, `EventPublisher.publish` outside a unit of work also publishes
through Spring in the caller's transaction.

**Independent test**: `PublishOnceContainerTest#eventPublisherReachesListener`.

### Tests

- [X] T026 [P] [US3] Add `ApplicationEventPublishingEventPublisherTest` in
  `spring-test/events/ApplicationEventPublishingEventPublisherTest.java`: EP1 (inside a scope: delegate only), EP2
  (outside: delegate then Spring, under the guard), EP3 (delegate failure → nothing published to Spring).
- [X] T027 [US3] Add cases to `spring-test/events/PublishOnceContainerTest.java`: `EventPublisher.publish` committed →
  listener once, step once; rolled back → neither; `mode=none` → saga only.

### Implementation

- [X] T028 [US3] Implement `ApplicationEventPublishingEventPublisher` (public, decorator holding `EventPublisher` and
  `ApplicationEventPublisher`) in `spring/events/ApplicationEventPublishingEventPublisher.java`.
- [X] T029 [US3] In `spring/events/ApplicationEventsConfiguration.java` (`InTransaction`), wrap the bean named
  `sagaForgeEventPublisher` only (a `BeanPostProcessor` matching by name, like the outbox wrapping); an
  application-defined `EventPublisher` is left alone; make T026, T027 pass.

---

## Phase 6: User Story 4 - Each consumer receives each event once, whatever the route (P1)

**Goal**: no loop between Saga Forge's own publications and the inbound route; Spring-delivered events recorded in a
unit of work are not published to Spring again; `after-delivery` refused with the route.

**Independent test**: `PublishOnceContainerTest` delivery-matrix cases and `AfterDeliveryConflictTest`.

### Tests

- [X] T030 [P] [US4] Add the delivery-matrix cases (contracts/inbound-route.md table, every row) to
  `spring-test/events/PublishOnceContainerTest.java`, counting stored messages per event, saga step runs and listener
  calls; include an outcome event and a plain synchronous `@EventListener`.
- [X] T031 [P] [US4] Add `AfterDeliveryConflictTest` in `spring-test/events/AfterDeliveryConflictTest.java`
  (`ApplicationContextRunner`): AD2 failure with both property names in the failure analysis; AD3 with
  `inbound.enabled=false` starts and logs the deprecation (AD1).

### Implementation

- [X] T032 [US4] Change `spring/events/ApplicationEventPublishingOutbox.java` per PO1 (publish under `PublishingGuard`,
  skip and remove entries in `AlreadyPublishedEvents`); wire the guard into `InboundSagaEventListener` IR1 and the
  publisher decorator EP2 if not done yet.
- [X] T033 [US4] Deprecate `ApplicationEventsMode.AFTER_DELIVERY` in `spring/events/ApplicationEventsMode.java`
  (`@Deprecated` + Javadoc), log `saga.applicationEvents.afterDeliveryDeprecated` once in the `AfterDelivery`
  configuration, and throw `SagaForgeConfigurationException` with the AD2 texts when the route is on, in
  `spring/events/ApplicationEventsConfiguration.java`; check `spring/diagnostics/SagaForgeConfigurationFailureAnalyzer.java`
  renders it.
- [X] T034 [US4] Update `spring-test/events/ApplicationEventsModeTest.java` for the deprecation and conflict; make T030,
  T031 pass.

---

## Phase 7: User Story 5 - A model with deadlines starts under Modulith without extra configuration (P3)

**Goal**: D9 / contracts/starter-configuration.md DI1 to DI4.

**Independent test**: `DeadlineInteropDefaultTest`.

- [X] T035 [P] [US5] Add `DeadlineInteropDefaultTest` in `spring-test/modulith/DeadlineInteropDefaultTest.java`
  (container, Modulith JDBC registry): DI1 (no property, deadline model → own log, INFO log), DI2 (explicit `true` →
  refused, analyzer text), DI3 (no deadline → `ModulithMessageStore`), DI4 (`sagaforge` endpoint names the store used).
- [X] T036 [US5] In `spring/modulith/ModulithInteropConfiguration.java`, return `MessageStore`: when
  `saga-forge.transport.modulith-interop` is not set and a class from `SagaModelCollector.collect()` declares
  `@StartDeadline`, return a `PostgresMessageStore` and log `saga.transport.modulithInterop.skipped`.
- [X] T037 [US5] In `spring/diagnostics/DeadlinesRequireOwnLogFailureAnalyzer.java`, treat "not set" as not active
  (explicit `true` only); in `spring/actuator/SagaForgeEndpoint.java` report the store actually used if it reports
  interop today; make T035 pass.

---

## Phase 8: Polish & Cross-Cutting

- [X] T038 [P] Switch `example/OrderPlacement.java` to Spring's `ApplicationEventPublisher` (SC-003); update its
  Javadoc; keep `saga-forge-example-spring/…test/example/spring/OrderApplicationContainerTest.java` green and assert the
  `FailedOrders` listener and saga each see one event.
- [X] T039 [P] Update `docs/architecture.md` (event flow with two logs, the three routes, Property 47 fan-out,
  ordering limits) and `docs/use-cases.md` where it shows `EventPublisher` from application code.
- [X] T040 [P] Update the guide: `docs/guide/getting-started.md`, `docs/guide/recipes.md` (publishing from module code,
  Spring Data `@DomainEvents`), `docs/guide/model-reference.md`, `docs/guide/limitations.md` (G-014-01, G-014-04,
  G-014-05, deprecated `after-delivery`), with snippets copied from compiling code naming their source file.
- [X] T041 [P] Update `saga-forge-spring-boot-starter/README.md` (defaults table, properties table with
  `saga-forge.application-events.inbound.enabled`, `after-delivery` deprecated, deadline default, an upgrade note for
  applications publishing an event both ways); `ReadmePropertiesTest` green.
- [X] T042 [P] Update `docs/agents/saga-forge/SKILL.md` and `docs/agents/AGENTS-snippet.md`; run
  `docs/guide/build-llms-full.sh` and commit `llms-full.txt`.
- [X] T043 Update `.specify/memory/memory.md`: close G-008-03 and G-ENG-24 (with the tests that prove them), update
  G-017-01 (implicit default done), revise G-014-04/G-014-05 to their final wording, record any new gap found; update
  `.specify/memory/roadmap.md` (014 done) and AGENTS.md "Current Status" (014 entry, test count).
- [ ] T044 Run `mvn spotless:apply`, then `mvn -Pcontainer-tests verify` from the repository root and
  `mvn -P release -Dgpg.skip -DskipTests install`; record the test count in AGENTS.md and the progress note; fix any
  failure before marking done.

---

## Dependencies & Execution Order

- Setup (T001, T002) → Foundational (T003 to T005) → stories.
- US1 (T006 to T014) needs T004 and T005.
- US2 (T015 to T025) needs only Setup; it touches no Spring code and can run in parallel with US1.
- US3 (T026 to T029) needs T010 (guard) from US1.
- US4 (T030 to T034) needs US1 and US3.
- US5 (T035 to T037) is independent of US1 to US4 (only Setup).
- Polish (T038 to T044) after all stories; T038 needs US1.

### Parallel opportunities

- After Foundational: US1, US2 and US5 in parallel (different modules or packages).
- Within US1: T006 to T011 in parallel (separate files).
- Within US2: T015 to T018 in parallel; T023 and T024 in parallel after T019.
- Polish: T038 to T042 in parallel.

### Parallel example: User Story 2

```text
T015 RecordedEventFanOutTest      (rt-test/)
T016 EventRouterSourceTest        (rt-test/)
T017 TransportAcceptanceSuite     (tr-test/support/)
T018 FixtureParityTest            (fixture-test/)
```

## Implementation Strategy

1. **MVP**: Setup, Foundational, US1: module code publishes through Spring and sagas react. With US2 in parallel, the
   silent loss of G-ENG-24 is fixed in the same increment.
2. **Increment 2**: US3 and US4: both publishing APIs equivalent, no duplicates, `after-delivery` refused.
3. **Increment 3**: US5, then Polish and the whole build.

## Progress note

- **Baseline (T001)**: `mvn verify` without containers green on 2026-11-26, 1,819 tests, 0 failures, 1 skipped. Docker
  is not available in this session, so no `@Tag("container")` suite ran (G-014-06).
- **Design skills (T002)**: `effective-java`, `clean-code-java` and `solid-design-review` are not installed; their
  principles were applied by hand (research.md R12).
- **Deviations**:
  - T005: the property is covered by `ReadmePropertiesTest` and `PublishOnceTest.inboundDisabled`, not a new
    `SagaForgePropertiesTest` case.
  - T007: `PublishOnceContainerTest` covers commit, rollback and no transaction over Postgres; the restart case was not
    added (the transport suites already restart stored messages). Written and compiled, not run.
  - T008 and T009: folded into `PublishOnceTest` over the runtime's doubles (a handler publishing through Spring inside
    its unit of work; the route switched off), not separate classes; a real Spring Data `@DomainEvents` save is not
    exercised (G-014-06).
  - T014, T017: the Modulith interop and Postgres setups of `TransportAcceptanceSuite` were not run (no Docker).
  - T018: no fan-out case in `FixtureParityTest`, whose hand-assembled runtime uses `ImmediateDispatchOutbox` (no source,
    G-014-04); `RecordedEventFanOutFixtureTest` checks the fixture instead. `CorrelationTest` was updated from the old
    G-ENG-24 behaviour.
  - T020: no separate `EventSubscriptionsTest`; covered by `SagaRuntimeEventTypesTest` and `RecordedEventFanOutTest`.
  - T027, T030, T031: in `PublishOnceTest` and `ApplicationEventsModeTest` instead of the container test and a new
    `AfterDeliveryConflictTest` class.
  - T035: `DeadlineInteropDefaultTest` runs without a container over stub registry beans; DI4 (Actuator) not added.
  - Existing tests changed because 014 changes behaviour (Property 47): `InTransactionOutboxTest`,
    `OutboxObligationsTest`, `OutboxObligationsPropertyTest`, `RecordedEventsTest`, `CorrelationTest`,
    `ApplicationEventsModeTest.afterDelivery`, `FailureAnalyzersTest`.
