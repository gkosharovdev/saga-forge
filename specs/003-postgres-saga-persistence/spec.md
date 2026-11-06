# Feature Specification: PostgreSQL Saga Persistence

**Feature Branch**: `003-postgres-saga-persistence`

**Created**: 2026-11-04

**Status**: Draft

**Input**: User description: "PostgreSQL saga persistence: concrete implementations of the core.spi contracts `StorageProvider` and `SagaContextStore`, living in the `saga-forge-storage-postgres` module. Scope: (1) StorageProvider Postgres implementation preserving existing SagaInstance invariants; (2) SagaContextStore Postgres implementation, transacted independently from SagaInstance so values survive unit-of-work rollback; (3) a saga lifecycle event/audit log capturing how an instance reached its current state (creation, step start/completion with outcome reference, failure), which has no existing vocabulary in saga-forge-core today; (4) unit-of-work/transaction management for the saga-scoped UoW, which AGENTS.md defers to 'the engine feature' as not yet existing."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Saga state survives a restart (Priority: P1)

A team runs an application built on Saga Forge. A saga instance is mid-flight (some steps completed, waiting for the next event) when the application process restarts (deploy, crash, scale-down/up). After restart, the saga instance resumes from exactly the state it was in before — no steps are re-run that already completed, and no steps are skipped.

**Why this priority**: Without this, "contracts only" persistence is a toy — every in-flight saga is lost on any restart, which makes the framework unusable for anything but a single long-lived process. This is the minimum viable slice: a working `StorageProvider` backed by Postgres.

**Independent Test**: Start a saga, complete one step, persist, simulate a restart (new process/JVM reads from the same database), fetch the instance by its sagaId, and verify its status, completed steps, and business context are identical to before the restart.

**Acceptance Scenarios**:

1. **Given** a saga instance has been started and one step has completed, **When** the instance is saved and later retrieved by its sagaId from a fresh process, **Then** the retrieved instance has the same status, step records, and associations as before.
2. **Given** no saga instance exists for a given sagaId, **When** it is looked up, **Then** the system reports "not found" rather than an error or a default/empty instance.
3. **Given** a saga instance has reached a terminal outcome, **When** it is retrieved, **Then** its outcome (if completed) or failure detail (if failed) is present and consistent with its status.

---

### User Story 2 - Business context survives a failed step (Priority: P1)

A saga step writes a business-context value (e.g., "payment attempt count") and then a later step in the same saga fails, rolling back that step's unit of work. The business-context value written earlier remains intact and readable by whatever handles the failure or a retry — it is not rolled back along with the failed step.

**Why this priority**: This is an already-documented correctness property (context values are decoupled from `SagaInstance` and must survive unit-of-work rollback). Getting this wrong silently corrupts saga business state and is hard to detect after the fact, so it is as critical as basic state durability.

**Independent Test**: Save a context value for a sagaId, then simulate a step whose unit of work rolls back (e.g., an exception during step execution after the context write), and verify the previously-saved context value is still present and unchanged.

**Acceptance Scenarios**:

1. **Given** a context value has been saved for a sagaId, **When** a later step's unit of work rolls back, **Then** the previously-saved context value is unaffected.
2. **Given** a context value has been saved for a sagaId, **When** the same key is saved again with a new value, **Then** retrieving the context returns the latest value only.
3. **Given** a saga has no context recorded yet, **When** its context is looked up, **Then** the system reports "not found" rather than an error.

---

### User Story 3 - Reconstructing how a saga got here (Priority: P2)

An operator or developer investigating a stuck or failed saga instance wants to see the sequence of events that led to its current state — when it was created, which steps ran and in what order, what outcome or failure occurred — not just the final snapshot.

**Why this priority**: Debugging a snapshot-only system means guessing at history. This is valuable but secondary to simply having durable, correct state (User Stories 1–2); a team can operate without it, just with more effort.

**Independent Test**: Drive a saga instance through creation, two step completions, and a terminal outcome; then query the persisted history for that sagaId and verify it lists the transitions in the order they actually occurred, including which step produced which outcome/failure.

**Acceptance Scenarios**:

1. **Given** a saga instance has been created, had steps complete, and reached a terminal state, **When** its history is read, **Then** the entries appear in the exact order the transitions occurred.
2. **Given** a step's unit of work rolled back (never committed), **When** the history is read, **Then** no entry claims that step completed.
3. **Given** a saga instance failed, **When** its history is read, **Then** the failure entry identifies which step failed and why, consistent with the failure detail on the final snapshot.

---

### User Story 4 - Partial failures don't corrupt state (Priority: P2)

While a step is being persisted, something goes wrong partway through (e.g., the database connection drops after writing part of the step's data). The saga instance must never be left in a state that mixes "old" and "new" data for the same step — either the whole step's persistence succeeds, or none of it does.

**Why this priority**: This is what makes User Stories 1–3 trustworthy under real-world failure conditions rather than just the happy path. Ranked after the first three because it's about hardening an already-working mechanism, not delivering new capability.

**Independent Test**: Simulate a failure injected between two of the writes a single step's persistence would normally perform, then verify the saga instance's persisted state matches either the pre-step or fully-completed-step state — never a mix of the two.

**Acceptance Scenarios**:

1. **Given** a step's persistence involves multiple writes, **When** a failure occurs between those writes, **Then** none of that step's partial writes are visible on retrieval.
2. **Given** two different processes attempt to persist a change to the same saga instance at the same time, **When** both attempts are made, **Then** at most one succeeds and the other is detectably rejected — the instance is never silently left in a state that reflects only part of either attempt.

---

### Edge Cases

- What happens when a saga instance is deleted while a `SagaContextRecord` still exists for the same sagaId — is the context removed too, or does it persist orphaned? (See Assumptions.)
- What happens when `findByAssociation` is called for an association property/value with no matches? Must return an empty result, not an error.
- What happens when two processes try to advance/complete the same saga instance concurrently? One must win detectably; the other must not silently overwrite or be silently discarded.
- What happens when the history/audit log write and the `SagaInstance` snapshot write for the same step are not both committed (one succeeds, one doesn't)? Must not happen — see User Story 4.
- What happens when a saga instance is retrieved whose per-step data was written by an older version of the framework (schema evolution over time)? Out of scope for this feature; assumed to be addressed by future migration tooling.
- What happens when a business-context value is very large (e.g., megabytes of nested data)? Out of scope for size limits in this feature; no enforced cap is specified here.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST durably persist a saga instance's full state (identity, saga type, business state, associations, status, timestamps, per-step records, outcome, failure detail) such that it can be reconstructed with no data loss after an application restart.
- **FR-002**: System MUST allow retrieving a saga instance by its sagaId, distinguishing "found" from "not found."
- **FR-003**: System MUST allow retrieving all saga instances matching a given association property/value pair, returning an empty result (not an error) when none match.
- **FR-004**: System MUST allow permanently removing a saga instance's persisted state by its sagaId.
- **FR-005**: System MUST persist a saga's business-context values independently of its instance state, keyed by sagaId, such that those values remain unaffected when a step's unit of work rolls back.
- **FR-006**: System MUST allow retrieving, saving, and deleting a saga's persisted business-context values by sagaId, distinguishing "found" from "not found" on retrieval.
- **FR-007**: System MUST record a chronological history of a saga instance's lifecycle transitions (creation, each step's completion with its outcome/failure reference, terminal completion or failure) sufficient to reconstruct how the instance reached its current state, expressed as new storage-agnostic vocabulary in `saga-forge-core` rather than a Postgres-specific record — so any future storage backend can produce and consume the same history shape.
- **FR-008**: System MUST commit a step's saga-instance state update and its corresponding history entry together, or roll back both together — one must never be persisted without the other.
- **FR-009**: System MUST ensure a step whose unit of work rolled back leaves no record (in instance state or history) suggesting it completed.
- **FR-010**: System MUST detect concurrent conflicting attempts to persist a change to the same saga instance, so that at most one attempt wins and the losing attempt is surfaced rather than silently discarded or silently overwriting.
- **FR-011**: System MUST preserve the existing saga-instance invariants through every persistence round-trip (outcome present if and only if status is completed; failure detail present if and only if status is failed; per-step records are never lost or duplicated).
- **FR-012**: System MUST durably record the lifecycle history from FR-007 such that it is inspectable via direct data-store access (e.g., standard database querying). A programmatic query/retrieval API for that history is explicitly OUT OF SCOPE for this feature and deferred to a later observability feature.
- **FR-013**: System MUST bracket a step's saga-instance write and its corresponding lifecycle-history write in a single database transaction internal to `saga-forge-storage-postgres`, without introducing a new public core SPI for unit-of-work/transaction boundaries. That abstraction remains reserved for a future, separate engine feature, per `specs/002-transactional-step-sequence/research.md` §9 ("committing to a transaction SPI before the engine exists risks designing it twice").
- **FR-014**: Implementation MUST use the already-documented behavioral contract in `specs/002-transactional-step-sequence/contracts/saga-unit-of-work.md` and the test-only `SagaTestHarness`/`HarnessUnitOfWork` as the reference for commit/rollback timing and for keeping `SagaContext` writes outside the transaction (FR-005) — as a design reference only, not something this feature promotes into a new core type.

### Key Entities

- **Saga Instance**: The existing per-saga durable record (identity, type, business state, associations, status, timestamps, per-step records, outcome, failure). This feature persists it; it does not change its shape.
- **Step Record**: The existing per-step outcome within a saga instance (whether it ran, what it touched). Persisted as part of the saga instance.
- **Saga Failure**: The existing record of which step failed, why, and when, attached to a failed saga instance.
- **Saga Context Record**: The existing per-saga business-context value set, stored and transacted independently of the saga instance.
- **Saga Lifecycle History Entry** *(new shared core vocabulary)*: A single chronological record of a transition a saga instance went through (e.g., created, a specific step completed with its outcome, reached a terminal outcome, or failed). Defined in `saga-forge-core` as storage-agnostic vocabulary; this feature provides its first (Postgres) persistence, but the shape itself is not Postgres-specific.
- **Saga-Scoped Unit of Work** *(existing behavioral contract; reference only, not a new type)*: The begin/run/commit/rollback behavior already specified in `specs/002-transactional-step-sequence/contracts/saga-unit-of-work.md` and proven by the test-only `SagaTestHarness`/`HarnessUnitOfWork`. This feature satisfies that behavior's persistence-side requirements (FR-008, FR-009, FR-013) with a transaction mechanism internal to `saga-forge-storage-postgres`. It does NOT promote this into a new public `saga-forge-core` SPI — that stays reserved for a future, separate engine feature.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of saga instances retrieved after a simulated restart match the state they had immediately before the restart (status, steps, outcome/failure, associations).
- **SC-002**: 100% of business-context values survive a rolled-back step unaffected, across all tested rollback scenarios.
- **SC-003**: For any saga instance driven through creation, steps, and a terminal state, the recorded history reproduces the actual order of transitions with zero omissions or out-of-order entries.
- **SC-004**: Under concurrent attempts to modify the same saga instance, zero instances end up in a corrupted or partially-overwritten state across repeated concurrency testing.
- **SC-005**: A typical save/find/delete operation completes within standard database round-trip latency and is not the dominant cost of executing a saga step under normal load.

## Assumptions

- This feature reuses the existing `StorageProvider`, `SagaContextStore`, `SagaInstance`, `StepRecord`, `StepStatus`, `SagaFailure`, and `SagaContextRecord` contracts as-is; it does not change their shape.
- Per Constitution Principle I (Zero-Dependency Core), all PostgreSQL-specific code (schema, connection handling, SQL) lives in `saga-forge-storage-postgres`; `saga-forge-core` gains no new dependency on PostgreSQL or JDBC.
- Concurrent-write detection (FR-010) is assumed to use an optimistic check (e.g., a version marker) rather than a schema change visible to framework consumers, since no explicit mechanism was specified. **Superseded by research.md §3 (row lock plus forward-transition check).**
- Deleting a saga instance is assumed to also remove its associated context record, to avoid orphaned business-context data. If the two should remain independently deletable, that should be revisited before planning.
- A saga's business-context record may only be saved once its saga instance has been persisted (instance first); saving context for an unknown sagaId fails. Decided during planning to keep referential integrity and the delete cascade — see research.md §8.
- Schema design, connection pooling, and migration tooling are implementation decisions for the planning phase, not specified here.
- This feature does not deliver a general, cross-instance queryable "process-level state" store — the constitution explicitly calls that out as a separate feature gap. It persists the existing per-instance contracts only.
- This feature does NOT introduce a new public core unit-of-work/transaction-boundary SPI. Per `specs/002-transactional-step-sequence/research.md` §9, that abstraction is deliberately reserved for a future, separate engine feature, to avoid designing it before the engine exists. This feature meets its own atomicity requirements (FR-008, FR-009, FR-013) with a transaction mechanism internal to `saga-forge-storage-postgres`, treating the already-documented behavioral contract (`specs/002-transactional-step-sequence/contracts/saga-unit-of-work.md`) and `SagaTestHarness`/`HarnessUnitOfWork` purely as a design reference.
- Because FR-007 adds new public vocabulary to `saga-forge-core` (the lifecycle-history entry shape), planning MUST explicitly verify it against Constitution Principle I (storage- and transport-agnostic) and Principle III (validate against `.kiro/specs/*/design.md` correctness properties) before implementation. FR-013/FR-014's transaction handling, by contrast, is internal to `saga-forge-storage-postgres` and adds no new core surface.
- Programmatic querying of the lifecycle history (beyond direct data-store access) is out of scope here; this feature only ensures the history is durably recorded in storage-agnostic form, per FR-012. A future observability feature is expected to build a query/retrieval API on top of it.

## Clarifications

### Session 2026-11-04

- Q: Should the lifecycle-history entries (FR-007) become new, shared vocabulary in `saga-forge-core` (visible to all storage backends, not just Postgres), or stay internal to `saga-forge-storage-postgres` as an implementation detail of this one backend? → A: New shared vocabulary in `saga-forge-core` (Option A). See FR-007, FR-012, Key Entities.
- Q: Does this feature need to introduce the saga-scoped unit-of-work/transaction-bracketing abstraction that AGENTS.md says doesn't exist yet (deferred to "the engine feature"), or should it assume a minimal, Postgres-module-internal transaction mechanism for now and leave the formal cross-cutting SPI to the engine feature? → A: Initially answered Option A (introduce it now). See "Revision 2026-11-05" below — this was reversed to Option B after discovering prior project research already settled this question.
- Q: Should the lifecycle history (FR-012) be exposed via a programmatic query capability in this feature, or is "durably recorded, queryable only via direct database access" sufficient for now, with a programmatic query/observability API deferred to a later feature? → A: Persist-only; programmatic querying deferred to a future observability feature (Option B). See FR-012, Assumptions.

### Revision 2026-11-05

- While scoping follow-on work, `specs/002-transactional-step-sequence/research.md` §9 and §11 were found to already document this exact decision: *"No transaction SPI is added to core in this feature; how the production engine opens, commits and rolls back the host application's local transaction is decided in the engine feature... Committing to a transaction SPI before the engine exists risks designing it twice. The harness becomes the reference model the engine must match."* `specs/002-transactional-step-sequence/contracts/saga-unit-of-work.md` and `HarnessUnitOfWork` already specify and prove the behavioral contract this feature must satisfy.
- Revised answer to the unit-of-work question above: **Option B** — this feature does NOT introduce a new public core unit-of-work SPI. It satisfies FR-008/FR-009's atomicity requirement with a transaction mechanism internal to `saga-forge-storage-postgres`, using 002's existing contract and harness as a design reference only. FR-013/FR-014, Key Entities, and Assumptions were updated accordingly. The formal cross-cutting `UnitOfWork`/`TransactionBoundary` SPI remains reserved for a future, separate engine feature, consistent with 002's original research.
