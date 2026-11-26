# Saga Forge Roadmap: next features

**Date**: 2026-11-19, updated 2026-11-24 after 011 | **Input**: booking-service feedback on 0.1.0
(`booking-service/specs/060-saga-forge-migration/saga-forge-feedback.md`) | **Register**: gap IDs refer to
`.specify/memory/memory.md`

This file turns adopter feedback into feature briefs that `/speckit-specify` can start from. Each brief is written in
generic saga-engine terms. The booking-service is evidence that the gap is real, not the shape of the solution.

## Genericity test

A proposal goes on the roadmap only if all of these hold:

1. **Recognised need.** Mature saga or process-manager engines (Axon, MassTransit, NServiceBus, Temporal, Eventuate)
   have it, or it follows from Saga Forge's own guarantees (outbox, compensation, at least once).
2. **No domain vocabulary.** Names, payloads and defaults say nothing about bookings, payments or any provider.
3. **Fits the constitution.** Core keeps zero framework dependencies (Principle I). The model stays annotation-driven
   (Principle II). Third-party integrations (Spring, jMolecules, Flyway) live in their own module or in the starter.
4. **Stays in scope.** No storage or transport beyond Postgres/MySQL/MongoDB and Kafka/in-memory (Principle IV) unless
   the user decides otherwise.

## Verdict per proposal

The feedback's numbers (SF-n, W-n) are kept so the adopter can trace them.

| Feedback | Verdict | What is kept | What is cut or changed, and why |
|---|---|---|---|
| SF-1 outcome events | **Accept, P1** | Framework events when a saga completes, fails or is compensated, written in the terminal write's transaction, delivered through the outbox. | `@OnSagaFailed` hooks deferred: a policy or another saga reacting to the event covers it without a second callback model. |
| SF-2 per-step boundary / command outcomes | **Accept (b), P1** | Outbox commands report a final or exhausted failure back to the sending instance, which compensates. | (a) "commit before next step" is deferred: with (b), a step that needs its own commit puts its risky work in an outbox command. One mechanism, not two. |
| SF-3 jMolecules, event harvesting | **Accept, narrowed, P2** | A core marker SPI plus a separate `saga-forge-jmolecules` module. A harvesting SPI for events held on aggregates (Spring Data `@DomainEvents` support in the starter). | Fluent `SagaModelCustomizer` registration cut (Principle II). "Handler returns `List<?>` of events" cut: a second recording style for the same thing. |
| SF-4 one bus with Spring Modulith | **Accept, P1** | `EventPublisher` inside a Spring transaction publishes like recorded events; an opt-in inbound route from `ApplicationEventPublisher`; recorded events also reach other saga types (G-ENG-24). | Deprecating `ApplicationEventBridge` is a spec decision, not assumed. |
| SF-5 operations API | **Accept, P1** | Query and repair API in the runtime, Actuator adapter in the starter, every action in the lifecycle history, status gauges. | "Retry from the failed step" needs the triggering event. Where it comes from is a spec question (see brief). |
| SF-6 multi-instance | **Accept (b) first, P1** | Active/standby relay with a lease, shown in health. | Partitioned leasing (true scale-out) left to the Kafka transport, or a later spec. |
| SF-7 versioned migrations | **Accept, P2** | Flyway-compatible resources in their own location, a startup `validate` mode. | Liquibase only if someone asks: one tool first. |
| SF-8 failure-text redaction | **Deferred** (user, 2026-11-20; G-ADOPT-03) | `FailureMessageRedactor` SPI, a length cap, a switch to store the cause type only. | No provider-specific default patterns (no "Stripe keys"). The default is a length cap plus a few generic credential shapes. |
| SF-9 deterministic `CommandId` | **Accept, P3** | Derived ids while handling a delivered message; `CommandId.derived(...)`. | None. |
| SF-10 metadata and MDC | **Accept, generalised, P2** | A metadata map captured at start, stored, carried on messages and restored around handlers. | No `initiator`/`InitiatorType` field: that is one application's key in the map. MDC restore lives in the starter (core uses `System.Logger`, G-010-04). |
| SF-11 deadlines and scheduled commands | **Accept, P2 (large)** | Durable due-at messages in the message store, deadlines on saga steps, cancelled when the saga ends. Also closes durable retry backoff. | None. Deadline syntax must be annotation-driven. |
| SF-12 Spring tests without Postgres | **Accept, needs a scope decision, P2** | Run the real engine in a Spring context over in-memory plug-ins. | In-memory *storage* is a new storage kind (Principle IV): it must stay test-scope, shipped from `saga-forge-test`, never a production option. Needs the user's yes. |
| SF-13 extra associations | **Accept, P3** | Add an association from a step. | It goes on `SagaLifecycle` (process state), not `SagaContext` (business values). |
| `FailureCategory` taxonomy (§2) | **Reject** | | Categories such as `BUSINESS_REJECTION` or `AUTHORIZATION` are application semantics. `causeType` plus `FailureClassification` lets the client map them. |
| D1 to D5 documentation | **Accept now** | Doc fixes, no spec needed (G-DOC-03). | |

## Answers to the adopter's questions

1. **SF-2: (a) or (b)?** (b). A command is already the unit that crosses a transaction in Saga Forge (`OUTBOX`
   delivery, 007). Reporting its failure back completes that model, and it is also the way to make a remote call from a
   saga. (a) would add a second boundary concept.
2. **SF-4: one bus?** Yes. With Modulith interop the publication registry is the one outbox. Whether
   `ApplicationEventBridge` stays is decided in feature 014. The direction is to make it unnecessary.
3. **SF-6: multi-node story for the in-memory transport?** Active/standby: correct with any number of replicas and safe
   for rolling deploys, but only one node relays. Scale-out delivery is the Kafka transport's job. The engine side is
   already database-locked (`findByIdAndLock` is the first storage call of every unit of work). The single-instance
   limit is the relay's (G-007-09).
4. **SF-1 and SF-5: core or Spring?** Core and runtime. Event types and the operations API sit in core/runtime, and the
   Actuator endpoints and Spring wiring are adapters in the starter.

## Feature order

Numbers are proposed `specs/NNN-*` directories. They are ordered by what removes the most adopter workarounds without
building on unfinished work.

| # | Feature | From | Closes | Removes (adopter) | Priority |
|---|---|---|---|---|---|
| 011 | Saga outcome events, `COMPENSATION_FAILED` rename, one unit of work (**done**, PR #18) | SF-1 | G-ADOPT-01, G-011-02 | W1, W2 | P1 |
| 012 | Outbox command failure feedback (**done**, `specs/012-outbox-command-failure-feedback`) | SF-2(b) | new G-ADOPT-02 | W3, W4 | P1 |
| 013 | Active/standby relay (**done**, `specs/013-active-standby-relay`) | SF-6 | G-007-09 | W9 | P1 |
| 014 | One event bus with Spring Modulith | SF-4 | G-008-03, G-ENG-24 | W7, W8 | P1 |
| 015 | Saga operations: query, repair, recovery | SF-5 | G-006-03, G-008-02, G-006-05, G-ENG-05 | W10 | P1 |
| 016 | Marker SPI and jMolecules adapter | SF-3 | new G-ADOPT-04, G-002-04 | W5, W6 | P2 |
| 017 | Durable scheduling: deadlines and scheduled commands (**done**, `specs/017-saga-deadlines`, taken ahead of 013); durable backoff left to a later spec | SF-11 | G-ENG-25 (G-005-01, G-005-02 still open) | W16 | P2 |
| 018 | Versioned schema migrations | SF-7 | G-003-25 | W11 | P2 |
| 019 | Process metadata propagation | SF-10 | new G-ADOPT-05, G-010-04 (partly) | W15 | P2 |
| 020 | Spring context tests without a database | SF-12 | G-009-01 | W12 | P2 |
| later | Derived command ids; extra associations | SF-9, SF-13 | new G-ADOPT-06, G-ADOPT-07 | W14 | P3 |

Dependencies: 012 runs its reaction through 011's one `UnitOfWork` and reuses its compensation-start work. 015's
operator actions are new works on that unit of work and read the lifecycle history it writes. 017 reuses
the message store's leasing that 013 changes, so it comes after 013. 014 and 016 are independent of the rest.

---

## Feature briefs

Each brief gives the problem in generic terms, the scope, what is out of scope, the constitution points it touches, the
questions the spec must settle, and acceptance.

### 011: Saga outcome events

**Implemented and tested** (2026-11-20, container suites included; PR #18): `specs/011-saga-outcome-events/`. In short:
- one `UnitOfWork` replaces the runtime's phase-specific unit-of-work and write classes (behaviour-preserving, first).
  Every runtime write goes through it (ArchUnit, `UnitOfWorkBoundaryTest`). A work's admission is a typed sealed
  `Admission<R>` (`Proceed`, or `Skip` with a typed reason), checked against the locked instance before anything runs;
- `REQUIRES_INTERVENTION` is renamed to `COMPENSATION_FAILED`;
- `SagaCompleted`, `SagaFailed` (a step or a compensation failed) and `SagaCompensated` are committed with the terminal
  write and delivered through the outbox.

Out of 011: failure-text cleaning (G-ADOPT-03), saga steps consuming outcome events (G-011-01), and any "requires
intervention" signal, since that is an observer's judgement.

### 012: Outbox command failure feedback

**Done** (2026-11-24, `specs/012-outbox-command-failure-feedback`). The spec settled the open questions: the relay's
backoff (not `@RetryPolicy`) retries; the relay hands a value (`CommandFailure`) to the runtime's
`CommandReceiver.receiveFailure` rather than writing a `CommandFailed` event; the outcome event's trigger kind is
`COMMAND_FAILED`; a `@StartCompensation` step on the failure stays out of scope (G-012-03). The brief below is the
original proposal.

**Problem.** In-process chaining keeps a multi-step saga in one unit of work, so a rejection rolls everything back and
compensation never runs. `@Command(delivery = OUTBOX)` gives a separate transaction, but a failing handler goes to the
relay's retry and set-aside path (`Relay`: retried up to `maxAttempts`, `FinalFailure` not recognised) and the saga
never learns it. A saga cannot both commit a step on its own and learn that the step's follow-up failed.

**Scope.**
- The relay recognises `FailureClassification` for outbox commands: `FinalFailure` is not retried. Exhausting
  `maxAttempts` counts as `EXHAUSTED`.
- When an outbox command sent from a saga step fails `FINAL` or `EXHAUSTED`, a framework event
  `CommandFailed(commandId, commandType, classification, causeType, attempts)` reaches the sending instance, routed
  through `CommandId.sagaId` (one instance, not by association; the in-memory transport can key it `saga:<sagaId>` like
  outcome events, so it stays in per-instance order).
- The reaction runs in the runtime's one `UnitOfWork` (011), not in the relay's set-aside transaction: the relay hands
  the failure to the runtime, which handles it in `UnitOfWork.forInstance(sagaId)`, reusing the compensation-start work.
  That keeps one write path, and the Kafka transport later needs only to deliver the failure, not to write saga state.
- Default reaction: the instance compensates every committed step (006 order), with the failure as the
  `CompensationTrigger`, and ends with one outcome event (`SagaCompensated`, or `SagaFailed` in the compensation
  phase). Optional: a `@StartCompensation @SagaStep` that takes `CommandFailed` handles it instead, to record a named
  business outcome (see the G-011-01 question).
- A failure of an outbox command sent outside a saga keeps today's set-aside behaviour.

**Out of scope.** A step-level commit boundary (SF-2a). A success notification: success already arrives as the events
the handler records.

**Constitution.** Principle II: compensation stays step-targeted (`forStep`). Principle III: new property "a final
outbox-command failure of a saga's command leads to compensation or a `@StartCompensation` step, exactly once".

**Spec questions.**
- Does a `TransientFailure` keep using the relay's backoff (bounded by `maxAttempts`) or the step's `@RetryPolicy`? The
  sender's policy is the cleaner rule.
- What happens when `CommandFailed` reaches an instance that already ended? 011's compensation-start work already
  skips a missing or terminal instance with a diagnostic (`Skip(MISSING_OR_TERMINAL)`), which is the likely answer.
  `COMPENSATION_FAILED` (formerly `REQUIRES_INTERVENTION`) does not fit: no compensation failed.
- Ties to 011: does the relay write `CommandFailed` itself (transport-level, the Kafka transport repeats it), or hand
  the failure to the runtime's `UnitOfWork` (one write path; the brief's default)? Outcome events go through the
  terminal write's batch and are routed to no saga; `CommandFailed` is routed to exactly one instance.
- Which `OutcomeTrigger` does the ending outcome event carry: a new trigger kind for a failed command, or
  `FINAL_FAILURE` with the sending step's name?
- The optional `@StartCompensation` step on `CommandFailed` meets G-011-01: the scanner accepts only `@DomainEvent`
  step parameters. Either 012 introduces steps on framework events (and so answers G-011-01 once for both), or it
  drops the optional step and ships the default compensation only.

**Acceptance.**
- A three-step saga whose step-3 command (outbox) throws `FinalFailure` compensates steps 2 and 1, newest first, and
  emits exactly one `SagaCompensated`.
- A `CommandFailed` for an instance that already ended changes nothing and logs a diagnostic.
- A latency-stubbed remote call inside an outbox handler never runs in the unit of work that holds the instance's
  earlier writes.

**Docs now (D1, D2, D3, D5).** Even before 012: a final failure compensates only after a unit of work committed; the
events that continue the same instance run in the same unit of work; `CommandGateway.send` marks the transaction
rollback-only before rethrowing; an outbox command's failure is not reported to the saga yet.

### 013: Active/standby relay

**Status (2026-11-26).** Done by `specs/013-active-standby-relay`: a fenced leader lease in the message store
(`LeaderElection`, `LeaderLease`, `MessageStore.lease(LeaderLease, ...)`, Postgres `saga_message_store_leader`), one
active relay and standbys, roles in Actuator, health (`DEGRADED`) and telemetry; the Modulith interop store shares the
same leadership. Deadlines work with several instances (G-017-02 closed). Gaps G-013-01 to G-013-06 in memory.md.

**Problem.** The in-memory transport's relay assumes one consumer per message store (007 FR-021). A second replica is
detected and logged, not coordinated. Applications must pin one replica and avoid deploy overlap.

**Scope.**
- Leader election over the store: one active relay per consumer id, the rest standby. A lease row with expiry (and
  optionally a Postgres advisory lock). Standby nodes take over when the lease expires.
- Health and the Actuator endpoint show `active` or `standby`. Metrics show the role.
- Documentation of the multi-node guarantees: the engine's instance lock is the database lock (`findByIdAndLock`). The
  in-process lock only avoids local contention. Per-key order holds because one node relays.

**Out of scope.** Partitioned leasing across active nodes (scale-out delivery: Kafka transport or a later spec).

**Constitution.** Principle IV: no new transport. `MessageStore` SPI gains lease operations, implemented by Postgres and
`InMemoryMessageStore`. The `MessageStoreContract` covers them.

**Spec questions.** Lease duration and takeover time. What a node that loses its lease mid-delivery does (finish and
commit, or abort). Whether the Modulith interop store needs the same lease.

**Acceptance.** Two application instances on one database, with a burst of events on one association, never run a step
twice and never reorder. Killing the active node hands over within the lease time.

### 014: One event bus with Spring Modulith

**Problem.** Three publication routes behave differently. Events recorded in a unit of work reach Modulith listeners
durably. `EventPublisher.publish` outside a unit of work reaches them only through the non-transactional
`ApplicationEventBridge`, after consumption. Events published with Spring's `ApplicationEventPublisher` never reach
sagas (G-008-03). An event that continues its own instance never reaches other saga types (G-ENG-24). Applications end
up with two publishing paths and rules about which events may start a saga.

**Scope.**
- `EventPublisher.publish` inside a Spring transaction also publishes through Spring in that transaction, like
  `ApplicationEventPublishingOutbox` (same `saga-forge.application-events.mode`).
- Opt-in inbound route: events published through `ApplicationEventPublisher` whose type is a saga event (`@DomainEvent`
  or a marker from 016) are stored for saga delivery in the publishing transaction. With Modulith interop it is the same
  publication row.
- Runtime: hand every recorded event that another saga type has a step for to the outbox, while the owning instance
  keeps in-process delivery (G-ENG-24).
- Decide `ApplicationEventBridge`: keep, restrict or deprecate, so no listener sees an event twice.

**Out of scope.** Modulith event externalization and `Scenario` support (G-008-01).

**Constitution.** Spring code stays in the starter. The G-ENG-24 change is a runtime rule (Principle III: fan-out
property revised).

**Acceptance.** An event published by any of the three routes reaches a saga step and an `@ApplicationModuleListener`
exactly once each, durably, after commit.

### 015: Saga operations: query, repair, recovery

**Problem.** The engine keeps rich instance state but exposes it only through the `StorageProvider` SPI. There is no
supported way to find instances, or to get a `FAILED`, `COMPENSATION_FAILED`, stranded `CREATED` or stalled
`COMPENSATING` instance moving again.

**Scope.**
- Runtime API `SagaOperations`: `find(sagaId)`, `findByAssociation(property, value)`, `list(filter, page)` (status,
  saga type, updated before/after).
- Repair: `resumeCompensation(sagaId)` (out of `COMPENSATION_FAILED` or a stalled `COMPENSATING`),
  `compensate(sagaId, reason)` (force compensation of committed steps), `markResolved(sagaId, reason)` (operator closes
  it with no further effect), `retry(sagaId)` (see questions).
- Every action writes lifecycle history with operator, reason and time. Operator identity comes from a host SPI.
- Each action is a work on 011's one `UnitOfWork`, with an admission that skips instances in the wrong status;
  `resumeCompensation` reuses the compensation work. A repair that ends an instance emits its outcome event.
- A view record: steps with attempts, timing, classification, compensation status. Context values excluded by default.
- Recovery: an optional sweeper that resumes `COMPENSATING` instances with no new event (G-006-05) and stranded
  `CREATED` instances (G-ENG-05).
- Starter: Actuator read operations on by default, write operations opt-in. Gauges per status
  (`saga_forge.instances{status}`).
- `StorageProvider` gains a paged query by status and type (Postgres and test doubles).

**Out of scope.** A UI. Editing context values.

**Constitution.** New terminal outcome for `markResolved`: either a new status or a recorded resolution on the
terminal status. Principle III: transitions allowed for operator actions.

**Spec questions.**
- `retry` re-runs the failed step, so it needs the triggering event. Options: store the trigger with the `FAILED`
  write, redeliver it from the message store when the transport kept it, or allow retry only for instances whose
  trigger is retrievable. This decides the feature's size.
- Do "cancel" and "markResolved" differ? (Cancel compensates, resolve does not.)
- Who may call write operations over HTTP (security decision, G-008-02).

**Acceptance.** A `FAILED` instance whose cause was fixed is retried through the API and completes. A
`COMPENSATION_FAILED` instance is resumed and ends `COMPENSATED`. Each action shows in the history.

### 016: Marker SPI and jMolecules adapter

**Problem.** The model accepts only Saga Forge's own markers. A project that keeps framework types out of its domain
must double-annotate aggregates or translate events in the application layer. Recording needs the static
`DomainEvents.record`, which a pure domain cannot call.

**Scope.**
- Core SPI `ModelVocabulary` (name to settle): tells the scanner which types and members count as aggregate, aggregate
  identifier and domain event, and how an event maps to its aggregate. The default is today's annotations.
- New module `saga-forge-jmolecules`: `@AggregateRoot`/`@Identity`/jMolecules `@DomainEvent` and the type interfaces.
  An event's aggregate is resolved from its identity type, or an explicit mapping when ambiguous.
- Core SPI `AggregateEventSource`: after a handler's aggregate is saved, the engine collects events the aggregate
  holds and records them as if `DomainEvents.record` had been called. The starter supports Spring Data's
  `@DomainEvents`/`@AfterDomainEventPublication`, and the jMolecules module supports its conventions.
- The scanner's error texts name the marker actually used.

**Out of scope.** Fluent model registration. Handlers returning events.

**Constitution.** Principle I: jMolecules only in its own module. Principle II: markers stay annotations. Principle
III: Properties 16 and 17 are restated over "aggregate marker" and "identifier marker". The architecture.md section
"jMolecules consideration" becomes this design (and fixes G-002-04).

**Acceptance.** A sample whose domain package depends only on jMolecules (ArchUnit check) runs the canonical saga end to
end, events harvested from aggregates.

### 017: Durable scheduling: deadlines, scheduled commands, durable backoff

**Status (2026-11-25).** Deadlines and scheduled commands are implemented by `specs/017-saga-deadlines`, ahead of 013 by
decision; 013 then made them work under its leader lease (G-017-02, closed 2026-11-26). Durable `@RetryPolicy` backoff was left out and stays open (G-005-01, G-005-02). Gaps
G-017-01 to G-017-10 in memory.md.

**Problem.** Nothing in Saga Forge can wait in time. A saga waits for its next event forever (G-ENG-25). Retry backoff
blocks the calling thread and is capped at 30 s (G-005-01, G-005-02). Every deadline lives in application tables and
schedulers.

**Scope.**
- Message store: messages with a due time, not delivered before it, cancellable by id. Appended in the unit of work's
  transaction.
- `CommandGateway.schedule(command, Instant)` → `ScheduledCommandId`, plus `cancel(id)`.
- Saga deadlines, annotation-driven: a step declares that a deadline starts (named, with a duration from a property or
  the context). A `@SagaStep` takes a framework `DeadlineReached(name)` event. Open deadlines are cancelled when the
  instance ends.
- `@RetryPolicy` can wait durably beyond the in-process cap: the attempt is rescheduled instead of sleeping.

**Out of scope.** Cron-style recurring schedules.

**Spec questions.** Clock source and precision. Behaviour for overdue messages after downtime. Whether deadline
durations may come from `SagaContext`.

**Acceptance.** A saga whose deadline expires runs its timeout step once, after a restart in between. A deadline set
before completion never fires after it. A retry waiting 10 minutes holds no thread.

**Example to add with this spec.** Extend the checkout story in `saga-forge-example`
(`io.github.gkosharovdev.sagaforge.example.checkout`, `docs/use-cases.md` §1, README) with
saga-owned deadlines: a payment window in `CheckoutSaga` (the customer abandons a card challenge) and a
courier-assignment deadline in `FulfilmentSaga`, each ending in a `@StartCompensation` timeout step, with fixture
tests that advance `FixtureClock`. The example was written without deadlines on 2026-11-24 for this reason.

**Input from the checkout story (2026-11-24)**, for the spec:
1. One `DeadlineReached` event type allows one deadline step per saga (Property 11 allows one step per event type), and
   the scanner rejects a step whose parameter is not a `@DomainEvent` (as in G-011-01). Checkout already needs two
   deadlines: the step needs a name filter, or each deadline its own event type.
2. Cancelling only when the instance ends is too late: "a courier within two hours" must stop once `CourierAssigned`
   arrives, while the saga keeps waiting days for delivery. A deadline needs cancelling when the awaited step
   completes (declared on the deadline, or by a later step).
3. Durations from `SagaContext` are needed, not only from properties: the payment window and courier SLA vary per
   order (express or standard).
4. A deadline due while the instance is `COMPENSATING` must not start a second compensation; "never fires after
   completion" should cover every non-`RUNNING` status.

### 018: Versioned schema migrations

**Problem.** The Postgres module ships `schema.sql` plus `schema-upgrade-0NN.sql` for the host to apply. Flyway users
copy them under their own numbers and guard against drift.

**Scope.**
- Flyway-compatible resources under their own location, for example `db/migration/saga-forge/postgres`, applied with a
  separate history table so numbering never clashes with the application's.
- The starter runs them when Flyway is on the class path (`saga-forge.storage.postgres.initialize-schema=migrate`).
- A `validate` mode checks tables, columns and constraints at startup and fails with a clear difference.

**Out of scope.** Liquibase (on request). MySQL and MongoDB.

**Acceptance.** An application with its own Flyway migrations at V1..V62 starts with Saga Forge migrations applied and
both histories intact. A missing column fails `validate` with its name.

### 019: Process metadata propagation

**Problem.** An instance records no correlation data, and handlers do not see the context of the request or message
that triggered them. Only OpenTelemetry trace context travels (010).

**Scope.**
- Core SPI `MetadataProvider` captures a string map when an instance starts and when a message is sent. Stored on the
  instance and in history, and carried on messages next to `traceContext`.
- Handlers, steps and compensations can read it (an injected `SagaMetadata`, or a parameter).
- Starter: capture from and restore to SLF4J's MDC for configured keys (`saga-forge.metadata.mdc-keys`).
- Included in 015's view. Adding it to outcome events changes 011's contract, which carries no context value or saga
  field on them, so the spec must decide it explicitly.

**Out of scope.** Typed metadata, fixed fields such as "initiator".

**Constitution.** Principle I: MDC only in the starter. `MessageStore` and Postgres schema gain a metadata column.

**Acceptance.** A correlation id set in the MDC of an HTTP request appears in the MDC of every step, handler and
compensation of the saga it starts, across transport hops and a restart.

### 020: Spring context tests without a database

**Problem.** Postgres is the only storage, so a Spring context test without Testcontainers must switch Saga Forge off,
and wiring mistakes show only in container suites. `SagaFixture` tests sagas, not wiring.

**Scope.**
- `saga-forge-test` gains Spring support: `@AutoConfigureSagaForgeTest` (or a test property) replaces storage and
  transport with the fixture's in-memory plug-ins and runs the real engine with synchronous delivery.
- Reuses 009's in-memory plug-ins, which also addresses G-009-02 (duplicated doubles).

**Out of scope.** Any in-memory storage usable in production.

**Decision needed.** In-memory storage is a storage kind outside Principle IV's list. It is acceptable only as
test-scope code in `saga-forge-test`. This needs the user's explicit yes before specifying.

**Acceptance.** A Spring context test for the Spring example, with no database, sends a request and sees the saga
complete.

### Later (P3)

- **Derived command ids (SF-9).** While handling a delivered message outside a saga, derive `CommandId` from (message
  id, command type, ordinal). `CommandId.derived(String...)` for callers holding their own idempotency key.
- **Extra associations (SF-13).** `SagaLifecycle.associateWith(property, value)` from a step, stored in
  `saga_association` (the schema already allows several rows). Routing and the duplicate-start check must consider
  every association.

---

## Kept out of Saga Forge

These stay in applications, whichever adopter asks.

- Business deduplication of user intent (request fingerprints, business idempotency keys on aggregates).
- Failure categories that name business meaning (rejection, authorization, conflict).
- Provider-specific secret patterns. The redactor SPI is where an application adds them.
- Read models and progress projections built from business events.
- Choreography policies that are business reactions (`@ApplicationModuleListener`s).
