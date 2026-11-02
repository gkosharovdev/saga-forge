---
description: "Task list for Saga Lifecycle and Context Abstractions"
---

# Tasks: Saga Lifecycle and Context Abstractions

**Input**: Design documents from `/specs/001-core-lifecycle-context/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/saga-lifecycle.md](./contracts/saga-lifecycle.md),
[contracts/saga-context.md](./contracts/saga-context.md), [quickstart.md](./quickstart.md)

**Tests**: Included. `plan.md`'s Technical Context and Project Structure explicitly scope unit tests (JUnit 5)
plus jqwik property tests for `SagaContext`/`SagaLifecycle` invariants, matching the existing
`saga-forge-core` convention (every shipped type — `SagaInstance`, `ReflectiveAnnotationScanner`,
`SagaValidationResult` — already has unit and/or property test coverage).

**Organization**: Tasks are grouped by user story (spec.md priorities: US1 = P1, US2 = P1, US3 = P2).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on an incomplete task)
- **[Story]**: US1 / US2 / US3, per spec.md
- All file paths are relative to the repository root

## Path Conventions

Single existing module, `saga-forge-core` — no new Maven module (plan.md Structure Decision).

- Main sources: `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/`
- Test sources: `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/`
- New package (main + test): `.../lifecycle/`

---

## Phase 1: Setup

**Purpose**: Minimal project scaffolding — no new Maven module, no new dependency (Constitution
Principle I; plan.md Constraints).

- [x] T001 Create the new main package `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/lifecycle/`
  and its corresponding test package `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/lifecycle/`
  (with a `fixtures/` subpackage for test-only doubles), per plan.md's Project Structure. Do **not** modify
  `saga-forge-core/pom.xml` — JUnit 5.11.4, jqwik 1.9.2, and AssertJ 3.27.3 are already present as test-scope
  dependencies and cover everything this feature needs.

---

## Phase 2: Foundational (Blocking Prerequisites)

**No cross-story blocking prerequisites exist for this feature.** `SagaLifecycle` (US2) and `SagaContext`
(US1) are independent interfaces with disjoint exception types and no shared new abstraction —
`data-model.md`'s "Relationship to Existing Types" section confirms neither new type depends on the other,
and both reuse existing, unmodified `SagaInstance`/`SagaStatus` types. Proceed directly to Phase 3; US1 and
US2 may be implemented in parallel by different people once Phase 1 is done.

**Checkpoint**: Phase 1 complete → user story implementation may begin.

---

## Phase 3: User Story 1 - Step author reads and writes business state across steps (Priority: P1) 🎯 MVP

**Goal**: A `@SagaStep` method can store a named business-state value and a later step of the same saga
instance can read it back by name and expected type — type-safe, absence-distinguishable, per-instance
isolated, with independently-updatable structured fields (FR-001–FR-004, FR-002a/b/c, FR-010).

**Independent Test**: Define a saga with two steps where the first writes a named value and the second
reads it back by name and type, exercised directly against a `SagaContext` test double — no lifecycle or
storage-provider implementation required (spec.md User Story 1 Independent Test).

### Core contract types for User Story 1

- [x] T002 [P] [US1] Create `SagaStateTypeMismatchException` in
  `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/lifecycle/SagaStateTypeMismatchException.java`
  — unchecked (`RuntimeException`), carrying the state name, expected type, and actual type for a precise
  message (FR-003; data-model.md Supporting Exception Types).
- [x] T003 [P] [US1] Create `NonStructuredStateValueException` in
  `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/lifecycle/NonStructuredStateValueException.java`
  — unchecked (`RuntimeException`), signaling a field-level update was attempted on an existing scalar value
  (FR-002c; data-model.md Supporting Exception Types).
- [x] T004 [US1] Create the `SagaContext` interface in
  `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/lifecycle/SagaContext.java` declaring
  `<T> Optional<T> getState(String name, Class<T> type)`, `void putState(String name, Object value)`, and
  `void updateStateAttribute(String name, String field, Object value)` exactly per
  `contracts/saga-context.md` (pre-conditions, effects, `@throws` Javadoc referencing T002/T003's exception
  types, and the FR-010 scoping-invariant Javadoc note). Depends on: T002, T003.

### Scanner extension for User Story 1 (closes Constitution Principle III gate)

- [x] T005 [US1] Extend `ReflectiveAnnotationScanner` in
  `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/scanner/ReflectiveAnnotationScanner.java`
  with a new private `validateSagaContextField(Class<?> sagaClass, List<String> errors)` method, mirroring
  the existing `validateSagaIdField` pattern: scan declared fields for type `SagaContext` (recognized by
  declared type only, no new marker annotation, per research.md §1), and append an error **only if more than
  one** such field is found (the field is optional — zero is valid, unlike the mandatory `@SagaId` rule).
  Wire the new method into `validate(...)` alongside the existing calls. Depends on: T004.
- [x] T006 [P] [US1] Add scanner test fixture `SagaWithContextFieldSaga` (valid: exactly one
  `SagaContext`-typed field, undecorated) in
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/scanner/fixtures/SagaWithContextFieldSaga.java`.
- [x] T007 [P] [US1] Add scanner test fixture `MultipleSagaContextFieldSaga` (invalid: two `SagaContext`-typed
  fields) in
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/scanner/fixtures/MultipleSagaContextFieldSaga.java`,
  mirroring the existing `MultipleSagaIdSaga` fixture shape.
- [x] T008 [US1] Extend
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/scanner/ReflectiveAnnotationScannerTest.java`
  with three cases: `SagaWithContextFieldSaga` → `isValid() == true`; `MultipleSagaContextFieldSaga` →
  `isValid() == false` with an error message about the `SagaContext` field count; the existing `ValidSaga`
  fixture (zero `SagaContext` fields) → still `isValid() == true` (proves the rule is optional, not
  mandatory). Depends on: T005, T006, T007.
- [x] T009 [US1] Close the Constitution Principle III gate flagged in plan.md: update
  `.kiro/specs/saga-forge-framework/design.md` to add a new Correctness Property (immediately after
  "Property 8: SagaValidationResult immutability") documenting the `SagaContext` field-cardinality rule —
  mirror "Property 1: SagaId cardinality enforcement"'s structure, but state the "at most one" (not "exactly
  one") cardinality — and add the corresponding row to the Error Handling message-pattern table. Explicitly
  confirm in the same edit that "Property 6: Valid saga classes pass validation" still holds for saga
  classes with zero or one `SagaContext` field. Depends on: T008.

### Contract-behavior tests for User Story 1

- [x] T010 [P] [US1] Create the `InMemorySagaContext` test double in
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/lifecycle/fixtures/InMemorySagaContext.java`
  — backed by a single instance-scoped `Map<String, Object>`, implementing `getState`/`putState`/
  `updateStateAttribute` exactly per `contracts/saga-context.md`. Test-only scaffolding used to exercise the
  contract; no production implementation ships in this feature. Depends on: T004.
- [x] T011 [US1] Write
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/lifecycle/SagaContextTest.java`
  covering spec acceptance scenarios 1–3: write-then-read round trip returns the same value; `getState` on a
  name never written returns `Optional.empty()` (FR-004); `getState` on a stored value with an incompatible
  requested type throws `SagaStateTypeMismatchException` (FR-003) — plus null/blank-argument validation for
  `name`/`type`. Depends on: T002, T010.
- [x] T012 [US1] Extend `SagaContextTest.java` with spec acceptance scenarios 5–7: `updateStateAttribute`
  twice on the same name with different fields leaves both fields present and unrelated fields untouched
  (FR-002a, SC-005); `updateStateAttribute` on a name with no existing value creates a new structured value
  containing just that field (FR-002b, upsert); `updateStateAttribute` on a name already holding a scalar
  (via `putState`) throws `NonStructuredStateValueException` without mutating the existing value (FR-002c).
  Depends on: T003, T011.
- [x] T013 [P] [US1] Write
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/lifecycle/SagaContextPropertyTest.java`
  (jqwik) with two properties: (a) two independently-constructed `InMemorySagaContext` instances that each
  `putState` under the same generated name never observe each other's value (acceptance scenario 4, FR-010);
  (b) a sequence of `updateStateAttribute` calls on distinct, generated field names under the same state name
  always leaves every previously-set field intact (SC-005, generalizing scenario 5). Depends on: T010.

**Checkpoint**: `SagaContext`'s contract is fully specified, scanner-recognized, and independently tested;
Constitution Principle III gate closed.

---

## Phase 4: User Story 2 - Framework governs saga instance lifecycle transitions (Priority: P1)

**Goal**: A single, coherent contract for starting a saga instance, advancing it, and completing it, with
invalid transitions rejected clearly (FR-005, FR-006).

**Independent Test**: Exercise `start`/`advance`/`complete` directly against a `SagaLifecycle` test double —
no real engine, storage, or transport required (spec.md User Story 2 Independent Test).

- [x] T014 [P] [US2] Create `DuplicateSagaInstanceException` in
  `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/lifecycle/DuplicateSagaInstanceException.java`
  — unchecked, signaling a `start` request collided with an already-active instance for the same saga type +
  correlation (FR-006; data-model.md Supporting Exception Types).
- [x] T015 [P] [US2] Create `InvalidSagaTransitionException` in
  `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/lifecycle/InvalidSagaTransitionException.java`
  — unchecked, signaling the addressed instance is missing or already in a terminal status (FR-006;
  data-model.md Supporting Exception Types).
- [x] T016 [US2] Create the `SagaLifecycle` interface in
  `saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/lifecycle/SagaLifecycle.java` declaring
  `SagaInstance start(Class<?> sagaType, String associationProperty, String associationValue)`,
  `SagaInstance advance(String sagaId)`, and `SagaInstance complete(String sagaId)` exactly per
  `contracts/saga-lifecycle.md` (pre-conditions, effects, `@throws` Javadoc referencing T014/T015). No
  implementation ships — same posture as `StorageProvider`/`EventDispatcher` today. Depends on: T014, T015.
- [x] T017 [P] [US2] Create the `InMemorySagaLifecycle` test double in
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/lifecycle/fixtures/InMemorySagaLifecycle.java`
  — backed by a `Map<String, SagaInstance>` keyed by `sagaId`, enforcing the pre-conditions and the
  `CREATED → RUNNING → COMPLETED` transition diagram from `data-model.md`. Test-only scaffolding; no
  production implementation ships in this feature. Depends on: T016.
- [x] T018 [US2] Write
  `saga-forge-core/src/test/java/io/github/gkosharovdev/sagaforge/core/lifecycle/SagaLifecycleTest.java`
  covering spec acceptance scenarios 1–3: `start` with no prior active instance returns a new instance with
  `status == CREATED`; `advance` on that instance reflects progress and moves it to `RUNNING`; `complete` on
  an instance whose current step is `@EndSaga` returns `status == COMPLETED`. Depends on: T017.
- [x] T019 [US2] Extend `SagaLifecycleTest.java` with acceptance scenario 4 and the duplicate-start edge
  case: `advance` or `complete` on the now-terminal (`COMPLETED`) instance throws
  `InvalidSagaTransitionException`; a second `start` call for a saga type + association value that already
  has an active instance throws `DuplicateSagaInstanceException`; `null` arguments to any of the three
  operations throw `IllegalArgumentException`, consistent with the existing SPI's null-argument convention.
  Depends on: T018.

**Checkpoint**: US1 and US2 both independently functional and testable; neither depends on the other.

---

## Phase 5: User Story 3 - Documentation accurately reflects what is implemented (Priority: P2)

**Goal**: Correct the stale "implemented and tested" claim about `SagaLifecycle`/`SagaContext` in
`AGENTS.md`/`docs/architecture.md` (FR-011) — and only once the types from Phases 3–4 actually exist with
passing tests, per spec.md User Story 3 Acceptance Scenario 1.

**Independent Test**: Cross-reference the "Current Status" claims in `AGENTS.md` and `docs/architecture.md`
against the actual contents of `saga-forge-core` after this feature lands (spec.md User Story 3 Independent
Test). **Depends on US1 and US2 being code-complete** — the correction can only be written accurately once
the types it describes exist (quickstart.md Scenario C).

- [x] T020 [US3] Correct `AGENTS.md`: in the "Current Status" section, move `SagaLifecycle`/`SagaContext` out
  of the "Implemented and tested" bullet and describe them accurately — interfaces plus four dedicated
  exception types (T002/T003/T014/T015), each with passing unit and property tests exercised via test-only
  doubles (`InMemorySagaContext`/`InMemorySagaLifecycle`); **no production implementation ships**, the same
  posture as `StorageProvider`/`EventDispatcher`. Also correct the "Known Gaps to Respect When Extending"
  bullet currently stating "`SagaContext` tracks step *history*, not arbitrary associated business state a
  later step can query" — this is now stale: the shipped `SagaContext` *does* expose arbitrary named
  business-state read/write via `getState`/`putState`/`updateStateAttribute` (FR-001–FR-004). Depends on:
  T009, T013, T019.
- [x] T021 [P] [US3] Correct `docs/architecture.md`:
  1. Update the "Status at a glance" table row for `SagaContext` / `SagaLifecycle` — do not leave it reading
     "Implemented, tested" unqualified; reflect that these are contract-only interfaces with no production
     implementation (consider aligning with the SPI row's "Defined, no implementations" wording, or an
     equally precise distinction), to avoid overstating what shipped the same way the stale claim did.
  2. Rewrite the "Queryable process-level state" feature-gap section: the capability is now **defined** via
     `SagaContext.getState`/`putState`/`updateStateAttribute` — not `SagaLifecycle.getAssociatedState`/
     `putAssociatedState` as currently drafted. Remove the "decision needed on which serialization format"
     sentence — `research.md` Decision #2 already settles this by reusing `SagaInstance.state`'s existing
     `Map<String, Object>` shape; no new serialization format is introduced. Keep accurate what is **still**
     an open gap: round-tripping this state through a concrete `StorageProvider` implementation across
     separate dispatches remains unbuilt (no storage provider exists yet).
  3. Correct the stale sentence describing `SagaContext`'s "existing history records
     (`HandledEventRecord`/`EmittedCommandRecord`/`CompletedStepRecord`)" — these record types do not exist
     anywhere in `saga-forge-core` and should not be referenced as if they do.

  Depends on: T009, T013, T019.
- [x] T022 [US3] Final verification pass (SC-003): search `AGENTS.md`, `docs/architecture.md`, and
  `README.md` for every remaining "implemented and tested" / "Implemented, tested" claim naming
  `SagaLifecycle`/`SagaContext`, and confirm each one is now accurate against the actual
  `saga-forge-core` source delivered in T002–T019 (in particular, re-check `README.md`'s Modules table row
  and its "annotation model, SPI contracts, and module layout exist and are tested" sentence don't overstate
  the two new interfaces' status). Depends on: T020, T021.

**Checkpoint**: All three user stories independently functional; SC-003 (zero stale "implemented" claims)
verified.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T023 [P] Run the full `saga-forge-core` test suite (`mvn -pl saga-forge-core test`) and confirm every
  existing test (scanner, `SagaInstance`) and every new test from T008/T011–T013/T018–T019 passes together
  with zero regressions.
- [x] T024 Walk `quickstart.md` Scenario A and Scenario B against the final shipped `SagaContext`/
  `SagaLifecycle` signatures; update `quickstart.md` if any method signature drifted from `contracts/` during
  implementation.
- [x] T025 [P] Diff `saga-forge-core/pom.xml` against its pre-feature state and confirm zero dependencies were
  added (Constitution Principle I) — required sign-off before merge per the constitution's Governance
  section.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Empty — no blocking prerequisites exist for this feature (see rationale above).
- **User Story 1 (Phase 3)** and **User Story 2 (Phase 4)**: Both depend only on Phase 1. They are mutually
  independent — neither story's types, exceptions, or tests reference the other's — and may be implemented
  in parallel.
- **User Story 3 (Phase 5)**: Depends on **both** Phase 3 and Phase 4 being complete (code + passing tests).
  This is the one cross-story dependency in this feature, required by FR-011's own wording ("MUST NOT claim
  ... implemented until they exist in code").
- **Polish (Phase 6)**: Depends on Phases 3, 4, and 5 all being complete.

### Within Each User Story

- US1: exceptions (T002, T003) → interface (T004) → scanner extension (T005) → scanner fixtures/tests
  (T006–T008) → Principle III gate closure (T009); independently, interface (T004) → test double (T010) →
  unit tests (T011, T012) and property tests (T013).
- US2: exceptions (T014, T015) → interface (T016) → test double (T017) → unit tests (T018, T019).
- US3: strictly sequential — T020 and T021 can run in parallel with each other, both gated on US1+US2
  completion; T022 runs last.

### Parallel Opportunities

- T002 and T003 (different exception files).
- T006 and T007 (different fixture files).
- T014 and T015 (different exception files).
- T010 and T013 can start as soon as T004 exists, in parallel with the scanner-extension work (T005–T009),
  since neither touches the scanner.
- T017 can start as soon as T016 exists, in parallel with nothing else in US2 (T018/T019 depend on it).
- Once Phase 1 is done, **all of Phase 3 and Phase 4 can proceed in parallel** (two developers, one per
  story) — the Checkpoint at the end of Phase 4 is the real synchronization point before Phase 5.
- T021 and T025 can run in parallel with other same-phase work in their respective phases.

---

## Parallel Example: User Story 1

```bash
# Launch the two independent exception types together:
Task: "Create SagaStateTypeMismatchException in .../lifecycle/SagaStateTypeMismatchException.java"
Task: "Create NonStructuredStateValueException in .../lifecycle/NonStructuredStateValueException.java"

# After SagaContext (T004) exists, launch the test double and the scanner fixtures together:
Task: "Create InMemorySagaContext test double in .../lifecycle/fixtures/InMemorySagaContext.java"
Task: "Add scanner test fixture SagaWithContextFieldSaga in .../scanner/fixtures/SagaWithContextFieldSaga.java"
Task: "Add scanner test fixture MultipleSagaContextFieldSaga in .../scanner/fixtures/MultipleSagaContextFieldSaga.java"
```

---

## Implementation Strategy

### MVP Scope

Both User Story 1 and User Story 2 are priority **P1** in spec.md — the feature's stated purpose ("define
both distinct abstractions") is only half-delivered by either alone. Treat **US1 + US2 together** as the
MVP:

1. Complete Phase 1 (Setup) — trivial, no blocking work.
2. Complete Phase 3 (US1) and Phase 4 (US2) — independently, in parallel if staffed.
3. **STOP and VALIDATE**: run T023 against both stories together; confirm the Phase 3 and Phase 4 checkpoints
   both hold.
4. Only then proceed to Phase 5 (US3) — the documentation correction is not meaningful, and per FR-011 must
   not be written, until US1+US2 exist in code.

If forced to sequence even P1 stories, US1 (🎯) is the single highest-priority item per spec.md's own
"Why this priority" note on User Story 1 ("the highest-priority documented feature gap") — ship it first if
only one story can land in a given iteration.

### Incremental Delivery

1. Setup → Foundational (no-op) → Foundation ready.
2. US1 lands → independently testable (`SagaContextTest`, `SagaContextPropertyTest`,
   `ReflectiveAnnotationScannerTest` extensions all pass) → demoable as "step authors can now thread named
   state across steps."
3. US2 lands → independently testable (`SagaLifecycleTest` passes) → demoable as "the future engine has an
   authoritative lifecycle contract to build against."
4. US3 lands last, correcting the documentation now that both are true — closes SC-003.
5. Polish (T023–T025) confirms no regressions and no new dependencies before merge.

---

## Notes

- [P] tasks touch different files with no incomplete-task dependency.
- [Story] labels map every Phase 3–5 task to spec.md's US1/US2/US3 for traceability.
- T009 is the explicit task that closes the Constitution Principle III gate plan.md left open — do not
  merge without it.
- Both `InMemorySagaContext` (T010) and `InMemorySagaLifecycle` (T017) are **test-only** scaffolding. Do not
  promote either into `src/main` — this feature ships contracts only, matching `StorageProvider`/
  `EventDispatcher`'s existing posture; a real implementation is explicitly a following, dependent feature
  (spec.md Assumptions).
- Commit after each task or logical group; stop at either Phase 3 or Phase 4's checkpoint to validate that
  story independently before continuing.
