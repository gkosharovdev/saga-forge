# Research: Spring Boot Starter

**Feature**: `specs/008-spring-boot-starter` | **Date**: 2026-11-11

Decisions, rationale and alternatives. Section numbers are cited from `plan.md`, `data-model.md` and `contracts/`.
Versions checked: Spring Boot 4.1.2 (Spring Framework 7.0.10, Spring Data 2026.0.2 / Commons 4.1.2), Spring Modulith
2.1.1.

## §1 Collecting the saga model (FR-002, FR-004)

- **Decision**: the model is the union of
  1. classes found by scanning the application's auto-configuration packages (`AutoConfigurationPackages`, the
     packages Spring Boot already uses for entities and repositories) for: `@Saga` types, `@Aggregate` types, and
     classes declaring a `@CommandHandler`, `@AggregateLoader` or `@AggregateSaver` method (a custom `TypeFilter`
     reading the class metadata, so no class is initialised while scanning);
  2. the bean classes (user class, not CGLIB proxy) of every bean whose type carries one of those annotations;
  3. extra packages from `saga-forge.scan-packages`, scanned like (1);
  4. classes contributed by `SagaModelContributor` beans (FR-004's registration bean).
  Events and commands are reached from signatures by `validateAll`, as today, and need no scanning.
- **Rationale**: scanning alone would miss handler beans defined by `@Bean` methods outside the base packages; beans
  alone would miss sagas and aggregates that are not beans (both are legitimate: FR-010, and aggregates are never
  beans). Auto-configuration packages match what Spring Data and JPA users already rely on.
- **Alternatives**: a `@EnableSagaForge(basePackages = ...)` annotation — an extra mandatory step, against FR-001;
  classpath-wide scanning — slow and picks up test fixtures and library classes.

## §2 Saga objects from the context (FR-010)

- **Decision**: `SpringSagaObjectProvider.supports(type)` is true when the context has a bean definition whose type is
  the saga class; `newInstance` returns `beanFactory.getBean(type)`. At startup a saga bean with any scope other than
  prototype fails with a startup error naming the bean and the fix (`@Scope("prototype")`). Saga classes without a
  bean keep the runtime's constructor path.
- **Rationale**: `SagaObjectProvider` was designed for exactly this (its Javadoc names prototype beans). A singleton
  saga would share state across units of work and instances.

## §3 Handler instances and parameters (FR-011, FR-012)

- **Decision**: `SpringHandlerInstanceProvider.instanceOf(type)` returns the single bean of that type; startup fails
  if a registered service-hosted handler, loader or saver class has none or several (checked eagerly after context
  refresh, before the runtime starts, so the error is a startup error). `SpringHandlerParameterResolver` resolves a
  parameter through `AutowireCapableBeanFactory.resolveDependency(new DependencyDescriptor(MethodParameter, true))`,
  which honours `@Qualifier`, `@Lazy`, `Optional`, `ObjectProvider` and collections like any Spring injection point;
  `supports` performs that resolution once and caches the outcome, reporting none-or-many as unsupported so the
  runtime's existing startup check lists the parameter (FR-012).
- **Rationale**: reusing Spring's own resolution rules means no surprises for Spring developers.

## §4 Aggregates through Spring Data (FR-013)

- **Decision**: `SpringDataAggregateResolver` (in the starter, active when `spring-data-commons` is present) uses
  `Repositories` (over the bean factory) and `DefaultRepositoryInvokerFactory` with the application's
  `ConversionService`: `supports(type)` = `repositories.hasRepositoryFor(type)`; `load` =
  `invoker.invokeFindById(id)` (the id is converted to the repository's id type); `save` = `invoker.invokeSave`.
  The runtime's precedence is unchanged: client loaders/savers first. At startup, an `@Aggregate` whose repository's id
  type cannot be converted from its `@AggregateIdentifier` field type fails naming both (spec edge case).
- **Rationale**: works for every Spring Data module (JDBC, JPA, MongoDB) through one API; the repository joins the
  unit of work's transaction because `SpringTransactionBoundary` runs it in the same Spring transaction.
- **Alternatives**: one resolver per Spring Data module — more code, same behaviour.

## §5 Detection (FR-006 to FR-008)

- **Decision**: a `StorageDetection` step registers the storage configuration for each storage module present
  (`@ConditionalOnClass` on the module's provider class: `PostgresStorageProvider` today; MySQL and MongoDB later).
  A `SagaForgeStartupCheck` bean, created before the runtime, fails with `SagaForgeConfigurationException` (FR-007)
  when no storage module is present and the application defines no `StorageProvider` and `SagaContextStore` of its
  own, or when several are present and `saga-forge.storage.type` does not pick one, naming the modules found.
  Transport: the in-memory configuration (007) applies when no broker transport class is present; a future Kafka
  configuration is `@ConditionalOnClass(KafkaProducer)` with in-memory backing off.
- **Rationale**: matches `docs/architecture.md`'s detection matrix and fails fast instead of guessing.

## §6 Runtime assembly and lifecycle (FR-001, FR-005, FR-014)

- **Decision**: `SagaRuntimeConfiguration` defines a `SagaRuntime` bean (`@ConditionalOnMissingBean`) built from the
  collected model and the SPI beans (storage, context store, `SpringTransactionBoundary`, the transport's dispatcher
  and outbox — wrapped by §8 — the Spring host SPIs, `runtime.max-total-retry-backoff`). Not started in the factory
  method. `SagaRuntimeLifecycle` (`SmartLifecycle`, phase `TransportLifecycle.PHASE - 1`) starts it after refresh;
  `TransportLifecycle` (007, one phase later) starts the transport after it and stops it first. With an
  application-defined `SagaRuntime`, both lifecycles still apply (FR-014). `CommandGateway` and `EventPublisher` beans
  delegate to the runtime (FR-003); they are safe to inject before start (they throw until started, as today).
- **Rationale**: building in the factory and starting in a lifecycle gives Spring's ordering and graceful-shutdown
  guarantees, and lets a refresh failure stop what started (spec edge case).

## §7 Failure reporting (FR-017, SC-003)

- **Decision**: a new `SagaForgeConfigurationException` (starter) for detection, bean and property problems, and
  `FailureAnalyzer`s for it and for `SagaRuntimeStartupException`, registered in `META-INF/spring.factories`
  (`org.springframework.boot.diagnostics.FailureAnalyzer`). Each produces a description (what, which class/bean/
  property) and an action (the fix). The runtime's report lists every error, unchanged.

## §8 Delivering saga events to Spring and Spring Modulith listeners (FR-018a)

- **Problem**: the outbox batch (`OutgoingMessages.events`) holds only events no step of the owning instance ran in
  process; an event that triggered the next step of the same instance never reaches the outbox, yet listeners in other
  modules must see it too (FR-018a: "every domain event recorded in a committed unit of work").
- **Decision**:
  - Core, additive: `OutgoingMessages.recordedEvents` — every event recorded in the unit of work, in recording order
    (a superset of `events`). The runtime fills it for saga, compensation and non-saga units of work. A batch may now
    carry recorded events while `events` and `commands` are empty, so the engine calls `Outbox.store` whenever any of
    the three is non-empty; `ImmediateDispatchOutbox` and the in-memory transport ignore the new list.
  - Starter: `ApplicationEventPublishingOutbox`, a decorator of the transport's `Outbox` with the same timing
    (`IN_TRANSACTION`). `store(batch)` first delegates, then calls `ApplicationEventPublisher.publishEvent` for each
    recorded event, inside the unit of work's Spring transaction. Spring then runs `@TransactionalEventListener`s and
    `@ApplicationModuleListener`s after commit and never on rollback; with Spring Modulith, its publication registry
    stores one publication per such listener in the same transaction, giving completion tracking and resubmission.
  - Mode property `saga-forge.application-events.mode`: `IN_TRANSACTION` (default), `AFTER_DELIVERY` (007's
    `ApplicationEventBridge`) or `NONE`. Only one mode is active, so a listener receives each event once (spec edge
    case). 007's `saga-forge.transport.publish-application-events` is replaced by this property.
- **Consequences to document**: a plain (non-transactional) `@EventListener` runs synchronously inside the unit of
  work, and a failure there rolls the unit of work back — standard Spring semantics; module listeners should use
  `@ApplicationModuleListener`. Events published by application code through Spring's own
  `ApplicationEventPublisher` do not reach sagas; sagas receive events through Saga Forge's `EventPublisher` or
  `DomainEvents.record` (recorded as G-008-03).
- **Alternatives**: publishing from the relay after delivery (007's bridge) — after commit but outside any
  transaction, so `@TransactionalEventListener`s do not fire and Modulith records nothing; a separate SPI for
  "recorded events" — a second call site with the same timing and transaction.

## §9 Module structure verification (FR-019)

- **Decision**: Saga Forge's beans live in `io.github.gkosharovdev.sagaforge.*`, outside any application's base
  package, so Spring Modulith treats them as library types. Verified by a test that builds `ApplicationModules.of(...)`
  for a three-module test application (orders, payments, shipping) and calls `verify()`.

## §10 Actuator endpoint and health (FR-020)

- **Decision**: `SagaForgeEndpoint` (`@Endpoint(id = "sagaforge")`, `@ReadOperation` only) returns
  `MessageStoreQueries.status()` as `{counts: {UNCONSUMED, CONSUMED, SET_ASIDE}, oldestUnconsumed: ISO-8601 duration}`;
  `SagaForgeHealthIndicator` (`org.springframework.boot.health.contributor.AbstractHealthIndicator`) reports `UP`, or
  `DOWN` with `setAside` count. Both in `SagaForgeActuatorConfiguration`, `@ConditionalOnClass` on the actuator and
  health classes and `@ConditionalOnAvailableEndpoint` / `@ConditionalOnEnabledHealthIndicator("sagaforge")`, so
  standard properties switch them off. Optional dependencies: `spring-boot-actuator`,
  `spring-boot-actuator-autoconfigure`, `spring-boot-health`.

## §11 Properties and metadata (FR-015, FR-016)

- **Decision**: `SagaForgeProperties` (`saga-forge.*`): `enabled` (default true), `scan-packages`, `storage.type`
  (`postgres`, later `mysql`, `mongodb`), `storage.postgres.initialize-schema` (`never` | `always`, default `never`),
  `runtime.max-total-retry-backoff` (default 30 s), `application-events.mode` (§8), plus 007's
  `TransportProperties` (`saga-forge.transport.*`). `@Validated` with constraints, and unknown enum values fail binding
  with the property name. Metadata generated by `spring-boot-configuration-processor` (optional, annotation processor).
- **Schema** (FR-016): with `always`, the starter checks for `saga_instance` through `DatabaseMetaData` and, if
  missing, runs `schema.sql` with `ResourceDatabasePopulator`; with `never`, a missing table fails startup naming the
  missing tables and the resource path. Existing tables are never altered — upgrades remain the host's job (G-003-25).

## §12 Example application (SC-001, SC-002, SC-006)

- **Decision**: a new module `saga-forge-example-spring` — a Spring Boot application with the canonical order saga,
  `Order` stored through Spring Data JDBC (`@Id`, `@Version` for new-entity detection), `PaymentService` as a bean,
  and only dependencies plus `application.properties`. The plain `saga-forge-example` stays framework-free. Its
  container test drives an order to `COMPLETED` and to `COMPENSATED`; a Modulith variant splits orders, payments and
  shipping into application modules.
- **Rationale**: the canonical example's `Order` is a plain class; a Spring Data JDBC aggregate needs mapping
  annotations, which must not leak into the framework-free example.

## §13 Testing approach and JUnit (G-DEP-01)

- **Decision**: keep the repository on JUnit 5. Starter tests use `ApplicationContextRunner` and contexts built with
  `SpringApplicationBuilder` (as in 007) instead of `@SpringBootTest`, whose `SpringExtension` follows Spring
  Framework 7's JUnit line. G-DEP-01 stays open with this note; applications using the starter are unaffected (they
  bring their own Boot-managed JUnit).
- **Alternatives**: move the whole repository to JUnit 6 now — jqwik 1.9 runs on the JUnit Platform 1.x line, so the
  property tests would need a jqwik upgrade first; a separate decision.

## Risks

- **R1**: Spring Data JDBC's new-entity detection for aggregates with assigned identifiers (handled with `@Version`
  in the example; documented for adopters).
- **R2**: `resolveDependency` in `supports` may instantiate lazy beans early; acceptable after refresh, documented.
- **R3**: plain `@EventListener`s now run inside saga units of work (§8); called out in the docs and README.
