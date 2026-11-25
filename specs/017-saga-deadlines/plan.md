# Implementation Plan: Saga Deadlines

**Branch**: `017-saga-deadlines` | **Date**: 2026-11-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/017-saga-deadlines/spec.md`

## Summary

A saga declares deadlines with annotations: `@StartDeadline` on the step that opens one (fixed duration, `${property}`
or a `SagaContext` attribute; `cancelledBy` steps), and `@DeadlineExpired("name")` on the `@SagaStep` that runs when it
expires (typically also `@StartCompensation`). Underneath:

1. **Message store**: a message may carry `notBefore`, stored as its first `nextAttemptAt`, so the existing leasable rule
   already holds it back (research R1); it has no ordering key (R2) and can be cancelled in a transaction to a new
   `CANCELLED` status (R3).
2. **Outbox**: `OutgoingMessages` gains `scheduled`, `Outbox` gains `cancel(ids)`; the in-memory transport's
   `IN_TRANSACTION` outbox appends and cancels in the unit of work's transaction; `Outbox.supportsScheduling()` gates it
   (R12).
3. **Runtime**: the one `UnitOfWork` (011) starts, replaces and cancels deadlines and keeps `SagaInstance.openDeadlines`
   (R6–R8); a delivered core `DeadlineReached` event (R4) runs the timeout step only if the instance is `RUNNING` and
   the deadline is still open with the same id, else it is ignored with a diagnostic.
4. **CommandGateway.schedule/cancel** for application commands (R14).
5. **Postgres, fixture, starter, telemetry, example and docs** follow (R13, R15–R18).

## Technical Context

**Language/Version**: Java 25 (as the rest of the build)

**Primary Dependencies**: none new. Core stays dependency-free; runtime and transport keep `opentelemetry-api`;
Postgres keeps Jackson 3 and the JDBC driver; starter keeps Spring Boot 4.1 / Spring Modulith.

**Storage**: Postgres — `saga_message_store` gains `not_before timestamptz NULL` and status value `CANCELLED`;
`saga_instance` gains `open_deadlines jsonb NOT NULL DEFAULT '{}'`; `schema.sql` and `schema-upgrade-017.sql`.

**Testing**: JUnit 5 + AssertJ; `MessageStoreContract` (in-memory and Postgres via Testcontainers, `@Tag("container")`);
runtime unit tests over the test-jar doubles; scanner tests per rule; `SagaFixture` tests in the example; starter
`ApplicationContextRunner` tests; jqwik-style randomized interleaving test for SC-002 (the runtime test-jar already has
the harness used by 011's property tests).

**Target Platform**: JVM library

**Project Type**: multi-module Maven library

**Performance Goals**: SC-003 delivery ≤ due time + one poll interval; SC-004 10,000 overdue deadlines after restart
(container suite, alongside 007's 10k restart test).

**Constraints**: Principle I (core: plain values and SPI defaults only); one running instance per message store (D8).

**Scale/Scope**: ~8 modules touched; no new module.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Check | Result |
|-----------|-------|--------|
| I. Zero-Dependency Core | Core gains annotations, records (`DeadlineReached`, `OpenDeadline`, `ScheduledCommandId`, `ScheduledMessage`), an enum value and SPI default methods; no dependency. | Pass |
| II. Annotation-Driven, Declarative | Deadlines are `@StartDeadline` / `@DeadlineExpired`; no imperative scheduling call is needed in a saga. `CommandGateway.schedule` is a gateway method like `send`, not saga orchestration. | Pass |
| III. Formal Spec Conformance | Rules R24–R29 and Properties 40–43 are added to `.kiro/specs/saga-forge-framework/design.md`; Properties 10/11 amended to scope them to event steps. Each gets a test. | Pass (task) |
| IV. Explicit, Narrow Scope | No new storage or transport; Modulith rejected explicitly; cron and durable backoff out. | Pass |
| V. Documentation Hierarchy | README, `docs/use-cases.md` §1, `docs/architecture.md`, `docs/guide/`, agent skill/snippet, `llms-full.txt`, memory.md updated by tasks. | Pass (task) |
| Development Workflow: composition, tested abstractions, known vocabulary | No new class hierarchy. New SPI surface is methods on existing SPIs (`MessageStore.cancel`, `supportsScheduling`), each with two implementations and a contract test. Vocabulary: Deadline, Schedule, Outbox, Cancel — common (Axon `DeadlineManager`, Temporal timers, MassTransit `Schedule`). | Pass |

AGENTS.md asks to propose the `effective-java`, `clean-code-java` and `solid-design-review` skills before class-level
design: proposed here for `/speckit-implement`; the data model below already follows them (immutable records with
validating constructors, defaults for SPI evolution, no boolean-trap parameters).

Post-design re-check: Pass. One deliberate amendment: `@SagaStep.associationProperty` gains `default ""` (R5), a
source-compatible change; the scanner keeps the old rule for event steps.

## Project Structure

### Documentation (this feature)

```text
specs/017-saga-deadlines/
├── plan.md              # this file
├── research.md          # R1–R18 decisions, gaps G-017-01..05
├── data-model.md        # types, fields, validation, state transitions
├── quickstart.md        # validation scenarios + example saga snippets
├── contracts/
│   ├── message-store-scheduling.md
│   ├── outbox-and-gateway.md
│   ├── deadline-annotations.md   # scanner rules R24–R29, Properties 40–43
│   ├── runtime-deadlines.md
│   └── fixture.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/
├── annotation/StartDeadline.java, StartDeadlines.java (repeatable container), DeadlineExpired.java, SagaStep.java (default "")
├── deadline/DeadlineReached.java, OpenDeadline.java, DeadlineDurationException.java, package-info.java
├── command/CommandGateway.java (+schedule/cancel), ScheduledCommandId.java
├── compensation/CompensationTriggerKind.java (+DEADLINE_EXPIRED), CompensationTrigger.java (+deadlineName)
├── outcome/OutcomeTrigger.java (+deadlineName)
├── scanner/ReflectiveAnnotationScanner.java (R24–R29)
└── spi/ Outbox.java (+supportsScheduling, cancel), OutgoingMessages.java (+scheduled), ScheduledMessage.java,
         SagaInstance.java (+openDeadlines), store/{NewMessage (+notBefore), StoredMessage (+notBefore),
         MessageStatus (+CANCELLED), MessageStore (+cancel, supportsScheduling)}
saga-forge-runtime/src/main/java/.../runtime/
├── SagaRuntime.java (Builder.propertyResolver; startup check)
└── internal/ DeadlinePlan (per-UoW start/cancel set), DeadlineModel (resolved declarations), DeadlineDelivery
             (routes DeadlineReached), UnitOfWork + SagaCommandGateway changes, SagaInstrumentation
saga-forge-transport-inmemory/  outbox appends/cancels; relay unchanged except telemetry; test-jar InMemoryMessageStore,
                                 MessageStoreContract (+scheduling cases)
saga-forge-storage-postgres/    PostgresMessageStore, PostgresStorageProvider (open_deadlines), schema.sql,
                                 schema-upgrade-017.sql
saga-forge-spring-boot-starter/ ModulithMessageStore (supportsScheduling=false), DeadlinesRequireOwnLogFailureAnalyzer,
                                 Environment-backed propertyResolver, shared Clock bean, README
saga-forge-test/                in-memory store scheduling, When.timeElapses, Then deadline expectations
saga-forge-example/             checkout: CheckoutSaga paymentWindow, FulfilmentSaga courierAssignment, fixture tests
saga-forge-example-spring/      courier deadline property in application.yml (keeps step with the example)
docs/                           README, use-cases §1, architecture, guide/deadlines.md, agents, llms-full.txt
```

**Structure Decision**: existing modules only; deadline runtime logic stays package-private in `runtime.internal`
behind `UnitOfWork` (ArchUnit `UnitOfWorkBoundaryTest`).

## Implementation Phases (input for `/speckit-tasks`)

1. **Core contracts** (no behaviour): records, enum values, annotations, SPI defaults, trigger fields.
2. **Message store**: in-memory + Postgres scheduling, `MessageStoreContract` cases, schema + upgrade script.
3. **Scanner**: R24–R29, amended Properties 10/11, design.md text.
4. **Runtime**: deadline model resolution at startup, startup capability check, `UnitOfWork` start/replace/cancel,
   `DeadlineReached` routing and ignore path, triggers, `CommandGateway.schedule/cancel`, telemetry.
5. **Transport**: outbox appends `scheduled` and implements `cancel`; acceptance suite cases; restart test (SC-001, SC-004).
6. **Fixture**: `timeElapses`, expectations, parity test with a hand-assembled runtime.
7. **Starter**: property resolver, shared clock, Modulith rejection + failure analyzer, README/metadata.
8. **Examples**: `CheckoutSaga` payment window, `FulfilmentSaga` courier deadline, fixture tests advancing
   `FixtureClock`; Spring example property — **explicit task per the user's request**.
9. **Docs**: README (deadline snippet in the checkout story), `docs/use-cases.md` §1, `docs/architecture.md` gaps,
   `docs/guide/deadlines.md` + `limitations.md`, `docs/agents/saga-forge/SKILL.md`, `AGENTS-snippet.md`,
   `build-llms-full.sh`; memory.md (close G-ENG-25, add G-017-01..05); roadmap 017 status; AGENTS.md status line —
   **explicit task per the user's request**.
10. **Verification**: whole build with `-Pcontainer-tests`; `mvn -P release -Dgpg.skip -DskipTests install` (Javadoc).

## Complexity Tracking

| Deviation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| `@SagaStep.associationProperty` default `""` | A timeout step has no event to correlate by | A dummy association on timeout steps would be misleading and validated against nothing |
