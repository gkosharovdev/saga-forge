# Quickstart: validating PostgreSQL Saga Persistence

**Feature**: [spec.md](./spec.md) | **Contracts**: [contracts/](./contracts/) | **Data model**:
[data-model.md](./data-model.md) | **Research**: [research.md](./research.md)

This guide lists the runnable checks that prove `saga-forge-storage-postgres`'s `StorageProvider` and
`SagaContextStore` implementations satisfy spec.md's four user stories. The scenario checks are written to run
against a real Postgres via Testcontainers (research.md §7) — no embedded/fake database, no shared external
instance.

> **Not yet run (G-003-23).** The container-backed scenario tests are written and compile, but have not been run
> against Postgres since US3 (the US4 ones never ran). By user decision at T032, Testcontainers testing was given
> up and only unit tests ran. Each scenario's evidence level is in "Delivered tests" below; the pure logic behind
> scenarios 7 to 9, 11 and 12 is also covered by unit tests that did run.

## Prerequisites

- JDK 21, Maven 3.9+, and a Docker-compatible container runtime (the team uses Podman). Testcontainers
  needs one to start the Postgres container; no manually-provisioned database is needed. With Podman on
  macOS, export `DOCKER_HOST=unix://<socket>`, with the socket path from
  `podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}'`, and set
  `TESTCONTAINERS_RYUK_DISABLED=true` if Ryuk fails under rootless Podman.
- Repository root: `saga-forge`, branch `003-postgres-saga-persistence`.
- The schema in [contracts/postgres-schema.md](./contracts/postgres-schema.md) (the classpath resource
  `schema.sql`) applied to the test container. The test helper `PostgresTestSupport` starts one shared
  `postgres:16-alpine` container in a static initializer and applies `schema.sql` once; `reset()` truncates
  between tests. This feature ships no migration tool, so tests own applying the DDL themselves.

## Commands

```bash
# unit + Testcontainers-backed integration tests (starts a container; not run so far, G-003-23)
mvn -B -pl saga-forge-storage-postgres -am test

# unit tests only, as run at T032: no container is started (G-003-27)
mvn -B clean test-compile
mvn -B test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='!SchemaTest,!PostgresStorageProviderTest*,!PostgresSagaContextStoreTest,!LifecycleHistoryTest,!ConcurrencyControlTest'
```

Each must end in `BUILD SUCCESS` with zero failures. A plain `mvn test` has no tag or profile that skips the
container classes, so it runs them (G-003-27). The container run is slower than `saga-forge-core`'s (one shared
container startup) — that cost is confined to this module.

## Validation scenarios

| # | Scenario | Expected outcome | Spec |
|---|---|---|---|
| 1 | Start a saga, complete one step, `save()`, open a fresh `PostgresStorageProvider`/`DataSource` (simulated restart), `findById` | Retrieved instance has identical `status`, `steps`, `associations` to what was saved | US1-1, SC-001 |
| 2 | `findById` for a `sagaId` never saved | `Optional.empty()`, no exception | US1-2 |
| 3 | Save a `COMPLETED` instance, then a `FAILED` one (different `sagaId`s), `findById` each | Each retrieved instance's `outcome`/`failure` is present and consistent with its `status` (schema `CHECK` constraints never trip) | US1-3, FR-011 |
| 4 | Save a `SagaInstance`, then a `SagaContextRecord` for it (instance first, research.md §8), then `save()` a `SagaInstance` update whose write is made to fail (e.g., by injecting a constraint violation) so the instance transaction rolls back, then `find` the context | Context value unchanged and still readable despite the instance-side rollback | US2-1, SC-002 |
| 5 | Save a context value for a key, save again with a new value for the same key, `find` | Latest value only | US2-2 |
| 6 | `find` context for a `sagaId` with no context ever saved | `Optional.empty()` | US2-3 |
| 7 | Drive one instance through: first `save()` (status `CREATED`), a `save()` completing step A, a `save()` completing step B, a `save()` reaching a terminal outcome; read `saga_lifecycle_history` directly via SQL | Four entries in ascending `sequence_number`, kinds `CREATED, STEP_COMPLETED(A), STEP_COMPLETED(B), COMPLETED`, each `occurred_at` consistent with the snapshot that produced it | US3-1, SC-003 |
| 8 | Save an instance whose `incoming` leaves a step `NOT_RUN` (never attempt the step), i.e. a save that never claims the step completed | No `STEP_COMPLETED` entry for that step appears in history | US3-2, FR-009 |
| 9 | Drive an instance to `FAILED`, read its history | A `FAILED` entry exists whose `failure` matches `findById(sagaId).failure()` exactly | US3-3 |
| 10 | Inject a failure between the `saga_instance` upsert and the `saga_lifecycle_history` insert within one `save()` call (e.g., a constraint violation on the history insert) | Neither the instance row's new state nor any history row for that call is visible after rollback — `findById` still returns the pre-call snapshot | US4-1, FR-008, FR-013 |
| 11 | Two threads/processes `findById` the same instance, then concurrently call `save()` with diverging `incoming` values (e.g., both think a different step completed next) | Exactly one `save()` returns normally; the other throws `ConcurrentSagaUpdateException` (contracts/concurrency-control.md); `findById` afterward reflects only the winner's write, never a mix | US4-2, FR-010, SC-004 |
| 12 | Repeat scenario 11 with **identical** `incoming` values from both callers (idempotent re-save) | Both calls succeed; `saga_lifecycle_history` gains no duplicate entries for the already-recorded transition (contracts/concurrency-control.md "What this does not detect") | FR-010 boundary case |
| 13 | `findByAssociation` for a property/value with no matching instance | Empty list, not an error | FR-003 |
| 14 | `delete(sagaId)` on an instance that also has a context record and history entries, then `findById`/`find`/direct SQL query | Instance, context, and all history rows for that `sagaId` are gone (cascade) | FR-004, orphan-cleanup Assumption |
| 15 | `PostgresSagaContextStore.save` of a record whose `sagaId` has no `saga_instance` row | Throws `UncheckedSqlException` (FK violation); no `saga_context` row is written | FR-006, write-order rule (research.md §8) |

## Delivered tests

Test classes are in `saga-forge-storage-postgres/src/test/java/io/github/gkosharovdev/sagaforge/storage/postgres/`.
Evidence: **unit-run** = ran green at T032; **compiled only (container)** = needs `PostgresTestSupport` and has
not run (G-003-23).

| # | Scenario test | Evidence | Supporting unit-run tests |
|---|---|---|---|
| 1 | `PostgresStorageProviderTest$RoundTrip.runningInstanceSurvivesRestart` | compiled only (container) | — |
| 2 | `PostgresStorageProviderTest$RoundTrip.unknownIdIsEmpty` | compiled only (container) | — |
| 3 | `PostgresStorageProviderTest$RoundTrip.terminalInstancesRoundTrip`; CHECK constraints in `SchemaTest.completedRequiresOutcome`, `SchemaTest.failedRequiresFailureStep` | compiled only (container) | — |
| 4 | `PostgresSagaContextStoreTest.contextSurvivesInstanceRollback` (fault injected by a `BEFORE UPDATE` trigger, `PostgresTestSupport.failOn`) | compiled only (container) | — |
| 5 | `PostgresSagaContextStoreTest.resaveKeepsLatest` | compiled only (container) | — |
| 6 | `PostgresSagaContextStoreTest.missingRecordIsEmpty` | compiled only (container) | — |
| 7 | `LifecycleHistoryTest.fullLifecycleInOrder` | compiled only (container) | `LifecycleHistoryDerivationTest.firstSaveIsCreated`, `oneStepCompleted`, `statusToCompleted`, `sequenceNumbersConsecutive` |
| 8 | `LifecycleHistoryTest.notRunStepHasNoRow` | compiled only (container) | `LifecycleHistoryDerivationTest.notRunStepIsSilent` |
| 9 | `LifecycleHistoryTest.failedRowMatchesInstanceFailure` | compiled only (container) | `LifecycleHistoryDerivationTest.statusToFailed` |
| 10 | `PostgresStorageProviderTest$Atomicity.failedHistoryInsertKeepsPreCallSnapshot`, `failedFirstSaveLeavesNothing` (fault injected by a `BEFORE INSERT` trigger) | compiled only (container) | — |
| 11 | `ConcurrencyControlTest.divergingConcurrentSaves` (`@RepeatedTest(20)`); also `staleSequentialWriteRejected`, `concurrentFirstSaves` | compiled only (container) | `ForwardTransitionPropertyTest.acceptsExactlyForwardTransitions` (jqwik) |
| 12 | `ConcurrencyControlTest.identicalConcurrentSaves` (`@RepeatedTest(20)`) | compiled only (container) | `ForwardTransitionPropertyTest.reSavingTheSameSnapshotIsAcceptedAndDerivesNothing` (jqwik), `LifecycleHistoryDerivationTest.identicalSnapshotsGiveNothing` |
| 13 | `PostgresStorageProviderTest$RoundTrip.findByAssociation` | compiled only (container) | — |
| 14 | split: `PostgresSagaContextStoreTest.providerDeleteCascadesToContext` (instance and context), `LifecycleHistoryTest.deleteRemovesHistory` (history), `SchemaTest.deleteCascades` (all three tables, by SQL) | compiled only (container) | — |
| 15 | `PostgresSagaContextStoreTest.saveWithoutInstanceFails` | compiled only (container) | — |

No scenario is proven by a unit-run test alone. Scenario 14 has no single test that deletes, through
`PostgresStorageProvider.delete`, an instance holding both a context record and history rows; the three tests
above cover the parts separately.

## Known limitation exercised but not fully closed by this test plan

Scenario 7 above uses one step per `save()` call, matching User Story 3's own Independent Test wording — it
does not exercise two steps completing within the *same* `save()` call. Two steps newly `COMPLETED` in one
`incoming` snapshot get the documented `stepName` tiebreak (`String.compareTo`, G-003-04, G-003-16; research.md
§5, contracts/saga-lifecycle-entry.md) rather than true execution order. This is documented in runnable form by
`LifecycleHistoryTest.sameSaveStepsUseAlphabeticalTiebreakNotTrueOrder` (compiled only, container) and
`LifecycleHistoryDerivationTest.twoStepsAlphabetical` (unit-run); neither can be asserted to "pass" against
SC-003's literal wording for that case — see research.md §5 for why.
