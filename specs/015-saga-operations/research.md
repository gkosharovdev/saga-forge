# Research: Saga Operations: Query, Repair and Recovery

**Feature**: `specs/015-saga-operations` | **Date**: 2026-11-26 | **Spec**: [spec.md](spec.md)

The spec has no open clarification (session 2026-11-26). This file settles the design questions the plan depends on.
Each entry gives the decision, the rationale and the alternatives considered.

## Spike (2026-11-26)

Before proposing the new abstractions (Development Workflow: "abstraction tested before proposed"), a throwaway spike
(`java Spike.java` in the session scratchpad, not committed) exercised the shape of `SagaOperations` with an in-memory
implementation and a caller:

- `SagaInstanceQuery` (statuses, saga types, modified before/after, limit 1..500, `PageCursor`) with keyset paging over
  (`lastModifiedAt`, `sagaId`): 150 instances, 120 of type A with heavy ties on `lastModifiedAt` (7 distinct values),
  50 per page → 3 pages, each of the 120 seen exactly once.
- `OperatorRequest` rejects a blank operator or reason at construction.
- `RepairResult` as a sealed `Applied` / `NotApplied(reason, actualStatus)`: resolve `FAILED` → `Applied`; again →
  `NotApplied(ALREADY_RESOLVED)`; unknown id → `NotApplied(NOT_FOUND)`.

6 checks passed. The real tests are the storage query contract (R2) on three implementations and the operations tests
over the runtime's in-memory doubles.

## R1 — Where the operations API lives, and what a view is

**Decision**: The contracts go in a new core package `core.operations`: the `SagaOperations` interface and its values
(`SagaInstanceQuery`, `SagaInstancePage`, `PageCursor`, `OperatorRequest`, `RepairResult`, `SagaResolution`,
`InstanceCounts`, `RecoveryReport`). The runtime implements it package-private (`runtime.internal.EngineSagaOperations`)
and exposes it as `SagaRuntime.operations()`. An instance view **is** the existing `core.spi.SagaInstance` record, which
already holds every field FR-005 asks for and gains `resolution` and `compensationRound`. History is
`List<SagaLifecycleEntry>`. Context values come from a separate `context(sagaId)` call (D8), never inside the view.

**Rationale**: the project's posture is contracts in core and production implementations package-private in the runtime
(`CommandGateway`, `EventPublisher`, `SagaLifecycle`). The starter and the fixture depend only on the contract.
Reusing `SagaInstance` avoids a parallel view type that would have to track every new instance field. `SagaInstance.state`
is always empty in the runtime (G-ENG-06), so the view leaks no saga fields. Keeping context values behind their own
call makes "excluded by default" structural, not a flag (no boolean `includeContext` parameter).

**Alternatives**: a dedicated `SagaInstanceView` record (rejected for now: a copy of `SagaInstance` minus `state`, and a
second place to update for every field). A `find(sagaId, boolean withContext)` overload (rejected: a flag argument).
Putting the interface in the runtime module (rejected: the starter's endpoint would depend on the runtime's concrete
types; core's contract keeps it swappable for the fixture).

## R2 — Storage SPI for the query, counts and history

**Decision**: `StorageProvider` gains three required methods:

- `SagaInstancePage find(SagaInstanceQuery query)`: filtered, keyset-paged, ordered by (`lastModifiedAt`, `sagaId`)
  ascending.
- `InstanceCounts countByStatus(Set<String> sagaTypes)`: an empty set means every type.
- `List<SagaLifecycleEntry> history(String sagaId)`: in stored order; empty for an unknown id.

They are abstract, not defaults. The library has no storage implementations outside this repository (same decision as
013's `MessageStore` change, user 2026-11-25). A shared contract, `StorageProviderQueryContract` (runtime test-jar, cases
Q1–Q10 in [contracts/storage-query-spi.md](contracts/storage-query-spi.md)), runs against
`TransactionalInMemoryStorageProvider`, the fixture's `FixtureStorage` and `PostgresStorageProvider` (container). The
test double's existing `history(String) → List<StoredEntry>` helper is renamed `storedHistory` so that the SPI method can
take the name.

**Rationale**: FR-009, closes the history read half of G-003-24. Keyset paging is the standard answer for stable paging
over a table that changes (R3).

**Alternatives**: a separate `SagaInstanceQueries` SPI interface beside `StorageProvider` (considered for ISP; rejected
because every storage must implement both anyway, and the runtime would need a second plug-in slot, starter detection and
fixture wiring for no extra substitution). Default methods throwing `UnsupportedOperationException` (rejected: LSP; 013
removed that pattern).

## R3 — Paging

**Decision**: keyset paging. `SagaInstanceQuery.limit` is 1..500 (default 50, FR-003). `after` is an optional
`PageCursor(lastModifiedAt, sagaId)`. A page returns `limit + 1` probe rows internally, and its `next` cursor is present
only when more rows exist. Postgres:
`WHERE ... AND (last_modified_at, saga_id) > (?, ?) ORDER BY last_modified_at, saga_id LIMIT ?`, served by a new index
`saga_instance_status_modified (status, last_modified_at, saga_id)`. Over HTTP the cursor is an opaque token
(base64url of `epochNanos:sagaId`).

**Rationale**: an unchanged instance appears exactly once (SC-005). An instance modified during paging moves past the
cursor and may appear again on a later page. That is acceptable and documented: no snapshot across pages (spec edge
case). Offset paging would skip or repeat unchanged rows when earlier rows change.

**Alternatives**: offset/limit (rejected, above). A server-side snapshot (rejected: holds a transaction across HTTP calls).

## R4 — Resume compensation

**Decision**: a new work, `ResumeCompensation` (`UnitOfWork.Work<SagaInstance, RepairRejection>`), admitted for
`COMPENSATION_FAILED` and `COMPENSATING` instances with no resolution. Its write, through a new
`EngineSagaLifecycle.resumeCompensation(sagaId, request)`:

- From `COMPENSATION_FAILED`: status → `COMPENSATING`. Every step compensation in `FAILED` goes back to `NOT_ATTEMPTED`,
  including 012's compensated-then-command-failed steps (`COMPENSATED → FAILED`), so that their compensation runs again and
  sends its command again. The instance-level `failure` of the compensation phase is cleared. `compensationRound` + 1.
- From `COMPENSATING`: no state change except `lastModifiedAt`.
- Both append one `COMPENSATION_RESUMED` history entry with the `OperatorRequest`.

The caller then runs the existing `CompensationRun` under the same in-process instance lock, as `CommandFailureHandler`
does (012). `remainingCompensations` already picks `NOT_ATTEMPTED` steps newest first, so a step already `COMPENSATED`
or `NOTHING_TO_COMPENSATE` never runs again (FR-012). The earlier failures stay in the history (`COMPENSATION_FAILED`
entries carry them). Compensations that need it see the unchanged `CompensationTrigger`.

**Rationale**: it reuses the one compensation loop and keeps "a compensation runs at most once per round". The
`lastModifiedAt` write on a stalled resume also keeps a second sweeper from picking up the same instance right away
(R8).

**Alternatives**: keeping `FAILED` compensations and letting `RunCompensation` retry `FAILED` ones (rejected: changes
006's "never retried" rule for normal runs and needs a mode flag). A new compensation status `RETRYING` (rejected: a
storage and telemetry change with no reader). Recording `FAILED → COMPENSATED` directly (rejected: the run needs
`NOT_ATTEMPTED` as its "to do" marker).

## R5 — Operator compensate

**Decision**: `StartCompensation` gains an operator factory, `byOperator(trigger)`. Its admission differs from the
failure path: it admits `RUNNING` and `FAILED` (unresolved), and does not skip `NOTHING_COMMITTED`. With nothing
committed, the existing lifecycle start writes `COMPENSATED` at once with `SagaCompensated` (011 behaviour; story 4
scenario 5). It uses a new `CompensationTriggerKind.OPERATOR_REQUEST`. `CompensationTrigger` and `OutcomeTrigger` gain a
nullable `OperatorRequest operatorRequest` component, required if and only if the kind is `OPERATOR_REQUEST`. For that
kind, `stepName` is the most recently completed step, or the start step when none completed. `failure` is the instance's
recorded failure for a `FAILED` instance, or null. A `RUNNING` instance leaving for `COMPENSATING` closes its deadlines
and clears its pending retry through the existing `EngineSagaLifecycle.replace` rule (017). The history entry is the
existing `COMPENSATION_STARTED` kind, whose trigger names the operator.

**Rationale**: one compensation start path, distinguished by the admission only (no boolean). Step-targeted compensation
is unchanged (Principle II). `stepName` stays non-null, so existing readers of the trigger keep working.

**Alternatives**: a separate `OperatorCompensation` work duplicating `StartCompensation.perform` (rejected:
duplication). A `null` `stepName` for operator triggers (rejected: breaks the non-null contract that every
`@SagaCompensation` reader and the Postgres codec rely on).

## R6 — Mark resolved

**Decision**: a new work, `MarkResolved`, admitted for unresolved `FAILED` and `COMPENSATION_FAILED` instances. It sets
`SagaInstance.resolution = SagaResolution(operator, reason, resolvedAt)` and appends a `RESOLVED` history entry. The
status is unchanged and no outcome event is emitted (FR-016). A resolved instance is closed: resume and compensate refuse
it with `ALREADY_RESOLVED`. Counts and health distinguish resolved from unresolved (`InstanceCounts.unresolved`).

**Rationale**: the user's decision (spec Clarifications). No new status means no change for `SagaStatus` readers, the
telemetry `status` attribute, `HandlingOutcome` or Postgres status checks.

**Alternatives**: settled in the spec (new `RESOLVED` status rejected).

## R7 — History entries carry the operator

**Decision**: `SagaLifecycleEntry` gains a nullable `OperatorRequest operator` component. New kinds are
`COMPENSATION_RESUMED`, `RESOLVED` and `START_RECOVERED` (R10). An operator compensate reuses `COMPENSATION_STARTED`;
a failed abandoned start reuses `FAILED`. Postgres history gains columns `operator TEXT` and `operator_reason TEXT`. The
recovery sweeper acts as `OperatorRequest.recovery(reason)` with the fixed identity `saga-forge-recovery` (FR-026).

**Rationale**: FR-017. An existing kind is reused where the transition already has one, so there are few new kinds.

## R8 — Recovery sweeper

**Decision**: a runtime-internal `RecoverySweeper`, configured by a public `runtime.RecoverySettings` record (`enabled`
default false, `interval` 1 min, `stallAge` 5 min, `batchSize` 100, `abandonmentAge` 15 min, `abandonedStartPolicy`
`REPORT`|`FAIL`) through `SagaRuntime.Builder.recovery(RecoverySettings)`. One sweep is also public as
`SagaOperations.recover() → RecoveryReport`, so a host with its own scheduler (Quartz, ShedLock, a Kubernetes CronJob
behind an endpoint) and the fixture can run it. When `enabled`, the runtime runs `recover()` on one daemon thread
(`saga-forge-recovery`) from `start()` at the interval. The new `SagaRuntime.stop()` (idempotent) cancels it and waits
for a running sweep to finish (FR-025). The starter's `SagaRuntimeLifecycle.stop()` calls it.

Per sweep:
1. `find(statuses = COMPENSATING, modifiedBefore = now − stallAge, limit = batchSize)`, then for each:
   `ResumeCompensation` as the recovery operator. Its admission re-checks status, resolution and age under the lock
   (`StalledCompensation` admission variant), so an instance that moved on is skipped.
2. `find(statuses = CREATED, modifiedBefore = now − abandonmentAge, limit = batchSize)`, then for each, with no step
   completed: `REPORT` logs `saga.recovery.startAbandoned` and counts it, at most once per `abandonmentAge` per instance
   per process (an in-memory map, cleared when the instance leaves `CREATED`). `FAIL` runs `FailAbandonedStart`
   (admission: still `CREATED`, still old, no step completed), which writes `FAILED` with a `SagaFailure` of
   `core.operations.SagaStartAbandonedException`, classification `UNCLASSIFIED`, attempts 1, and emits `SagaFailed`.
3. An error on one instance is logged (`saga.recovery.failed`) and the sweep goes on (FR-024).

Time is the runtime's `Clock` (`TimeAndIdentity`), so the fixture drives it (FR-027).

**Rationale**: D5. Re-checking under the lock is what makes concurrent sweepers safe. A public `recover()` is the
familiar "run once, schedule it yourself" shape (Spring Batch job launch, ShedLock-guarded `@Scheduled` methods) and is
what the fixture needs without reaching into internals.

**Alternatives**: running the sweep only on the active relay (013) (rejected as the safety mechanism, deferred as an
optimisation: G-015-02). The runtime has no transport dependency; a `BooleanSupplier` gate would be an abstraction with
no proven need yet. A scheduler SPI (rejected: `recover()` already lets the host schedule).

## R9 — Abandoned-start report memory

**Decision**: the "report at most once per abandonment age" rule (FR-029) is kept in process memory. After a restart an
abandoned instance is reported again, once. This is recorded as a limitation (G-015-07).

**Rationale**: a durable "reported" marker would be a storage write for a log line.

## R10 — A redelivered start adopts a stranded instance

**Decision**: in `EventRouter.start`, when `EngineSagaLifecycle.start` throws `DuplicateSagaInstanceException`, the router
looks up the active instance of that (saga type, association property, value). If it is `CREATED` with no completed step,
the router runs the start step on it with `runUnitOfWork(reaction, existingId, started = false, lease)` under the
instance lock, instead of reporting `DUPLICATE_START`. `RunSteps` already admits a start step on a `CREATED` instance, and
its admission re-checks under the lock, so a concurrent first unit of work makes the adoption see `RUNNING` and report a
duplicate as before. The unit of work appends a `START_RECOVERED` entry before the step's own entries. The result is
reported as handled (`HandlingOutcome` of the step), with telemetry attribute `saga_forge.start.recovered = true`.

**Rationale**: FR-021. It closes G-ENG-05 and G-013-05 for every redelivering transport. The duplicate-start lock order
(duplicate-start lock → instance lock → storage) is kept. The instance lock is taken after the duplicate check fails, as
in the normal path.

**Alternatives**: committing the creation write together with the first unit of work (rejected: 004 FR-012 separates
them deliberately, and a creation write needs to exist before the context store's FK, research 003 §8). Having the
sweeper re-run a `CREATED` start (rejected: it has no event).

## R11 — Outcome event ids after a resume

**Decision**: `SagaInstance.compensationRound` (int, starts at 0, + 1 per resume from `COMPENSATION_FAILED`). Outcome
event ids stay `sagaId:STATUS` in round 0, so 011's ids are unchanged for every instance that is never resumed. From
round 1 on they are `sagaId:STATUS:r<round>`. The core factories (`Values.eventId`) take the round. Property 38 is revised
to "one outcome event per terminal write, with an id unique per instance" (FR-018, FR-041).

**Rationale**: the ids stay deterministic (a redelivered outbox message carries the same id), and consumers that
deduplicate by id keep working.

**Alternatives**: the unit-of-work id as a suffix (rejected: changes every existing id, and is not derivable from the
instance). A storage sequence number (rejected: the runtime does not see it).

## R12 — Storage forward-transition rules

**Decision**: Postgres `ForwardTransition` (and the in-memory doubles' equivalent checks) allow:

- `COMPENSATION_FAILED → COMPENSATING` only when `compensationRound` grows by exactly 1 in the same save. In that save, a
  step compensation may move `FAILED → NOT_ATTEMPTED`, and the instance `failure` may be cleared.
- `FAILED → COMPENSATING` only when the incoming trigger is `OPERATOR_REQUEST` and the previous trigger was null.
- `resolution` null → set on an unchanged `FAILED`/`COMPENSATION_FAILED` status. After that it never changes.
- `compensationRound` never decreases.

Every other backward transition stays rejected (FR-019).

## R13 — Telemetry

**Decision**: a span `saga_forge.operation` (attributes `saga_forge.operation.action` = `resume_compensation` |
`compensate` | `mark_resolved` | `recover`, `saga_forge.operation.result` = `applied` | `not_found` | `wrong_status` |
`already_resolved`, plus saga type and id). The compensation units of work keep their existing
`saga_forge.compensation` spans as children. There is a counter `saga_forge.operations{action, result}`, a counter
`saga_forge.recovery{kind = compensation_resumed | start_reported | start_failed | error}` and an observable gauge
`saga_forge.instances{saga_forge.saga.status, saga_forge.resolved}`. The gauge callback serves cached counts, refreshed
by one `countByStatus` call at most every `SagaTelemetrySettings.instanceCountInterval` (default 30 s, FR-031). Log
events: `saga.operation.applied`, `saga.operation.notApplied`, `saga.recovery.compensationResumed`,
`saga.recovery.startAbandoned`, `saga.recovery.startFailed`, `saga.recovery.failed`, `saga.start.recovered`.

## R14 — Starter: Actuator endpoint and security

**Decision**: a new endpoint, `@Endpoint(id = "sagas", defaultAccess = Access.READ_ONLY)` (`SagasEndpoint`). It needs
only the `SagaRuntime` bean and does not depend on the transport, unlike `sagaforge`.

- Read: `GET /actuator/sagas` lists, with query parameters `status`, `type`, `modifiedBefore`, `modifiedAfter`, `limit`
  and `cursor`, and returns `{instances, next, counts}`. `GET /actuator/sagas/{sagaId}` returns `{instance, history}`,
  plus `context` only when `saga-forge.operations.expose-context-values=true` (FR-035).
- Write: `POST /actuator/sagas/{sagaId}/{action}` with `action` = `resume-compensation` | `compensate` | `resolve` and a
  body `{reason}`. The operation method takes a `java.security.Principal`. With none it returns 401 and changes nothing
  (FR-034). A not-applied result returns 409 with the reason.
- Write access is Spring Boot's own access model: with `defaultAccess = READ_ONLY`, the writes exist only when the
  application sets `management.endpoint.sagas.access=unrestricted` (FR-033). Web exposure stays the application's
  (`management.endpoints.web.exposure.include`).

The sweeper is configured by `saga-forge.recovery.*` (enabled **true** in the starter, FR-036). The `sagaforge` health
indicator gains details `instances.compensationFailed` and `instances.failedUnresolved`, without a status change
(FR-037). Its runtime-backed part is registered also when there is no transport.

**Rationale**: `defaultAccess` and `Principal` injection are Spring Boot's built-in mechanisms (Boot 3.4+, available in
4.1). The opt-in is a Boot property operators already know, so there is no custom switch. Separating `sagas` from
`sagaforge` keeps the existing endpoint's shape and its dependency on the transport.

**Alternatives**: adding the operations to `sagaforge` (rejected: that endpoint only exists with the in-memory transport,
and its write access would be all-or-nothing). A custom `saga-forge.operations.write-enabled` property (rejected:
duplicates Boot's access control). A Spring MVC controller (rejected: Actuator is where operators look, and it brings
exposure and security integration).

## R15 — Test fixture

**Decision**: `SagaFixture.operations()` returns the runtime's `SagaOperations`. `SagaFixture.Builder.recovery(RecoverySettings)`
enables recovery. When enabled, `whenTimeElapses`/`whenTimeReaches`/`givenTimeElapses` run `operations().recover()` after
advancing the clock, so a stall becomes "advance 5 minutes". `InstanceView` gains `resolution()` and `history()`. `Then`
gains `expectResolved()`, `expectNotResolved()` and `expectHistory(SagaLifecycleEventKind...)` (a subsequence check).
The fixture never runs the sweeper's thread.

## Gaps to record (memory.md)

- **G-015-01** (already recorded): no retry of a failed step (roadmap 015b).
- **G-015-02**: the sweeper runs on every application instance; leader-only sweeping is an optimisation not taken.
- **G-015-03**: a resume resets failed compensations to `NOT_ATTEMPTED`, so the instance keeps only the latest round's
  failure; earlier ones live only in the history.
- **G-015-04**: repairs and sweeps run compensations synchronously and hold the instance (like G-006-08); a long
  compensation blocks the HTTP call.
- **G-015-05**: no bulk repair over a query.
- **G-015-06**: listings are not snapshot-consistent across pages.
- **G-015-07**: the abandoned-start report is deduplicated in process memory only (R9).
