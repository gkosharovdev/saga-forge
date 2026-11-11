---
description: "Task list for the Spring Boot Starter"
---

# Tasks: Spring Boot Starter

**Input**: Design documents from `/specs/008-spring-boot-starter/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Key decisions (plan.md Summary, research.md)**:
- **Model** (§1): auto-configuration packages + bean classes + `saga-forge.scan-packages` + `SagaModelContributor`
  beans; sagas, aggregates and handler/loader/saver hosts; events and commands come from signatures.
- **Host SPIs** (§2–§4): prototype saga beans; handler beans; `resolveDependency` for handler parameters; Spring Data
  `Repositories` + repository invoker for aggregates.
- **Runtime** (§6): built in a `@ConditionalOnMissingBean` factory, started by `SagaRuntimeLifecycle` one phase before
  007's `TransportLifecycle`.
- **Failures** (§7): `SagaForgeConfigurationException` and `SagaRuntimeStartupException` explained by
  `FailureAnalyzer`s.
- **Events** (§8): additive `OutgoingMessages.recordedEvents`; `ApplicationEventPublishingOutbox` publishes in the
  unit of work's transaction; `saga-forge.application-events.mode` replaces 007's `publish-application-events`.
- **Actuator** (§10): read-only `sagaforge` endpoint and health indicator.
- **Example** (§12): new module `saga-forge-example-spring` with Spring Data JDBC.

**Tests**: required (spec Independent Tests, SC-001 to SC-006, quickstart.md scenarios 1 to 12). Same convention as 004
to 007:
- production code first, then its tests in the same or a later task of the same phase;
- no task in the middle of a phase runs tests (compiling is allowed);
- each phase ends with one checkpoint task.

Container suites (`@Tag("container")`) run at the checkpoints with `-Pcontainer-tests`, since a container runtime is
available locally (Podman), as in 007.

**Organization**: by user story (US1 P1, US2 P1, US3 P2, US4 P2, US5 P2, US6 P3).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 to US6; Setup, Foundational and Polish tasks carry no story label
- All paths are relative to the repository root `saga-forge/`

## Path Conventions

- **Core**: `saga-forge-core/src/{main,test}/java/io/github/gkosharovdev/sagaforge/core/` → `core/…`, `core-test/…`
- **Runtime**: `saga-forge-runtime/src/{main,test}/java/io/github/gkosharovdev/sagaforge/runtime/` → `runtime/…`,
  `runtime-test/…` (package-private machinery in `runtime/internal/`)
- **Starter**: `saga-forge-spring-boot-starter/src/main/java/io/github/gkosharovdev/sagaforge/spring/` → `spring/…`;
  resources `saga-forge-spring-boot-starter/src/main/resources/` → `spring-res/…`; tests
  `saga-forge-spring-boot-starter/src/test/java/io/github/gkosharovdev/sagaforge/spring/` → `spring-test/…`
- **Spring example**: `saga-forge-example-spring/src/{main,test}/java/io/github/gkosharovdev/sagaforge/example/spring/`
  → `xspring/…`, `xspring-test/…`; resources `saga-forge-example-spring/src/main/resources/` → `xspring-res/…`
- **Compile check**: `mvn -B -pl <modules> -am -DskipTests clean verify` (the `clean` avoids stale IDE classes,
  G-REPO-02).
- **Unit checkpoint**: `mvn -B -pl <modules> -am clean verify`; **container checkpoint**: add `-Pcontainer-tests`.

---

## Phase 1: Setup

- [X] T001 Update `.kiro/specs/saga-forge-framework/design.md`: extend Property 26 (outbox obligations) with
  `recordedEvents` per [contracts/application-events.md](./contracts/application-events.md) (every recorded event, in
  recording order, a superset of `events`; `store` called when any of `events`, `commands`, `recordedEvents` is
  non-empty), and add a note that 008 changes no annotation, rule or other property.
- [X] T002 [P] Add the section "Spring Boot starter (008), implementation notes" to `.specify/memory/memory.md` after
  G-008-04, holding a dated note pointing to this tasks.md; T058 fills it in.
- [X] T003 [P] Update `saga-forge-spring-boot-starter/pom.xml`:
  - compile: `spring-boot` 4.1.2 and `spring-context` (FailureAnalyzer, `AutoConfigurationPackages`);
  - optional: `spring-data-commons` 4.1.2, `spring-boot-actuator`, `spring-boot-actuator-autoconfigure`,
    `spring-boot-health` 4.1.2;
  - annotation processor: `spring-boot-configuration-processor` 4.1.2 (optional);
  - test: `spring-boot-starter-data-jdbc` 4.1.2 (with `spring-boot-data-jdbc`, `spring-data-jdbc`, `spring-data-relational`
    pinned in `dependencyManagement` to the Boot 4.1.2 line), `spring-modulith-starter-core` and
    `spring-modulith-core` 2.1.1 (for `ApplicationModules`), the runtime test-jar.
  Pin every new version without importing Boot's BOM (G-DEP-01).
- [X] T004 [P] Create module `saga-forge-example-spring` (pom: parent, Boot 4.1.2 pins as in the starter,
  dependencies `saga-forge-spring-boot-starter`, `saga-forge-storage-postgres`, `saga-forge-transport-inmemory`,
  `spring-boot-starter-data-jdbc`, `postgresql`; test: JUnit 5, AssertJ, Testcontainers Postgres; surefire
  `excludedGroups` and `container-tests` profile as in the starter) and add it to the root `pom.xml` modules after
  `saga-forge-example`.

---

## Phase 2: Foundational

**Purpose**: the core/runtime change for FR-018a, properties, exceptions and failure analysis, with every module
green.

- [X] T005 Extend `core/spi/OutgoingMessages.java` with `List<Object> recordedEvents` per the contract: canonical
  constructor with six components; the five- and four-argument constructors default `recordedEvents` to `events`;
  unmodifiable copy, no null element; non-empty when any of the three lists is non-empty. Extend
  `core-test/spi/OutgoingMessagesTest.java`.
- [X] T006 In `runtime/internal/SagaUnitOfWork.java`, `CompensationUnitOfWork.java` and `NonSagaUnitOfWork.java`, fill
  `recordedEvents` with every recorded event in recording order (including those a step of the instance ran in
  process) and build a batch when any list is non-empty; `ImmediateDispatchOutbox` still dispatches `events` only.
- [X] T007 [P] Write `runtime-test/RecordedEventsTest.java`: an event that triggers the next step of the same instance
  appears in `recordedEvents` but not `events`; order matches recording; a unit of work whose only events ran in process
  still calls `store` (IN_TRANSACTION) and `ImmediateDispatchOutbox` dispatches nothing for it; rollback stores nothing.
  Extend `runtime-test/OutboxObligationsPropertyTest.java` with the superset and order invariant.
- [X] T008 [P] Create `spring/SagaForgeProperties.java` (`@ConfigurationProperties("saga-forge")`, `@Validated`): the
  properties of [data-model.md](./data-model.md) except `transport.*` (kept in 007's `TransportProperties`), with
  nested `Storage` (`type`, `Postgres.initializeSchema`), `Runtime.maxTotalRetryBackoff`,
  `ApplicationEvents.mode` (`ApplicationEventsMode` enum in `spring/events/ApplicationEventsMode.java`).
- [X] T009 [P] Create `spring/SagaForgeConfigurationException.java` (message, `description`, `action`) and
  `spring/diagnostics/SagaForgeConfigurationFailureAnalyzer.java` and
  `spring/diagnostics/SagaRuntimeStartupFailureAnalyzer.java` (the latter lists every error of the
  `StartupReport`); register both in `spring-res/META-INF/spring.factories` under
  `org.springframework.boot.diagnostics.FailureAnalyzer`.
- [X] T010 [P] Write `spring-test/diagnostics/FailureAnalyzersTest.java`: description and action for both exception
  types; every report error appears.
- [X] T011 Checkpoint: `mvn -B -pl saga-forge-core,saga-forge-runtime,saga-forge-spring-boot-starter -am clean verify`.

---

## Phase 3: User Story 1 — runtime without assembly code (P1) 🎯 MVP

**Goal**: a Spring Boot app with the starter, Postgres and a datasource gets a started runtime.

**Independent Test**: test app with the canonical saga classes (copies in `spring-test/fixtures/order/`), no assembly
code; `OrderCreatedEvent` through the `EventPublisher` bean reaches `COMPLETED` (quickstart scenario 1, part 1).

- [X] T012 [US1] Create `spring/runtime/SagaModelContributor.java` (functional interface: `Collection<Class<?>>
  classes()`) and `spring/runtime/SagaModelCollector.java`: scan auto-configuration packages and
  `saga-forge.scan-packages` with a `ClassPathScanningCandidateComponentProvider` and a `TypeFilter` that matches
  `@Saga`, `@Aggregate`, or any method annotated `@CommandHandler` / `@AggregateLoader` / `@AggregateSaver` (class
  metadata only); add the user classes (`ClassUtils.getUserClass`) of matching beans and every contributor's classes;
  return a stable, de-duplicated, ordered list (research §1, data-model.md).
- [X] T013 [US1] Create `spring/runtime/SagaRuntimeConfiguration.java` (`@AutoConfiguration`, after
  `InMemoryTransportConfiguration` and the storage configuration; `@ConditionalOnProperty saga-forge.enabled`
  matchIfMissing): beans `SagaModelCollector`; `SagaRuntime` (`@ConditionalOnMissingBean`, built with the collected
  model, `StorageProvider`, `SagaContextStore`, `TransactionBoundary`, the transport's dispatcher and outbox (via
  `ObjectProvider<Outbox>` so T040 can decorate it), the host SPI beans of US2 when present, and
  `runtime.max-total-retry-backoff`; not started); `CommandGateway` and `EventPublisher` beans delegating to it.
- [X] T014 [US1] Move the `SpringTransactionBoundary` bean from 007's `spring/transport/InMemoryTransportConfiguration.java`
  into a new `spring/SpringTransactionConfiguration.java` (`@ConditionalOnMissingBean(TransactionBoundary)`) shared by
  storage, transport and runtime; keep its behaviour.
- [X] T015 [US1] Create `spring/storage/PostgresStorageConfiguration.java` (`@ConditionalOnClass(PostgresStorageProvider)`,
  `DataSource` bean, `storage.type` unset or `postgres`): `PostgresStorageProvider(dataSource, boundary)` and
  `PostgresSagaContextStore(dataSource)` (`@ConditionalOnMissingBean` each). Move the `PostgresMessageStore` bean from
  007's transport configuration here.
- [X] T016 [US1] Create `spring/runtime/SagaRuntimeLifecycle.java` (`SmartLifecycle`, phase
  `TransportLifecycle.PHASE - 1`): starts the `SagaRuntime` bean if not started; `isRunning` reflects it. Change 007's
  `spring/transport/TransportLifecycle.java` to no longer start the runtime itself (it only starts the transport
  around a started runtime), and update its test.
- [X] T017 [US1] Register the new auto-configurations in
  `spring-res/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` in dependency order.
- [X] T018 [P] [US1] Create test fixtures `spring-test/fixtures/order/` (canonical order saga, `Order` with an
  `@AggregateLoader`/`@AggregateSaver` repository bean for US1, `PaymentService` bean, commands, events) and
  `spring-test/support/StarterApp.java` (a `SpringApplicationBuilder` helper over Testcontainers Postgres with
  `initialize-schema=always`, reusing 007's `StarterPostgres`).
- [X] T019 [US1] Write `spring-test/runtime/SagaModelCollectorTest.java` (scan, beans, proxies, scan-packages,
  contributors, order) and `spring-test/runtime/RuntimeAssemblyContainerTest.java` (`@Tag("container")`): US1
  scenarios 1 to 4 (single started runtime, `EventPublisher`/`CommandGateway` beans drive the saga, shutdown stops the
  transport first then the runtime with nothing lost, in-memory transport delivers between sagas).
- [X] T020 [US1] Checkpoint: `mvn -B -Pcontainer-tests -pl saga-forge-spring-boot-starter -am clean verify`.

---

## Phase 4: User Story 2 — Spring idioms for sagas, handlers and aggregates (P1)

**Goal**: injected sagas, bean handlers, resolved parameters, Spring Data aggregates.

**Independent Test**: the order fixture with `Order` as a Spring Data JDBC aggregate and no loader/saver completes;
a constructor-injected saga uses its collaborator (quickstart scenarios 2, 3).

- [X] T021 [P] [US2] Create `spring/host/SpringSagaObjectProvider.java` per
  [contracts/spring-host-spis.md](./contracts/spring-host-spis.md), including the prototype-scope check (startup error
  through `SagaForgeConfigurationException`).
- [X] T022 [P] [US2] Create `spring/host/SpringHandlerInstanceProvider.java` with the eager startup check (none or
  several beans for a registered host class → `SagaForgeConfigurationException` naming it).
- [X] T023 [P] [US2] Create `spring/host/SpringHandlerParameterResolver.java` (`resolveDependency` with a
  `DependencyDescriptor` over the `MethodParameter`, cached per parameter; none-or-many = unsupported).
- [X] T024 [P] [US2] Create `spring/host/SpringDataAggregateResolver.java` (`Repositories`,
  `DefaultRepositoryInvokerFactory` with the application `ConversionService`; startup check that the identifier type
  converts to the repository id type).
- [X] T025 [US2] Create `spring/host/SpringHostConfiguration.java` and `spring/host/SpringDataAggregateConfiguration.java`
  (`@ConditionalOnClass(Repositories)`), each bean `@ConditionalOnMissingBean`; wire them into T013's runtime builder.
- [X] T026 [P] [US2] Create fixture `spring-test/fixtures/jdbc/` (an `Order` aggregate mapped for Spring Data JDBC with
  `@Id`, `@Version`; `OrderRepository extends CrudRepository<Order, String>`; table DDL in
  `spring-test-res/jdbc-orders.sql`), a saga with a constructor-injected `Clock` bean, and a handler with a
  `@Qualifier`-ed parameter.
- [X] T027 [US2] Write `spring-test/host/SpringHostSpisContainerTest.java` (`@Tag("container")`): US2 scenarios 1 to 5,
  singleton saga bean refused, `@Qualifier` honoured, none/several parameter beans fail startup naming the parameter,
  client loader wins over a repository, unconvertible identifier type fails naming both types.
- [X] T028 [US2] Checkpoint: `mvn -B -Pcontainer-tests -pl saga-forge-spring-boot-starter -am clean verify`.

---

## Phase 5: User Story 3 — misconfiguration fails at startup (P2)

**Goal**: every misconfiguration stops startup with a description and an action.

**Independent Test**: each misconfigured context fails; failure analysis text checked (quickstart scenarios 4 to 6).

- [X] T029 [US3] Create `spring/runtime/SagaForgeStartupCheck.java` (bean created before the runtime): storage
  detection per [data-model.md](./data-model.md) "Detection result" — none (and no application `StorageProvider` +
  `SagaContextStore`), several without `storage.type`, `storage.type` naming an absent module — and two `SagaRuntime`
  beans; each a `SagaForgeConfigurationException` with description and action.
- [X] T030 [US3] Make every startup error of US2 (T021 to T024) and the runtime's `SagaRuntimeStartupException` reach
  the failure analyzers (no wrapping that hides the cause); add the action texts.
- [X] T031 [US3] Write `spring-test/diagnostics/MisconfigurationTest.java` with `ApplicationContextRunner` (and
  `FilteredClassLoader` to hide modules): no storage, unknown `storage.type`, invalid model (full report), singleton
  saga bean, handler class without a bean, two runtimes; each asserts the failure type and that the analysis names the
  class/bean/property/module and gives an action (SC-003). A second storage module is simulated by an extra
  test-only storage marker on the class path.
- [X] T032 [US3] Checkpoint: `mvn -B -pl saga-forge-spring-boot-starter -am clean verify`.

---

## Phase 6: User Story 4 — overrides and properties (P2)

**Goal**: every piece replaceable; properties validated and documented; schema initialization.

**Independent Test**: one test per replaceable bean; each property's effect; schema `always`/`never` (quickstart
scenarios 7, 8).

- [X] T033 [US4] Review every starter bean for `@ConditionalOnMissingBean` (the nine types of
  [contracts/starter-configuration.md](./contracts/starter-configuration.md)) and `saga-forge.enabled`; with an
  application-defined `SagaRuntime`, both lifecycles still apply.
- [X] T034 [US4] Create `spring/storage/SchemaInitializer.java` (`always`: apply `schema.sql` via
  `ResourceDatabasePopulator` when `saga_instance` is missing; `never`: missing tables → `SagaForgeConfigurationException`
  naming the tables and the resource path) and wire it in `PostgresStorageConfiguration` before the runtime bean.
- [X] T035 [P] [US4] Write `spring-test/runtime/OverridesTest.java` (`ApplicationContextRunner`, one nested test per
  replaceable type, SC-004, plus `saga-forge.enabled=false`) and `spring-test/SagaForgePropertiesTest.java` (binding,
  invalid values fail naming the property, `runtime.max-total-retry-backoff` reaches the runtime).
- [X] T036 [P] [US4] Write `spring-test/storage/SchemaInitializerContainerTest.java` (`@Tag("container")`): `always` on
  an empty schema creates the tables; `never` on an empty schema fails naming tables and resource; existing tables are
  never altered.
- [X] T037 [US4] Check that `spring-boot-configuration-processor` produces
  `META-INF/spring-configuration-metadata.json` for `saga-forge.*` (including 007's `transport.*`) with descriptions
  and defaults; add `spring-res/META-INF/additional-spring-configuration-metadata.json` for enum hints.
- [X] T038 [US4] Checkpoint: `mvn -B -Pcontainer-tests -pl saga-forge-spring-boot-starter -am clean verify`.

---

## Phase 7: User Story 5 — Spring Modulith (P2)

**Goal**: one outbox, module verification, `@ApplicationModuleListener` delivery with Modulith semantics.

**Independent Test**: three-module test app; `ApplicationModules.verify()` passes; a listener in another module
receives a saga event once after commit, never on rollback, resubmitted after a failure (quickstart scenario 9).

- [X] T039 [US5] Create `spring/events/ApplicationEventPublishingOutbox.java` per
  [contracts/application-events.md](./contracts/application-events.md).
- [X] T040 [US5] Create `spring/events/ApplicationEventsConfiguration.java`: for `in-transaction`, wrap the transport's
  `Outbox` in `ApplicationEventPublishingOutbox` for the runtime builder; for `after-delivery`, register 007's
  `ApplicationEventBridge`; for `none`, neither. Remove `publish-application-events` from 007's `TransportProperties`
  and the bridge registration from `InMemoryTransportConfiguration`; update 007's tests and the 007 property docs.
- [X] T041 [P] [US5] Create the Modulith test app `spring-test/modulith/app/` with application modules `orders`
  (saga), `payments` (handler, `PaymentProcessedEvent`) and `notifications` (an `@ApplicationModuleListener` for
  `PaymentProcessedEvent` that records calls and can be told to fail once), sharing the order fixture types.
- [X] T042 [US5] Write `spring-test/modulith/ModulithModulesTest.java` (`ApplicationModules.of(App.class).verify()`
  passes with Saga Forge on the class path, FR-019) and `spring-test/modulith/ModuleListenerContainerTest.java`
  (`@Tag("container")`, Spring Modulith JDBC registry): US5 scenarios 1 to 5 — one outbox; listener runs once after
  commit, in its own transaction, publication completed; never on rollback; failed listener's publication resubmitted
  (via `IncompleteEventPublications`).
- [X] T043 [US5] Write `spring-test/events/ApplicationEventsModeTest.java`: `in-transaction` publishes recorded events
  (including in-process ones) once; a plain `@EventListener` failure rolls the unit of work back (documented
  semantics); `after-delivery` uses the bridge only; `none` publishes nothing.
- [X] T044 [US5] Checkpoint: `mvn -B -Pcontainer-tests -pl saga-forge-spring-boot-starter -am clean verify`.

---

## Phase 8: User Story 6 — Actuator (P3)

**Goal**: read-only endpoint and health indicator.

**Independent Test**: endpoint and health with a set-aside message (quickstart scenario 10).

- [X] T045 [P] [US6] Create `spring/actuator/SagaForgeEndpoint.java`, `spring/actuator/SagaForgeHealthIndicator.java`
  and `spring/actuator/SagaForgeActuatorConfiguration.java` per [contracts/actuator.md](./contracts/actuator.md);
  register the configuration in the imports file.
- [X] T046 [US6] Write `spring-test/actuator/ActuatorTest.java` (`ApplicationContextRunner` with an
  `InMemoryMessageStore` double): payload, `UP`/`DOWN` with `setAside`, no write operation, switched off by
  `management.*` properties, nothing registered without actuator classes (`FilteredClassLoader`).
- [X] T047 [US6] Checkpoint: `mvn -B -pl saga-forge-spring-boot-starter -am clean verify`.

---

## Phase 9: Polish & cross-cutting

- [X] T048 [P] Write the Spring example in `saga-forge-example-spring`: `xspring/OrderApplication.java`
  (`@SpringBootApplication`), the canonical saga, `Order` as a Spring Data JDBC aggregate (`@Id`, `@Version`),
  `OrderRepository extends CrudRepository<Order, String>`, `PaymentService` and `PaymentProvider` beans, commands and
  events, `xspring-res/application.properties` (datasource placeholders, `saga-forge.storage.postgres.initialize-schema=always`),
  `xspring-res/schema-orders.sql` — and no Saga Forge assembly code (SC-001).
- [X] T049 Write `xspring-test/OrderApplicationContainerTest.java` (`@Tag("container")`): order completes; a stock
  shortage after payment ends `COMPENSATED` with the refund sent (quickstart scenario 1, SC-002).
- [X] T050 Change 007's `spring-test/SpringSetup.java` to let the starter assemble the runtime (drop its hand-built
  `SagaRuntime` bean and register `TransportAcceptanceSuite.MODEL` through a `SagaModelContributor`), so
  `SpringOwnLogAcceptanceContainerTest` and `ModulithInteropAcceptanceContainerTest` run the 007 suite through the
  starter (SC-006).
- [X] T051 [P] Write `spring-test/runtime/StartupOverheadTest.java`: a generated model of 20 saga classes; context
  start with and without the starter, median of 5 runs, difference ≤ 1 s (SC-005; tag `slow` if it exceeds 10 s).
- [X] T052 [P] Update `README.md`: "Getting started" with the real dependencies and the minimal
  `application.properties` for the baseline setup, linking to the starter README (T053) for defaults, properties and
  overrides instead of repeating them; Modules table rows for the starter and the new example.
- [X] T053 Create `saga-forge-spring-boot-starter/README.md`, written from
  [contracts/starter-configuration.md](./contracts/starter-configuration.md) and the other 008 contracts:
  1. **What you get by default**: a table of what the starter assembles and from what (runtime, saga model sources,
     Postgres storage and context store, in-memory transport and message store, `SpringTransactionBoundary`, the
     Spring host SPIs, application-events mode, lifecycle order, Actuator endpoint and health).
  2. **Common setups**, each with its dependencies and `application.properties` lines: Spring Boot + Postgres +
     in-memory transport (baseline); the same with Spring Modulith (one outbox, `@ApplicationModuleListener`
     delivery); adding Actuator.
  3. **Properties**: every `saga-forge.*` property (including `transport.*`) with default and one-line meaning.
  4. **Overrides**, a short snippet each: an application `SagaRuntime` bean; a custom `AggregateResolver` and a client
     `@AggregateLoader`; an `EventReceiverCustomizer` for tracing; a `SagaModelContributor`; `application-events.mode`
     and `initialize-schema` switched; `saga-forge.enabled=false`.
  5. **Gotchas**: saga beans must be prototype-scoped; a plain `@EventListener` runs inside the saga's unit of work
     (module listeners use `@ApplicationModuleListener`); `initialize-schema=always` is for development, production
     schemas belong in Flyway/Liquibase with the Postgres module's scripts; one runtime per application;
     `@AggregateIdentifier` type must convert to the repository's id type.
  6. **When startup fails**: what the failure analysis reports, with one real example message.
  Add `spring-test/ReadmePropertiesTest.java`: every `saga-forge.*` property named in the README exists in the
  generated `META-INF/spring-configuration-metadata.json`, and every property in that metadata appears in the
  README, so the two cannot drift silently.
- [X] T054 [P] Update `docs/architecture.md`: starter section (what it assembles, overrides, detection matrix as
  implemented, failure analysis, Modulith delivery, Actuator); status table; G-007-02 closed.
- [X] T055 [P] Update `AGENTS.md`: Current Status bullet for 008, Module Map (starter, `saga-forge-example-spring`),
  Known Gaps line for G-008-01 to G-008-04.
- [X] T056 Self-check: every FR-001 to FR-020 (with FR-018a/b) and SC-001 to SC-006 maps to a completed task or a
  recorded gap; close G-007-02 and G-003-26 in memory.md if covered.
- [X] T057 Final checkpoint: `mvn -B -Pcontainer-tests clean verify` for the whole build.
- [X] T058 Fill the memory.md implementation notes from T002 (per-module changes, test counts, deviations) and set
  `specs/008-spring-boot-starter/spec.md` **Status** to "Implemented (date)".

---

## Dependencies & Execution Order

| Phase | Tasks | Depends on |
|---|---|---|
| 1. Setup | T001–T004 | — |
| 2. Foundational | T005–T011 | Phase 1 |
| 3. US1 (MVP) | T012–T020 | Phase 2 |
| 4. US2 | T021–T028 | US1 |
| 5. US3 | T029–T032 | US1, US2 (startup errors) |
| 6. US4 | T033–T038 | US1, US2 |
| 7. US5 | T039–T044 | US1 (T005–T007 for `recordedEvents`) |
| 8. US6 | T045–T047 | US1 |
| 9. Polish | T048–T058 | all |

```text
Setup → Foundational → US1 → US2 ─┬─ US3 ─┐
                          │       └─ US4 ─┤
                          ├──── US5 ──────┼─ Polish
                          └──── US6 ──────┘
```

## Parallel Examples

| Phase | Parallel tasks |
|---|---|
| Phase 1 | T002, T003, T004 after T001 |
| Phase 2 | T008, T009 beside T005–T006; T007 and T010 together |
| US2 | T021, T022, T023, T024 together; T026 beside them |
| US4 | T035 and T036 together |
| Stories | US5 and US6 can run beside US3/US4 once US1 is done |
| Polish | T048, T051, T052, T054, T055 together; T053 after T052 |

## Implementation Strategy

### MVP (US1)

Phases 1 to 3: a Spring Boot app gets a started runtime from dependencies alone, with Postgres and the in-memory
transport. That alone removes hand assembly for adopters.

### Incremental delivery

1. MVP (US1).
2. US2: Spring idioms (injection, Spring Data aggregates).
3. US3 and US4: failures and overrides.
4. US5: Spring Modulith listener delivery.
5. US6: Actuator.
6. Polish: the Spring example, 007 suite through the starter, docs.

Each checkpoint leaves a green build.
