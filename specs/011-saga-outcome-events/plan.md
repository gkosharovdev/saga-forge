# Implementation Plan: Saga Outcome Events

**Branch**: `011-saga-outcome-events` | **Date**: 2026-11-20 (revised the same day) | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/011-saga-outcome-events/spec.md`

## Summary

The work lands in three phases. Each phase leaves the build green.

1. **One unit of work** (refactoring, no behaviour change). The runtime's seven transaction-owning classes become one
   `UnitOfWork`:
   - the three phase-specific units of work: `SagaUnitOfWork`, `CompensationUnitOfWork`, `NonSagaUnitOfWork`;
   - the four status writes: `FailedWrite`, `AttemptWrite`, `CompensationStartWrite`, `CompensationFailedWrite`.

   The `UnitOfWork` owns the transaction, the instance lock and write, recording, the outgoing batch and the outbox
   timing, and does not know which phase it serves. What runs inside is a `Work` named by what it does: `RunSteps`,
   `RunCompensation`, `HandleCommand`, `RecordFailure`, `ClaimAttempt`, `StartCompensation`,
   `RecordCompensationFailure`. `AttemptLoop`, `CompensationRun` and `EventRouter` keep deciding what runs next. The
   existing suites, unchanged, prove the refactoring changed nothing, and an ArchUnit rule keeps the boundary in one
   class.

2. **`REQUIRES_INTERVENTION` → `COMPENSATION_FAILED`.** The rename covers the status, the handling outcome and the
   telemetry value. The redundant history kind is removed. Postgres gets `schema-upgrade-011.sql`, and the docs and
   the formal design follow.

3. **Outcome events.** The lifecycle creates `SagaCompleted`, `SagaFailed` (phase `STEP` or `COMPENSATION`) or
   `SagaCompensated` at each terminal transition. The unit of work appends it as the last event of its batch, so every
   terminal write, including the `FAILED` write after a rollback, hands it to the outbox in its own transaction.
   - The router treats outcome events as "no saga reacted", and the in-memory transport orders them per instance.
   - The starter publishes them to Spring like recorded events.
   - The fixture gains outcome-event expectations.
   - Outcome events can be switched off (`outcomeEvents(false)`, `saga-forge.outcome-events.enabled`).

Failure-text cleaning was dropped from this feature (spec Clarifications; G-ADOPT-03 stays open). Details:
[research.md](research.md).

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**:
- no runtime dependency is added;
- ArchUnit (`com.tngtech.archunit:archunit-junit5`) at test scope in `saga-forge-runtime`, for the boundary rule (an
  acknowledged candidate in G-GOV-01).

**Storage**: Postgres. `schema-upgrade-011.sql` and an updated `schema.sql` carry the rename. Outcome events are
stored as messages with no schema change.

**Testing**:
- JUnit 5 and AssertJ unit suites;
- Testcontainers suites under `-Pcontainer-tests`;
- `SagaFixture` tests in both examples;
- ArchUnit for rule U1.

**Target Platform**: JVM library (Maven Central).

**Project Type**: multi-module library.

**Performance Goals**: none new. The refactoring keeps the number of transactions and storage calls per handling
identical. An outcome event adds one message per instance lifetime.

**Constraints**:
- phase 1 is behaviour-preserving (G1 to G4 in [quickstart.md](quickstart.md));
- core stays JDK-only;
- the rename is a breaking change of public enum values and one telemetry value, allowed for 0.x (DEC-REL-01) and
  called out in the release notes with the upgrade script.

**Scale/Scope**:
- runtime: 7 classes removed, about 10 added or rewritten;
- core: 6 new types, 3 enums renamed;
- Postgres: 2 SQL files and `ForwardTransition`;
- transport: 1 class;
- starter: properties and README;
- fixture: 4 classes;
- docs, examples and the `.kiro` design.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Check | Result |
|---|---|---|
| I. Zero-dependency core | `core.outcome` types are JDK records, enums and a sealed interface. ArchUnit is test scope in the runtime. Spring wiring stays in the starter. | Pass |
| II. Annotation-driven style | Outcome events need no annotation (FR-016). No callback annotation. Users write no orchestration code. | Pass |
| III. Formal spec conformance | Property 38 added; Properties 27 and 30 to 34 restated for the rename; no scanner rule changes (a test pins that outcome types are rejected as step parameters). Speckit flow followed. | Pass |
| IV. Explicit, narrow scope | No storage or transport added. No saga consumption of outcome events (G-011-01). No failure-text cleaning (G-ADOPT-03). | Pass |
| V. Documentation hierarchy | README, architecture, use cases, guide, skill, snippet, `llms-full.txt`, starter README and AGENTS.md follow the rename and the new events. "Outcome event" is documented as a framework event, not a Domain Event. | Pass |

**Post-design re-check**: Pass. The unit-of-work refactoring stays inside `runtime.internal`, which carries no
compatibility promise (G-REL-02), so the public surface changes only where the spec says it does.

## Project Structure

### Documentation (this feature)

```text
specs/011-saga-outcome-events/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── unit-of-work.md
│   ├── outcome-events.md
│   └── builders-and-properties.md
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/
├── outcome/            # NEW: SagaOutcome, SagaCompleted, SagaFailed, SagaCompensated, OutcomeTrigger,
│                       #      FailurePhase, package-info                                          (phase 3)
└── spi/                # SagaStatus, HandlingOutcome, SagaLifecycleEventKind renamed; SagaInstance,
                        # SagaHandlingResult validation (phase 2); Outbox, EventReceiver, OutgoingMessages
                        # Javadoc (phase 3)

saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime/
├── SagaRuntime.java    # Builder.outcomeEvents                                                     (phase 3)
└── internal/
    ├── UnitOfWork.java           # class replacing the interface                                    (phase 1)
    ├── WorkContext.java          # NEW                                                              (phase 1)
    ├── RunSteps.java, RunCompensation.java, HandleCommand.java,                                     (phase 1)
    │   RecordFailure.java, ClaimAttempt.java, StartCompensation.java, RecordCompensationFailure.java
    ├── (removed) SagaUnitOfWork, CompensationUnitOfWork, NonSagaUnitOfWork,                         (phase 1)
    │   FailedWrite, AttemptWrite, CompensationStartWrite, CompensationFailedWrite
    ├── AttemptLoop.java, CompensationRun.java, EngineCommandGateway.java, EngineRuntime.java         (phase 1)
    ├── EngineSagaLifecycle.java  # rename (2); PendingWrite.outcome (3)
    ├── EventRouter.java          # rename (2); SagaOutcome → empty report (3)
    └── EngineSettings.java       # outcomeEvents (3)
saga-forge-runtime/src/test/java/.../runtime/UnitOfWorkBoundaryTest.java                              (phase 1)

saga-forge-storage-postgres/src/main/
├── java/.../storage/postgres/ForwardTransition.java                                                  (phase 2)
└── resources/.../storage/postgres/schema.sql, schema-upgrade-011.sql (NEW)                           (phase 2)

saga-forge-transport-inmemory/src/main/java/.../transport/inmemory/internal/OrderingKeys.java         (phase 3)

saga-forge-spring-boot-starter/                    # outcome-events.enabled property, README, metadata (phase 3)
saga-forge-test/src/main/java/.../test/            # SagaFixture.Builder.outcomeEvents, LoopBackTransport,
                                                   # EventResult, Then; InstanceView Javadoc         (phases 2, 3)
saga-forge-example/, saga-forge-example-spring/    # fixture assertions; Spring listener on SagaFailed (phase 3)
.kiro/specs/saga-forge-framework/design.md         # Properties 27, 30 to 34 (2); 38 (3)
README.md, docs/architecture.md, docs/use-cases.md, docs/guide/, docs/agents/, AGENTS.md, .specify/memory/memory.md
```

**Structure Decision**: existing modules only. The refactoring stays inside `runtime.internal`. Public additions sit
in the module that owns the behaviour.

## Phase Outputs

- Phase 0: [research.md](research.md). There is no remaining NEEDS CLARIFICATION.
- Phase 1: [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md).

## Risks

| Risk | Mitigation |
|---|---|
| The refactoring changes behaviour subtly: outbox timing, conflict versus failure, the result of a work that ran nothing | phase gate G1 to G4 on its own commit before phases 2 and 3; existing suites untouched in that commit; unit-of-work contract U4 keeps diagnostic and span names |
| `RunSteps` admission rules (resume a claimed retry, the compensating hand-over) lose an edge case | they move verbatim from `SagaUnitOfWork.lockInstance`; `AttemptLoop` tests cover each result |
| The rename breaks hosts that store or switch on `REQUIRES_INTERVENTION` | release notes, upgrade script, compile errors for Java users (an enum constant disappears rather than changing meaning) |
| A host `EventDispatcher` or outbox rejects unknown event classes | `outcomeEvents(false)` |
| `PayloadCodec` cannot decode a nested record or enum in an outcome type | container round-trip per type |

## Complexity Tracking

No constitution violations to justify.
