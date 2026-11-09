# Implementation Plan: Saga Compensation

**Branch**: `006-saga-compensation` | **Date**: 2026-11-09 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-saga-compensation/spec.md`

## Summary

This plan makes `@SagaCompensation` run. A saga author marks the step that handles a business-failure event (for
example, delivery rejected) with the new `@StartCompensation`, next to `@SagaStep`. Committing that step moves the
instance to `COMPENSATING`. A failure that 005 classifies `FINAL` or `EXHAUSTED` does the same, as long as at least one
step had committed.

The engine then undoes the steps whose units of work committed:
- **Order**: newest first, by a new per-instance `completionSequence` on `StepRecord`.
- **Isolation**: each compensation runs in its own saga-scoped unit of work, in the same handling call and under the
  instance lease the `AttemptLoop` already holds.
- **Durable marks**: each step's `COMPENSATED` mark commits with the compensation's effects, so a crash resumes at the
  next step and never repeats one. Any later event for a `COMPENSATING` instance resumes the run.
- **Outcomes**: the saga ends `COMPENSATED`, or `REQUIRES_INTERVENTION` when a compensation fails. A failed
  compensation is never retried.
- **Compensation inputs**: a compensation may take a persisted `CompensationTrigger`, and it uses the saga's
  `SagaContext` and `CommandGateway` like a step. Its commands get 005's stable identities.
- **Storage**: Postgres gains a trigger column, step JSON fields, three new `ForwardTransition` checks and an upgrade
  script.

**Key decisions** (research.md; open to review before `/speckit-tasks`):
1. **Failure outcome**: `@StartCompensation` is a marker co-occurring with `@SagaStep`, and its method name is the
   outcome (§1). Scanner rules R20 to R22 become Properties 30 to 32 (§2).
2. **Statuses**: new terminal statuses `COMPENSATED` and `REQUIRES_INTERVENTION`. `COMPENSATING` is used for the first
   time, and `outcome` and `failure` keep their meaning (§3).
3. **Trigger, not the event**: a compensation receives the persisted trigger, not the event object, so it behaves the
   same after a restart. Event data goes through `SagaContext` (§4, gap G-006-06).
4. **Order**: reverse completion order comes from a per-instance `completionSequence`. The triggering step is
   excluded, and a final failure with nothing committed stays `FAILED` (§5).
5. **Transactions**: each compensation runs in its own transaction in the triggering handling call, under the held
   lease, and the last commit also writes the terminal status (§7).
6. **Resume**: any event for a `COMPENSATING` instance resumes compensation instead of running its step. This is
   checked before 005's duplicate check, and it refines the spec's "late forward events" edge case (§10).
7. **No retry**: a failed compensation is classified but never retried, and the saga goes straight to
   `REQUIRES_INTERVENTION` (§9, gap G-006-09).

## Technical Context

**Language/Version**: Java 21 (root `pom.xml`, unchanged).

**Primary Dependencies**: none new. Core stays dependency-free and the engine depends on core only. Postgres keeps
its JDBC driver and `tools.jackson.core:jackson-databind`.

**Storage**: Postgres through `saga-forge-storage-postgres`, which gains a `compensation_trigger` column on two
tables, step JSON fields and `schema-upgrade-006.sql`. The engine test-jar has in-memory doubles. MySQL and MongoDB
remain placeholders.

**Testing**: JUnit 5.11, AssertJ 3.27 and jqwik 1.9. jqwik covers the property tests for selection order (Property 33)
and resume (Property 34). The Testcontainers suites are fenced by `@Tag("container")` and `-Pcontainer-tests`.

**Target Platform**: JVM library, for any host: plain Java now, the Spring Boot starter later.

**Project Type**: multi-module Maven library.

**Performance Goals**: no extra statement on the success path. Compensation costs one transaction per selected step
with a compensation, plus the start write for technical failures. The terminal status rides on the last compensation
commit.

**Constraints**:
- Compensations run on the caller's thread and hold the in-process lease for the whole sequence (G-006-08).
- There is no scheduler; a stranded `COMPENSATING` instance waits for its next event (G-006-05).
- The 004 handoff timings apply to each compensation unit of work.

**Scale/Scope**:
- `saga-forge-core`: about 7 new or changed types, plus 3 scanner rules.
- `saga-forge-engine`: unit of work, attempt loop, lifecycle and model, plus about 4 new internal types.
- `saga-forge-storage-postgres`: rows, codec, forward transition and schema.
- Also `saga-forge-example`, the docs and the `.kiro` design.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Result |
|---|---|---|
| I. Zero-Dependency Core | The new core types (`StartCompensation`, `core.compensation.*`, `StepCompensation`, `CompensationStatus`) use the JDK only. Trigger JSON lives in Postgres. | Pass |
| II. Annotation-Driven, Declarative | Business failure is declared with a framework-owned marker, and compensation stays `@SagaCompensation(forStep)`. The author writes no orchestration code (SC-005). Commands are dispatched through `CommandGateway`, never returned. | Pass |
| III. Formal Spec Conformance | `.kiro/specs/saga-forge-framework/design.md` gains `@StartCompensation`, R20 to R22 and Properties 30 to 34 before implementation (first task). Properties 1 to 29 are unchanged. The Speckit flow is followed. | Pass (gated by the first task) |
| IV. Explicit, Narrow Scope | No storage or transport is added. Only Postgres is updated. Out-of-scope items (FR-021) are recorded as gaps. | Pass |
| V. Documentation Hierarchy | README, `docs/architecture.md` (feature gap "Failure categorization and automatic compensation dispatch"), `docs/use-cases.md` and `AGENTS.md` are updated to the implemented state. Vocabulary is unchanged: Compensation and Saga are already defined. | Pass |

Post-design re-check (after Phase 1): unchanged. No design artifact introduces a dependency, storage, transport or
imperative orchestration. The refinement of the "late forward events" edge case is a spec change made with this plan.

## Project Structure

### Documentation (this feature)

```text
specs/006-saga-compensation/
├── spec.md
├── plan.md              # this file
├── research.md          # §1–§14 decisions
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── declaring-compensation.md
│   ├── compensation-run.md
│   └── storage-delta.md
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
.kiro/specs/saga-forge-framework/design.md        # @StartCompensation, R20–R22, Properties 30–34 (first)

saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/
├── annotation/StartCompensation.java             # new
├── compensation/CompensationTrigger.java         # new
├── compensation/CompensationTriggerKind.java     # new
├── compensation/package-info.java                # new
├── scanner/ReflectiveAnnotationScanner.java      # R20, R21, R22
└── spi/
    ├── SagaStatus.java                           # + COMPENSATED, REQUIRES_INTERVENTION
    ├── HandlingOutcome.java                      # + COMPENSATED, REQUIRES_INTERVENTION
    ├── StepRecord.java                           # + completionSequence, compensation
    ├── StepCompensation.java                     # new
    ├── CompensationStatus.java                   # new
    ├── SagaInstance.java                         # + compensationTrigger, invariants
    ├── SagaLifecycleEventKind.java               # + 5 kinds
    └── SagaLifecycleEntry.java                   # + trigger

saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/internal/
├── SagaModel.java                                # startsCompensation, Compensation, compensationFor, run
├── EngineSagaLifecycle.java                      # sequence; startCompensation, recordCompensated, recordCompensationFailed; isTerminal
├── SagaUnitOfWork.java                           # @StartCompensation as end; Compensating result before duplicate check
├── AttemptLoop.java                              # FINAL/EXHAUSTED → CompensationStartWrite; run remaining
├── CompensationStartWrite.java                   # new
├── CompensationRun.java                          # new
├── CompensationUnitOfWork.java                   # new
├── CompensationFailedWrite.java                  # new
└── Diagnostics.java                              # saga.compensation.*

saga-forge-storage-postgres/src/main/
├── java/.../storage/postgres/{SagaInstanceRows,LifecycleEntryRows,ForwardTransition,PostgresStorageProvider}.java
└── resources/.../storage/postgres/{schema.sql,schema-upgrade-006.sql}

saga-forge-example/src/main/java/.../example/
├── OrderProcessingSaga.java                      # rejectDelivery, compensatePayment(CompensationTrigger)
├── PaymentService.java, PaymentProvider.java     # refund, idempotent by CommandId
├── commands/RefundPaymentCommand.java            # new
└── events/DeliveryRejectedEvent.java             # new

tests: matching *Test classes under each module's src/test; engine fixtures/compensation/* (three-step saga,
fault script); CompensationFaultInjectionTest (SC-002, SC-003); Postgres CompensationPersistenceTest and container
additions to SchemaTest / PostgresStorageProviderTest.
```

**Structure Decision**: the existing module layout is kept and no module is added. The public engine surface is
unchanged. Everything new in the engine stays in `engine.internal`, and the new user-facing types are in core.

## Phasing (input to `/speckit-tasks`)

1. **Foundation**:
   - the `.kiro` update;
   - the core types and record changes, with compatibility constructors;
   - scanner rules R20 to R22;
   - `completionSequence` assigned by the lifecycle;
   - engine test-jar doubles and existing test projections compiling against the new records.
2. **US1 and US2 (P1)**:
   - `@StartCompensation` handling in the unit of work;
   - `startCompensation`, `CompensationRun` and `CompensationUnitOfWork`;
   - reverse-order selection and `NOTHING_TO_COMPENSATE`;
   - `DUPLICATE` after the end.
3. **US3 (P2)**: `CompensationStartWrite` from the `AttemptLoop` for `FINAL`/`EXHAUSTED`, including the "exhausted on
   resume" path; `UNCLASSIFIED` stays `FAILED`.
4. **US4 (P2)**: `CompensationFailedWrite`, `REQUIRES_INTERVENTION` and no retry.
5. **US5 (P3)**: the `Compensating` result before the duplicate check, resuming from any event, and the fault-injection
   suite.
6. **Postgres**:
   - rows and codec;
   - constraint and forward transition;
   - `schema-upgrade-006.sql`;
   - the container suites, which the user runs.
7. **Example, docs, gap register**: the example's refund on rejection; README, architecture, use-cases and AGENTS;
   memory.md gaps G-006-05 to G-006-09.

## Complexity Tracking

No constitution violations to justify.
