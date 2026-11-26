# Feature Specification: Saga Operations: Query, Repair and Recovery

**Feature Branch**: `015-saga-operations`

**Created**: 2026-11-26

**Status**: Implemented (2026-11-26)

**Input**: User description: "015 saga operations: query, repair and recovery (roadmap `.specify/memory/roadmap.md` brief 015, without retry)" — closes G-006-03, G-006-05, G-008-02, G-ENG-05, G-013-05; addresses G-012-04 and G-017-07; booking-service feedback SF-5, workaround W10. Retrying a failed step (`retry(sagaId)`) is out of scope by user decision (2026-11-26) and becomes its own roadmap item.

## Context

The runtime keeps rich, durable process state for every saga instance: status, per-step records with attempts,
completion order and compensation status, the failure and its classification, the compensation trigger, open deadlines
and a lifecycle history. None of it is reachable through a supported API: applications read it only by calling the
`StorageProvider` SPI themselves, and the starter's `sagaforge` Actuator endpoint reports message-store counts only
(G-008-02).

Worse, some instances can never move again without hand-written SQL:

- A compensation that failed ends the instance `COMPENSATION_FAILED`, a dead end with no way out (G-006-03).
- A crash in the middle of compensation leaves the instance `COMPENSATING`. Only the next event routed to that instance
  resumes it; an instance that receives no further event stalls forever (G-006-05). The same holds after a crash
  between the reaction to a failed outbox command and its compensations (G-012-04), and a deadline that comes due for a
  `COMPENSATING` instance is ignored rather than resuming it (G-017-07).
- A crash between an instance's creation write and its first unit of work leaves it `CREATED` with no step run, and a
  redelivered start event is rejected as a duplicate, so nothing ever resumes it (G-ENG-05). Since 013 the window is
  reachable by a crash of any one instance among several during a failover (G-013-05).

This feature gives operators and applications a supported way to **find** saga instances, **repair** ones that need a
human decision, and lets the runtime **recover** stranded and stalled ones on its own. Every repair runs through the
runtime's one unit of work (011), is recorded in the instance's lifecycle history with who did it and why, and, when it
ends the instance, emits the outcome event (011) like any other terminal write.

## Decisions Taken In This Spec

These settle the questions in the roadmap brief; each is reflected in the requirements below.

- **D1 — No retry in this feature.** Re-running a failed step needs the event that triggered it, which the runtime
  does not keep. Storing it (or retrieving it from the transport) decides a feature of its own; it is recorded as a gap
  and a roadmap item (FR-040).
- **D2 — Three repair actions, distinct by effect.**
  - *Resume compensation* continues an instance that is `COMPENSATION_FAILED` or stalled in `COMPENSATING`: it runs the
    failed compensation again and every remaining one, newest first, as compensation already does (006).
  - *Compensate* is an operator's decision to undo a `RUNNING` or `FAILED` instance's committed steps: it starts
    compensation exactly as a `FINAL` failure would, with an operator trigger carrying the reason.
  - *Mark resolved* closes a `FAILED` or `COMPENSATION_FAILED` instance with **no** further effect: no step,
    compensation or command runs, and the status stays; a resolution (operator, reason, time) is attached. The operator
    states that the remaining work was handled outside the saga, and the instance stops counting as needing attention.
  "Cancel" is not a separate action: cancelling a running saga is *compensate*.
- **D3 — Operator identity is part of each request.** Every repair action takes an operator request: who (a non-blank
  identity), why (a non-blank reason). The runtime records both and the time; it does not look the identity up itself.
  The starter fills the identity from the authenticated caller of the HTTP endpoint. A host SPI for identity is not
  needed: the caller of the API always knows who it is acting for.
- **D4 — Repairs are admitted against the locked instance.** Each action is a work on the runtime's one unit of work
  (011), whose admission checks the instance's current status under the instance lock. An action on an instance in the
  wrong status changes nothing and reports why (not found, wrong status, already resolved); it never throws for a
  status mismatch.
- **D5 — Recovery is safe on every application instance.** The recovery sweeper is idempotent: it selects candidates
  by status and age, then re-checks each one under the instance lock (`findByIdAndLock`) before acting, so two
  application instances sweeping at the same time never run the same compensation or start step twice. Running it only
  on the active relay (013) is an optimisation the starter may apply, not the safety mechanism.
- **D6 — A redelivered start event adopts a stranded start.** When the start event of an instance arrives again and the
  instance is still `CREATED` with no step completed, the runtime runs the start step on that instance instead of
  rejecting the event as a duplicate start. This closes the transport-backed case of G-ENG-05 and G-013-05, where the
  start event's message is unconsumed and is redelivered.
- **D7 — HTTP write operations are off by default and require an authenticated caller.** The starter exposes read
  operations through Actuator by default; write operations need an explicit property. Even when enabled, a write
  request without an authenticated principal is refused, so the recorded operator identity is never empty or invented.
  Who may call them (roles, network exposure) stays the application's security configuration.
- **D8 — Context values stay out of views by default.** The instance view carries process state only. `SagaContext`
  values are business data and may be sensitive; they are included only when the caller explicitly asks for them, and
  never over HTTP unless the application enables it. Editing them is out of scope.
- **D9 — Repairs that end an instance emit an outcome event.** A repair that leaves the instance `COMPENSATED` or
  `COMPENSATION_FAILED`, and a failed abandoned start (FR-029), emit the matching outcome event in its unit of work.
  Marking resolved emits none: it changes no status. Because an
  instance can now reach a terminal status more than once (for example `COMPENSATION_FAILED`, resumed, then
  `COMPENSATED`), "one outcome event per instance" (011, Property 38) is revised to "one outcome event per terminal
  write", each with a distinct event id.

## Clarifications

### Session 2026-11-26

- Q: Which statuses may *mark resolved* close, and how is it stored? → A: Only `FAILED` and `COMPENSATION_FAILED`; the
  status stays and a resolution (operator, reason, time) is attached; no new status, no outcome event (FR-016).
- Q: What does the sweeper do with an abandoned `CREATED` instance that is never redelivered? → A: A configurable
  abandoned-start policy: `report` by default, `fail` (end it `FAILED`, emit `SagaFailed`) when configured (FR-029).
- Q: Is retrying a failed step part of this feature? → A: No; it becomes its own roadmap item (D1, FR-040).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Find saga instances and see what happened (Priority: P1)

An operator investigating a customer complaint, or an application showing process progress, looks up a saga instance
by its id or by a business key (association), or lists instances by status, saga type and last-modified time, and sees
its status, every step with attempts and timing, the failure and its classification, the compensation status of each
step and the outcome.

**Why this priority**: every repair starts with finding the instance; and reading state is useful on its own (support,
dashboards, alerting) with no risk to running sagas.

**Independent Test**: start several sagas that end in different statuses, then query by id, by association and by
status filter with paging, and compare the views with the stored state.

**Acceptance Scenarios**:

1. **Given** a completed saga instance, **When** it is looked up by its saga id, **Then** the view shows `COMPLETED`,
   its outcome step, and every step with its status, attempt count and completion order.
2. **Given** an instance started for association `orderId = 42`, **When** instances are looked up by `orderId = 42`,
   **Then** that instance is returned, together with any other instance of any saga type sharing that association.
3. **Given** 120 `FAILED` instances of one saga type and 30 of another, **When** `FAILED` instances of the first type
   are listed 50 per page, **Then** three pages return all 120 exactly once, in a stable order, and none of the other
   type.
4. **Given** an instance whose context holds values, **When** it is viewed without asking for context values, **Then**
   no context value appears in the view.
5. **Given** an unknown saga id, **When** it is looked up, **Then** the result is empty, not an error.

---

### User Story 2 - Get a COMPENSATION_FAILED saga moving again (Priority: P1)

A compensation failed because a downstream service was down. The operator fixes the cause and asks the runtime to
resume compensation. The failed compensation runs again, the remaining ones follow, and the instance ends
`COMPENSATED`.

**Why this priority**: `COMPENSATION_FAILED` is today a dead end that leaves committed effects in place with no
supported exit (G-006-03); it is the adopter's highest-cost workaround.

**Independent Test**: make a compensation fail, then succeed on its next run; call resume and check the final status,
the order of compensations, the history entries and the outcome event.

**Acceptance Scenarios**:

1. **Given** a three-step saga compensating, whose step-2 compensation failed (instance `COMPENSATION_FAILED`), **When**
   the operator resumes compensation with a reason and the step-2 compensation now succeeds, **Then** step 2 and then
   step 1 are compensated, the instance ends `COMPENSATED`, and one `SagaCompensated` outcome event is emitted.
2. **Given** the same instance, **When** the resumed compensation fails again, **Then** the instance is
   `COMPENSATION_FAILED` again, the new failure is recorded on the step, and one new `SagaFailed` (compensation phase)
   outcome event with an id distinct from the first is emitted.
3. **Given** a resumed instance, **When** its lifecycle history is read, **Then** an entry shows the resume with
   operator, reason and time, followed by the compensation entries.
4. **Given** a `COMPLETED` instance, **When** resume compensation is requested, **Then** nothing changes and the result
   says the instance is in the wrong status.

---

### User Story 3 - Stalled and stranded instances recover on their own (Priority: P1)

After a crash or a failover, an instance stuck in `COMPENSATING` with no further event, or stuck in `CREATED` before
its first step, is picked up by the runtime without an operator: compensation is resumed, and a stranded start is run
when its start event is redelivered.

**Why this priority**: with several application instances (013), any single crash can strand a start (G-013-05) or
stall a compensation (G-006-05, G-012-04). These are silent: nobody notices until a customer does.

**Independent Test**: inject a crash after the creation write, and another after the first compensation's commit;
restart, and check that both instances reach their terminal status without a new business event and without an
operator.

**Acceptance Scenarios**:

1. **Given** an instance left `COMPENSATING` by a crash after its first compensation committed and the sweeper enabled,
   **When** the stall age passes, **Then** the sweeper resumes compensation and the instance ends `COMPENSATED`, with
   each compensation run exactly once.
2. **Given** an instance left `CREATED` by a crash before its first unit of work committed, with its start event's
   message still unconsumed, **When** the message is redelivered, **Then** the start step runs on that instance, the
   saga continues normally, and no duplicate instance is created.
3. **Given** two application instances sharing one database, both with the sweeper enabled, **When** both sweep the
   same stalled instance at the same time, **Then** every compensation runs exactly once and the instance ends in one
   terminal status.
4. **Given** an instance that is `COMPENSATING` and actively progressing (last modified more recently than the stall
   age), **When** the sweeper runs, **Then** it leaves the instance alone.
5. **Given** the sweeper disabled (the default in the runtime), **When** an instance stalls, **Then** nothing resumes it
   automatically, and it shows in the status counts and in a `COMPENSATING` listing.

---

### User Story 4 - Compensate or close a saga by operator decision (Priority: P2)

A saga is `RUNNING` but the business wants it undone (a customer cancelled by phone), or a saga `FAILED` with an
unclassified failure and its committed effects must be rolled back. The operator asks the runtime to compensate it.
Separately, an instance whose remaining work was handled by hand is marked resolved, with no further effect.

**Why this priority**: valuable, but less urgent than the dead-end and stranded cases: today these situations can be
handled by a business event that triggers a `@StartCompensation` step, or left as they are.

**Independent Test**: compensate a `RUNNING` and a `FAILED` instance; mark a `COMPENSATION_FAILED` instance resolved;
check statuses, compensations run, history entries and outcome events.

**Acceptance Scenarios**:

1. **Given** a `RUNNING` instance with two committed steps, **When** the operator compensates it with a reason, **Then**
   both steps are compensated newest first, the instance ends `COMPENSATED`, and the outcome event's trigger says it was
   an operator request.
2. **Given** a `FAILED` instance (unclassified failure) with one committed step, **When** the operator compensates it,
   **Then** that step is compensated and the instance ends `COMPENSATED`.
3. **Given** a `COMPENSATION_FAILED` instance, **When** the operator marks it resolved with a reason, **Then** no
   compensation or command runs, the instance is recorded as resolved by that operator, and it no longer counts as
   needing attention.
4. **Given** a `RUNNING` instance whose deadline is open, **When** the operator compensates it, **Then** the open
   deadline is closed, as whenever an instance leaves `RUNNING` (017).
5. **Given** an instance with no committed step, **When** the operator compensates it, **Then** no compensation runs, the
   instance ends `COMPENSATED` with one `SagaCompensated` outcome event, and the history records the request.

---

### User Story 5 - Operate sagas over HTTP and dashboards (Priority: P2)

In a Spring Boot application, an operator lists and views saga instances through the Actuator endpoint, sees
per-status counts on a dashboard, and — when the application enabled it and the operator is authenticated — resumes,
compensates or resolves an instance over HTTP.

**Why this priority**: it makes stories 1, 2 and 4 usable in production without application code, but the runtime API
already delivers the value for hosts that wire their own surface.

**Independent Test**: start the Spring example, drive sagas into several statuses, call the read operations, enable
writes with an authenticated user, call a repair, and check the gauges.

**Acceptance Scenarios**:

1. **Given** the starter with default properties, **When** the instance-list read operation is called, **Then** it
   returns instances; **When** a write operation is called, **Then** it is not available.
2. **Given** writes enabled and an authenticated user `alice`, **When** `alice` resumes compensation of an instance,
   **Then** the history records `alice` as the operator.
3. **Given** writes enabled and an unauthenticated request, **When** a write operation is called, **Then** it is
   refused and nothing changes.
4. **Given** instances in several statuses, **When** metrics are scraped, **Then** a gauge per status reports the
   number of instances in it.

---

### Edge Cases

- A repair and a business event for the same instance arrive at the same time: the instance lock orders them; the
  second sees the first's result and is admitted or skipped against it.
- Resume compensation on a `COMPENSATING` instance that is not stalled (another node is mid-compensation): the action
  waits for the lock, then continues from what is committed; a compensation already done is never run again.
- Resume compensation on a `COMPENSATION_FAILED` instance whose failing compensation method no longer exists after a
  deployment: startup validation already fails for a missing mapped step; at runtime the resume records a failure and
  leaves the instance `COMPENSATION_FAILED`.
- Mark resolved on an instance already resolved: nothing changes; the result says it is already resolved.
- Mark resolved on a `RUNNING`, `CREATED` or `COMPENSATING` instance: refused as wrong status (FR-016); the operator
  compensates a `RUNNING` instance instead, resumes a `COMPENSATING` one, and handles an abandoned `CREATED` one through
  the abandoned-start policy (FR-029) and then marks it resolved once `FAILED`.
- Compensate on a `COMPENSATED` or `COMPLETED` instance: refused as wrong status. Compensating a completed saga is a new
  business process, not a repair (G-012-02).
- A stranded `CREATED` instance whose start event is never redelivered (at-most-once outbox, or the message was set
  aside): handled by the abandoned-start policy (FR-029): reported by default, ended `FAILED` when configured to fail.
- A start event redelivered after the sweeper failed the abandoned instance: the instance is no longer `CREATED`, so
  FR-021 does not apply; since only an active instance blocks a start, a new instance starts. This is why `report` is
  the default policy.
- A redelivered start event for a `CREATED` instance that a concurrent first unit of work is completing: the lock
  serialises them; the second sees `RUNNING` (or later) and is handled as today (duplicate start rejected).
- The sweeper finds thousands of stalled instances after an outage: it works in bounded batches per sweep, so one sweep
  never holds a transaction or connection for the whole backlog.
- A deadline comes due for a `COMPENSATING` instance: still ignored (017 FR-013); the sweeper, not the deadline,
  resumes it.
- A listing page is requested while instances change status: each instance appears at most once per page; results
  reflect committed state at query time; no snapshot isolation across pages is promised.
- Outcome events disabled (`outcomeEvents(false)`): repairs emit none, as with every other terminal write.

## Requirements *(mandatory)*

### Functional Requirements

**Query and view**

- **FR-001**: The runtime MUST offer a saga operations API, obtainable from the running runtime, that finds one
  instance by saga id and returns an empty result for an unknown id.
- **FR-002**: The API MUST find all instances, of any saga type, that hold a given association property and value.
- **FR-003**: The API MUST list instances matching a filter of any combination of: one or more statuses, one or more
  saga types, last modified before, last modified after; with paging (page size bounded, default 50, maximum 500) in a
  stable order (last modified, then saga id) so that every matching instance unchanged during paging appears exactly
  once.
- **FR-004**: The API MUST count instances per status, optionally restricted to saga types.
- **FR-005**: An instance view MUST carry: saga id, saga type, associations, status, created and last-modified times,
  outcome step, failure (step, classification, cause type, attempts, message as stored), compensation trigger, open
  deadlines, resolution (when resolved), and per step: status, attempts, completion order, and compensation status with
  its time and failure.
- **FR-006**: An instance view MUST NOT include `SagaContext` values unless the caller explicitly asks for them; when
  asked, values are returned as stored.
- **FR-007**: The API MUST return an instance's lifecycle history in occurrence order, including the operator entries
  of this feature.
- **FR-008**: Query operations MUST be read-only: they take no instance lock and change no state.
- **FR-009**: Storage MUST support the paged, filtered query and the per-status count of FR-003 and FR-004; the Postgres
  storage and every in-memory storage double (runtime test doubles, the test fixture) MUST implement them and pass one
  shared contract test.

**Repair actions**

- **FR-010**: Every repair action MUST take an operator request with a non-blank operator identity and a non-blank
  reason, and MUST reject a request missing either before touching the instance.
- **FR-011**: Every repair action MUST run as a work on the runtime's single unit of work, admitted against the
  instance's status read under the instance lock, and MUST return a typed result: applied, or not applied with the
  reason (not found, wrong status with the actual status, already resolved).
- **FR-012**: *Resume compensation* MUST be admitted for `COMPENSATION_FAILED` and `COMPENSATING` instances. It MUST
  move a `COMPENSATION_FAILED` instance back to `COMPENSATING`, then run, newest first by completion order, every step
  compensation whose status is failed or not yet attempted, each in its own unit of work as in 006; steps already
  compensated or with nothing to compensate MUST NOT run again.
- **FR-013**: *Compensate* MUST be admitted for `RUNNING` and `FAILED` instances. It MUST start compensation with an
  operator trigger (operator, reason, time) as the compensation trigger, and then compensate every committed step as
  006 does; the trigger MUST be visible to `@SagaCompensation` methods that take a `CompensationTrigger` and on the
  ending outcome event.
- **FR-014**: *Compensate* on a `RUNNING` instance MUST close its open deadlines and clear a pending retry, as every
  transition out of `RUNNING` does (017).
- **FR-015**: *Mark resolved* MUST run no step, compensation or command, and MUST record a resolution (operator,
  reason, time) on the instance.
- **FR-016**: *Mark resolved* MUST be admitted only for `FAILED` and `COMPENSATION_FAILED` instances without a
  resolution. The instance keeps its status; the resolution is attached to it. No new status is introduced and no
  outcome event is emitted (the terminal write already emitted one). To close a `RUNNING` instance, an operator
  compensates it (FR-013). (User decision, 2026-11-26.)
- **FR-017**: Every applied repair action MUST append lifecycle history entries recording the action, operator, reason
  and time, in the same transaction as the state change.
- **FR-018**: A repair whose unit of work writes a terminal status MUST emit the matching outcome event in that unit of
  work (011); every outcome event MUST have a distinct event id, also when one instance reaches the same terminal
  status more than once.
- **FR-019**: A storage forward-transition check MUST allow exactly the operator transitions of this feature
  (`COMPENSATION_FAILED → COMPENSATING`, `FAILED → COMPENSATING`) and the resolution write on an unchanged `FAILED` or
  `COMPENSATION_FAILED` status, and continue to reject every other backward transition.
- **FR-020**: Repair actions MUST be safe with several application instances: an action and a concurrent event,
  deadline, command-failure report or sweep on the same instance are serialised by the instance lock, and no step or
  compensation runs twice because of the overlap.

**Recovery**

- **FR-021**: When the start event of an instance is delivered again while the instance is `CREATED` and no step has
  completed, the runtime MUST run the start step on that instance instead of rejecting the event as a duplicate start.
- **FR-022**: The runtime MUST offer an optional recovery sweeper, disabled by default in the runtime builder, with a
  configurable sweep interval (default 1 min), stall age (default 5 min) and batch size (default 100).
- **FR-023**: Each sweep MUST select `COMPENSATING` instances last modified earlier than now minus the stall age and,
  for each, re-check status and age under the instance lock and resume compensation (as FR-012, with the runtime as
  operator and a fixed recovery reason) only if both still hold.
- **FR-024**: The sweeper MUST be safe to run on every application instance at once (FR-020), MUST process at most the
  batch size per sweep, and MUST keep running after an error on one instance, reporting it.
- **FR-025**: The sweeper MUST stop with the runtime and MUST NOT start a new instance's work after the runtime began
  stopping.
- **FR-026**: Compensation resumed by the sweeper MUST be recorded in history like an operator resume, with the
  runtime's recovery identity as operator.
- **FR-027**: The sweeper's time source MUST be the runtime's clock, so the test fixture can drive it by advancing time.
- **FR-028**: A deadline that comes due for a `COMPENSATING` instance MUST stay ignored (017); recovery of such an
  instance is the sweeper's job (G-017-07 is addressed by FR-023, not by deadlines).
- **FR-029**: For a `CREATED` instance with no completed step, last modified earlier than an abandonment age (default
  15 min), the sweeper MUST act according to a configurable abandoned-start policy: *report* (the default) logs a
  diagnostic, counts it in a metric and leaves it unchanged, visible in `CREATED` listings, for an operator; *fail*
  ends it `FAILED` with an "abandoned start" failure, under the instance lock and after re-checking status, age and
  "no completed step", records it in history and emits `SagaFailed` (step phase); nothing is compensated, since nothing
  committed. A reported instance is reported once per sweep at most and is not reported again until its age crosses the
  next multiple of the abandonment age. (User decision, 2026-11-26.)

**Observability and the starter**

- **FR-030**: Every repair and recovery action MUST emit a span and a counter (by action and result) through the
  runtime's OpenTelemetry instrumentation (010), and a structured log event.
- **FR-031**: The runtime MUST publish a gauge of instance counts per status (`saga_forge.instances` with a status
  attribute), refreshed at a bounded interval, not on every scrape.
- **FR-032**: The starter MUST expose list, view, history and per-status count as Actuator read operations, enabled
  with the existing `sagaforge` endpoint.
- **FR-033**: The starter MUST expose resume compensation, compensate and mark resolved as Actuator write operations
  only when the application sets an explicit property; disabled by default.
- **FR-034**: An enabled write operation MUST refuse a request with no authenticated principal and MUST use the
  principal's name as the operator identity; the reason is a required request field.
- **FR-035**: Context values MUST NOT be returned over HTTP unless the application sets an explicit property.
- **FR-036**: The starter MUST configure the recovery sweeper from properties (enabled, interval, stall age, batch
  size, abandonment age, abandoned-start policy `report`/`fail`), enabled by default in the starter, and the property documentation in the starter README MUST
  stay in step (`ReadmePropertiesTest`).
- **FR-037**: The `sagaforge` health indicator MUST report per-status counts of `COMPENSATION_FAILED` and unresolved
  `FAILED` instances as details, without changing its status for them.

**Test fixture, docs and register**

- **FR-038**: The saga test fixture MUST let a test call the repair actions and the query API against its in-memory
  runtime, and drive the sweeper by advancing the fixture clock, with expectations on the resulting status and history.
- **FR-039**: The user guide, the agent skill and snippet, and `llms-full.txt` MUST describe querying, repairing and
  recovery, each snippet copied from compiling code; `limitations.md` MUST list the gaps this feature leaves open.
- **FR-040**: `memory.md` MUST record a new gap for retrying a failed step (needs the triggering event), and the
  roadmap MUST list it as the next item after this feature; the gaps this feature closes (G-006-03, G-006-05, G-008-02,
  G-ENG-05, G-013-05) and addresses (G-012-04, G-017-07) MUST be updated.
- **FR-041**: Correctness properties MUST be added to `.kiro/specs/saga-forge-framework/design.md`: operator
  transitions (FR-019), "a repair runs each compensation at most once" (FR-012, FR-020), "a redelivered start adopts a
  stranded instance, never creates a second one" (FR-021), and the revised outcome-event property (FR-018).

### Key Entities

- **Saga operations API**: the host-facing entry point for queries and repair actions on a running runtime.
- **Instance view**: a read-only snapshot of one instance's process state (FR-005), with optional context values.
- **Instance query**: filter (statuses, saga types, modified before/after) plus page request; a page of views plus a
  cursor or page token for the next page.
- **Operator request**: operator identity and reason given with every repair.
- **Repair result**: applied, or not applied with a reason (not found, wrong status, already resolved).
- **Resolution**: operator, reason and time recorded when an instance is marked resolved.
- **Operator trigger**: the compensation trigger kind for an operator's *compensate*, carrying operator and reason.
- **Recovery sweeper**: the optional background task that resumes stalled compensations and handles abandoned starts.
- **Abandoned-start policy**: `report` (default) or `fail`, what the sweeper does with an abandoned `CREATED` instance.
- **Lifecycle history entries (new kinds)**: compensation resumed, compensation requested by operator, resolved,
  stranded start adopted, abandoned start failed (FR-029, policy `fail`).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An operator finds any instance by saga id or business key, and sees why it failed, in one call, with no
  database access.
- **SC-002**: 100% of `COMPENSATION_FAILED` instances whose cause was fixed reach `COMPENSATED` through one resume
  request, with every compensation run exactly once across the original attempt and the resume.
- **SC-003**: In a fault-injection test of 1,000 sagas with crashes injected after the creation write and after a
  compensation commit, every instance whose start event is redelivered, and every stalled compensation, reaches a
  terminal status within stall age plus two sweep intervals, without an operator.
- **SC-004**: With two application instances sweeping concurrently over 500 stalled instances, no compensation runs
  twice and every instance ends in exactly one terminal status.
- **SC-005**: Listing 10,000 instances 500 at a time returns each unchanged instance exactly once.
- **SC-006**: Every applied repair is visible in the instance's history with operator, reason and time; no repair over
  HTTP is recorded without an authenticated operator.
- **SC-007**: Adopters remove their hand-written SQL repair scripts and direct storage reads (booking-service W10).

## Assumptions

- The runtime's one unit of work (011), compensation run (006/012) and instance lock (`findByIdAndLock`) are reused;
  no new write path is introduced.
- Delivered messages are consumed in the reaction's transaction, so a crash before the first unit of work commits
  leaves the start event's message unconsumed and it is redelivered (in-memory transport, 007/013). With the default
  at-most-once `ImmediateDispatchOutbox` there is no redelivery; FR-029 covers that case.
- The stall age is chosen well above the time a live compensation takes between its units of work; an instance that is
  actively compensating on another node and picked up anyway is still safe (FR-020), only redundant.
- `lastModifiedAt` is updated on every write to an instance and is the age used for stall and abandonment.
- Authorisation beyond "authenticated" (roles, network exposure of Actuator) is the application's Spring Security
  configuration; the starter documents a recommended setup but enforces none.
- Existing Postgres databases apply an upgrade script for any new column or index the paged query and resolution need.
- Out of scope: retrying a failed step (D1), a UI, editing context values, bulk repair actions over a query (an operator
  repeats single actions), redelivering set-aside messages over HTTP (stays as in 008's transport operations), and an
  irreversible-step marker (G-006-04).
