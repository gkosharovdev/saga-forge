# Requirements Document

## Introduction

Saga Forge is a declarative Java 24 framework for orchestrating long-running, multi-step business processes (sagas) that span multiple DDD aggregates, inspired by Axon Framework's saga capabilities. It uses domain events and an annotation-driven programming model as first-class building blocks. The core coordination pattern is: a running saga receives a `@DomainEvent`-annotated event, the saga step handler processes it, and the step emits a `@Command`-annotated command to trigger an aggregate state transition. The framework is distributed as a multi-module Maven project colocated in a single git repository, where each module is independently deployable as a separate Maven package. Spring Boot integration is first-class, with storage adapters instantiated as Spring beans via auto-configuration, while modularity remains the primary trait — persistence is provided as separate packages. This specification covers the annotation model definitions (including `@DomainEvent` and `@Command` marker annotations), multi-module project structure, module dependency boundaries, stateful saga instance persistence, pub/sub event delivery, the event-to-command flow pattern, and an example saga demonstrating the programming model. Internal engine implementation, storage provider implementations, and Spring Boot auto-configuration internals are out of scope and will be addressed in separate specifications.

## Glossary

- **Saga**: A long-running business process that coordinates multiple steps across DDD aggregates, declared via the `@Saga` annotation on a Java class.
- **Saga_Engine**: The core runtime component that discovers, instantiates, correlates, and executes saga definitions. (Implementation is out of scope for this spec.)
- **Saga_Instance**: A single running occurrence of a saga, identified by a unique saga identity and holding process-level state. Each Saga_Instance is stateful and requires durable persistence on every state transition.
- **Saga_Step**: An individual event-handling method within a saga, annotated with `@SagaStep`, that reacts to a specific `@DomainEvent`-annotated domain event class.
- **Association_Property**: A correlation key declared on `@SagaStep(associationProperty = "...")` that links incoming domain events to the correct Saga_Instance.
- **Compensation_Handler**: A method annotated with `@SagaCompensation(forStep = "...")` that reverses the effect of a specific Saga_Step when failure occurs.
- **Domain_Event**: An immutable object representing something that happened in the domain, marked with the `@DomainEvent` annotation, consumed by Saga_Steps to advance a Saga_Instance.
- **Command**: An object representing an intent to trigger an aggregate state transition, marked with the `@Command` annotation, emitted by Saga_Steps after processing a Domain_Event.
- **Annotation_Scanner**: The component within the Saga_Engine that discovers classes annotated with `@Saga` and introspects their annotated methods.
- **Storage_Provider**: A pluggable persistence adapter responsible for durably saving and loading Saga_Instance state on every state transition. (Implementation is out of scope for this spec.)
- **Spring_Boot_Starter**: The auto-configuration module that integrates Saga Forge into Spring Boot applications, instantiating Storage_Provider adapters as Spring beans. (Implementation is out of scope for this spec.)
- **Event_Dispatcher**: The transport-agnostic pub/sub SPI responsible for routing incoming Domain_Events to the correct Saga_Instance and Saga_Step based on Association_Property correlation. The Event_Dispatcher contract makes no assumptions about locality — it supports both local (in-process) and distributed (cross-instance via message broker) event delivery topologies. (Implementation is out of scope for this spec.)
- **Event_Transport**: The underlying delivery mechanism used by an Event_Dispatcher implementation to move Domain_Events from the publisher to the target Saga_Instance. An Event_Transport may be in-memory (single-instance deployments) or broker-backed such as Kafka or RabbitMQ (distributed deployments). The Event_Transport is a pluggable concern, analogous to how Storage_Provider is pluggable for persistence.

## Requirements

### Requirement 1: Saga Declaration Annotation

**User Story:** As a developer, I want to declare sagas using an `@Saga` annotation on plain Java classes, so that I can define long-running business processes declaratively without framework-specific base classes.

#### Acceptance Criteria

1. THE `@Saga` annotation SHALL have `@Target(ElementType.TYPE)` and `@Retention(RetentionPolicy.RUNTIME)`.
2. THE `@Saga` annotation SHALL be applicable only to class-level declarations.
3. WHEN a Java class is annotated with `@Saga`, THE Annotation_Scanner SHALL recognize the class as a saga definition.
4. IF a `@Saga` annotation is placed on an interface or abstract class, THEN THE Annotation_Scanner SHALL report a configuration error at startup.

### Requirement 2: Saga Identity Annotation

**User Story:** As a developer, I want to mark a field as the saga identity using `@SagaId`, so that the framework can uniquely identify each saga instance.

#### Acceptance Criteria

1. THE `@SagaId` annotation SHALL have `@Target(ElementType.FIELD)` and `@Retention(RetentionPolicy.RUNTIME)`.
2. THE `@SagaId` annotation SHALL be applicable only to field-level declarations within a `@Saga`-annotated class.
3. IF a `@Saga`-annotated class does not contain exactly one `@SagaId`-annotated field, THEN THE Annotation_Scanner SHALL report a configuration error at startup.
4. THE `@SagaId`-annotated field SHALL be of type `String`.

### Requirement 3: Saga Lifecycle Annotations

**User Story:** As a developer, I want to mark methods that start and end a saga using `@StartSaga` and `@EndSaga`, so that the framework knows the lifecycle boundaries of each saga instance.

#### Acceptance Criteria

1. THE `@StartSaga` annotation SHALL have `@Target(ElementType.METHOD)` and `@Retention(RetentionPolicy.RUNTIME)`.
2. THE `@EndSaga` annotation SHALL have `@Target(ElementType.METHOD)` and `@Retention(RetentionPolicy.RUNTIME)`.
3. IF a `@Saga`-annotated class does not contain exactly one method annotated with `@StartSaga`, THEN THE Annotation_Scanner SHALL report a configuration error at startup.
4. IF a `@Saga`-annotated class does not contain at least one method annotated with `@EndSaga`, THEN THE Annotation_Scanner SHALL report a configuration error at startup.
5. WHEN a method is annotated with `@StartSaga`, THE method SHALL also be annotated with `@SagaStep`.
6. WHEN a method is annotated with `@EndSaga`, THE method SHALL also be annotated with `@SagaStep`.
7. IF a method annotated with `@StartSaga` or `@EndSaga` is not also annotated with `@SagaStep`, THEN THE Annotation_Scanner SHALL report a configuration error at startup.

### Requirement 4: Saga Step Annotation

**User Story:** As a developer, I want to annotate event-handling methods with `@SagaStep` and specify an association property, so that incoming domain events are correlated to the correct saga instance.

#### Acceptance Criteria

1. THE `@SagaStep` annotation SHALL have `@Target(ElementType.METHOD)` and `@Retention(RetentionPolicy.RUNTIME)`.
2. THE `@SagaStep` annotation SHALL require exactly one `associationProperty` attribute of type `String`.
3. THE `@SagaStep` annotation SHALL be applicable only to method-level declarations within a `@Saga`-annotated class.
4. WHEN a `@SagaStep` method is declared, THE method SHALL accept exactly one parameter whose type is a class annotated with `@DomainEvent`.
5. IF a `@SagaStep` method declares zero parameters or more than one parameter, THEN THE Annotation_Scanner SHALL report a configuration error at startup.
6. IF a `@SagaStep` method parameter type is not annotated with `@DomainEvent`, THEN THE Annotation_Scanner SHALL report a configuration error at startup.

### Requirement 5: Saga Compensation Annotation

**User Story:** As a developer, I want to define compensation logic for individual saga steps using `@SagaCompensation`, so that the framework can reverse completed steps when a failure occurs.

#### Acceptance Criteria

1. THE `@SagaCompensation` annotation SHALL have `@Target(ElementType.METHOD)` and `@Retention(RetentionPolicy.RUNTIME)`.
2. THE `@SagaCompensation` annotation SHALL require exactly one `forStep` attribute of type `String` that references the method name of the target Saga_Step.
3. THE `@SagaCompensation` annotation SHALL be applicable only to method-level declarations within a `@Saga`-annotated class.
4. IF the `forStep` attribute of `@SagaCompensation` references a method name that does not exist in the same `@Saga`-annotated class, THEN THE Annotation_Scanner SHALL report a configuration error at startup.
5. IF the `forStep` attribute of `@SagaCompensation` references a method that is not annotated with `@SagaStep`, THEN THE Annotation_Scanner SHALL report a configuration error at startup.

### Requirement 6: Multi-Module Maven Project Structure

**User Story:** As a library consumer, I want each framework module to be independently deployable as a separate Maven package colocated in a single git repository, so that I can include only the modules I need while benefiting from first-class Spring Boot integration.

#### Acceptance Criteria

1. THE project SHALL be structured as a multi-module Maven project with a parent POM using groupId `io.github.gkosharovdev`, with all modules colocated in the same git repository.
2. THE parent POM SHALL define the following modules: `saga-forge-core`, `saga-forge-storage-postgres`, `saga-forge-storage-mongodb`, `saga-forge-storage-mysql`, and `saga-forge-spring-boot-starter`.
3. THE project SHALL target Java 24 as the minimum language level, configured in the parent POM.
4. THE `saga-forge-core` module SHALL contain all annotation definitions (including `@Saga`, `@SagaId`, `@StartSaga`, `@EndSaga`, `@SagaStep`, `@SagaCompensation`, `@DomainEvent`, and `@Command`) and the Annotation_Scanner interface.
5. THE `saga-forge-core` module SHALL have zero dependencies on any storage module, Spring framework, or other saga-forge modules.
6. Each storage module (`saga-forge-storage-postgres`, `saga-forge-storage-mongodb`, `saga-forge-storage-mysql`) SHALL depend only on `saga-forge-core` and the respective database driver.
7. THE `saga-forge-spring-boot-starter` module SHALL depend on `saga-forge-core` and Spring Boot auto-configuration libraries.
8. THE `saga-forge-spring-boot-starter` module SHALL provide auto-configuration that instantiates Storage_Provider adapters as Spring beans.
9. THE event transport mechanism SHALL be pluggable, following the same module boundary pattern as storage: the `saga-forge-core` module defines the Event_Dispatcher SPI, and concrete Event_Transport implementations (in-memory, Kafka, RabbitMQ) are provided as separate modules.

### Requirement 7: Domain Event Marker Annotation

**User Story:** As a developer, I want to mark event classes with a `@DomainEvent` annotation, so that the framework can identify which classes represent domain events eligible for saga step handling.

#### Acceptance Criteria

1. THE `@DomainEvent` annotation SHALL have `@Target(ElementType.TYPE)` and `@Retention(RetentionPolicy.RUNTIME)`.
2. THE `@DomainEvent` annotation SHALL be applicable only to class-level declarations.
3. THE `@DomainEvent` annotation SHALL serve as a marker annotation with no attributes.
4. WHEN a class is annotated with `@DomainEvent`, THE Annotation_Scanner SHALL recognize the class as a valid domain event type for Saga_Step method parameters.

### Requirement 8: Command Marker Annotation

**User Story:** As a developer, I want to mark command classes with a `@Command` annotation, so that the framework can identify which classes represent commands intended to trigger aggregate state transitions.

#### Acceptance Criteria

1. THE `@Command` annotation SHALL have `@Target(ElementType.TYPE)` and `@Retention(RetentionPolicy.RUNTIME)`.
2. THE `@Command` annotation SHALL be applicable only to class-level declarations.
3. THE `@Command` annotation SHALL serve as a marker annotation with no attributes.
4. WHEN a Saga_Step method emits an object, THE object SHALL be an instance of a class annotated with `@Command`.

### Requirement 9: Stateful Saga Instance Persistence

**User Story:** As a developer, I want running saga instances to durably persist their process-level state on every state transition, so that saga progress is never lost and can be recovered after failures.

#### Acceptance Criteria

1. WHEN a Saga_Instance is created via a `@StartSaga` step, THE Saga_Engine SHALL persist the initial state of the Saga_Instance using the configured Storage_Provider.
2. WHEN a Saga_Instance transitions state as a result of a Saga_Step execution, THE Saga_Engine SHALL persist the updated state of the Saga_Instance using the configured Storage_Provider before the step is considered complete.
3. WHEN a Saga_Instance is ended via an `@EndSaga` step, THE Saga_Engine SHALL persist the final state of the Saga_Instance using the configured Storage_Provider.
4. IF the Storage_Provider fails to persist a state transition, THEN THE Saga_Engine SHALL report the persistence failure and not advance the Saga_Instance to the next state.

### Requirement 10: Pub/Sub Event Delivery to Saga Instances

**User Story:** As a developer, I want domain events to be delivered to running saga instances via a pub/sub mechanism, so that sagas advance through their steps whenever a relevant domain event is received.

#### Acceptance Criteria

1. WHEN a Domain_Event is published, THE Event_Dispatcher SHALL route the Domain_Event to all Saga_Instances whose Association_Property value matches the corresponding field value in the Domain_Event.
2. THE Event_Dispatcher SHALL use the `associationProperty` declared on each `@SagaStep` to correlate incoming Domain_Events to the correct Saga_Instance.
3. IF a published Domain_Event does not match any active Saga_Instance by Association_Property correlation, and the Domain_Event type is handled by a `@StartSaga` step, THEN THE Saga_Engine SHALL create a new Saga_Instance.
4. IF a published Domain_Event does not match any active Saga_Instance and is not handled by a `@StartSaga` step, THEN THE Event_Dispatcher SHALL discard the Domain_Event without error.
5. THE Event_Dispatcher SPI SHALL NOT assume that the event publisher and the target Saga_Instance reside on the same application instance or JVM process.
6. THE Event_Dispatcher SPI SHALL be transport-agnostic, supporting both local (in-process) and distributed (cross-instance via message broker) event delivery through pluggable Event_Transport implementations.
7. THE Event_Dispatcher contract SHALL decouple event publication from Saga_Instance location, so that the publisher is unaware of whether the target Saga_Instance is co-located or remote.

### Requirement 11: Event-to-Command Flow Pattern

**User Story:** As a developer, I want the framework to enforce an event-to-command coordination pattern, so that sagas receive domain events, process them in step handlers, and emit commands that trigger aggregate state transitions.

#### Acceptance Criteria

1. WHEN a Saga_Step method receives a Domain_Event, THE Saga_Step method SHALL be capable of emitting one or more `@Command`-annotated objects as its output.
2. THE Saga_Engine SHALL deliver emitted Command objects to the appropriate aggregate command handlers. (Delivery mechanism is out of scope for this spec.)
3. THE coordination flow SHALL follow the pattern: Domain_Event received → Saga_Step handler processes event → Saga_Step emits Command → Command triggers aggregate state transition.
4. IF a Saga_Step method emits an object that is not annotated with `@Command`, THEN THE Saga_Engine SHALL report a validation error.

### Requirement 12: Example Saga Demonstrating the Annotation Model

**User Story:** As a developer evaluating Saga Forge, I want to see a complete example saga class using all annotations including `@DomainEvent` and `@Command`, so that I understand the intended programming model and the event-to-command flow pattern.

#### Acceptance Criteria

1. THE project SHALL include an `OrderProcessingSaga` example class annotated with `@Saga`.
2. THE `OrderProcessingSaga` SHALL contain a `@SagaId`-annotated field of type `String`.
3. THE `OrderProcessingSaga` SHALL contain a method annotated with both `@StartSaga` and `@SagaStep(associationProperty = "orderId")` that handles an `OrderCreatedEvent` class annotated with `@DomainEvent`.
4. THE `OrderProcessingSaga` SHALL contain at least one intermediate `@SagaStep` method with `associationProperty = "orderId"` that handles a `@DomainEvent`-annotated event class.
5. THE `OrderProcessingSaga` SHALL contain a method annotated with both `@EndSaga` and `@SagaStep(associationProperty = "orderId")`.
6. THE `OrderProcessingSaga` SHALL contain at least one `@SagaCompensation` method with a `forStep` attribute referencing an existing Saga_Step method name.
7. THE example event classes (`OrderCreatedEvent`, `PaymentProcessedEvent`, `OrderCompletedEvent`) SHALL each be annotated with `@DomainEvent`.
8. THE example SHALL demonstrate at least one Saga_Step method emitting a `@Command`-annotated command object (e.g., `ProcessPaymentCommand`).
9. THE `OrderProcessingSaga` example SHALL compile and pass annotation validation by the Annotation_Scanner.
