# Implementation Plan: Saga Operations: Query, Repair and Recovery

**Branch**: `015-saga-operations` | **Date**: 2026-11-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/015-saga-operations/spec.md`

## Summary

A supported way to find saga instances, repair those that need a human decision, and let the runtime recover stranded
and stalled ones. Underneath:

1. **Core contracts** (`core.operations`): the `SagaOperations` interface and immutable values (`SagaInstanceQuery`,
   `PageCursor`, `SagaInstancePage`, `InstanceCounts`, `OperatorRequest`, `SagaResolution`, sealed `RepairResult`,
   `RecoveryReport`). A view is the existing `SagaInstance` (research R1). `SagaInstance` gains `resolution` and
   `compensationRound`; `SagaLifecycleEntry` gains `operator`; new history kinds and the trigger kind `OPERATOR_REQUEST`
   (R5–R7). Outcome event ids get a round suffix after a resume (R11).
2. **Storage SPI**: `StorageProvider.find(query)`, `countByStatus(types)` and `history(sagaId)`, required and covered by
   `StorageProviderQueryContract` on three implementations (R2, R3). Postgres has new columns, an index,
   `schema-upgrade-015.sql` and forward-transition rules (R12).
3. **Runtime**: `EngineSagaOperations` (one entry point for API and sweeper) running new works on the one `UnitOfWork`:
   `ResumeCompensation`, `MarkResolved`, `FailAbandonedStart`, and `StartCompensation.byOperator`, then the existing
   `CompensationRun`, all under the shared `InstanceLocks` (R4–R6). `EventRouter` adopts a stranded `CREATED` instance on
   a redelivered start (R10). `RecoverySweeper` with public `RecoverySettings` and `SagaOperations.recover()`, plus
   `SagaRuntime.operations()`/`stop()` (R8, R9). Telemetry: span, counters and a cached instances gauge (R13).
4. **Starter**: an Actuator endpoint `sagas` with `defaultAccess = READ_ONLY`, so writes need
   `management.endpoint.sagas.access=unrestricted`, an authenticated `Principal` as operator, `saga-forge.recovery.*`
   (on by default in the starter), and health details (R14).
5. **Fixture**: `operations()`, `recovery(...)`, recovery on clock advance, history and resolution expectations (R15).
6. **Docs and register**: guide, agent skill and snippet, `llms-full.txt`, architecture, memory.md (close G-006-03,
   G-006-05, G-008-02, G-ENG-05, G-013-05; update G-012-04, G-017-07, G-003-24; add G-015-02..07), roadmap,
   `.kiro` properties.

## Technical Context

**Language/Version**: Java, `maven.compiler.release` 21 (CI also on 25)

**Primary Dependencies**: none new. Core stays dependency-free; runtime keeps `opentelemetry-api`; Postgres keeps JDBC +
Jackson 3; starter keeps Spring Boot 4.1 (Actuator `Access`, `Principal` injection).

**Storage**: Postgres: `saga_instance.resolution`, `saga_instance.compensation_round`, `saga_lifecycle_history.operator`
and `.operator_reason`, index `saga_instance_status_modified`; `schema.sql` and `schema-upgrade-015.sql`.

**Testing**: JUnit 5 + AssertJ (+ jqwik where a property fits: paging "each unchanged once"). The new
`StorageProviderQueryContract` (runtime test-jar) runs on the runtime double, the fixture storage and Postgres
(container). Runtime: `EngineSagaOperationsTest`, `RepairConcurrencyTest`, `RecoverySweeperTest`,
`StrandedStartTest`, `UnitOfWorkBoundaryTest` (new works stay inside the boundary). Postgres container:
`OperationsAcceptanceTest` (SC-003, SC-004, SC-005), `ForwardTransitionTest`, `SchemaUpgrade015Test`. Starter:
`SagasEndpointTest`, `ReadmePropertiesTest`, Spring example `SagasEndpointWebContainerTest`. Fixture:
`OperationsFixtureTest` and an example fixture test.

**Target Platform**: JVM library | **Project Type**: multi-module Maven library

**Performance Goals**: a listing page is one indexed query (no N+1: steps live in the `steps` JSONB column; history is
read only for the view). The gauge issues at most one `GROUP BY` per 30 s per process. A sweep issues two indexed
queries plus one locked unit of work per candidate, bounded by the batch size.

**Constraints**: Principle I (core gains records, one interface, one exception; JDK only). Principle II (no imperative
orchestration for saga authors; compensation stays `forStep`-targeted). Principle IV (no new storage or transport). The
`StorageProvider` SPI changes incompatibly (abstract methods), accepted as for 013's `MessageStore` (no external
storages). Release-build Javadoc rules (no `<h3>`, no `@throws` on record comments).

**Scale/Scope**: core, runtime (+ test-jar), storage-postgres, starter, test fixture, both examples, docs. No new module.
Annotations and scanner are unchanged.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Check | Result |
|-----------|-------|--------|
| I. Zero-Dependency Core | `core.operations` holds records, one interface, an enum and one exception; changed core records stay JDK-only. Actuator and `Principal` handling stay in the starter. | Pass |
| II. Annotation-Driven, Declarative | No new saga-author API. Operator compensation reuses step-targeted `@SagaCompensation`; the API is for operators and hosts, not for steps (no programmatic abort from a step, which stays dropped). | Pass |
| III. Formal Spec Conformance | New and revised properties in `.kiro/specs/saga-forge-framework/design.md` (FR-041): operator transitions, at most one run per compensation per round, adopted start never duplicates, Property 38 revised. Each one is backed by a contract case (RC3, RC4, ST1–ST3, SW5, F1–F8). | Pass (task) |
| IV. Explicit, Narrow Scope | Same storage and transports; the in-memory doubles are test-only. | Pass |
| V. Documentation Hierarchy | architecture.md (operations and recovery section), guide (operations, recovery, limitations), starter README, agent skill/snippet, `llms-full.txt`, memory.md, roadmap, AGENTS.md status, all as tasks. | Pass (task) |
| Workflow: composition over inheritance | No class hierarchy. `EngineSagaOperations` holds `CompensationRun`, `InstanceLocks` and `Collaborators`; `RecoverySweeper` holds `EngineSagaOperations`. The sealed `RepairResult` is a value variant. | Pass |
| Workflow: abstraction tested before proposed | Spike (research "Spike", 6 checks): `SagaOperations` with an in-memory implementation and a caller; keyset `SagaInstanceQuery`/`PageCursor`; `OperatorRequest` validation; sealed `RepairResult`. Real tests: storage contract on three implementations; the operations API through the runtime, the fixture and the endpoint (three callers). | Pass |
| Workflow: recognisable abstractions | Rated below. | Pass |

### Abstraction familiarity rating

| Abstraction | Kind | Community counterpart | Rating |
|-------------|------|-----------------------|--------|
| `SagaOperations` | interface | Spring Batch `JobOperator`, Temporal `WorkflowServiceStubs` admin calls, Axon `SagaRepository` + admin tooling | High |
| `SagaInstanceQuery` / `PageCursor` / `SagaInstancePage` | records | Spring Data `KeysetScrollPosition`/`Window`, GraphQL cursor connections | High |
| `OperatorRequest` | record | audit "actor + reason" (Kubernetes audit `user`, `kubectl annotate --reason`-style change causes) | Medium–high |
| `SagaResolution` | record | incident "resolved by, resolution note" | High |
| `RepairResult` (`Applied`/`NotApplied`) | sealed result | Result types; Spring Batch `JobOperator` returning outcomes | High |
| `RecoverySweeper` / `recover()` | internal class / method | "sweeper"/"reaper" in Quartz misfire handling, Eventuate/Axon dead-letter processors; "recover" in Spring Retry `@Recover` | Medium–high |
| `RecoverySettings` / `AbandonedStartPolicy` | settings record / enum | Spring Boot property groups; "policy" enums (`RejectedExecutionHandler` policies) | High |
| `resumeCompensation`/`compensate`/`markResolved` | operations | Temporal `reset`/`terminate`, MassTransit saga repair, incident-tool "resolve" | High |
| `compensationRound` | field | "attempt"/"generation" counters (Kubernetes `metadata.generation`) | Medium; documented as "resume generation" |
| Actuator endpoint `sagas` | endpoint | Spring Boot `scheduledtasks`, `quartz` endpoints | High |

No boutique names. "Sweeper" is the one moderately specialised term, and it matches its use in schedulers and outboxes.

### Design-rule application (effective-java, clean-code-java, solid-design-review)

- **SRP**: queries and repairs share one entry point (`EngineSagaOperations`) because both serve the operator. Each
  write is its own `Work` class (`ResumeCompensation`, `MarkResolved`, `FailAbandonedStart`). Scheduling is split from
  sweeping (`RecoverySweeper.sweep()` vs. its thread), and report deduplication from both (`AbandonedStartReports`).
  `EventRouter` gains only the adoption branch (one private method).
- **OCP**: a new repair is a new `Work` plus one `SagaOperations` method, with no change to `UnitOfWork`.
  `RepairRejection` is a closed enum on purpose: callers switch over it.
- **LSP**: no `UnsupportedOperationException` defaults on `StorageProvider` (R2). Every storage implements the query.
- **ISP**: `SagaOperations` mixes reads and writes (10 methods). Accepted because all three callers (endpoint, fixture,
  sweeper) use both halves. The endpoint splits them into read and write operations at the HTTP edge. Revisit if a
  read-only caller appears.
- **DIP**: `RecoverySweeper` takes a `ScheduledExecutorService` supplier and the runtime clock through its constructor;
  tests call `sweep()` directly. There is no static time or thread access.
- **EJ Item 2/17/50/55**: immutable validated records; withers on `SagaInstanceQuery` instead of a telescoping
  constructor; `Optional` only as a return value (`find`, `SagaInstancePage.next`); copies of every collection.
- **EJ Item 69/72**: wrong status is a `NotApplied` value, not an exception. Programming errors (blank operator,
  limit out of range) throw the standard `IllegalArgumentException`/`NullPointerException`.
- **EJ Item 78/81**: the sweeper's report map is a `ConcurrentHashMap`; stop uses `ExecutorService.shutdown` and
  `awaitTermination`; `stop()` is idempotent and documented.
- **Clean Code**: no boolean flags (`find` vs. `context` instead of `includeContext`; admission variants as named
  factories `ResumeCompensation.byOperator(...)`/`.stalled(...)`, not a flag). The sweeper's per-instance error handling
  logs and counts and never swallows silently. Log event names state intent.

Post-design re-check: Pass. Deliberate deviations are in Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/015-saga-operations/
├── plan.md              # this file
├── research.md          # spike, R1–R15, gaps G-015-02..07
├── data-model.md        # records, SPI changes, Postgres DDL, state transitions, admissions
├── quickstart.md        # validation runs
├── contracts/
│   ├── operations-api.md        # O1–O7, R1–R6, RC1–RC7, CP1–CP6, MR1–MR4, ST1–ST4, SW1–SW10
│   ├── storage-query-spi.md     # Q1–Q10, F1–F8, schema upgrade
│   └── starter-operations.md    # properties, `sagas` endpoint E1–E7, telemetry, logs
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/
├── operations/  SagaOperations, SagaInstanceQuery, PageCursor, SagaInstancePage, InstanceCounts, OperatorRequest,
│                SagaResolution, RepairResult, RepairRejection, RecoveryReport, SagaStartAbandonedException,
│                package-info                                                                       (new)
├── spi/         SagaInstance (+resolution, compensationRound), SagaLifecycleEntry (+operator),
│                SagaLifecycleEventKind (+3), StorageProvider (+find, countByStatus, history)
├── compensation/ CompensationTriggerKind (+OPERATOR_REQUEST), CompensationTrigger (+operatorRequest)
├── outcome/     OutcomeTrigger (+operatorRequest), SagaCompleted/SagaFailed/SagaCompensated, Values (round in eventId)
└── telemetry/   SagaTelemetrySettings (+instanceCountInterval)
saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime/
├── SagaRuntime (+operations(), stop(), Builder.recovery), RecoverySettings, AbandonedStartPolicy           (new public)
└── internal/ EngineSagaOperations, ResumeCompensation, MarkResolved, FailAbandonedStart, RecoverySweeper,
              AbandonedStartReports (new); StartCompensation (+byOperator), EngineSagaLifecycle (+4 transitions),
              EventRouter (adoption), EngineRuntime (wiring), SagaInstrumentation, Diagnostics
saga-forge-runtime/src/test/java/.../runtime/
├── support/ TransactionalInMemoryStorageProvider (+query, history rename), StorageProviderQueryContract (new)
└── internal/ EngineSagaOperationsTest, RepairConcurrencyTest, RecoverySweeperTest, StrandedStartTest (new);
              UnitOfWorkBoundaryTest (new works)
saga-forge-storage-postgres/
├── src/main/java/.../ PostgresStorageProvider (+find, countByStatus, history), SagaInstanceRows, LifecycleEntryRows,
│                      JsonCodec (resolution, trigger operator), ForwardTransition (R12)
├── src/main/resources/.../ schema.sql, schema-upgrade-015.sql
└── src/test/java/.../ PostgresStorageProviderQueryContainerTest, OperationsAcceptanceTest, SchemaUpgrade015Test (new),
                       ForwardTransitionTest (F1–F8); src/test/resources/.../schema-before-015.sql
saga-forge-spring-boot-starter/src/main/java/io/github/gkosharovdev/sagaforge/spring/
├── actuator/ SagasEndpoint (new), SagaForgeActuatorConfiguration (sagas endpoint without transport),
│             SagaForgeHealthIndicator (instance details)
├── runtime/  SagaRuntimeConfiguration (recovery settings), SagaRuntimeLifecycle (stop)
├── SagaForgeProperties (+recovery, operations, telemetry.instance-count-interval), a FailureAnalyzer for recovery
└── META-INF/additional-spring-configuration-metadata.json, README.md
saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/
└── SagaFixture (+operations(), Builder.recovery), FixtureStorage (+query, history), InstanceView (+resolution, history),
    Then (+expectResolved, expectNotResolved, expectHistory)
saga-forge-example/ …/OrderProcessingSagaOperationsFixtureTest (new)
saga-forge-example-spring/ application properties (sagas exposure in test), SagasEndpointWebContainerTest (new)
docs/ architecture.md, guide/ (operations.md new or extended, recovery, limitations), agents/, llms-full.txt;
.kiro/specs/saga-forge-framework/design.md (properties); .specify/memory/ memory.md, roadmap.md; AGENTS.md status
```

**Structure Decision**: existing modules only. The contracts live in core so that the starter's endpoint and the
fixture depend on the interface, not on runtime internals. Every implementation type stays package-private in
`runtime.internal`, held to the `UnitOfWork` boundary by the existing ArchUnit rule.

## Implementation Phases (input for `/speckit-tasks`)

1. **Core values and SPI**: `core.operations` records and interface; changed records with backwards constructors;
   `StorageProvider` methods; record tests (`OperationsRecordsTest`: validation, copies, cursor token round trip; trigger
   rules for `OPERATOR_REQUEST`; event id with round).
2. **Storage contract first**: `StorageProviderQueryContract` Q1–Q10 (red), then the runtime double and `FixtureStorage`
   (green); the doubles' forward checks F1–F8.
3. **Postgres**: columns, index, codec, `find`/`countByStatus`/`history`, `ForwardTransition` R12,
   `schema-upgrade-015.sql`, `SchemaUpgrade015Test`; the contract green in the container suite.
4. **Runtime lifecycle and works**: `EngineSagaLifecycle` transitions; `ResumeCompensation`, `MarkResolved`,
   `StartCompensation.byOperator`, `FailAbandonedStart`; `EngineSagaOperations` and `SagaRuntime.operations()`;
   `EngineSagaOperationsTest` (O, R, RC, CP, MR cases) and `RepairConcurrencyTest` (R6).
5. **Stranded start**: `EventRouter` adoption; `StrandedStartTest` ST1–ST3.
6. **Recovery**: `RecoverySettings`, `RecoverySweeper`, `AbandonedStartReports`, `recover()`, `SagaRuntime.stop()`,
   startup validation of settings; `RecoverySweeperTest` SW1–SW10.
7. **Telemetry and diagnostics**: span, counters, gauge with cached counts, log events; telemetry tests with the SDK
   testing exporter.
8. **Fixture**: `operations()`, `recovery(...)`, recovery on clock advance, `InstanceView` and `Then` additions;
   `OperationsFixtureTest`; example `OrderProcessingSagaOperationsFixtureTest`.
9. **Starter**: properties, metadata, README table, `SagasEndpoint`, health details, lifecycle stop, failure analyzer;
   `SagasEndpointTest` E1–E7; Spring example `SagasEndpointWebContainerTest` with Spring Security (test scope).
10. **Acceptance (container)**: `OperationsAcceptanceTest`: ST4, SW5 over Postgres (SC-004), 10,000-instance listing
    (SC-005), the 1,000-saga fault injection (SC-003).
11. **Docs and register**: architecture "Operations and recovery"; guide pages with snippets from compiling code;
    agent skill/snippet; `build-llms-full.sh`; `.kiro` properties (FR-041); memory.md closures and G-015-02..07;
    roadmap 015 status; AGENTS.md "Current Status".
12. **Verification**: `mvn spotless:apply`; `mvn -Pcontainer-tests verify`; `mvn -P release -Dgpg.skip -DskipTests
    install`.

## Complexity Tracking

| Deviation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Incompatible `StorageProvider` SPI change (three abstract methods) | Every storage must answer the query, counts and history for the API, the gauge and the sweeper (FR-009) | Default methods throwing `UnsupportedOperationException` break LSP; there are no external storages to protect (same call as 013) |
| A runtime-owned background thread (`saga-forge-recovery`), plus `SagaRuntime.stop()` | Stalled instances must recover with no event and no operator (FR-022, story 3) | Only `recover()` with host scheduling would leave plain-Java hosts without recovery; the thread is off by default in the runtime and `recover()` stays public for hosts that schedule themselves |
| `SagaOperations` combines reads and writes (ISP) | Each of the three callers uses both | Two interfaces would double the wiring in the fixture and the starter for no caller that needs only one |
| Outcome event id format changes after a resume (`:r<round>`) | One instance can now reach a terminal status twice, and ids must stay unique and deterministic (FR-018) | Changing every id (unit-of-work suffix) would break 011's documented ids for instances never resumed |
