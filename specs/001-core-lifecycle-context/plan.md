# Implementation Plan: Saga Lifecycle and Context Abstractions

**Branch**: `001-core-lifecycle-context` | **Date**: 2026-10-11 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-core-lifecycle-context/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Introduce two distinct abstractions into `saga-forge-core`, neither of which exists in code today despite
being claimed as "implemented and tested" in `AGENTS.md`/`docs/architecture.md`: **`SagaLifecycle`**, a
process-orchestration contract (start / advance / complete a saga instance) consumed by the future saga
engine, and **`SagaContext`**, a step-execution contract (`getState`/`putState`/`updateStateAttribute`)
giving a `@SagaStep` method typed, per-instance read/write access to named business-state values. The
technical approach reuses the existing `SagaInstance.state` field's `Map<String, Object>` shape as the
representational backbone for `SagaContext`'s named values (scalars as direct entries, structured values
as nested maps with independently-updatable fields), avoiding any new serialization contract. `SagaContext`
reaches a step method via a type-recognized field on the `@Saga` class (no new marker annotation), mirroring
the existing `@SagaId` field-recognition idiom in `ReflectiveAnnotationScanner` without touching the
already-tested "exactly one `@DomainEvent` parameter" rule on `@SagaStep` methods. `SagaLifecycle` ships as
a core-defined interface with no implementation (same posture as `StorageProvider`/`EventDispatcher` today).
Delivery also corrects the stale "implemented" documentation claim (FR-011).

## Technical Context

**Language/Version**: Java 21 (per root `pom.xml` `maven.compiler.source`/`target`)

**Primary Dependencies**: None beyond the JDK for `saga-forge-core` main sources (Principle I). Test-scope
only: JUnit 5.11.4, jqwik 1.9.2 (property-based tests), AssertJ 3.27.3 — matching the existing
`ReflectiveAnnotationScannerPropertyTest`/`SagaValidationResultPropertyTest` style already in the module.

**Storage**: N/A for this feature — persistence mechanics are an explicit, following, dependent feature
(see spec Assumptions). `SagaContext`'s named values are defined to be representable within the existing
`SagaInstance.state : Map<String, Object>` field already present in the SPI today; no new `StorageProvider`
method or serialization contract is introduced here.

**Testing**: JUnit 5 + jqwik property-based tests + AssertJ, consistent with existing `saga-forge-core` test
conventions (unit tests per type plus property tests for invariants such as per-instance isolation and
field-level update preserving unrelated fields).

**Target Platform**: JVM library (Java 21); no platform-specific behavior.

**Project Type**: Library — single existing Maven module, `saga-forge-core`. No new module.

**Performance Goals**: No explicit targets in the spec; reasonable default given the design is pure
in-process method calls over in-memory data (no I/O performed by `SagaContext`/`SagaLifecycle`
themselves) — N/A beyond "no measurable overhead over a direct `Map` access."

**Constraints**: Zero new runtime dependencies (Principle I). Must not alter the already-implemented,
already-tested `@SagaStep` "exactly one `@DomainEvent`-annotated parameter" scanner validation rule — this
directly shaped the `SagaContext`-injection design (field recognition, not a second method parameter).
Must not introduce a new storage/transport technology (Principle IV — not implicated, no storage/transport
code is added by this feature).

**Scale/Scope**: One new package (`io.github.gkosharovdev.sagaforge.core.lifecycle`) in `saga-forge-core`
holding `SagaLifecycle`, `SagaContext`, and ~4 dedicated unchecked exception types; a scanner extension to
recognize an optional `SagaContext`-typed field on `@Saga` classes; corrections to `AGENTS.md` and
`docs/architecture.md` (FR-011, executed at `/speckit-implement` time, not during planning).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment |
|---|---|
| I. Zero-Dependency Core | **PASS.** New types land in `saga-forge-core`; no new runtime dependency (JDK types only: `Map`, `Optional`, `Class<T>`). |
| II. Annotation-Driven, Declarative Style | **PASS, with one documented deviation.** `SagaContext` injection is recognized by field *type*, not a new marker annotation (unlike `@SagaId`). Field declaration itself remains declarative (no imperative lookup/`ThreadLocal`); the deviation and its rationale are recorded in Complexity Tracking below. |
| III. Formal Spec Conformance | **GATE, not yet closed.** Extending `ReflectiveAnnotationScanner` to recognize a `SagaContext`-typed field touches scanner behavior backed by `.kiro/specs/saga-forge-framework/design.md`. Tasks phase MUST include a validation step cross-checking the new recognition rule against that design doc's correctness properties before merge. |
| IV. Explicit, Narrow Scope | **PASS.** No storage or transport technology introduced; feature is entirely intra-`saga-forge-core`. |
| V. Documentation Hierarchy as Source of Truth | **SUPPORTS.** FR-011 requires this feature to correct the stale "implemented" claim in `AGENTS.md`/`docs/architecture.md` as part of delivery — directly reinforces this principle rather than risking a violation. |

**Re-check after Phase 1 design**: Confirmed below (see end of Phase 1 section) — no new violations introduced
by the data model or contracts; Principle III's open gate is carried forward into `tasks.md` as an explicit
task, not resolved by design alone.

## Project Structure

### Documentation (this feature)

```text
specs/001-core-lifecycle-context/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   ├── saga-lifecycle.md
│   └── saga-context.md
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

saga-forge is an existing Maven multi-module project (`AGENTS.md` Module Map). This feature touches only
`saga-forge-core` — no new module. It adds one new package alongside the three existing ones, and extends
the scanner and its test fixtures:

```text
saga-forge-core/
├── pom.xml                                    # unchanged — zero new dependencies
├── src/main/java/io/github/gkosharovdev/sagaforge/core/
│   ├── annotation/                            # existing: @Saga, @SagaId, @SagaStep, @StartSaga, @EndSaga,
│   │                                           #   @SagaCompensation, @DomainEvent, @Command — unchanged
│   ├── scanner/                               # existing: AnnotationScanner, ReflectiveAnnotationScanner,
│   │   │                                       #   SagaValidationResult
│   │   └── (extended)                         # recognize an optional SagaContext-typed field on @Saga
│   │                                           #   classes, mirroring the existing @SagaId field-recognition
│   │                                           #   rule — no change to the @SagaStep single-parameter rule
│   ├── spi/                                   # existing: SagaInstance, SagaStatus, StorageProvider,
│   │                                           #   EventDispatcher — unchanged; SagaInstance.state's
│   │                                           #   existing Map<String, Object> shape is reused, not altered
│   └── lifecycle/                             # NEW package for this feature
│       ├── SagaLifecycle.java                 # process-orchestration contract (interface, no impl shipped)
│       ├── SagaContext.java                   # step-execution contract (interface, no impl shipped)
│       ├── DuplicateSagaInstanceException.java
│       ├── InvalidSagaTransitionException.java
│       ├── SagaStateTypeMismatchException.java
│       └── NonStructuredStateValueException.java
└── src/test/java/io/github/gkosharovdev/sagaforge/core/
    ├── scanner/
    │   ├── fixtures/                          # extended: a saga fixture with a SagaContext-typed field
    │   └── ReflectiveAnnotationScannerTest.java / *PropertyTest.java   # extended cases
    └── lifecycle/                             # NEW: unit + jqwik property tests for SagaLifecycle/SagaContext
```

Documentation corrections required by FR-011 (`AGENTS.md`, `docs/architecture.md`) are executed as part of
`/speckit-implement`, not during planning — tracked as an explicit task in the forthcoming `tasks.md`.

**Structure Decision**: Single existing library module (`saga-forge-core`); one new package
(`io.github.gkosharovdev.sagaforge.core.lifecycle`) holding both new abstractions and their dedicated
exception types, plus a scanner extension and corresponding test fixtures/tests. No new Maven module, no
new dependency — consistent with Technical Context and Constitution Principle I.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| `SagaContext` field recognized by type only, no new marker annotation — departs from the `@SagaId` precedent of annotation-based field recognition (Principle II) | A new single-purpose marker annotation (e.g. a hypothetical `@InjectContext`) would exist solely to disambiguate a field whose type is already unambiguous on an `@Saga` class — ceremony without added clarity, and it grows the annotation surface for a one-off case | Rejected adding a second `@SagaStep` method parameter because it requires changing the already-implemented, already-tested "exactly one `@DomainEvent` parameter" scanner validation rule for no spec-mandated reason (spec does not ask to change `@SagaStep`'s shape). Rejected a `ThreadLocal`-based `SagaContext.current()` static accessor because it hides the dependency as ambient/implicit state rather than a declared field, conflicting with Principle II's preference for explicit, inspectable declaration over hidden state threading — even though it would be technically safe within one synchronous dispatch call stack. |

## Post-Phase 1 Constitution Re-Check

Re-evaluated against `research.md` and `data-model.md`/`contracts/` as designed:

- **I. Zero-Dependency Core** — still **PASS**. `data-model.md` adds only JDK types (`Map`, `Optional`,
  `Class<T>`, `RuntimeException` subtypes) to `saga-forge-core`.
- **II. Annotation-Driven, Declarative Style** — still the one documented deviation above (type-based
  field recognition), justified in Complexity Tracking; no *new* deviation surfaced by the data model or
  contracts.
- **III. Formal Spec Conformance** — gate remains open by design, carried into `tasks.md`: the scanner
  extension (recognizing a `SagaContext`-typed field) must be checked against
  `.kiro/specs/saga-forge-framework/design.md`'s correctness properties before merge. Not resolved by
  planning alone — this is an explicit, tracked task, not a silent pass.
- **IV. Explicit, Narrow Scope** — still **PASS**. No storage/transport technology appears anywhere in
  `data-model.md` or the two contracts; `SagaInstance`/`StorageProvider`/`EventDispatcher` signatures are
  explicitly unchanged.
- **V. Documentation Hierarchy** — still **SUPPORTS**; `quickstart.md` Scenario C keeps FR-011's
  documentation correction as an explicit delivery checklist item, not an afterthought.

**Result**: Design may proceed to `/speckit-tasks`. One gate (Principle III) remains open and MUST be
closed as a task during implementation, not deferred further.
