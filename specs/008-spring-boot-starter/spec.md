# Feature Specification: Spring Boot Starter

**Feature Branch**: `008-spring-boot-starter`

**Created**: 2026-11-11

**Status**: Implemented (2026-11-12)

**Input**: User description: "008 spring boot starter"

## Context

Saga Forge's runtime, Postgres storage and in-memory transport all work, but a Spring Boot application still has to
assemble them by hand: build the `SagaRuntime` with every saga, aggregate and handler class, supply storage, context
store, transactions, handler instances and the transport, and start it (G-007-02). The 007 feature put only the
in-memory transport's wiring into `saga-forge-spring-boot-starter`.

This feature completes the starter so that a Spring Boot application gets a running saga runtime by adding
dependencies and writing its sagas — no assembly code. It is the prerequisite for the first production project,
which is a Spring Modulith application on Postgres with the in-memory transport.

The starter follows the design already laid out in `docs/architecture.md`: it detects which storage and transport are
on the classpath, assembles the runtime from the application's own classes and beans, and fails fast with a clear
message when the choice is missing or ambiguous rather than guessing. It backs the host SPIs that were designed for it
(`SagaObjectProvider`, `HandlerInstanceProvider`, `HandlerParameterResolver`, `AggregateResolver`) with the
application context and Spring Data.

Running example: the canonical `OrderProcessingSaga` with `Order` (an aggregate stored through a Spring Data
repository), `PaymentService` (a Spring bean handling `ProcessPaymentCommand` and `RefundPaymentCommand`) and a
Postgres database.

## Clarifications

### Session 2026-11-11

- Q: Which Spring Modulith capabilities belong in 008? → A: 007's single outbox and module-structure compatibility,
  plus delivery of saga-produced events to `@ApplicationModuleListener`s with Spring Modulith's transactional
  semantics. Event externalization and `Scenario`-based test support are deferred to a later Spring Modulith feature
  (G-008-01).
- Q: Should 008 expose operator endpoints through Spring Boot Actuator? → A: A read-only endpoint (message counts per
  status, age of the oldest unconsumed message) and a health indicator that reports down while messages are set
  aside. Redelivery through an endpoint, and anything beyond, is deferred to the observability feature (G-008-02).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A Spring Boot application runs its sagas without assembly code (Priority: P1)

A developer adds the starter and the Postgres storage module to a Spring Boot application that already has a
database configured, and writes sagas, aggregates, commands and handlers. When the application starts, the saga
runtime is assembled from those classes, started, and ready to receive events; when the application stops, it stops
cleanly. The developer writes no `SagaRuntime.builder()` code.

**Why this priority**: this is the starter's reason to exist; without it every adopter hand-assembles the runtime.

**Independent Test**: a Spring Boot application containing only the canonical order saga, its aggregate, commands,
events, `PaymentService`, a repository, the starter and datasource properties starts; publishing `OrderCreatedEvent`
through the injected `EventPublisher` drives the saga to `COMPLETED`.

**Acceptance Scenarios**:

1. **Given** an application with the starter, Postgres storage and a configured datasource, **When** it starts,
   **Then** a single saga runtime is running with every `@Saga` of the application registered, and no assembly code
   was written.
2. **Given** the running application, **When** application code publishes a domain event through the
   `EventPublisher` bean or sends a command through the `CommandGateway` bean, **Then** sagas react as they do when
   assembled by hand.
3. **Given** the running application, **When** it shuts down, **Then** the transport stops delivering first and the
   runtime stops afterwards, with no message lost (007 FR-020).
4. **Given** no transport module other than the in-memory one is on the classpath, **When** the application starts,
   **Then** the in-memory transport is used and events between sagas are delivered through its message store.

---

### User Story 2 - Sagas and handlers use Spring the way the rest of the application does (Priority: P1)

Saga classes, command handlers, and aggregate loading and saving use the application's beans and repositories with
ordinary Spring idioms: a saga can receive a collaborator by constructor injection, a handler method can take a
collaborator as a parameter, and an aggregate is loaded and saved through its existing Spring Data repository with no
Saga Forge-specific loader or saver.

**Why this priority**: without it, sagas and handlers are second-class citizens in a Spring application, and every
adopter writes adapters.

**Independent Test**: the canonical example, with `@AggregateLoader`/`@AggregateSaver` removed from the example's
repository and `Order` persisted through a Spring Data repository, completes an order; a saga with a constructor-injected
bean uses it in a step.

**Acceptance Scenarios**:

1. **Given** a `@Saga` class that is a Spring bean with constructor-injected dependencies, **When** a unit of work runs,
   **Then** a fresh saga object is obtained from the application context for that unit of work, with its dependencies
   injected and its `SagaContext` and `CommandGateway` supplied by the runtime.
2. **Given** a `@CommandHandler` method on a Spring bean, **When** its command is sent, **Then** it runs on that bean.
3. **Given** a handler method with a parameter other than the command (and other than `CommandId`), **When** a bean
   of that parameter's type exists, **Then** the bean is passed; **When** none or several exist, **Then** startup fails
   naming the handler and parameter.
4. **Given** an `@Aggregate` type with a Spring Data repository and no `@AggregateLoader`/`@AggregateSaver`, **When**
   an instance-method handler of that aggregate runs, **Then** the aggregate is loaded by its identifier and saved
   through the repository, in the unit of work's transaction.
5. **Given** an aggregate type with neither a client loader nor a repository, **When** the application starts, **Then**
   startup fails naming the aggregate (the existing startup check).

---

### User Story 3 - Misconfiguration fails at startup with a clear message (Priority: P2)

When the starter cannot assemble a working runtime — no storage module, two storage modules, an invalid saga model, a
handler without a resolvable instance — the application refuses to start, and the message says what is wrong and how
to fix it.

**Why this priority**: silent fallbacks and late failures are the costliest mistakes in a saga framework; this turns
them into a failed startup.

**Independent Test**: start the application in each misconfigured setup and assert the startup failure and its
message.

**Acceptance Scenarios**:

1. **Given** no storage module on the classpath, **When** the application starts, **Then** startup fails stating that
   a saga needs durable storage and naming the supported storage modules.
2. **Given** two storage modules on the classpath and no explicit choice, **When** the application starts, **Then**
   startup fails naming both and the property that selects one.
3. **Given** a saga model that violates a validation rule, **When** the application starts, **Then** startup fails
   with the full validation report (every error, as `SagaRuntimeStartupException` reports today).
4. **Given** a service-hosted `@CommandHandler` whose class is not a bean, **When** the application starts, **Then**
   startup fails naming the handler class.

---

### User Story 4 - Every default can be overridden (Priority: P2)

The developer can tune the runtime through configuration properties, and can replace any assembled piece — the whole
runtime, the storage provider, the context store, the transport, the transaction boundary, or a host SPI — by defining
their own bean, which the starter then uses instead.

**Why this priority**: a starter that cannot be overridden is abandoned at the first unusual requirement.

**Independent Test**: for each replaceable piece, define a custom bean and assert the runtime uses it; set each
documented property and assert its effect.

**Acceptance Scenarios**:

1. **Given** a `SagaRuntime` bean defined by the application, **When** it starts, **Then** the starter assembles no
   runtime of its own and still manages the transport's lifecycle around the application's runtime.
2. **Given** an application-defined bean for a storage provider, context store, transport, transaction boundary,
   saga-object provider, handler-instance provider, parameter resolver or aggregate resolver, **When** the application
   starts, **Then** the starter uses it instead of its own.
3. **Given** documented properties (runtime retry backoff cap, storage selection, schema initialization, transport
   settings), **When** they are set, **Then** the runtime behaves accordingly, and an unknown or invalid value fails
   startup with the property's name.
4. **Given** `saga-forge.enabled=false`, **When** the application starts, **Then** no Saga Forge bean is created.

---

### User Story 5 - Spring Modulith applications get first-class support (Priority: P2)

A Spring Modulith application uses Saga Forge without friction: Saga Forge keeps a single outbox with Spring
Modulith's event publication registry (007), its types pass Modulith's module structure verification, and the domain
events sagas and their handlers produce reach other modules' `@ApplicationModuleListener`s with the same guarantees
Modulith gives any application event: after the producing transaction commits, never for one that rolled back, and
recorded in the event publication registry so an interrupted listener is resubmitted.

**Why this priority**: the first production project is a Spring Modulith application.

**Independent Test**: a two-module Spring Modulith application with a saga in one module and a handler in the other
passes Modulith's module verification and runs the saga end to end, with one outbox; an `@ApplicationModuleListener`
in a third module receives the saga's `PaymentProcessedEvent` after commit.

**Acceptance Scenarios**:

1. **Given** a Spring Modulith application with the JDBC event publication registry, **When** it starts, **Then** Saga
   Forge's messages live in the registry with Saga Forge's delivery records beside them (007 FR-028), with no
   configuration.
2. **Given** a saga in one application module and its command handler in another, **When** Modulith's module structure
   verification runs, **Then** Saga Forge's own types introduce no violation.
3. **Given** an `@ApplicationModuleListener` for a domain event that a saga step's handler records, **When** the unit
   of work commits, **Then** the listener runs once, after the commit, in its own transaction, and the publication is
   completed in the registry.
4. **Given** the same unit of work rolls back, **When** the rollback completes, **Then** the listener never runs and no
   publication for it remains.
5. **Given** the listener fails or the process stops before it finishes, **When** the application restarts with
   Modulith's resubmission enabled, **Then** the publication is resubmitted to the listener, as for any Modulith
   event.

---

### User Story 6 - Operators can see the message store through the application's management endpoints (Priority: P3)

An operator checks, through the application's management endpoints, whether Saga Forge's messages are flowing: how
many are waiting, consumed or set aside, and how old the oldest waiting message is. The application's health turns
down while any message is set aside, so existing monitoring notices a stuck message without new tooling.

**Why this priority**: useful in production, but the runtime works without it.

**Independent Test**: query the endpoint with messages in every status.

**Acceptance Scenarios**:

1. **Given** the management endpoints are enabled, **When** an operator reads the Saga Forge endpoint, **Then** it
   reports the message store's counts per status and the age of the oldest unconsumed message.
2. **Given** no message is set aside, **When** the health endpoint is read, **Then** Saga Forge reports up; **Given**
   one or more are set aside, **Then** it reports down with their count.
3. **Given** the endpoint, **When** an operator tries to change anything through it, **Then** nothing changes: the
   endpoint is read-only (redelivery stays programmatic, G-008-02).
4. **Given** the application has no management endpoints on its classpath, **When** it starts, **Then** Saga Forge
   registers no endpoint and no health indicator, and nothing fails.

### Edge Cases

- **Two `SagaRuntime` beans** (for example an application-defined one plus one from a library): startup fails; the
  starter supports one runtime per application.
- **Saga classes outside the application's component scan**: they are not registered; the documented way to register
  them is a property or an explicit registration bean (FR-004).
- **An aggregate type with both a Spring Data repository and a client `@AggregateLoader`**: the client loader wins, as
  the existing precedence rules say.
- **A Spring Data repository whose identifier type differs from the aggregate's `@AggregateIdentifier` type**: startup
  fails naming the aggregate and both types.
- **A saga bean declared as a singleton**: startup fails; saga objects are per unit of work, so a saga bean must be
  prototype-scoped (or not a bean at all, in which case the runtime constructs it).
- **The application starts a Spring transaction and publishes an event inside it**: the event is stored in the
  host's transaction and delivered after it commits (007 behaviour); the runtime joins, it does not suspend
  (G-ENG-12 resolved for Spring by `SpringTransactionBoundary`).
- **Schema not applied**: when schema initialization is off and the tables are missing, startup fails naming the
  missing tables and the schema resource, rather than failing on the first saga.
- **Spring DevTools restart**: payload classes are loaded with the application's class loader after a restart (007
  research §7).
- **007's application-event bridge and FR-018a together**: a listener must receive each event once. The in-transaction
  publication of FR-018a replaces the after-delivery re-publication of 007's `ApplicationEventBridge` whenever it is
  active; the bridge remains only for applications that turn FR-018a's publication off.
- **Context refresh failure after the runtime started**: the transport and runtime are stopped; nothing keeps
  delivering into a half-started context.

## Requirements *(mandatory)*

### Functional Requirements

**Assembly**

- **FR-001**: With the starter on the classpath, the application MUST get exactly one started `SagaRuntime` bean,
  assembled and started without application code, and stopped when the application context closes.
- **FR-002**: The starter MUST register, as the runtime's model, every `@Saga` class and every class declaring a
  `@CommandHandler`, `@AggregateLoader` or `@AggregateSaver` among the application's beans, and every `@Aggregate`
  class found in the application's auto-configuration packages (the packages Spring Boot already scans for entities
  and repositories).
- **FR-003**: The starter MUST expose the runtime's `CommandGateway` and `EventPublisher` as beans, so application
  code injects them.
- **FR-004**: The application MUST be able to add classes to the model that FR-002 does not find, through a property
  listing packages to scan and through a registration bean.
- **FR-005**: The runtime MUST be started after the application context is refreshed and before the transport starts
  delivering, and stopped after the transport stopped, in the application's graceful shutdown.

**Detection**

- **FR-006**: The starter MUST select the storage provider and context store from the storage modules on the
  classpath, per the detection matrix in `docs/architecture.md`; today only Postgres exists.
- **FR-007**: With no storage module, startup MUST fail stating that durable storage is required and listing the
  supported modules; with more than one, it MUST fail naming them unless `saga-forge.storage.type` selects one.
- **FR-008**: The starter MUST select the transport per the detection matrix; with no broker transport on the
  classpath, the in-memory transport (007) is used.
- **FR-009**: Postgres storage MUST use the application's `DataSource` and transaction manager through
  `SpringTransactionBoundary` (007), so saga writes, outbox writes and the application's own writes share one
  transaction.

**Spring integration of the host SPIs**

- **FR-010**: Saga objects MUST come from the application context when the saga class is a bean (prototype scope
  required; a singleton saga bean fails startup), and otherwise from the saga class's constructor as today.
- **FR-011**: Service-hosted handlers, loaders and savers MUST be invoked on their beans; a handler class that is not
  a bean MUST fail startup naming it.
- **FR-012**: A handler parameter other than the command and `CommandId` MUST be resolved to the single bean of its
  type (honouring `@Qualifier`); none or several MUST fail startup naming the handler and parameter.
- **FR-013**: An `@Aggregate` type without a client loader/saver MUST be loaded and saved through the Spring Data
  repository whose domain type is that aggregate, by its `@AggregateIdentifier` value, in the unit of work's
  transaction. Client loaders and savers keep precedence.

**Configuration and overrides**

- **FR-014**: Every bean the starter assembles MUST back off when the application defines its own bean of that type,
  including the whole `SagaRuntime`; with an application-defined runtime, the starter MUST still manage the
  transport's lifecycle around it.
- **FR-015**: The starter MUST bind and validate properties under `saga-forge.*`: `enabled`, `storage.type`,
  `storage.postgres.initialize-schema` (`never` by default, or `always`), `runtime.max-total-retry-backoff`, extra
  scan packages (FR-004) and the existing `saga-forge.transport.*` (007). Invalid values MUST fail startup naming the
  property. Properties MUST be documented with configuration metadata so IDEs complete them.
- **FR-016**: With `storage.postgres.initialize-schema=always`, the starter MUST apply `schema.sql` when the tables do
  not exist yet; with `never`, missing tables MUST fail startup naming them and the schema resource.

**Failure reporting**

- **FR-017**: A model rejected by validation or by the runtime's startup checks MUST fail application startup with the
  complete report, every error listed, through Spring Boot's failure analysis so the console shows a description and
  an action.

**Spring Modulith**

- **FR-018**: Spring Modulith interop from 007 (one outbox through the event publication registry) MUST keep working
  with no configuration.
- **FR-018a**: In a Spring Modulith application, every domain event recorded in a committed unit of work MUST reach
  the application's `@ApplicationModuleListener`s (and other transactional application-event listeners) with
  Spring Modulith's guarantees: published inside the producing unit of work's transaction so the registry records a
  publication per listener in it, delivered after commit, never for a rollback, and resubmittable after a failure or
  restart. This is in addition to, and independent of, delivery to sagas through the in-memory transport.
- **FR-018b**: Spring Modulith's event externalization and `Scenario`-based test support are out of scope (G-008-01).
- **FR-019**: Saga Forge's own beans MUST not cause violations in Spring Modulith's module structure verification of a
  multi-module application.

**Operations**

- **FR-020**: When Spring Boot's management endpoints are present, the starter MUST register a read-only `sagaforge`
  endpoint reporting message counts per status and the age of the oldest unconsumed message, and a health indicator
  that is up when no message is set aside and down, with the set-aside count, otherwise. Both MUST be switchable off
  with Spring Boot's standard endpoint and health properties. Write operations are out of scope (G-008-02).

### Key Entities

- **Saga model**: the set of classes the runtime is assembled from — sagas, handler hosts, loader/saver hosts and
  aggregates — collected from beans, auto-configuration packages and explicit registrations.
- **Starter properties**: `saga-forge.*` configuration with validation and IDE metadata.
- **Detection result**: the selected storage provider, context store and transport, or a startup failure explaining
  why none or several were found.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The canonical order example runs as a Spring Boot application with zero lines of Saga Forge assembly
  code: dependencies, datasource properties and the saga classes only.
- **SC-002**: The same example's assembly code shrinks from the current hand-written builder (about 15 lines plus
  start/stop handling) to none, and its tests need no Saga Forge-specific setup beyond the database.
- **SC-003**: Every misconfiguration listed in User Story 3 and the Edge Cases fails startup, and in 100% of them the
  message names the offending class, bean, property or module and states the fix.
- **SC-004**: Each of the eight replaceable pieces (FR-014) can be replaced by an application bean, proven by one
  test each.
- **SC-005**: An application with the starter starts no more than 1 second slower than the same application without
  sagas, for a model of 20 saga classes.
- **SC-006**: The canonical example and the 007 acceptance suite pass when assembled by the starter, in both the
  plain Spring and the Spring Modulith setup.

## Assumptions

- **Spring Boot line**: Spring Boot 4.1 (Spring Framework 7), as the 007 wiring; Spring Modulith 2.1. Older lines are
  out of scope.
- **Storage and transport available today**: Postgres and the in-memory transport. MySQL, MongoDB and Kafka plug into
  the same detection when their modules exist; this feature defines the detection rules, not those modules.
- **Spring Data**: aggregate repositories are Spring Data repositories (`CrudRepository` or a sub-interface) whose
  domain type is the `@Aggregate` class; JPA and JDBC repositories both qualify. Non-Spring-Data persistence uses
  client loaders/savers as today.
- **One runtime per application**: multiple runtimes in one context are out of scope.
- **Schema management**: the default (`never`) suits applications that manage schema with Flyway or Liquibase; the
  Postgres module's `schema.sql` and upgrade scripts are the scripts they include. `always` suits development and
  tests.
- **Testing support** (a saga test fixture, test slices) is a separate feature; this one only ensures the starter
  works in ordinary `@SpringBootTest` tests.
- **Observability** (tracing, metrics, write operations on the operator endpoint) is a separate feature; 008's
  endpoint and health indicator are its starting point (G-008-02).
- **Publication**: publishing the starter to a Maven repository (versioning, release workflow) is a separate effort
  needed before the first project can depend on a release.
- **Quarkus**: unaffected; nothing in core, runtime, transport or storage may start depending on Spring (Constitution
  Principle I, G-007-01).
