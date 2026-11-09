# Implementation Plan: Step Retry and Idempotent Command Processing

**Branch**: `005-step-retry` | **Date**: 2026-11-09 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-step-retry/spec.md`

## Summary

Make running a step again safe, then run steps again. Every command a saga step dispatches gets a deterministic
`CommandId` (name-based UUID over instance, step, command type and per-type position), which a `@CommandHandler`
receives as a parameter through a built-in `HandlerParameterResolver` and passes to outside systems as their
idempotency key. Committed step records list the identities they handled, so a command is never handled twice, and an
event for a step already `COMPLETED` is skipped as `DUPLICATE`. On top of that, `@SagaStep` gains a framework-owned
`@RetryPolicy` (attempts, backoff, `retryOn`); failures are classified through core marker interfaces into
`TRANSIENT`, `FINAL`, `EXHAUSTED` or `UNCLASSIFIED`; a new engine `AttemptLoop` holds the instance lease, re-runs the
whole unit of work after an in-process, bounded, interruptible pause, and claims each next attempt in its own write
(`SagaInstance.pendingRetry`) before waiting so restarts never exceed the limit. Postgres persists the new fields with
three new columns and JSONB extensions, plus an upgrade script.

**Key decisions** (research.md; open to review before `/speckit-tasks`):
1. `CommandId` is a name-based UUID, supplied as a handler parameter, not a field on commands (§1, §2).
2. Handled identities live in `StepRecord.handledCommands`, written with the instance; no new table or SPI (§3).
3. Retry policy is a nested `@RetryPolicy` attribute of `@SagaStep`; `.kiro` design updated first (§6).
4. Total backoff bounded by an engine setting, default 30 s, checked at startup (§7).
5. The attempt count is claimed before each wait on `SagaInstance.pendingRetry`, keyed by trigger step (§9).
6. Shutdown during a wait is thread interruption → `NOT_HANDLED`; no new engine `stop()` (§10).
7. Duplicates and skipped commands are logged and reported, not written to lifecycle history (§11; FR-018 narrowed).

## Technical Context

**Language/Version**: Java 21 (root `pom.xml`, unchanged).

**Primary Dependencies**: none new. Core stays dependency-free; the engine depends on core only; Postgres keeps its
003 dependencies (JDBC driver, `tools.jackson.core:jackson-databind`).

**Storage**: Postgres via `saga-forge-storage-postgres` (new columns and JSONB fields, `schema-upgrade-005.sql`);
in-memory test doubles in the engine test-jar. MySQL/MongoDB remain placeholders.

**Testing**: JUnit 5.11, AssertJ 3.27, jqwik 1.9 (property tests for `CommandId` stability, backoff arithmetic and
classification); Testcontainers suites fenced by `@Tag("container")` / `-Pcontainer-tests`.

**Target Platform**: JVM library, any host (plain Java, later Spring Boot starter).

**Project Type**: multi-module Maven library.

**Performance Goals**: no extra statement on the success path except the already-existing instance write (handled
identities ride in it); one extra transaction per failed attempt that will be retried.

**Constraints**: total retry wait per event `<=` `maxTotalRetryBackoff` (default 30 s); in-process waiting only;
retries never change the 004 rule of one unit of work per instance.

**Scale/Scope**: touches `saga-forge-core` (≈8 new/changed types), `saga-forge-engine` (gateway, unit of work, router,
startup checks, ≈5 new internal types), `saga-forge-storage-postgres` (codec, rows, forward transition, schema),
`saga-forge-example`, docs and `.kiro` design.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Result |
|---|---|---|
| I. Zero-Dependency Core | New core types (`CommandId`, `core.failure.*`, `RetryPolicy`, `PendingRetry`) use the JDK only; Jackson stays in Postgres; no Spring/transport types. | Pass |
| II. Annotation-Driven, Declarative | Retry is a framework-owned `@SagaStep` attribute of primitive data; classification is framework-owned markers; no alias to a third-party retry annotation; no orchestration code in sagas. | Pass |
| III. Formal Spec Conformance | `.kiro/specs/saga-forge-framework/design.md` updated with `@RetryPolicy` and Property 29 before implementation (first task); existing Properties 1–28 unchanged; Speckit flow followed. | Pass (gated by the first task) |
| IV. Explicit, Narrow Scope | No storage or transport added; Postgres only; MySQL/Mongo untouched. | Pass |
| V. Documentation Hierarchy | `README.md`, `docs/architecture.md` (feature gaps: idempotent step execution, per-step retry), `docs/use-cases.md` and `AGENTS.md` updated to the implemented state; vocabulary unchanged. | Pass |

Post-design re-check (after Phase 1): unchanged — no design artifact introduces a dependency, storage, transport or
non-declarative mechanism. The narrowing of FR-018 is a spec change made with this plan, not a principle violation.

## Project Structure

### Documentation (this feature)

```text
specs/005-step-retry/
├── spec.md
├── plan.md              # this file
├── research.md          # §1–§13 decisions
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── retry-policy.md
│   ├── command-identity.md
│   ├── attempt-loop.md
│   └── storage-delta.md
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
.kiro/specs/saga-forge-framework/design.md        # @RetryPolicy, Property 29 (first)

saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/
├── annotation/SagaStep.java                      # + retry()
├── annotation/RetryPolicy.java                   # new
├── command/CommandId.java                        # new
├── failure/TransientFailure.java                 # new
├── failure/FinalFailure.java                     # new
├── failure/FailureClassification.java            # new
├── scanner/ReflectiveAnnotationScanner.java      # Property 29
└── spi/
    ├── StepRecord.java                           # + attempts, handledCommands
    ├── SagaInstance.java                         # + pendingRetry
    ├── PendingRetry.java                         # new
    ├── SagaFailure.java                          # + classification, attempts
    ├── SagaLifecycleEventKind.java               # + ATTEMPT_FAILED
    ├── SagaLifecycleEntry.java                   # rules for ATTEMPT_FAILED
    └── HandlingOutcome.java                      # + DUPLICATE

saga-forge-engine/src/main/java/io/github/gkosharovdev/sagaforge/engine/
├── SagaEngine.java                               # builder: maxTotalRetryBackoff, retryPause
├── RetryPause.java                               # new, public
└── internal/
    ├── AttemptLoop.java                          # new
    ├── AttemptWrite.java                         # new
    ├── FailureClassifier.java                    # new
    ├── Backoff.java                              # new
    ├── CommandIds.java                           # new
    ├── EngineCommandGateway.java                 # identities, skip handled, CommandId resolver
    ├── SagaUnitOfWork.java                       # duplicate check, attempt k, no lease close, classification
    ├── EventRouter.java                          # hands the lease to AttemptLoop
    ├── FailedWrite.java                          # classification + attempts
    ├── EngineSagaLifecycle.java                  # attempts, handledCommands, pendingRetry clearing
    ├── StartupChecks.java                        # total backoff; one CommandId parameter
    └── Diagnostics.java                          # new event names

saga-forge-storage-postgres/src/main/
├── java/.../storage/postgres/{JsonCodec,SagaInstanceRows,LifecycleEntryRows,ForwardTransition}.java
└── resources/.../storage/postgres/{schema.sql,schema-upgrade-005.sql}

saga-forge-example/src/main/java/.../example/{OrderProcessingSaga,PaymentService}.java

tests: matching *Test classes under each module's src/test; engine test-jar doubles extended for
pendingRetry/attempts; fault-injection suite in saga-forge-engine (SC-001).
```

**Structure Decision**: Existing module layout from 004; no new module. Public engine surface grows by `RetryPause`
and two builder methods; everything else stays in `engine.internal`.

## Phasing (input to `/speckit-tasks`)

1. **Foundation**: `.kiro` update; core types and record changes; scanner Property 29; engine test-jar doubles
   compile against the new records.
2. **US1 + US2 (P1, idempotency)**: `CommandId` assignment and resolver; handled-command check; `DUPLICATE`.
3. **US3 (P1, retry)**: classifier, backoff, `AttemptLoop`, `AttemptWrite`, startup bound.
4. **US4 (P2)**: final/unclassified/exhausted recording; `SagaFailure` extension through `FailedWrite`.
5. **US5 (P3)**: claim-before-wait, redelivery continuation, interruption.
6. **Postgres**: codec, columns, forward transition, upgrade script; container suite run (also closes G-003-23 if it
   passes).
7. **Example, docs, gap register**: example saga and service, README/architecture/use-cases/AGENTS, memory.md.

## Complexity Tracking

No constitution violations to justify.
