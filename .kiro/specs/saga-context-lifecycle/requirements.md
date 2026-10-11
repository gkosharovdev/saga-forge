# Requirements Document

## Introduction

This specification adds SagaContext and SagaLifecycle support to the Saga Forge framework. The feature introduces two distinct layers of saga state: automatic framework-managed progress state (which events have been handled, which commands emitted, which steps completed) and developer-managed process-level state (SagaContext) that enables developers to augment sagas with data fetched from read models/projections via repositories. The SagaLifecycle static class provides a clean entry point for accessing saga lifecycle capabilities and registering context properties from within step methods, keeping method signatures clean. Developers leverage external DI frameworks (Spring, Jakarta CDI, Micronaut, Guice) to inject repository instances into saga classes; the framework introduces a `@ReadModel` marker annotation to explicitly identify read model classes. The saga progress state is queryable at runtime through the SagaContext, enabling developers to make decisions based on what has already occurred in the saga. All core contracts (SagaContext, SagaLifecycle, `@ReadModel` annotation) live in `saga-forge-core`. Spring-specific DI implementation belongs in the starter module but is out of scope for this specification — only the core contracts are defined here.

## Glossary

- **Saga_Context**: A read-only, developer-accessible object representing the process-level state of a running saga instance. The Saga_Context holds the queryable progress state (events handled, commands emitted, steps completed) and registered properties. Properties are registered via the Saga_Lifecycle static class, not directly on the Saga_Context.
- **Saga_Progress_State**: The framework-managed, automatically-persisted portion of saga state that tracks which Domain_Events have been handled, which Commands have been emitted, which Saga_Steps have been completed, and the Association_Property/correlation identifiers. The Saga_Progress_State is updated by the Saga_Engine on every state transition without developer intervention.
- **Saga_Lifecycle**: A static utility class that serves as the entry point for accessing and mutating saga lifecycle capabilities from within Saga_Step methods. Inspired by Axon Framework's AggregateLifecycle pattern. Provides access to the current Saga_Context via `getContext()` and property registration via `registerProperty()`. Keeps step method signatures clean.
- **Saga_Repository**: A developer-defined interface or class that provides access to read models or projections. Saga_Repository instances are injected into saga classes by external DI frameworks (Spring, Jakarta CDI, Micronaut, Guice), enabling step handlers to fetch additional data needed for command creation.
- **Read_Model**: A class annotated with `@ReadModel` that represents a queryable data projection. Read models are the data sources that saga step handlers query (via injected repositories) to obtain information needed for enriching commands.
- **Handled_Event_Record**: An entry in the Saga_Progress_State representing a Domain_Event that has been successfully processed by a Saga_Step, including the event type, Association_Property value, and timestamp.
- **Emitted_Command_Record**: An entry in the Saga_Progress_State representing a Command that has been emitted by a Saga_Step, including the command type, target step name, and timestamp.
- **Completed_Step_Record**: An entry in the Saga_Progress_State representing a Saga_Step that has been fully executed, including the step method name, triggering event type, and timestamp.
- **Saga_Engine**: The core runtime component that discovers, instantiates, correlates, and executes saga definitions. The Saga_Engine is responsible for updating the Saga_Progress_State automatically.
- **Saga_Instance**: A single running occurrence of a saga, identified by a unique saga identity and holding process-level state.
- **Domain_Event**: An immutable object representing something that happened in the domain, marked with the `@DomainEvent` annotation.
- **Command**: An object representing an intent to trigger an aggregate state transition, marked with the `@Command` annotation.
- **Association_Property**: A correlation key declared on `@SagaStep(associationProperty = "...")` that links incoming Domain_Events to the correct Saga_Instance.

## Requirements

### Requirement 1: SagaContext Class

**User Story:** As a developer, I want to access a read-only SagaContext object that holds both the queryable progress state and registered context properties for a running saga instance, so that I can make informed decisions in step handlers based on what has already occurred and use enriched data from external sources.

#### Acceptance Criteria

1. THE Saga_Context class SHALL reside in the `io.github.gkosharovdev.sagaforge.core.context` package within the `saga-forge-core` module.
2. THE Saga_Context SHALL expose the saga instance identifier as a read-only `String` property via a `getSagaId()` method.
3. THE Saga_Context SHALL expose the saga type (fully qualified class name of the `@Saga` class) as a read-only `String` property via a `getSagaType()` method.
4. THE Saga_Context SHALL expose the current `SagaStatus` via a `getStatus()` method.
5. WHEN a developer calls `getHandledEvents()` on the Saga_Context, THE Saga_Context SHALL return an unmodifiable list of Handled_Event_Records representing all Domain_Events processed by the saga instance.
6. WHEN a developer calls `getEmittedCommands()` on the Saga_Context, THE Saga_Context SHALL return an unmodifiable list of Emitted_Command_Records representing all Commands emitted by the saga instance.
7. WHEN a developer calls `getCompletedSteps()` on the Saga_Context, THE Saga_Context SHALL return an unmodifiable list of Completed_Step_Records representing all Saga_Steps that have been fully executed.
8. THE Saga_Context SHALL provide a `getProperty(String key, Class<T> type)` method for developers to retrieve registered context properties with type-safe casting.
9. THE Saga_Context SHALL NOT expose any setter or mutation methods for properties directly on the Saga_Context instance.
10. IF a developer calls `getProperty` with a key that has not been registered, THEN THE Saga_Context SHALL return `null`.
11. IF a developer calls `getProperty` with a type that does not match the stored value type, THEN THE Saga_Context SHALL throw a `ClassCastException`.

### Requirement 2: SagaLifecycle Static Entry Point

**User Story:** As a developer, I want to access the current saga's context and register context properties via static method calls on SagaLifecycle, so that my step method signatures remain clean and I can enrich the saga context with data fetched from repositories.

#### Acceptance Criteria

1. THE Saga_Lifecycle class SHALL reside in the `io.github.gkosharovdev.sagaforge.core.context` package within the `saga-forge-core` module.
2. THE Saga_Lifecycle class SHALL provide a static `getContext()` method that returns the Saga_Context for the currently executing saga instance.
3. WHEN `SagaLifecycle.getContext()` is called from within a Saga_Step method during saga execution, THE Saga_Lifecycle SHALL return the Saga_Context associated with the currently executing Saga_Instance.
4. IF `SagaLifecycle.getContext()` is called outside of a saga step execution context, THEN THE Saga_Lifecycle SHALL throw an `IllegalStateException` with a descriptive message.
5. THE Saga_Lifecycle SHALL provide a static `registerProperty(String key, Object value)` method that registers a named property on the current Saga_Context.
6. WHEN `SagaLifecycle.registerProperty(key, value)` is called, THE property SHALL be accessible via `SagaContext.getProperty(key, type)` for the remainder of the saga instance lifecycle.
7. IF `SagaLifecycle.registerProperty()` is called outside of a saga step execution context, THEN THE Saga_Lifecycle SHALL throw an `IllegalStateException` with a descriptive message.
8. THE Saga_Lifecycle SHALL use a thread-local mechanism to associate the current execution thread with the active Saga_Context.
9. WHEN a Saga_Step method execution completes, THE Saga_Engine SHALL clear the thread-local Saga_Context from the Saga_Lifecycle.
10. THE Saga_Lifecycle class SHALL have a private constructor to prevent instantiation.

### Requirement 3: Automatic Progress State Tracking

**User Story:** As a developer, I want the framework to automatically track which events have been handled, which commands have been emitted, and which steps have been completed for each saga instance, so that I can query this progress state at runtime without manual bookkeeping.

#### Acceptance Criteria

1. WHEN a Saga_Step method successfully processes a Domain_Event, THE Saga_Engine SHALL automatically add a Handled_Event_Record to the Saga_Progress_State of the corresponding Saga_Instance.
2. THE Handled_Event_Record SHALL contain the event class name, the Association_Property value from the event, and the timestamp of processing.
3. WHEN a Saga_Step method emits a Command, THE Saga_Engine SHALL automatically add an Emitted_Command_Record to the Saga_Progress_State of the corresponding Saga_Instance.
4. THE Emitted_Command_Record SHALL contain the command class name, the name of the Saga_Step method that emitted the command, and the timestamp of emission.
5. WHEN a Saga_Step method execution completes successfully, THE Saga_Engine SHALL automatically add a Completed_Step_Record to the Saga_Progress_State of the corresponding Saga_Instance.
6. THE Completed_Step_Record SHALL contain the step method name, the triggering event class name, and the timestamp of completion.
7. THE Saga_Engine SHALL persist the updated Saga_Progress_State via the configured Storage_Provider after each state transition.
8. IF the Storage_Provider fails to persist the updated Saga_Progress_State, THEN THE Saga_Engine SHALL report the persistence failure and not mark the step as completed.

### Requirement 4: ReadModel Marker Annotation

**User Story:** As a developer, I want to mark classes that represent read models/projections with a `@ReadModel` annotation, so that the framework is explicitly aware which classes serve as queryable data sources used by saga step handlers to enrich command creation.

#### Acceptance Criteria

1. THE `saga-forge-core` module SHALL define a `@ReadModel` annotation in the `io.github.gkosharovdev.sagaforge.core.annotation` package.
2. THE `@ReadModel` annotation SHALL have `@Target(ElementType.TYPE)` and `@Retention(RetentionPolicy.RUNTIME)`.
3. THE `@ReadModel` annotation SHALL be applicable only to class-level declarations.
4. THE `@ReadModel` annotation SHALL serve as a marker annotation with no attributes.
5. WHEN a class is annotated with `@ReadModel`, THE Annotation_Scanner SHALL recognize the class as a valid read model type eligible for use in saga step handlers.
6. THE framework SHALL NOT define a custom dependency injection mechanism for injecting read model repositories into saga classes.
7. THE framework SHALL rely on external DI frameworks (Spring, Jakarta CDI, Micronaut, Guice) to inject repository instances that provide access to `@ReadModel`-annotated types into saga class fields.

### Requirement 5: Progress State Query Methods

**User Story:** As a developer, I want to query the saga progress state at runtime to determine whether specific events have been handled or specific steps have been completed, so that I can implement conditional logic in step handlers based on saga history.

#### Acceptance Criteria

1. THE Saga_Context SHALL provide a `hasHandledEvent(Class<?> eventType)` method that returns `true` if the saga instance has processed at least one Domain_Event of the specified type.
2. THE Saga_Context SHALL provide a `hasCompletedStep(String stepMethodName)` method that returns `true` if the saga instance has completed the Saga_Step with the specified method name.
3. THE Saga_Context SHALL provide a `hasEmittedCommand(Class<?> commandType)` method that returns `true` if the saga instance has emitted at least one Command of the specified type.
4. THE Saga_Context SHALL provide a `getHandledEventCount()` method that returns the total number of Domain_Events processed by the saga instance.
5. THE Saga_Context SHALL provide a `getEmittedCommandCount()` method that returns the total number of Commands emitted by the saga instance.
6. THE Saga_Context SHALL provide a `getCompletedStepCount()` method that returns the total number of Saga_Steps completed by the saga instance.
7. IF a developer passes `null` to any query method accepting a class or string parameter, THEN THE Saga_Context SHALL throw an `IllegalArgumentException`.

### Requirement 6: Progress State Record Types

**User Story:** As a developer, I want the progress state records (handled events, emitted commands, completed steps) to be well-defined immutable types, so that I can reliably inspect saga history with type safety.

#### Acceptance Criteria

1. THE Handled_Event_Record SHALL be defined as a Java record in the `io.github.gkosharovdev.sagaforge.core.context` package.
2. THE Handled_Event_Record SHALL contain fields: `String eventClassName`, `String associationPropertyValue`, and `Instant timestamp`.
3. THE Emitted_Command_Record SHALL be defined as a Java record in the `io.github.gkosharovdev.sagaforge.core.context` package.
4. THE Emitted_Command_Record SHALL contain fields: `String commandClassName`, `String emittingStepName`, and `Instant timestamp`.
5. THE Completed_Step_Record SHALL be defined as a Java record in the `io.github.gkosharovdev.sagaforge.core.context` package.
6. THE Completed_Step_Record SHALL contain fields: `String stepMethodName`, `String triggeringEventClassName`, and `Instant timestamp`.
7. THE record types SHALL enforce non-null field values via compact constructor validation.
8. THE record types SHALL reside in the `saga-forge-core` module with zero external dependencies.

### Requirement 7: SagaContext Persistence Integration

**User Story:** As a developer, I want the SagaContext (including both progress state and registered properties) to be persisted alongside the saga instance state, so that context is preserved across saga instance rehydration after failures or restarts.

#### Acceptance Criteria

1. WHEN the Saga_Engine persists a Saga_Instance via the Storage_Provider, THE Saga_Engine SHALL include the Saga_Progress_State (handled events, emitted commands, completed steps) in the persisted state.
2. WHEN the Saga_Engine persists a Saga_Instance via the Storage_Provider, THE Saga_Engine SHALL include the registered properties from the Saga_Context in the persisted state.
3. WHEN the Saga_Engine rehydrates a Saga_Instance from the Storage_Provider, THE Saga_Engine SHALL reconstruct the Saga_Context with the previously persisted Saga_Progress_State.
4. WHEN the Saga_Engine rehydrates a Saga_Instance from the Storage_Provider, THE Saga_Engine SHALL reconstruct the Saga_Context with the previously persisted registered properties.
5. THE registered properties stored in the Saga_Context SHALL be serializable to a `Map<String, Object>` representation for persistence compatibility with existing Storage_Provider implementations.
6. IF a property value passed to `SagaLifecycle.registerProperty()` is not serializable, THEN THE Saga_Lifecycle SHALL throw an `IllegalArgumentException`.

### Requirement 8: Thread Safety of SagaLifecycle

**User Story:** As a developer, I want the SagaLifecycle thread-local mechanism to be safe for concurrent saga execution, so that multiple saga instances executing on different threads do not interfere with each other's context.

#### Acceptance Criteria

1. WHILE multiple Saga_Instances are executing concurrently on separate threads, THE Saga_Lifecycle SHALL maintain independent Saga_Context references per thread.
2. THE Saga_Lifecycle thread-local storage SHALL NOT leak Saga_Context references after step execution completes.
3. WHEN a Saga_Step method execution completes (normally or via exception), THE Saga_Engine SHALL clear the thread-local Saga_Context in a finally block.
4. IF a nested saga step invocation occurs on the same thread, THEN THE Saga_Lifecycle SHALL preserve and restore the outer Saga_Context after the inner invocation completes.

### Requirement 9: Example Enhancement with Repository Usage

**User Story:** As a developer evaluating Saga Forge, I want to see the OrderProcessingSaga example enhanced to demonstrate fetching data from a read model repository and registering it on the saga context via SagaLifecycle, so that I understand how to use SagaContext, SagaLifecycle, and read model access in practice.

#### Acceptance Criteria

1. THE `OrderProcessingSaga` example SHALL declare at least one repository field that provides access to a `@ReadModel`-annotated type, injected via standard DI (e.g., constructor or field injection by Spring).
2. THE example SHALL demonstrate a Saga_Step method that calls `SagaLifecycle.getContext()` to access the Saga_Context.
3. THE example SHALL demonstrate a Saga_Step method that uses an injected repository to fetch data from a `@ReadModel`-annotated read model.
4. THE example SHALL demonstrate using `SagaLifecycle.registerProperty()` to register fetched repository data on the saga context.
5. THE example SHALL demonstrate using `SagaContext.getProperty()` to retrieve a previously registered property for enriching a Command object.
6. THE example SHALL demonstrate calling at least one progress state query method (e.g., `hasHandledEvent` or `hasCompletedStep`) to show conditional logic based on saga history.
7. THE example SHALL include a sample repository interface (e.g., `OrderRepository`) and a `@ReadModel`-annotated class (e.g., `OrderDetails`) that the repository provides access to.
8. THE example SHALL compile and pass annotation validation by the Annotation_Scanner.
