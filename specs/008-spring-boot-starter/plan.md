# Implementation Plan: Spring Boot Starter

**Branch**: `008-spring-boot-starter` | **Date**: 2026-11-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/008-spring-boot-starter/spec.md`

## Summary

Complete `saga-forge-spring-boot-starter` so a Spring Boot application gets a started `SagaRuntime` from dependencies
alone. The starter:
- collects the saga model from the auto-configuration packages and the application's beans;
- detects storage and transport on the classpath, failing fast on none or ambiguity;
- backs the host SPIs with Spring: prototype saga beans, handler beans, `resolveDependency` for handler parameters,
  Spring Data repositories for aggregates;
- builds the runtime in a bean and starts it in a `SmartLifecycle` before the 007 transport lifecycle.

Every assembled piece backs off for an application bean; properties live under `saga-forge.*` with IDE metadata.
Misconfigurations become startup failures explained by `FailureAnalyzer`s.

For Spring Modulith, an outbox decorator publishes every recorded domain event through Spring's
`ApplicationEventPublisher` inside the unit of work's transaction, so `@ApplicationModuleListener`s receive them with
Modulith's guarantees. This needs one additive core change, `OutgoingMessages.recordedEvents`.

An optional Actuator endpoint and health indicator expose the message store's state. A new `saga-forge-example-spring`
module shows the canonical saga as a Spring Boot application. Details: [research.md](research.md).

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: core and runtime unchanged (JDK only). Starter: Spring Boot 4.1.2 (`spring-boot-autoconfigure`,
`spring-boot`), Spring Framework 7.0.10 (`spring-context`, `spring-jdbc`, `spring-tx`); optional: Spring Data Commons
4.1.2, Spring Modulith 2.1.1 (`spring-modulith-events-core`), `spring-boot-actuator`/`-actuator-autoconfigure`/`-health`
4.1.2, `spring-boot-configuration-processor` (annotation processor). Versions pinned without importing Boot's BOM
(G-DEP-01).

**Storage**: PostgreSQL through the 003/007 module; optional schema initialization from `schema.sql`.

**Testing**: JUnit 5, AssertJ; `ApplicationContextRunner` for conditions and properties; `SpringApplicationBuilder`
contexts with Testcontainers Postgres for end-to-end (`@Tag("container")`); Spring Modulith `ApplicationModules`
verification; the 007 `TransportAcceptanceSuite`.

**Target Platform**: Spring Boot 4.1 applications (servlet, reactive or none).

**Project Type**: multi-module Maven library plus one example application module.

**Performance Goals**: SC-005 — startup overhead ≤ 1 s for a 20-saga model (scanning limited to auto-configuration
packages; one `validateAll`).

**Constraints**: nothing outside the starter and the Spring example may depend on Spring (Constitution Principle I);
one runtime per application; JUnit 5 in this repository (research §13).

**Scale/Scope**: one starter module, one example module, one additive core field.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Result |
|---|---|---|
| I. Zero-Dependency Core | Core gains one list on `OutgoingMessages` (JDK only). All Spring code stays in `saga-forge-spring-boot-starter` and `saga-forge-example-spring`. | Pass |
| II. Annotation-Driven, Declarative Style | No new annotation; sagas and handlers stay plain annotated classes. Spring integration is through existing SPIs, not orchestration glue. | Pass |
| III. Formal Spec Conformance | No change to annotation semantics or validation rules. Property 26 (outbox obligations) gains `recordedEvents`; `.kiro` design updated in the same change. | Pass (task) |
| IV. Explicit, Narrow Scope | No new storage or transport. Spring Data and Actuator are host-framework integrations, not storage or transport technologies. | Pass |
| V. Documentation Hierarchy | README "Getting started" becomes real; `docs/architecture.md` starter section and detection matrix updated; AGENTS.md status. | Pass (task) |

**Post-design re-check (after Phase 1)**: unchanged. The contracts keep Spring inside the starter and the example
module; the only core change is the additive `recordedEvents` list. New gaps G-008-03 and G-008-04 are recorded in
`.specify/memory/memory.md`.

## Project Structure

### Documentation (this feature)

```text
specs/008-spring-boot-starter/
├── plan.md
├── research.md          # §1–§13, risks R1–R3
├── data-model.md        # model sources, properties, detection, endpoint payload
├── quickstart.md
├── contracts/
│   ├── starter-configuration.md   # beans, conditions, ordering, overrides, properties
│   ├── spring-host-spis.md        # saga objects, handler instances/parameters, Spring Data aggregates
│   ├── application-events.md      # recordedEvents, publishing outbox, modes, Modulith semantics
│   └── actuator.md                # endpoint and health indicator
└── tasks.md
```

### Source Code (repository root)

```text
saga-forge-core/.../core/spi/OutgoingMessages.java         # + recordedEvents (additive)
saga-forge-runtime/.../runtime/internal/*UnitOfWork.java   # fill recordedEvents; store when any list is non-empty

saga-forge-spring-boot-starter/src/main/java/io/github/gkosharovdev/sagaforge/spring/
├── SagaForgeProperties.java, SagaForgeConfigurationException.java
├── runtime/    SagaRuntimeConfiguration, SagaRuntimeLifecycle, SagaModelCollector, SagaModelContributor,
│               StorageDetection (implemented in place of a separate SagaForgeStartupCheck bean)
├── host/       SpringSagaObjectProvider, SpringHandlerInstanceProvider, SpringHandlerParameterResolver,
│               SpringDataAggregateResolver
├── storage/    PostgresStorageConfiguration, SchemaInitializer
├── events/     ApplicationEventPublishingOutbox, ApplicationEventsMode, ApplicationEventsConfiguration
├── actuator/   SagaForgeEndpoint, SagaForgeHealthIndicator, SagaForgeActuatorConfiguration
├── diagnostics/SagaForgeConfigurationFailureAnalyzer, SagaRuntimeStartupFailureAnalyzer
├── transport/  (007) InMemoryTransportConfiguration, TransportLifecycle, ApplicationEventBridge, ...
└── modulith/   (007) ModulithInteropConfiguration, ModulithMessageStore, ...
saga-forge-spring-boot-starter/src/main/resources/META-INF/
├── spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
└── spring.factories                                       # FailureAnalyzers

saga-forge-example-spring/                                 # new module: Spring Boot app with the canonical saga
```

**Structure Decision**: extend the existing starter; add one example module; the core and runtime change is a single
additive field.

## Phases (for /speckit-tasks)

1. **Core and runtime**: `OutgoingMessages.recordedEvents`; the runtime fills it and calls `store` when any list is
   non-empty; Property 26 text in `.kiro` design.
2. **Model collection and runtime assembly**: properties, model collector, runtime bean and lifecycle, `CommandGateway`
   / `EventPublisher` beans, detection and startup checks, failure analyzers (US1, US3).
3. **Spring host SPIs**: saga objects, handler instances and parameters, Spring Data aggregates (US2).
4. **Overrides and properties**: back-off of every bean, schema initialization, metadata (US4).
5. **Application events and Spring Modulith**: publishing outbox, modes, module verification, Modulith listener
   delivery (US5).
6. **Actuator** (US6).
7. **Example application, docs, memory**: `saga-forge-example-spring`, README, architecture, AGENTS.md, gap register.

## Complexity Tracking

| Item | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Additive `OutgoingMessages.recordedEvents` in core | FR-018a needs every recorded event, including those a step of the same instance ran in process, published inside the transaction (research §8) | Publishing from the relay misses in-process events and runs outside the transaction, so Modulith records nothing. |
| New example module `saga-forge-example-spring` | SC-001/SC-002 need a real Spring Boot app; Spring Data mapping annotations must not leak into the framework-free canonical example | Adding Spring to `saga-forge-example` breaks its framework-free role. |
