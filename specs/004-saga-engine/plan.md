# Implementation Plan: Saga Engine Runtime

**Branch**: `004-saga-engine` | **Date**: 2026-11-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-saga-engine/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Build the production saga runtime that 002's test-only `SagaTestHarness` stands in for, as a new module
`saga-forge-engine` (JDK and `saga-forge-core` only). A transport hands events to a core `EventReceiver`; the engine
routes them to saga types and instances, commits a new instance in its own write, and runs each unit of work in the
host's local transaction through a new core `TransactionBoundary`/`LocalTransaction` contract that maps
operation-for-operation onto JTA and Spring's transaction manager. Storage joins that transaction by asking it for its
resource by type (`resource(Connection.class)` for Postgres). One unit of work per instance holds in-process (a
per-`sagaId` lock) and across processes (a new `StorageProvider.findByIdAndLock`, the first statement of the
transaction, `FOR NO KEY UPDATE` in Postgres). The engine produces the lifecycle entries and hands them over with each
write through a new `save(instance, entries)`; storage numbers them, so `SagaLifecycleEntry` loses `sequenceNumber`
and Postgres drops its diffing derivation. Committed units of work hand their buffered events to a core `EventHandoff`,
`AFTER_COMMIT` by default (`EventDispatcher.dispatch`, at most once) or `IN_TRANSACTION` for a later outbox. Startup
runs `validateAll` plus loader, parameter and saga-instantiation checks in one report. Diagnostics go through
`System.Logger`. Tests are unit tests over in-memory doubles; the Postgres adaptation is checked with a
statement-recording JDBC fake, and the 003 container suites are fenced off with a JUnit tag.

**Key decisions, confirmed by the user on 2026-11-06**:
1. **Engine location**: new module `saga-forge-engine` (research §1), not a package in core. Changes the Module Map.
2. **Sequence-number ownership**: storage assigns positions; `SagaLifecycleEntry.sequenceNumber` is removed (research
   §4), a core record change.
3. **Lock at load**: `findByIdAndLock` as a new abstract `StorageProvider` operation, with `FOR NO KEY UPDATE` in
   Postgres, held for the whole unit of work (research §3).
4. **Conflict handling**: a write-time conflict is reported `NOT_HANDLED` for redelivery, not retried (research §3);
   `NOT_HANDLED` also covers an event that reaches a still-`CREATED` instance (research §6).

## Technical Context

**Language/Version**: Java 21 (root `pom.xml`, unchanged).

**Primary Dependencies**: None new (FR-037). `saga-forge-engine` depends on `saga-forge-core` only.
`saga-forge-storage-postgres` keeps its 003 dependencies (driver `42.7.14`, `jackson-databind` `3.1.7`); the adaptation
uses JDBC only. Test scope: JUnit 5.11.4, jqwik 1.9.2, AssertJ 3.27.3 (existing), plus internal test-jars
(`saga-forge-core` tests for the engine and example; `saga-forge-engine` tests for the example). Build configuration
only: `maven-jar-plugin` `test-jar` goal on core and engine; `maven-surefire-plugin` `excludedGroups` in the Postgres
module. JTA, Spring and SLF4J are named only in mappings; none is used (G-GOV-01).

**Storage**: No new backend. In-memory test doubles in engine test sources. Postgres adapted (FR-023); schema
unchanged.

**Testing**: Unit tests only, JUnit 5 + AssertJ, jqwik where a property is stated (research §13, §14): differential
harness-vs-engine tests (SC-001), failure-position matrix (SC-002), repeated concurrent runs with threads (SC-003,
SC-012), two engines over shared doubles (SC-004), recording handoff and logger (SC-011, FR-038), JDBC fake for
Postgres (research §15). No Testcontainers, Postgres, Kafka, Spring or containers. The full unit suite runs once at the
end of each implementation phase (quickstart.md).

**Target Platform**: JVM library (Java 21).

**Project Type**: Library. New module `saga-forge-engine`; changes in `saga-forge-core`, `saga-forge-storage-postgres`,
`saga-forge-example`, root `pom.xml`, docs.

**Performance Goals**: None in the spec. Structural notes: one storage lock and one instance write per unit of work;
lock hold time equals unit-of-work duration (G-ENG-11).

**Constraints**: No third-party dependency anywhere. Engine free of Spring, JDBC, datastore and transport types (it
uses `TransactionBoundary` and core SPIs only). No storage or transport added. The engine owns no thread pool; a unit
of work runs on the caller's thread. Pre-1.0 breaking SPI changes accepted and confined to core, its tests, the
harness, the Postgres module and its tests. 002 fixtures and the example classes run unchanged (SC-009).

**Scale/Scope**: Core: 12 new `spi` types (`TransactionBoundary`, `LocalTransaction`, `EventReceiver`,
`HandlingReport`, `SagaHandlingResult`, `HandlingOutcome`, `EventHandlingException`, `EventHandoff`,
`HandedOffEvents`, `SagaConcurrencyConflictException`, `HandlerParameterResolver`, `SagaObjectProvider`), two changed
(`StorageProvider`, `SagaLifecycleEntry`), eight Javadoc-only corrections. Engine: `SagaEngine` and builder,
`StartupReport`, `SagaEngineStartupException`, `DispatchingEventHandoff`, about ten internal classes, test doubles and
24 quickstart scenarios. Postgres: `PostgresStorageProvider` adapted, `JdbcTransactionBoundary`, `LifecycleEntryRows`,
`ForwardTransition` (renamed), unit tests over a JDBC fake, a tag on five container classes. Docs per FR-040 and
`.kiro` Properties 21 to 28.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment |
|---|---|
| I. Zero-Dependency Core | **PASS.** Core stays contracts only; every new core type is JDK-only and storage- and transport-agnostic (`resource(Class<R>)` keeps JDBC out of core; `Executable`/`Parameter` are `java.lang.reflect`). The runtime lives in its own module with no third-party dependency, so later SLF4J/JTA adoption need not touch core (research §1). |
| II. Annotation-Driven, Declarative Style | **PASS.** No annotation or authoring surface changes. New capabilities are SPI extension points (transaction boundary, handoff, parameter resolution, saga objects). The engine builder is host-facing; application developers keep not assembling an engine (README principle 5). |
| III. Formal Spec Conformance | **GATE, carried into tasks.** `StorageProvider` gains two operations and changes `save`'s meaning; `SagaLifecycleEntry` loses a component; `SagaLifecycle`/`EventDispatcher` Javadoc changes meaning (routing moves to the engine). Tasks add Properties 21 to 28 to `.kiro/specs/saga-forge-framework/design.md` (research §13) and apply contracts/contract-deltas.md. No existing property 1 to 20 changes. |
| IV. Explicit, Narrow Scope | **PASS.** No storage or transport added; `saga-forge-engine` is neither and ships no in-memory store or transport in `src/main`. |
| V. Documentation Hierarchy | **ACTION, carried into tasks.** A new module changes AGENTS.md's Module Map, README's Modules table and Status, and `docs/architecture.md` (Status at a glance, Module responsibilities incl. "storage modules record history by diffing", the transaction-boundary gap, "None of these has a runtime implementation yet") (FR-040). |

## Project Structure

### Documentation (this feature)

```text
specs/004-saga-engine/
├── spec.md
├── plan.md                    # This file
├── research.md                # Phase 0
├── data-model.md              # Phase 1
├── quickstart.md              # Phase 1
├── contracts/                 # Phase 1
│   ├── event-receiver.md
│   ├── transaction-boundary.md
│   ├── storage-provider.md
│   ├── event-handoff.md
│   ├── host-plugins.md
│   ├── engine-startup.md
│   ├── diagnostics.md
│   └── contract-deltas.md     # deltas on 001/002/003 contracts
├── checklists/requirements.md
└── tasks.md                   # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
pom.xml                                   + <module>saga-forge-engine</module> after saga-forge-core

saga-forge-core/
├── pom.xml                               + maven-jar-plugin test-jar goal
├── src/main/java/io/github/gkosharovdev/sagaforge/core/
│   ├── spi/        NEW TransactionBoundary, LocalTransaction, EventReceiver, HandlingReport, SagaHandlingResult,
│   │               HandlingOutcome, EventHandlingException, EventHandoff, HandedOffEvents,
│   │               SagaConcurrencyConflictException, HandlerParameterResolver, SagaObjectProvider
│   │               CHANGED StorageProvider (+save(instance, entries), +findByIdAndLock), SagaLifecycleEntry
│   │               (-sequenceNumber); Javadoc: EventDispatcher, SagaContextStore, SagaInstance,
│   │               SagaLifecycleEventKind, DomainEventRecorder, HandlerInstanceProvider
│   ├── lifecycle/  SagaLifecycle Javadoc
│   ├── event/      EventPublisher Javadoc
│   └── command/    CommandGateway Javadoc
└── src/test/java/io/github/gkosharovdev/sagaforge/core/
    ├── spi/        SagaLifecycleEntryTest updated; tests for the new records
    └── execution/harness/InMemoryStorageProvider   + the two SPI operations (harness otherwise unchanged)

saga-forge-engine/                        NEW module
├── pom.xml                               saga-forge-core; test: junit, jqwik, assertj, saga-forge-core:test-jar;
│                                         maven-jar-plugin test-jar goal (doubles for the example)
├── src/main/java/io/github/gkosharovdev/sagaforge/engine/
│   ├── SagaEngine, StartupReport, SagaEngineStartupException, DispatchingEventHandoff
│   └── internal/   SagaModel, SagaUnitOfWork, NonSagaUnitOfWork, EngineSagaLifecycle, EngineSagaContext,
│                   EngineCommandGateway, AggregateAccess, EngineEventPublisher, InstanceLocks, Diagnostics,
│                   Reflection
└── src/test/java/io/github/gkosharovdev/sagaforge/engine/
    ├── support/    InMemoryTransactionBoundary, TransactionalInMemoryStorageProvider, CapturingEventHandoff,
    │               RecordingLogger, TestTransport, map-backed HandlerInstanceProvider
    ├── fixtures/   failure-position saga (service, loader/saver and resolver handlers)
    └── *Test, *PropertyTest   quickstart.md scenarios 2–22

saga-forge-storage-postgres/
├── pom.xml                               surefire excludedGroups=${saga.excludedTestGroups} (default container),
│                                         profile container-tests
├── src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/
│   ├── PostgresStorageProvider           joins the boundary; save(instance, entries); findByIdAndLock;
│   │                                     FOR NO KEY UPDATE; no derivation
│   ├── JdbcTransactionBoundary           NEW, public
│   ├── LifecycleEntryRows                NEW, package-private mapper
│   ├── ForwardTransition                 was LifecycleHistoryDerivation (derive removed)
│   └── ConcurrentSagaUpdateException     extends SagaConcurrencyConflictException
└── src/test/java/.../postgres/
    ├── JdbcFake, PostgresStorageProviderJoinTest, JdbcTransactionBoundaryTest, LifecycleEntryRowsTest  NEW (unit)
    ├── ForwardTransitionPropertyTest, LifecycleHistoryDerivationTest → ForwardTransition tests
    └── SchemaTest, PostgresStorageProviderTest, PostgresSagaContextStoreTest, LifecycleHistoryTest,
        ConcurrencyControlTest            @Tag("container"); updated to the new write contract; not run

saga-forge-example/
├── pom.xml                               test: saga-forge-engine, its test-jar, saga-forge-core test-jar
└── src/test/java/.../example/OrderProcessingSagaEngineTest   NEW (main sources unchanged)

docs: README.md (Status, Modules), docs/architecture.md, AGENTS.md (Current Status, Module Map, Known Gaps),
      .kiro/specs/saga-forge-framework/design.md (Properties 21–28), .specify/memory/memory.md
```

**Structure Decision**: One new module for the runtime; contracts that storage, transport and host modules need go
into `saga-forge-core`'s `spi` package so none of them depends on the engine. The starter (next feature) will depend on
`saga-forge-engine`; this feature does not touch the starter's placeholder POM. The 002 harness stays in core's test
sources as the frozen reference model; the engine's tests reach it and the 002 fixtures through core's test-jar.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| New module `saga-forge-engine` (Module Map change, Principle V) | Principle I keeps core to contracts; the engine will later take SLF4J/JTA (G-GOV-01); transports and storage must not carry the runtime | A `core.engine` package widens core's scope and pulls future logging/transaction libraries into core (research §1) |
| Breaking `StorageProvider` change: two new abstract operations, changed `save(SagaInstance)` meaning (Principle III) | FR-025 needs a lock taken before any step; FR-033 needs entries handed over with the write | Default methods would let a provider silently skip locking; a separate history call could be forgotten (research §3, §4) |
| `SagaLifecycleEntry` loses `sequenceNumber` (core record change) | The engine reads no history and cannot number entries; storage can, under the lock | A counter on `SagaInstance` changes every store's shape; a placeholder number would lie until persisted (research §4) |
| Pessimistic lock held for the whole unit of work, including handler calls to external systems | FR-025: the waiting unit of work must not run steps or write context first | Optimistic versioning lets the loser run its steps and write context before it fails (research §3); hold time is recorded as G-ENG-11 |
| Harness and engine coexist (two executable models of the 002 contract) | Core tests cannot depend on the engine; SC-001 is proven by comparing them | Moving or rewriting 40+ harness tests buys no behavior; retirement is G-ENG-13 |

## Post-Phase 1 Constitution Re-Check

- **I** still **PASS**: contracts/ define only JDK types in core; `JdbcTransactionBoundary` and every `java.sql` use
  stay in `saga-forge-storage-postgres`; the engine imports no JDBC, Spring or transport type.
- **II** still **PASS**: data-model.md and contracts/ add no annotation and no attribute; saga authors see no change.
- **III** gate stays open and becomes explicit tasks: Properties 21 to 28 (research §13), the `StorageProvider` and
  `SagaLifecycleEntry` deltas (contracts/storage-provider.md), the 001/002/003 deltas (contracts/contract-deltas.md),
  and recording that the `.kiro/specs/saga-context-lifecycle` properties do not apply (G-002-05 unchanged).
- **IV** still **PASS**: no storage or transport; test doubles stay in `src/test`.
- **V** **ACTION** unchanged: the doc tasks of FR-040 plus the Module Map entry for `saga-forge-engine`. No
  constitution amendment is needed: Principle I names modules only as homes for framework-specific wiring, which the
  engine is not, and the Technical Constraints defer module boundaries to AGENTS.md's Module Map. G-GOV-01's pending
  amendment should name the engine module when it rewords Principle I.

**Result**: Ready for `/speckit-tasks`; the four key decisions in the Summary were confirmed by the user on 2026-11-06. Carried forward:
G-ENG-05 (stranded `CREATED`), G-ENG-07 (at-most-once handoff), G-ENG-08 (cross-process duplicate starts), G-ENG-06
(`state` unused), and the new G-ENG-09 to G-ENG-13 in `.specify/memory/memory.md`. G-003-23 grows: the Postgres
adaptation is verified by a JDBC fake only (research §15).
