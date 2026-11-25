# Implementation Plan: Outbox Command Failure Feedback

**Branch**: `012-outbox-command-failure-feedback` | **Date**: 2026-11-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/012-outbox-command-failure-feedback/spec.md`

## Summary

An outbox command sent by a saga that fails for good (`FINAL`, or `EXHAUSTED` once the relay's attempts are used up)
is reported back to the sending instance, and the instance compensates. Today the relay sets the message aside and the
saga never hears of it.

The work lands in four phases. Each phase leaves the build green.

1. **Core vocabulary** (additive). `core.spi.CommandFailure` (the report, no message text);
   `FailureClassification.of(Throwable)` (the marker rule, shared by runtime and transport);
   `CommandReceiver.receiveFailure(CommandFailure, Consumer<LocalTransaction>)`; `MessageStore.setAside(LocalTransaction,
   ...)`; `CompensationTriggerKind.COMMAND_FAILED` with a nullable `commandType` on `CompensationTrigger` and
   `OutcomeTrigger`, old constructors kept.
2. **Runtime reaction.** `CommandFailureHandler` (orchestrator, like `AttemptLoop`) takes the instance lease and runs,
   in the one `UnitOfWork`:
   - for a command sent by a step: `StartCompensation` with a `COMMAND_FAILED` trigger, then the existing
     `CompensationRun`;
   - for a command sent by a compensation: the new work `RecordCompensationCommandFailure`, which ends a
     `COMPENSATING` instance `COMPENSATION_FAILED`;
   - otherwise a diagnostic and a standalone `HandleCommand` that only sets the message aside.

   The set-aside runs in the reaction's transaction. Postgres `ForwardTransition` and the in-memory storages accept
   the one new move (a step's compensation `COMPENSATED → FAILED` while the instance goes `COMPENSATING →
   COMPENSATION_FAILED`).
3. **Transport.** The relay classifies a failed outbox command: `FINAL` → no retry, report, set aside
   `FINAL_FAILURE`; otherwise retry with its own settings and, after `maxAttempts`, report as `EXHAUSTED` and set aside
   `MAX_ATTEMPTS`. Commands outside a saga keep today's behaviour. Telemetry attributes and a counter.
4. **Fixture, examples, docs.** `LoopBackTransport` emulates classification and attempts
   (`SagaFixture.Builder.outboxCommandAttempts`, default 1) and calls `receiveFailure`; the canonical example shows a
   failing outbox command compensating; the guide, agent skill and formal design follow.

Details and alternatives: [research.md](research.md).

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: none added. Core stays dependency-free; the transport keeps depending on core and
`opentelemetry-api` only.

**Storage**: Postgres. The trigger is JSONB, so no column changes; `saga_message_store` gains no column (the set-aside
reason is a value). `ForwardTransition` gains one guarded move. No upgrade script is expected; if task work finds one
is needed it is `schema-upgrade-012.sql`.

**Testing**:
- JUnit 5 and AssertJ unit suites (runtime, transport, core, fixture);
- the transport's `TransportAcceptanceSuite` and `MessageStoreContract` gain the failure cases, run against the
  in-memory store and, under `-Pcontainer-tests`, Postgres;
- `SagaFixture` tests in both examples;
- `UnitOfWorkBoundaryTest` (ArchUnit) keeps every write in `UnitOfWork`.

**Target Platform**: JVM library (Maven Central).

**Project Type**: multi-module library.

**Performance Goals**: none new. A `FINAL` failure costs one handler attempt (SC-004); the reaction is one unit of
work plus one per compensation, as for a step's own failure.

**Constraints**: released API (DEC-REL-01): every public change is additive; existing constructors and SPI methods
keep their behaviour. The transport never writes saga state (FR-007).

**Scale/Scope**: six modules touched (core, runtime, transport-inmemory, storage-postgres, test, starter only if the
telemetry or a property needs wiring) plus the examples and docs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Rule | Check | Status |
|------|-------|--------|
| I Zero-Dependency Core | `CommandFailure`, `FailureClassification.of`, the trigger changes and the SPI methods are JDK-only. | Pass |
| II Annotation-Driven Declarative Style | No new annotation; the reaction is the framework default (FR-014). Applications change nothing to get it. | Pass |
| III Formal Spec Conformance | `.kiro/specs/saga-forge-framework/design.md` gains Property 39 (a final or exhausted outbox command of a live, non-compensating instance starts compensation exactly once) and the trigger kind; no scanner rule changes. | Pass, with task |
| IV Explicit Narrow Scope | In-memory transport and Postgres only; Kafka repeats the contract later (memory.md, roadmap). No step-level commit boundary, no success notification. | Pass |
| V Documentation Hierarchy | `docs/architecture.md` feature gaps, `docs/guide/` (failure handling page), `docs/agents/`, `llms-full.txt` updated; AGENTS.md status line. | Pass, with task |
| Composition over inheritance | `StartCompensation` takes its trigger as a constructor argument; `CommandFailureHandler` holds `InstanceLocks`, `UnitOfWork` and `CompensationRun`. No new class hierarchy. | Pass |
| Test abstractions before proposing them | New abstractions: `CommandReceiver.receiveFailure` (caller: relay and fixture; tested with `TestTransport` and the fixture), `MessageStore.setAside(tx, ...)` (caller: relay; `MessageStoreContract`), `CommandFailure` (value, unit-tested), `RecordCompensationCommandFailure` and `CommandFailureHandler` (runtime unit tests over in-memory doubles). Each gets its test in the same task as its first caller. | Pass |
| Intention-revealing, familiar abstractions | See the familiarity table below. | Pass |

### Familiarity of the new abstractions

| Abstraction | Community counterpart | Rating |
|-------------|----------------------|--------|
| `CommandFailure` | MassTransit `Fault<T>`, NServiceBus failed-message notification; the repo's `DeliveryFailure`, `SagaFailure` | High |
| `CommandReceiver.receiveFailure` | the receive side of a message endpoint (Hohpe/Woolf); dead-letter notification | High |
| `MessageStore.setAside(tx, ...)` | dead-letter / poison-message queue, transactional outbox | High |
| `FailureClassification.of` | retryable vs. non-retryable exception classification (Spring Retry, Resilience4j) | High |
| `CommandFailureHandler` | `...Handler` (message handler); same shape as the repo's `AttemptLoop` | High |
| `RecordCompensationCommandFailure` | verb-object work name, as 011's works | Medium (repo-specific, but self-describing) |
| `CompensationTriggerKind.COMMAND_FAILED` | extends the repo's own enum | High |

No boutique abstraction is introduced.

## Project Structure

### Documentation (this feature)

```text
specs/012-outbox-command-failure-feedback/
├── plan.md              # This file
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   ├── command-receiver-failure.md
│   ├── relay-classification.md
│   ├── runtime-reaction.md
│   └── fixture.md
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/
├── failure/FailureClassification.java           # + of(Throwable)
├── compensation/CompensationTrigger.java        # + commandType, commandFailed(...)
├── compensation/CompensationTriggerKind.java    # + COMMAND_FAILED
├── outcome/OutcomeTrigger.java                  # + commandType
└── spi/
    ├── CommandFailure.java                      # new
    ├── CommandReceiver.java                     # + receiveFailure(...)
    └── store/MessageStore.java                  # + setAside(LocalTransaction, ...)

saga-forge-runtime/src/main/java/io/github/gkosharovdev/sagaforge/runtime/internal/
├── CommandFailureHandler.java                   # new orchestrator
├── RecordCompensationCommandFailure.java        # new work
├── StartCompensation.java                       # trigger as argument, ALREADY_COMPENSATING
├── EngineSagaLifecycle.java                     # + recordCompensationCommandFailed(...)
├── FailureClassifier.java                       # delegates markers to core
└── EngineRuntime.java                           # CommandReceiver.receiveFailure -> CommandFailureHandler

saga-forge-transport-inmemory/src/main/java/.../transport/inmemory/
├── internal/Relay.java                          # classify, report, set aside in tx
└── internal/TransportInstrumentation.java       # classification attribute, failures counter

saga-forge-storage-postgres/src/main/java/.../storage/postgres/
├── ForwardTransition.java                       # guarded COMPENSATED -> FAILED
├── PostgresMessageStore.java                    # setAside(tx, ...)
└── JsonCodec.java                               # commandType on the trigger

saga-forge-test/src/main/java/io/github/gkosharovdev/sagaforge/test/
├── LoopBackTransport.java                       # classify, attempts, receiveFailure
└── SagaFixture.java                             # Builder.outboxCommandAttempts(int)

saga-forge-example/ and saga-forge-example-spring/  # a fixture test of a failing outbox command
docs/guide/, docs/agents/, docs/architecture.md, .kiro/specs/saga-forge-framework/design.md
```

**Structure Decision**: the existing multi-module layout; no new module.

## Constitution Check (post-design)

Re-checked after Phase 1: the data model and contracts add no dependency to core, no annotation, no hierarchy and no
abstraction without a named caller and test. Gate passes.

## Complexity Tracking

No violations to justify.
