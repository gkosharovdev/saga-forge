# Implementation Plan: Saga Forge Framework

## Overview

Incremental build-out of the Saga Forge annotation model, scanner, SPI contracts, and example saga. Each task builds on the previous, starting with project structure and annotations, then the scanner with its validation rules, SPI interfaces, and finally the example module. Tests are woven in close to each implementation step.

## Tasks

- [x] 1. Set up multi-module Maven project structure
  - [x] 1.1 Create parent POM with module list, Java 24, and dependency management
    - Create `pom.xml` at project root with groupId `io.github.gkosharovdev`, artifactId `saga-forge`, packaging `pom`
    - Define `<modules>`: `saga-forge-core`, `saga-forge-storage-postgres`, `saga-forge-storage-mongodb`, `saga-forge-storage-mysql`, `saga-forge-transport-inmemory`, `saga-forge-transport-kafka`, `saga-forge-spring-boot-starter`, `saga-forge-example`
    - Set `<maven.compiler.source>` and `<maven.compiler.target>` to 24
    - Add `<dependencyManagement>` entries for JUnit 5, jqwik, and AssertJ
    - _Requirements: 6.1, 6.2, 6.3_

  - [x] 1.2 Create child module POMs with correct dependency boundaries
    - `saga-forge-core/pom.xml`: zero external compile deps; JUnit 5 + jqwik + AssertJ as test-scoped
    - `saga-forge-storage-postgres/pom.xml`: depends on `saga-forge-core` + PostgreSQL driver (placeholder, empty src)
    - `saga-forge-storage-mongodb/pom.xml`: depends on `saga-forge-core` + MongoDB driver (placeholder, empty src)
    - `saga-forge-storage-mysql/pom.xml`: depends on `saga-forge-core` + MySQL driver (placeholder, empty src)
    - `saga-forge-spring-boot-starter/pom.xml`: depends on `saga-forge-core` + `spring-boot-autoconfigure` (placeholder, empty src)
    - `saga-forge-transport-inmemory/pom.xml`: depends on `saga-forge-core` only (placeholder, empty src)
    - `saga-forge-transport-kafka/pom.xml`: depends on `saga-forge-core` + Kafka client (placeholder, empty src)
    - `saga-forge-example/pom.xml`: depends on `saga-forge-core` only
    - _Requirements: 6.4, 6.5, 6.6, 6.7, 6.9_

- [x] 2. Implement annotation definitions in saga-forge-core
  - [x] 2.1 Create all 8 annotation classes
    - Create package `io.github.gkosharovdev.sagaforge.core.annotation`
    - Implement `@Saga` — `@Target(TYPE)`, `@Retention(RUNTIME)`, `@Documented`, no attributes
    - Implement `@SagaId` — `@Target(FIELD)`, `@Retention(RUNTIME)`, `@Documented`, no attributes
    - Implement `@StartSaga` — `@Target(METHOD)`, `@Retention(RUNTIME)`, `@Documented`, no attributes
    - Implement `@EndSaga` — `@Target(METHOD)`, `@Retention(RUNTIME)`, `@Documented`, no attributes
    - Implement `@SagaStep` — `@Target(METHOD)`, `@Retention(RUNTIME)`, `@Documented`, `String associationProperty()`
    - Implement `@SagaCompensation` — `@Target(METHOD)`, `@Retention(RUNTIME)`, `@Documented`, `String forStep()`
    - Implement `@DomainEvent` — `@Target(TYPE)`, `@Retention(RUNTIME)`, `@Documented`, no attributes
    - Implement `@Command` — `@Target(TYPE)`, `@Retention(RUNTIME)`, `@Documented`, no attributes
    - _Requirements: 1.1, 1.2, 2.1, 2.2, 3.1, 3.2, 4.1, 4.2, 5.1, 5.2, 7.1, 7.2, 7.3, 8.1, 8.2, 8.3_

  - [x] 2.2 Write unit tests for annotation metadata
    - Create `AnnotationMetadataTest.java` in `saga-forge-core/src/test/java/.../annotation/`
    - Verify `@Target` and `@Retention` for all 8 annotations via reflection
    - Verify `associationProperty` attribute on `@SagaStep`
    - Verify `forStep` attribute on `@SagaCompensation`
    - Verify `@DomainEvent` and `@Command` are marker annotations with no attributes
    - _Requirements: 1.1, 1.2, 2.1, 2.2, 3.1, 3.2, 4.1, 4.2, 4.3, 5.1, 5.2, 5.3, 7.1, 7.2, 7.3, 8.1, 8.2, 8.3_

- [x] 3. Implement SagaValidationResult and AnnotationScanner interface
  - [x] 3.1 Create SagaValidationResult record and AnnotationScanner interface
    - Create package `io.github.gkosharovdev.sagaforge.core.scanner`
    - Implement `SagaValidationResult` as a Java record with `sagaClass`, `errors` fields, defensive `List.copyOf()` in compact constructor, and `isValid()` method
    - Implement `AnnotationScanner` interface with `SagaValidationResult validate(Class<?> sagaClass)` method
    - _Requirements: 1.3, 2.3, 3.3_

  - [x] 3.2 Write property test for SagaValidationResult immutability
    - **Property 8: SagaValidationResult immutability**
    - Create `SagaValidationResultPropertyTest.java`
    - Use jqwik to generate arbitrary lists of strings, construct `SagaValidationResult`, assert `errors()` list throws `UnsupportedOperationException` on mutation attempts
    - **Validates: Requirements 1.3, 2.3**

- [x] 4. Implement ReflectiveAnnotationScanner with validation rules
  - [x] 4.1 Create pre-built test fixture classes for scanner tests
    - Create package `io.github.gkosharovdev.sagaforge.core.scanner.fixtures`
    - Create `ValidSaga.java` — a fully valid saga class with all required annotations
    - Create fixture classes for each invalid case: `NoSagaIdSaga`, `MultipleSagaIdSaga`, `NonStringSagaIdSaga`, `NoStartSagaSaga`, `MultipleStartSagaSaga`, `NoEndSagaSaga`, `StartSagaWithoutStepSaga`, `EndSagaWithoutStepSaga`, `StepWithNoParamSaga`, `StepWithMultipleParamsSaga`, `StepWithNonDomainEventParamSaga`, `CompensationInvalidForStepSaga`, `CompensationForNonStepMethodSaga`, `AbstractSagaClass`, `InterfaceSaga`
    - Create fixture domain event and command classes used by fixtures (annotated with `@DomainEvent` / `@Command`)
    - _Requirements: 1.3, 1.4, 2.3, 2.4, 3.3, 3.4, 3.5, 3.6, 3.7, 4.4, 4.5, 4.6, 5.4, 5.5_

  - [x] 4.2 Implement ReflectiveAnnotationScanner — class-level and @SagaId validation
    - Implement `validateClassLevel()`: check `@Saga` present, class is concrete (not interface/abstract)
    - Implement `validateSagaIdField()`: exactly one `@SagaId` field of type `String`
    - Null check on input: throw `IllegalArgumentException` for null `sagaClass`
    - _Requirements: 1.3, 1.4, 2.3, 2.4_

  - [x] 4.3 Implement ReflectiveAnnotationScanner — lifecycle and step validation
    - Implement `validateLifecycle()`: exactly one `@StartSaga`, at least one `@EndSaga`, all must co-occur with `@SagaStep`
    - Implement `validateSagaSteps()`: each `@SagaStep` method has exactly one parameter annotated with `@DomainEvent`; return map of step method names for compensation validation
    - _Requirements: 3.3, 3.4, 3.5, 3.6, 3.7, 4.4, 4.5, 4.6_

  - [x] 4.4 Implement ReflectiveAnnotationScanner — compensation validation
    - Implement `validateCompensation()`: each `@SagaCompensation.forStep` references an existing `@SagaStep` method name
    - _Requirements: 5.4, 5.5_

  - [x] 4.5 Write unit tests for ReflectiveAnnotationScanner
    - Create `ReflectiveAnnotationScannerTest.java`
    - Test valid saga passes validation (using `ValidSaga` fixture)
    - Test each invalid fixture produces the expected error message
    - Test null input throws `IllegalArgumentException`
    - Test that all errors are collected in a single pass (multi-error saga fixture)
    - _Requirements: 1.3, 1.4, 2.3, 2.4, 3.3, 3.4, 3.5, 3.6, 3.7, 4.4, 4.5, 4.6, 5.4, 5.5_

  - [x] 4.6 Write property test: SagaId cardinality enforcement
    - **Property 1: SagaId cardinality enforcement**
    - Use jqwik `@ForAll` with `@From` to select from fixture classes with wrong @SagaId count/type
    - Assert `validate()` returns `isValid() == false` with @SagaId-related error
    - **Validates: Requirements 2.3, 2.4**

  - [x] 4.7 Write property test: Lifecycle method cardinality enforcement
    - **Property 2: Lifecycle method cardinality enforcement**
    - Use jqwik to select from fixture classes with wrong @StartSaga/@EndSaga counts
    - Assert `validate()` returns `isValid() == false`
    - **Validates: Requirements 3.3, 3.4**

  - [x] 4.8 Write property test: Lifecycle annotations require @SagaStep co-occurrence
    - **Property 3: Lifecycle annotations require @SagaStep co-occurrence**
    - Use jqwik to select from fixture classes where lifecycle methods lack @SagaStep
    - Assert `validate()` returns `isValid() == false` with co-occurrence error
    - **Validates: Requirements 3.5, 3.6, 3.7**

  - [x] 4.9 Write property test: SagaStep parameter validation
    - **Property 4: SagaStep parameter validation**
    - Use jqwik to select from fixture classes with wrong param count or non-@DomainEvent param
    - Assert `validate()` returns `isValid() == false` with parameter error
    - **Validates: Requirements 4.4, 4.5, 4.6**

  - [x] 4.10 Write property test: SagaCompensation forStep reference validity
    - **Property 5: SagaCompensation forStep reference validity**
    - Use jqwik to select from fixture classes with invalid forStep references
    - Assert `validate()` returns `isValid() == false` with forStep error
    - **Validates: Requirements 5.4, 5.5**

  - [x] 4.11 Write property test: Valid saga classes pass validation
    - **Property 6: Valid saga classes pass validation**
    - Use jqwik to select from structurally valid fixture classes
    - Assert `validate()` returns `isValid() == true` and `errors()` is empty
    - **Validates: Requirements 1.3, 2.3, 3.3, 3.4, 3.5, 3.6, 4.4, 5.4, 5.5**

  - [x] 4.12 Write property test: Non-concrete saga classes are rejected
    - **Property 7: Non-concrete saga classes are rejected**
    - Use jqwik to select from interface and abstract class fixtures annotated with @Saga
    - Assert `validate()` returns `isValid() == false` with concrete-class error
    - **Validates: Requirements 1.4**

- [x] 5. Checkpoint — Annotations and scanner
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Implement SPI interfaces in saga-forge-core
  - [x] 6.1 Create SagaStatus enum and SagaInstance record
    - Create package `io.github.gkosharovdev.sagaforge.core.spi`
    - Implement `SagaStatus` enum: `CREATED`, `RUNNING`, `COMPENSATING`, `COMPLETED`, `FAILED`
    - Implement `SagaInstance` record with fields: `sagaId`, `sagaType`, `state`, `associations`, `status`, `createdAt`, `lastModifiedAt`; defensive copies via `Map.copyOf()` in compact constructor
    - _Requirements: 9.1, 9.2, 9.3_

  - [x] 6.2 Create StorageProvider interface
    - Implement `StorageProvider` interface with methods: `save(SagaInstance)`, `findById(String)`, `findByAssociation(String, String)`, `delete(String)`
    - Add Javadoc documenting null-safety contracts for future implementors
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 6.6_

  - [x] 6.3 Create EventDispatcher interface
    - Implement `EventDispatcher` interface with methods: `dispatch(Object)`, `registerSagaType(Class<?>)`
    - Add Javadoc documenting transport-agnostic contract, locality decoupling, and implementor concerns (serialization, routing, delivery semantics)
    - _Requirements: 10.1, 10.2, 10.5, 10.6, 10.7, 6.9_

  - [x] 6.4 Write unit tests for SagaInstance record
    - Test defensive copy behavior (`Map.copyOf` in compact constructor)
    - Test immutability of `state` and `associations` maps
    - _Requirements: 9.1_

- [x] 7. Implement OrderProcessingSaga example module
  - [x] 7.1 Create domain event classes in saga-forge-example
    - Create package `io.github.gkosharovdev.sagaforge.example.events`
    - Implement `OrderCreatedEvent` record annotated with `@DomainEvent`, with `orderId` field
    - Implement `PaymentProcessedEvent` record annotated with `@DomainEvent`, with `orderId` field
    - Implement `OrderCompletedEvent` record annotated with `@DomainEvent`, with `orderId` field
    - _Requirements: 12.3, 12.4, 12.7_

  - [x] 7.2 Create command classes in saga-forge-example
    - Create package `io.github.gkosharovdev.sagaforge.example.commands`
    - Implement `ProcessPaymentCommand` record annotated with `@Command`
    - Implement `ReserveStockCommand` record annotated with `@Command`
    - Implement `RequestDeliveryCommand` record annotated with `@Command`
    - _Requirements: 12.8_

  - [x] 7.3 Create OrderProcessingSaga class
    - Implement `OrderProcessingSaga` annotated with `@Saga`
    - Add `@SagaId` field of type `String`
    - Add `@StartSaga @SagaStep(associationProperty = "orderId")` method handling `OrderCreatedEvent`
    - Add intermediate `@SagaStep(associationProperty = "orderId")` method handling `PaymentProcessedEvent`
    - Add `@EndSaga @SagaStep(associationProperty = "orderId")` method handling `OrderCompletedEvent`
    - Add `@SagaCompensation(forStep = "processPayment")` method
    - Demonstrate at least one step emitting a `@Command`-annotated object
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 12.8_

  - [x] 7.4 Write integration test validating OrderProcessingSaga with the scanner
    - Create `OrderProcessingSagaValidationTest.java` in `saga-forge-example` test sources
    - Instantiate `ReflectiveAnnotationScanner` and call `validate(OrderProcessingSaga.class)`
    - Assert `isValid() == true` and `errors()` is empty
    - _Requirements: 12.9_

- [x] 8. Final checkpoint — Full build verification
  - Ensure all tests pass across all modules, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Property tests validate the 8 correctness properties from the design document
- Pre-built test fixture classes are used for property tests (simpler than runtime class generation)
- Storage and transport module POMs are placeholders — implementations are out of scope for this spec
