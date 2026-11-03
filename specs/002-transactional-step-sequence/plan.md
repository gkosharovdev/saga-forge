# Implementation Plan: Saga Units of Work, Command Gateway, and Command Handlers

**Branch**: `002-transactional-step-sequence` | **Date**: 2026-11-03 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-transactional-step-sequence/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution
workflow.

## Summary

Replace the "step returns a command" model with an Axon-style one. `@SagaStep` methods return `void` and
dispatch commands through a core `CommandGateway`. A new `@CommandHandler` marker identifies handlers on
`@Aggregate` classes (instance methods, or constructors and static factories for creation) or on services.
Aggregate persistence stays entirely in client code: aggregates are loaded and saved through the client's
`@AggregateLoader` / `@AggregateSaver` methods or, in the Spring starter later, its existing Spring Data
repositories, and no client class implements or extends a Saga Forge type. Code in a unit of work records
events with a static `DomainEvents.record(...)`; application code publishes through an injectable
`EventPublisher`. Every event names its aggregate (`@DomainEvent(aggregate = ...)`) and carries one
`@AggregateIdentifier`, so each completed step records the aggregates it touched for the compensation spec.
Execution semantics are specified as an engine contract: a saga-scoped unit of work runs every step triggered
in-process, commits at an `@EndSaga` step or a wait point, and rolls back in full on failure (ending the saga
`FAILED`). `SagaContext` moves to its own `SagaContextRecord` behind a `SagaContextStore` SPI so it survives
rollbacks. `SagaInstance` gains per-step status, outcome, and failure, and 001's `SagaLifecycle` gains
`recordStepCompleted`, `complete(sagaId, outcome)`, and `fail`. The scanner gains a one-pass `validateAll`
with the new rules. As in 001, core ships contracts only; behavior is proven by a test-only `SagaTestHarness`.
Docs, the example module, and the `.kiro` specs are brought in line.

## Technical Context

**Language/Version**: Java 21 (root `pom.xml` `maven.compiler.source`/`target`)

**Primary Dependencies**: None for `saga-forge-core` main sources (Principle I). Test scope only: JUnit 5.11.4,
jqwik 1.9.2, AssertJ 3.27.3, as today.

**Storage**: N/A in this feature. New `SagaContextStore` SPI and changed `SagaInstance` shape are contracts
only; storage modules implement them in their own features. The harness uses in-memory stores.

**Testing**: JUnit 5 + AssertJ unit tests per contract and scanner rule; jqwik properties for unit-of-work
invariants (rollback leaves no aggregate change, context survives rollback, untaken outcomes stay `NOT_RUN`);
harness scenario tests mapped to the spec's acceptance scenarios (quickstart.md).

**Target Platform**: JVM library (Java 21).

**Project Type**: Library. Existing modules `saga-forge-core` and `saga-forge-example`; no new module.

**Performance Goals**: No targets in the spec. All new core code is in-process method calls; N/A.

**Constraints**: Zero new runtime dependencies. No Spring, JPA, or transport types in core. No new storage or
transport technology. The `@SagaStep` single `@DomainEvent` parameter rule stays. Breaking changes to 001's
`SagaLifecycle.complete`, the `SagaInstance` constructor, and the new required `@DomainEvent.aggregate`
attribute are accepted pre-1.0 and confined to core, its tests, and the example module.

**Scale/Scope**: About 20 new core types: 5 annotations (`@CommandHandler`, `@Aggregate`,
`@AggregateIdentifier`, `@AggregateLoader`, `@AggregateSaver`) plus a required `aggregate` attribute on
`@DomainEvent`; `CommandGateway`, `NoCommandHandlerException`, `AggregateNotFoundException`, `DomainEvents`,
`EventPublisher`, `DomainEventScope`, `DomainEventRecorder`, `AggregateResolver`, `HandlerInstanceProvider`,
`SagaContextStore`, `SagaContextRecord`, `StepStatus`, `StepRecord`, `AggregateReference`, `SagaFailure`,
`ValidationReport`. Changes to `SagaInstance`, `SagaLifecycle`, `AnnotationScanner`,
`ReflectiveAnnotationScanner`, every existing `@DomainEvent` fixture (new required attribute), a test harness
with two reference sagas, the rewritten example module, and the documentation set of FR-027.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment |
|---|---|
| I. Zero-Dependency Core | **PASS.** Every new type is JDK-only. Spring, JPA and transaction handling stay behind SPIs (`HandlerInstanceProvider`, `AggregateResolver`, `DomainEventScope`) or in the future engine (research §3, §9). |
| II. Annotation-Driven, Declarative Style | **PASS, with two documented deviations.** New capabilities are framework-owned annotations (`@CommandHandler`, `@Aggregate`, `@AggregateIdentifier`, `@AggregateLoader`, `@AggregateSaver`) or SPI extension points. Deviations: the `CommandGateway` field is recognized by type (same idiom as 001's `SagaContext`), and events are recorded through a static facade rather than an annotation. Steps dispatch commands imperatively inside their body, but which step runs, when, in which transaction, and for which instance stays declarative. See Complexity Tracking. |
| III. Formal Spec Conformance | **GATE, carried into tasks.** `@SagaStep` semantics change (must return `void`) and six validation rules are added. `.kiro/specs/saga-forge-framework/design.md` needs a correctness property per rule and `requirements.md` Requirement 8.4 and 11 must be marked superseded before merge. |
| IV. Explicit, Narrow Scope | **PASS.** No storage or transport technology added. `SagaContextStore` is implemented later by the existing three storage modules only; after-commit delivery reuses `EventDispatcher`. |
| V. Documentation Hierarchy | **SUPPORTS.** US4 corrects README, `docs/use-cases.md`, `docs/architecture.md`, `AGENTS.md`, and 001's quickstart, which currently teach the old model. The constitution's own Technical Constraints line "`SagaContext` tracks step history" is stale; a PATCH amendment (1.0.1) is planned as a task (research §12). |

## Project Structure

### Documentation (this feature)

```text
specs/002-transactional-step-sequence/
├── spec.md
├── plan.md              # This file
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/           # Phase 1
│   ├── command-gateway.md
│   ├── event-publisher.md
│   ├── saga-unit-of-work.md
│   ├── saga-context-store.md
│   ├── saga-lifecycle.md
│   └── validation.md
├── checklists/requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
saga-forge-core/                                   # pom.xml unchanged
├── src/main/java/io/github/gkosharovdev/sagaforge/core/
│   ├── annotation/   + CommandHandler, Aggregate, AggregateIdentifier, AggregateLoader, AggregateSaver;
│   │                 DomainEvent gains aggregate(); SagaStep Javadoc: must return void
│   ├── command/      NEW: CommandGateway, NoCommandHandlerException, AggregateNotFoundException
│   ├── event/        NEW: DomainEvents (static facade), EventPublisher
│   ├── lifecycle/    SagaLifecycle (changed), SagaContext (Javadoc: representation)
│   ├── scanner/      AnnotationScanner (+validateAll), ReflectiveAnnotationScanner (new rules),
│   │                 NEW: ValidationReport
│   └── spi/          SagaInstance (changed); NEW: StepStatus, SagaFailure, SagaContextRecord,
│                     StepRecord, AggregateReference, SagaContextStore, AggregateResolver,
│                     HandlerInstanceProvider, DomainEventScope, DomainEventRecorder
└── src/test/java/io/github/gkosharovdev/sagaforge/core/
    ├── annotation/, command/, event/                   # contract unit tests (incl. DomainEvents.capture)
    ├── lifecycle/    updated 001 tests and InMemorySagaLifecycle double
    ├── scanner/      new rule tests + fixtures (invalid sagas, handler classes, duplicate handlers)
    ├── spi/          SagaInstanceTest updated; SagaContextRecord / SagaFailure tests
    └── execution/    NEW
        ├── harness/  SagaTestHarness, in-memory AggregateResolver, SagaContextStore, StorageProvider,
        │             EventDispatcher recorder
        ├── fixtures/ three-step in-process saga; CreateBookingSaga with Inventory (@AggregateLoader),
        │             Booking (creation handler) and PaymentService (service) handlers
        └── *Test, *PropertyTest   # US1–US3 acceptance scenarios, SC-002..SC-004

saga-forge-example/   OrderProcessingSaga rewritten (void steps, CommandGateway, SagaContext);
                      + an @Aggregate and a service handler; validation test uses validateAll

docs: README.md, docs/use-cases.md, docs/architecture.md, AGENTS.md,
      specs/001-core-lifecycle-context/quickstart.md, .kiro/specs/saga-forge-framework/{requirements,design}.md,
      .specify/memory/constitution.md (PATCH 1.0.1)
```

**Structure Decision**: Stay within the two existing modules. Author-facing contracts get their own packages
(`command`, `event`) so a step author's imports read naturally; everything implemented by hosts,
storage, or transport stays in `spi`. The harness lives in test sources, the same posture as 001's doubles.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| `CommandGateway` saga field recognized by type, no marker annotation (Principle II) | Steps need the gateway; the engine creates saga instances, so it must inject it | A second step parameter changes the tested single-parameter rule; a static accessor hides ambient state; a marker annotation adds ceremony for an unambiguous type. Same reasoning as 001 for `SagaContext`. |
| Static `DomainEvents.record(...)` facade backed by a thread-bound recorder (Principle II; contrasts with 001 rejecting a static `SagaContext.current()`) | Aggregates are owned and instantiated by client code (often an ORM), so the framework can neither inject into them nor require a supertype or interface; events must still be intercepted wherever they are recorded | A `RecordsDomainEvents` interface or base class was rejected by the user, event return values were rejected in clarification, and injecting a publisher into aggregates needs client wiring. Saga instances are created by the engine and can be injected; aggregates have no framework-controlled construction point, so the 001 objection does not carry over. |
| Imperative `commandGateway.send(...)` inside step bodies (Principle II) | Steps build commands from event and `SagaContext` data | Declaring command mappings on `@SagaStep` was offered and rejected in clarification (Q1, option C); orchestration itself (routing, correlation, transactions, outcomes) stays declarative. |

## Post-Phase 1 Constitution Re-Check

- **I** still **PASS**: data-model.md and contracts/ add JDK-only types; no SPI exposes a Spring, JPA, or
  transport type.
- **II** unchanged: only the three deviations above, all justified.
- **III** gate stays open and becomes explicit tasks: one `.kiro` design property per rule in
  contracts/validation.md, Requirement 8.4 and 11 marked superseded, and 001's `SagaLifecycle` contract
  delta (contracts/saga-lifecycle.md) applied to its tests.
- **IV** still **PASS**: `SagaContextStore` adds no backend; `EventDispatcher` is reused unchanged.
- **V** still **SUPPORTS**: quickstart scenario 11 (SC-005) verifies the documentation sweep.

**Result**: Ready for `/speckit-tasks`. Open items carried forward: the Principle III gate tasks, and four
items deferred to the engine feature: the transaction SPI (research §9), cross-process per-instance
serialization (§11), resolving non-command `@CommandHandler` parameters (contracts/command-gateway.md),
and the startup check that every aggregate with instance-method handlers has a loader. The Spring Data
repository adapter behind `AggregateResolver` belongs to the Spring starter feature.
