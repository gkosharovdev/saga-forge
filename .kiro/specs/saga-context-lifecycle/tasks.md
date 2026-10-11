# Implementation Plan: Saga Context & Lifecycle

## Overview

This plan implements the SagaContext, SagaLifecycle, progress state records, and @ReadModel annotation in `saga-forge-core`, followed by an enhanced OrderProcessingSaga example in `saga-forge-example`. Tasks are ordered to build foundational types first (records, annotation), then the core classes (SagaContext, SagaLifecycle), then the example, with tests woven in close to each implementation step.

## Tasks

- [ ] 1. Create progress state record types
  - [ ] 1.1 Create `HandledEventRecord` Java record
    - Create `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/context/HandledEventRecord.java`
    - Fields: `String eventClassName`, `String associationPropertyValue`, `Instant timestamp`
    - Compact constructor with `Objects.requireNonNull` for all fields
    - _Requirements: 6.1, 6.2, 6.7, 6.8_
  - [ ] 1.2 Create `EmittedCommandRecord` Java record
    - Create `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/context/EmittedCommandRecord.java`
    - Fields: `String commandClassName`, `String emittingStepName`, `Instant timestamp`
    - Compact constructor with `Objects.requireNonNull` for all fields
    - _Requirements: 6.3, 6.4, 6.7, 6.8_
  - [ ] 1.3 Create `CompletedStepRecord` Java record
    - Create `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/context/CompletedStepRecord.java`
    - Fields: `String stepMethodName`, `String triggeringEventClassName`, `Instant timestamp`
    - Compact constructor with `Objects.requireNonNull` for all fields
    - _Requirements: 6.5, 6.6, 6.7, 6.8_
  - [ ]* 1.4 Write unit tests for progress state records
    - Create `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/context/HandledEventRecordTest.java`
    - Create `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/context/EmittedCommandRecordTest.java`
    - Create `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/context/CompletedStepRecordTest.java`
    - Test field access, null rejection for each field, equality
    - _Requirements: 6.7_
  - [ ]* 1.5 Write property test for progress state records
    - **Property 10: Record construction preserves fields and rejects nulls**
    - **Validates: Requirements 3.2, 3.4, 3.6, 6.7**
    - Create `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/context/ProgressStateRecordPropertyTest.java`
    - Use jqwik generators for random valid field values; verify accessor round-trip
    - Use jqwik generators with null fields; verify NullPointerException

- [ ] 2. Create `@ReadModel` marker annotation
  - [ ] 2.1 Create `ReadModel.java` annotation
    - Create `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/annotation/ReadModel.java`
    - `@Target(ElementType.TYPE)`, `@Retention(RetentionPolicy.RUNTIME)`, `@Documented`
    - No attributes — pure marker annotation
    - _Requirements: 4.1, 4.2, 4.3, 4.4_
  - [ ]* 2.2 Write unit test for `@ReadModel` annotation metadata
    - Create `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/annotation/ReadModelAnnotationTest.java`
    - Verify `@Target`, `@Retention`, no declared methods (marker)
    - _Requirements: 4.2, 4.3, 4.4_

- [ ] 3. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 4. Implement `SagaContext` class
  - [ ] 4.1 Create `SagaContext.java`
    - Create `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/context/SagaContext.java`
    - Package-private constructor accepting `sagaId`, `sagaType`, `SagaStatus`
    - Read-only public API: `getSagaId()`, `getSagaType()`, `getStatus()`, `getHandledEvents()`, `getEmittedCommands()`, `getCompletedSteps()`, `getProperty(key, type)`
    - Progress state query methods: `hasHandledEvent(Class<?>)`, `hasCompletedStep(String)`, `hasEmittedCommand(Class<?>)`, `getHandledEventCount()`, `getEmittedCommandCount()`, `getCompletedStepCount()`
    - Package-private mutators: `addHandledEvent()`, `addEmittedCommand()`, `addCompletedStep()`, `putProperty()`, `setStatus()`, `getProperties()`
    - Null argument validation with `Objects.requireNonNull` and `IllegalArgumentException` for query methods
    - Collections returned as unmodifiable lists
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10, 1.11, 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7_
  - [ ]* 4.2 Write unit tests for `SagaContext`
    - Create `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/context/SagaContextTest.java`
    - Test construction with valid args, construction with null args
    - Test `getProperty` with null key, null type, missing key, type mismatch
    - Test query methods with null arguments throw `IllegalArgumentException`
    - Test unmodifiable list behavior
    - _Requirements: 1.2, 1.3, 1.4, 1.9, 1.10, 1.11, 5.7_
  - [ ]* 4.3 Write property tests for `SagaContext`
    - Create `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/context/SagaContextPropertyTest.java`
    - **Property 1: Construction preserves identity**
    - **Validates: Requirements 1.2, 1.3, 1.4**
    - **Property 2: Collection getters return unmodifiable lists**
    - **Validates: Requirements 1.5, 1.6, 1.7**
    - **Property 4: Unregistered property returns null**
    - **Validates: Requirements 1.10**
    - **Property 5: Type mismatch throws ClassCastException**
    - **Validates: Requirements 1.11**
    - **Property 11: Query methods reflect progress state contents**
    - **Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 5.6**

- [ ] 5. Implement `SagaLifecycle` static utility
  - [ ] 5.1 Create `SagaLifecycle.java`
    - Create `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/context/SagaLifecycle.java`
    - `ThreadLocal<Deque<SagaContext>>` with `ThreadLocal.withInitial(ArrayDeque::new)`
    - Private constructor throwing `UnsupportedOperationException`
    - Public static `getContext()` — peeks stack, throws `IllegalStateException` if empty
    - Public static `registerProperty(key, value)` — validates non-null, checks `Serializable`, peeks stack, calls `ctx.putProperty()`
    - Package-private static `setContext(SagaContext)` — pushes onto stack
    - Package-private static `clearContext()` — pops from stack, removes ThreadLocal when empty
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10, 7.6, 8.1, 8.2, 8.3, 8.4_
  - [ ]* 5.2 Write unit tests for `SagaLifecycle`
    - Create `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/context/SagaLifecycleTest.java`
    - Test `getContext()` outside execution throws `IllegalStateException`
    - Test `registerProperty()` outside execution throws `IllegalStateException`
    - Test private constructor via reflection throws `UnsupportedOperationException`
    - Test `registerProperty()` with null key/value throws `NullPointerException`
    - Test `registerProperty()` with non-Serializable value throws `IllegalArgumentException`
    - _Requirements: 2.4, 2.7, 2.10, 7.6_
  - [ ]* 5.3 Write property tests for `SagaLifecycle`
    - Create `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/context/SagaLifecyclePropertyTest.java`
    - **Property 3: Property registration round-trip**
    - **Validates: Requirements 1.8, 2.6**
    - **Property 6: getContext returns the set context**
    - **Validates: Requirements 2.3**
    - **Property 7: Set then clear lifecycle**
    - **Validates: Requirements 2.9, 8.2**
    - **Property 8: Thread isolation**
    - **Validates: Requirements 2.8, 8.1**
    - **Property 9: Nested context stack preservation**
    - **Validates: Requirements 8.4**
    - **Property 12: Non-serializable values rejected**
    - **Validates: Requirements 7.6**

- [ ] 6. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 7. Enhance OrderProcessingSaga example with SagaContext and SagaLifecycle usage
  - [ ] 7.1 Create `@ReadModel`-annotated `OrderDetails` class and `OrderRepository` interface
    - Create `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/readmodel/OrderDetails.java` annotated with `@ReadModel`
    - Create `saga-forge-example/src/main/java/io/github/gkosharovdev/sagaforge/example/readmodel/OrderRepository.java` interface
    - `OrderDetails` should contain fields like `orderId`, `customerName`, `totalAmount`
    - `OrderRepository` should declare a `findByOrderId(String orderId)` method returning `Optional<OrderDetails>`
    - _Requirements: 9.7_
  - [ ] 7.2 Update `OrderProcessingSaga` to use SagaLifecycle and repository
    - Add `OrderRepository` field to `OrderProcessingSaga` (constructor-injected)
    - In a step method, call `SagaLifecycle.getContext()` to access the context
    - Use the injected repository to fetch `OrderDetails` from the read model
    - Call `SagaLifecycle.registerProperty("orderDetails", orderDetails)` to register fetched data
    - In a subsequent step, call `SagaContext.getProperty("orderDetails", OrderDetails.class)` to retrieve the property
    - Demonstrate `hasHandledEvent()` or `hasCompletedStep()` for conditional logic
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.8_
  - [ ]* 7.3 Write integration test for enhanced OrderProcessingSaga
    - Create `saga-forge-example/src/test/java/io/github/gkosharovdev/sagaforge/example/OrderProcessingSagaIntegrationTest.java`
    - Verify the saga compiles and passes annotation validation via `ReflectiveAnnotationScanner`
    - Verify `@ReadModel` annotation is present on `OrderDetails`
    - Verify repository field and SagaLifecycle usage patterns are structurally correct
    - _Requirements: 9.8_

- [ ] 8. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document (12 properties total)
- Unit tests validate specific examples, edge cases, and error messages
- The saga engine integration (Requirement 3 automatic tracking, Requirement 7 persistence) defines contracts only — actual engine implementation is out of scope for this spec
- All code targets Java 21 with zero external compile dependencies in `saga-forge-core`
