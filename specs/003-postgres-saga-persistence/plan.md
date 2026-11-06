# Implementation Plan: PostgreSQL Saga Persistence

**Branch**: `003-postgres-saga-persistence` | **Date**: 2026-11-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-postgres-saga-persistence/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Fill in the currently-empty `saga-forge-storage-postgres` module with `PostgresStorageProvider`
(`StorageProvider`) and `PostgresSagaContextStore` (`SagaContextStore`), the first production
implementations of either SPI. `PostgresStorageProvider.save(SagaInstance)` is the only write entry point
and stays that way: it opens one JDBC transaction per call, upserts the instance row, and — inside that
same transaction — derives and inserts the saga's lifecycle-history entries (FR-007) by diffing the
previously-persisted snapshot against the incoming one, rather than via any new write method or core SPI
(research.md §1–§2). Concurrent conflicting writes are rejected by a row lock plus a monotonic
forward-transition check (`ConcurrentSagaUpdateException`), since `SagaInstance` has no version field and
its shape cannot change (research.md §3 — this supersedes spec.md's own "optimistic check / version
marker" Assumption). The schema is four tables — `saga_instance`, a normalized `saga_association`,
`saga_context`, and the new `saga_lifecycle_history` — with JSONB for the variable-shaped `state`/`steps`
and plain columns for the fixed-shape `SagaFailure` (research.md §4); JSONB is encoded with Jackson 3
(`tools.jackson`, research.md §9). `PostgresSagaContextStore` writes through its own, separate `Connection`
so context values survive an instance-side rollback (FR-005, research.md §6); the `saga_context` FK is kept,
so the instance row must be saved before its first context record (research.md §8). Two new
storage-agnostic core types, `SagaLifecycleEntry` and `SagaLifecycleEventKind`, are added to
`saga-forge-core` for FR-007 — the only new core surface this feature introduces. One scope
limitation is carried forward explicitly rather than silently assumed away: true execution-order fidelity
for two steps completed within the *same* `save()` call cannot be fully guaranteed given `SagaInstance`'s
existing, unordered `Map<String, StepRecord>` shape (research.md §5) — a deterministic alphabetical
tiebreak is used instead, and this is flagged as a known gap against SC-003 for that specific case.

## Technical Context

**Language/Version**: Java 21 (root `pom.xml` `maven.compiler.source`/`target`, unchanged).

**Primary Dependencies**: `saga-forge-core` (existing). `org.postgresql:postgresql`, bumped from `42.7.4`
(already declared in `saga-forge-storage-postgres/pom.xml`, unused until now) to `42.7.14`.
`tools.jackson.core:jackson-databind` `3.1.7` (new, compile scope, this module only, via a module-local
`<jackson.version>` property; research.md §9). Both versions are the ones Spring Boot 4.1.2 manages.
`javax.sql.DataSource` for connection injection — a JDK interface, not a new dependency. No connection pool,
no Flyway/Liquibase (research.md §7).

**Storage**: PostgreSQL. Four tables (`saga_instance`, `saga_association`, `saga_context`,
`saga_lifecycle_history`); full DDL in [contracts/postgres-schema.md](./contracts/postgres-schema.md).

**Testing**: JUnit 5.11.4 + AssertJ 3.27.3 (existing root `dependencyManagement`, reused). jqwik 1.9.2 for
concurrency/forward-transition properties. New test-scope-only dependencies added to
`saga-forge-storage-postgres/pom.xml`: `testcontainers`, `testcontainers-junit-jupiter`,
`testcontainers-postgresql` `2.0.5` (confirmed absent elsewhere in the repo — greenfield; research.md §7).
These tests need a Docker-compatible container runtime (the team uses Podman).

**Target Platform**: JVM library module (Java 21), consumed by a future engine; no standalone service.

**Project Type**: Library — fills in an existing, currently-empty module (`saga-forge-storage-postgres`);
no new module.

**Performance Goals**: No numeric target in the spec; SC-005 is qualitative ("not the dominant cost of
executing a saga step"). Addressed structurally: `findByAssociation` uses an indexed equality lookup
(research.md §4), not a JSONB containment scan.

**Constraints**: Zero changes to any existing `core.spi` type's shape (`SagaInstance`, `StepRecord`,
`StepStatus`, `SagaFailure`, `SagaContextRecord`, `StorageProvider`, `SagaContextStore` — all verified
verbatim against current source during this planning pass). No new public core SPI method or
unit-of-work/transaction-boundary type (FR-013/FR-014, spec.md's "Revision 2026-11-05").
`saga-forge-storage-postgres`'s runtime dependencies are `saga-forge-core`, the JDBC driver (`42.7.14`), and
`jackson-databind` (`3.1.7`); nothing else. The root `pom.xml` and `saga-forge-core/pom.xml` are not
modified. `saga-forge-core` gains exactly two new types (`SagaLifecycleEntry`, `SagaLifecycleEventKind`) and
nothing else. A `SagaContextRecord` is saved only after its `SagaInstance` row exists (research.md §8).

**Scale/Scope**: Two new production classes (`PostgresStorageProvider`, `PostgresSagaContextStore`), one
new unchecked exception (`ConcurrentSagaUpdateException`), two new `saga-forge-core` types, four DDL
tables, and the Testcontainers-backed integration test suite in quickstart.md (15 scenarios mapped to
FR-001–FR-012 and SC-001–SC-005).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment |
|---|---|
| I. Zero-Dependency Core | **PASS.** The two new core types (`SagaLifecycleEntry`, `SagaLifecycleEventKind`) are JDK-only records/enums referencing only existing core types (`AggregateReference`, `SagaFailure`); no Postgres, JDBC, or Spring type leaks into `saga-forge-core`. All Postgres-specific code (schema, `DataSource`, SQL, `ConcurrentSagaUpdateException`) and the Jackson 3 dependency live in `saga-forge-storage-postgres` only; no `tools.jackson`/`com.fasterxml` type is imported by `saga-forge-core`. |
| II. Annotation-Driven, Declarative Style | **N/A.** This feature adds no annotations and touches no saga-authoring surface; it implements existing SPI contracts behind the scenes. |
| III. Formal Spec Conformance | **PASS.** No change to annotation semantics, validation rules, or `SagaContext`/`SagaLifecycle` contracts. The new core types are additive vocabulary, not a change to an existing correctness property in `.kiro/specs/*/design.md`; `SagaInstance`'s own invariants (outcome ⟺ COMPLETED, failure ⟺ FAILED) are reused as-is and mirrored defensively in the schema's `CHECK` constraints (contracts/postgres-schema.md). Per spec.md's Assumptions, the FR-007 vocabulary was explicitly checked against Principle I here (storage-agnostic — see research.md §2) and Principle III (no existing property changed). |
| IV. Explicit, Narrow Scope | **PASS.** Fills in an already-in-scope module (Postgres storage); introduces no new storage or transport technology. Testcontainers is a new *test-scope* dependency; `jackson-databind` is a new runtime dependency of this one module, used only to encode JSONB (research.md §9). Neither is a new storage or transport technology. |
| V. Documentation Hierarchy | **SUPPORTS.** No existing doc claims Postgres persistence works today (AGENTS.md's "Current Status" explicitly lists storage modules as placeholder-only); this feature is additive to that status line, to be updated as a task in `/speckit-tasks`, not a conflict to resolve now. |

## Project Structure

### Documentation (this feature)

```text
specs/003-postgres-saga-persistence/
├── spec.md
├── plan.md                        # This file
├── research.md                    # Phase 0
├── data-model.md                  # Phase 1
├── quickstart.md                  # Phase 1
├── contracts/                     # Phase 1
│   ├── saga-lifecycle-entry.md
│   ├── postgres-schema.md
│   └── concurrency-control.md
├── checklists/requirements.md
└── tasks.md                       # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
saga-forge-core/                                       # existing module, additive only
├── src/main/java/io/github/gkosharovdev/sagaforge/core/
│   └── spi/          NEW: SagaLifecycleEntry (record), SagaLifecycleEventKind (enum)
└── src/test/java/io/github/gkosharovdev/sagaforge/core/
    └── spi/          NEW: SagaLifecycleEntryTest (construction/validation, present-iff rules)

saga-forge-storage-postgres/                           # currently empty; this feature's primary deliverable
├── pom.xml                                             # + jackson-databind 3.1.7 (compile), driver 42.7.14,
│                                                       #   testcontainers test-scope deps (research.md §7, §9)
├── src/main/java/io/github/gkosharovdev/sagaforge/storage/postgres/
│   ├── PostgresStorageProvider.java        implements StorageProvider; owns the derivation algorithm
│   │                                        (data-model.md) and the row-lock/forward-transition check
│   │                                        (contracts/concurrency-control.md)
│   ├── PostgresSagaContextStore.java        implements SagaContextStore; separate Connection per call
│   ├── ConcurrentSagaUpdateException.java   NEW unchecked exception, this module only
│   └── JsonCodec (Jackson 3), row mapping, history derivation — package-private internals, not public SPI
└── src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/
    ├── PostgresStorageProviderTest.java      Testcontainers-backed; quickstart.md scenarios 1–3, 7–10, 13, 14
    ├── PostgresSagaContextStoreTest.java     Testcontainers-backed; quickstart.md scenarios 4–6, 15
    ├── ConcurrencyControlTest.java           Testcontainers-backed; quickstart.md scenarios 11–12
    └── (jqwik properties for the forward-transition check and idempotent re-save)

docs: AGENTS.md "Current Status" (Postgres module moves from placeholder to implemented — tracked as a
      task in /speckit-tasks, not edited by this plan itself)
```

**Structure Decision**: Two modules touched, no new module. `saga-forge-core` gains exactly the two new
`spi` types FR-007 requires (Constitution Principle I). `saga-forge-storage-postgres` — currently an empty
placeholder with only a `pom.xml` — receives its first production source: the two SPI implementations plus
one module-local exception type. No `saga-forge-spring-boot-starter` wiring is in scope here (that module
stays untouched; autoconfiguration is a separate feature per AGENTS.md's module map).

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No unjustified violations. The one deliberate scope boundary this plan documents —
`ConcurrentSagaUpdateException` as a new, module-local exception type rather than a new core SPI member —
is not a Principle I/IV violation: it is a plain Java exception thrown by one module's own implementation,
never referenced from `saga-forge-core`, and does not change any existing contract's signature.

## Post-Phase 1 Constitution Re-Check

- **I** still **PASS**: `data-model.md` and `contracts/` confirm the only new core types are
  `SagaLifecycleEntry`/`SagaLifecycleEventKind` (JDK-only); every Postgres-specific concept (schema, row
  lock, `ConcurrentSagaUpdateException`, the Jackson 3 dependency) stayed inside
  `saga-forge-storage-postgres` through Phase 1.
- **II** still **N/A**: no annotation-facing change appeared during design.
- **III** still **PASS**: `contracts/postgres-schema.md`'s `CHECK` constraints and
  `contracts/concurrency-control.md`'s forward-transition rule both reuse `SagaInstance`'s *existing*
  invariants rather than inventing new ones; no `.kiro/specs/*/design.md` property needs amendment.
  - T029 gate closed (post-implementation): re-read both `.kiro/specs/*/design.md` against the delivered code; no
    correctness property (framework 1–20, context-lifecycle 1–12) or Error Handling row changes. The core diff is
    additive (`SagaLifecycleEntry`/`SagaLifecycleEventKind` only; scanner and `SagaInstance` untouched), and
    `PostgresStorageProvider` honours the "SPI Error Contracts" rows (`save(null)`/`findById(null)` →
    `IllegalArgumentException`). Pre-003 drift between those design docs and core is recorded as G-002-05.
- **IV** still **PASS**: the new dependencies are Testcontainers (test scope) and `jackson-databind`
  (runtime, JSONB encoding), both confined to `saga-forge-storage-postgres`; the driver bump to `42.7.14` is a
  version alignment, not a new dependency.
- **V** still **SUPPORTS**: carried forward as a `/speckit-tasks` item (AGENTS.md status update), not a
  conflict blocking this plan.

**Result**: Ready for `/speckit-tasks`. Two decisions in this plan go beyond what spec.md states literally
and are surfaced for explicit sign-off before tasking proceeds:
1. **Concurrency mechanism** (research.md §3, contracts/concurrency-control.md) — a row lock plus a
   monotonic forward-transition check, not the "optimistic check, e.g. a version marker" spec.md's own
   Assumptions anticipated. The version-marker approach was found not to be implementable without changing
   `SagaInstance`'s shape, which is out of scope; the mechanism adopted here achieves the same FR-010/SC-004
   guarantee without that change.
2. **Step-ordering limitation** (research.md §5, contracts/saga-lifecycle-entry.md, quickstart.md) — when
   two steps complete within one `save()` call, their lifecycle-history order is a deterministic
   alphabetical tiebreak, not verified true execution order, because `SagaInstance.steps()` carries no
   per-step ordering signal today. SC-003's "zero out-of-order entries" is not fully guaranteed for that
   specific (multi-step-per-commit) case; it is fully guaranteed for the one-step-per-commit case this
   feature's own test plan exercises.

Decided 2026-11-05, after the sign-off above:
3. **Write order** (research.md §8) — the `saga_context` FK with `ON DELETE CASCADE` stays. A
   `SagaContextRecord` may only be saved once its `SagaInstance` row exists; a context save for an unknown
   `sagaId` fails with `UncheckedSqlException` (FK violation). Dropping the FK was rejected.
4. **JSON encoding** (research.md §9) — Jackson 3 (`tools.jackson.core:jackson-databind` `3.1.7`, this module
   only) behind a package-private `JsonCodec`, with the JDBC driver aligned to `42.7.14` (both Spring Boot
   4.1.2-managed). A hand-written codec, Jackson 2.x, and polymorphic default typing were rejected.

Carried forward, unchanged from spec.md/AGENTS.md (not this feature's job to close): the engine feature
still owns the formal unit-of-work SPI and cross-process per-instance serialization policy; the Spring Boot
starter still owns wiring a `DataSource`/connection pool automatically; a future observability feature still
owns a programmatic query API over `saga_lifecycle_history` (FR-012).

Known gaps and deferred items from this feature are tracked in `.specify/memory/memory.md`: G-003-01 and
G-003-02 (explicitly deferred), G-003-03 to G-003-08, and G-ENG-01 (instance-first write order for the
engine).
