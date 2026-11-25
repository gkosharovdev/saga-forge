# Research: Saga Deadlines (017)

Each entry: Decision, Rationale, Alternatives considered. R-numbers are cited from `plan.md` and the contracts.

## R1. A due time is the message's first `nextAttemptAt`

**Decision**: `NewMessage` gains a nullable `notBefore` (`Instant`). On append, a store sets the stored message's
`nextAttemptAt` to `notBefore` (or leaves it null as today). `StoredMessage` gains `notBefore` for queries.

**Rationale**: the store's leasable rule already says "due (`nextAttemptAt <= now`)" (`MessageStore` Javadoc, 007).
A scheduled message is then just a message whose first attempt is due later: leasing, renewal, backoff and set-aside
need no new logic, and Postgres' existing lease index (status, next_attempt_at) still serves the query. FR-002 holds by
construction.

**Alternatives**: a separate `saga_scheduled_message` table moved into the log by a "promoter" when due — two writes,
a second poller, and a window where a cancelled message was already promoted. Rejected.

## R2. A scheduled message carries no ordering key

**Decision**: a message with `notBefore` must have a `null` ordering key (`NewMessage` rejects both together).

**Rationale**: an unconsumed message blocks every later message with the same key (007 per-aggregate order). A
deadline two hours out under the key `saga:<sagaId>` or the aggregate's key would hold back every event for that
aggregate for two hours. Order between a deadline and other events is decided by the runtime's instance lock and the
open-deadline check (R6), not by the log.

**Alternatives**: a per-deadline unique key — equivalent to null but noisier; skipping not-yet-due messages in the key
rule — changes 007's ordering contract for every store. Rejected.

## R3. Cancel is a status, written in the caller's transaction

**Decision**: `MessageStatus` gains `CANCELLED`. `MessageStore.cancel(LocalTransaction tx, Collection<String>
messageIds, Instant at)` moves each listed `UNCONSUMED`, not currently leased message to `CANCELLED` and returns the ids
it changed. A cancelled message is never leased, does not block a key, and is removed by retention like `CONSUMED`.
Default method throws `UnsupportedOperationException` (same pattern as 012's transactional `setAside`), with
`supportsScheduling()` default `false`.

**Rationale**: the spec keeps cancelled messages for operator queries (FR-003). Writing it in the unit of work's
transaction makes "the awaited step completed" and "the deadline is cancelled" one commit (FR-012).

A message already leased (being delivered right now) cannot be cancelled; the cancel reports it unchanged and the
runtime's open-deadline check (R6) turns its delivery into `saga.deadline.ignored`. So cancel is an optimisation for
the log, never the correctness guard.

**Alternatives**: delete the row (loses the operator trail); set aside with reason `CANCELLED` (mixes failure and
intent in one status, and set-aside messages surface in failure dashboards). Rejected.

## R4. How a deadline reaches the runtime: a core `DeadlineReached` event, no new receiver SPI

**Decision**: a deadline is stored as a `MessageKind.EVENT` whose payload is the core record
`io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached(sagaType, sagaId, deadlineName, deadlineId, dueAt,
startedByStep)`. The relay delivers it through the existing `EventReceiver.receive`, and the runtime recognises the type
(as it already recognises `SagaOutcome` events, 011) and routes it by `sagaType` + `sagaId`, never by association.

**Rationale**: no new SPI to invent and test (AGENTS.md "test an abstraction before you propose it"); a future Kafka
transport carries it like any event. The core record is a plain value (Principle I).

**Alternatives**: a `DeadlineReceiver` SPI beside `CommandReceiver` — a third receiver for one message type; a
`@DomainEvent` per deadline written by the application — rejected in the spec (D1).

## R5. Timeout steps keyed by deadline name

**Decision**: `@DeadlineExpired("name")` on a `@SagaStep` method; the method takes no parameter or one
`DeadlineReached`. `@SagaStep.associationProperty` gains `default ""`: the scanner keeps requiring a non-blank value
for event steps (unchanged rule) and requires it blank on a timeout step (R27). Property 10 and Property 11 are
amended: they apply to event steps; timeout steps are unique per deadline name (Property 40).

**Rationale**: settles the checkout-story input (several deadlines per saga, G-011-01) without event types the
application has to invent. `@DeadlineExpired` reads like `@StartCompensation`: a modifier on a step.

**Alternatives**: a name filter on `@SagaStep(deadline = "...")` — mixes two step kinds' attributes on one annotation;
a mandatory associationProperty on a step that has no event to read it from — misleading. Rejected.

## R6. The open deadline lives on the instance, and is the correctness guard

**Decision**: `SagaInstance` gains `Map<String, OpenDeadline> openDeadlines` (name → `deadlineId`, `dueAt`,
`startedByStep`). A delivered `DeadlineReached` runs its timeout step only if, under the instance lock, the instance is
`RUNNING` and `openDeadlines.get(name).deadlineId()` equals the message's `deadlineId`. Otherwise the runtime consumes
the message, writes nothing, and logs `saga.deadline.ignored` (reason `NOT_RUNNING`, `CANCELLED`, `REPLACED`).

`deadlineId` is the stored message id: `<sagaId>:deadline:<name>:<unitOfWorkId>`, unique per start (a restart replaces
it, R8).

**Rationale**: makes FR-011/FR-013 hold whatever the log does (a lost cancel, a duplicate delivery, a race with the
cancelling step). The instance row is already locked and versioned by `forInstance` units of work (004, 011).

**Alternatives**: rely on cancel alone — races with in-flight delivery; a separate deadlines table — another store
contract, and the instance row already commits atomically with the step. Rejected.

## R7. Where cancellation is written

**Decision**: the runtime's one `UnitOfWork` (011) computes, per unit of work, the deadlines to start and cancel:
- starting: deadlines declared on each step that completed in it (`@StartDeadline`), duration resolved per R9;
- cancelling: deadlines whose `cancelledBy` names a step that completed in it, every deadline replaced by a restart,
  the deadline whose timeout step ran, and **every** open deadline when the write leaves `RUNNING` (completed,
  compensation start, `FAILED` write, compensation-command failure, 012).

It removes them from `openDeadlines`, cancels their messages through `Outbox.cancel(ids)` inside the unit of work's
transaction, and hands new ones to the outbox in `OutgoingMessages.scheduled` (`List<ScheduledMessage>`). Cancels run
before the batch is stored, so a replace in one unit of work cancels the old id and appends the new one.

**Rationale**: one place (ArchUnit `UnitOfWorkBoundaryTest` already forces every write through it), so "leaves RUNNING
⇒ cancel all" is a single rule, Property 41.

## R8. Starting a deadline that is already open replaces it

**Decision**: replace — cancel the old message, store a new one with a new `deadlineId`. Diagnostic
`saga.deadline.replaced`.

**Rationale**: matches "the window restarts" (a customer retries the card challenge). Rejecting would make a valid
saga fail at runtime; ignoring would silently keep a stale due time.

## R9. Duration sources and the clock

**Decision**: `@StartDeadline(name, duration = "PT15M" | "${key}", durationFrom = "contextAttribute", cancelledBy =
{...})`; exactly one of `duration`/`durationFrom` (R25). `${key}` is resolved once at startup through
`SagaRuntime.Builder.propertyResolver(Function<String, Optional<String>>)` (the starter passes Spring's `Environment`);
an unresolved or unparseable value fails startup (`SagaRuntimeStartupException`). `durationFrom` reads a `String` ISO-8601
value from `SagaContext` after the step's method returned; missing or unparseable → `IllegalStateException` thrown by the
runtime, classified `FINAL` through a `FinalFailure` marker subclass `DeadlineDurationException`, so the step's unit of
work fails like any step failure (compensates if an earlier unit of work committed). Negative durations are due at once.

`dueAt = clock.instant() + duration` using the runtime's `Clock` (`SagaRuntime.Builder.clock`, default system UTC),
truncated to milliseconds (Postgres `timestamptz` keeps microseconds; ms keeps the in-memory and Postgres stores equal).
The relay compares with its own clock (`InMemoryTransport.Builder.clock`); the starter passes one `Clock` bean to both.

**Rationale**: context values are durable as JSON strings (009 FR-019, G-003-07), the same posture as the example's
amounts. Failing `FINAL` is right: a missing duration will not appear on retry.

**Alternatives**: `java.time.Duration` in the context (not a durable value under `DurableContextValues`); a SpEL-like
expression (boutique, Principle II prefers plain attributes).

## R10. Latency: poll interval

**Decision**: no wake-up mechanism. A due message is leased by the relay's next poll: latency ≤ `pollInterval`
(default from 007's settings) plus delivery time. Documented in the guide; SC-003 tests it with the in-memory store and a
controlled clock.

**Rationale**: business deadlines are minutes to days (spec Assumptions). A timer per message would duplicate the
relay and not survive restarts anyway.

## R11. Overdue after downtime: order

**Decision**: every overdue message is leasable at relay start; the lease is in position order (unchanged 007 contract).
For one instance, deadlines are appended in start order; due order across instances is not guaranteed. Spec D3 and
SC-004 are amended to "delivered at once, none skipped, each at most one timeout step"; recorded as **G-017-03**.

**Rationale**: changing lease order to `(nextAttemptAt, position)` would also reorder retried messages and needs a new
Postgres index; correctness never depends on due order because of R6.

## R12. Outbox capability and startup checks

**Decision**: `Outbox.supportsScheduling()` default `false`; the transport's `IN_TRANSACTION` outbox returns `true` when
its `MessageStore.supportsScheduling()` does. `SagaRuntime.start()` fails with `SagaRuntimeStartupException` if the
model declares a deadline and the outbox does not support scheduling (names saga, deadline, and "configure an
IN_TRANSACTION outbox, e.g. InMemoryTransport"). `CommandGateway.schedule` throws `UnsupportedOperationException` at
the call in the same case. `ImmediateDispatchOutbox` returns `false`.

## R13. Spring Modulith interop: reject

**Decision**: `ModulithMessageStore.supportsScheduling()` returns `false`; with interop enabled and a model with
deadlines, startup fails through the runtime check, and the starter adds `DeadlinesRequireOwnLogFailureAnalyzer` that
says to set `saga-forge.transport.modulith-interop=false`. Gap **G-017-01**.

**Rationale**: Modulith's event publication registry has no "not before" column; emulating it in Saga Forge's delivery
records would split one message's state across two stores.

## R14. Scheduled commands

**Decision**: `CommandGateway` gains `ScheduledCommandId schedule(Object command, Instant at)` and `boolean
cancel(ScheduledCommandId id)` (default methods throwing `UnsupportedOperationException`, so test doubles compile).
Only inside a unit of work (saga step, handler, or `standalone`), like an outbox command; the command type must be
`@Command(delivery = OUTBOX)` (else `IllegalArgumentException`: a scheduled command cannot run in the sender's unit of
work). The `CommandId` is assigned at `schedule` time; from a saga step it is saga-scoped, so 012 failure feedback
applies. `cancel` returns `true` only when it can tell the message was pending: it is written in the current
transaction, and returns the store's result. `ScheduledCommandId` is a core value record wrapping the message id.

## R15. Telemetry

**Decision**: `saga_forge.handle` span with `saga_forge.deadline.name` for a deadline delivery; counter
`saga_forge.deadlines` with attribute `event` = `started|cancelled|expired|ignored`; delivery span attribute
`saga_forge.message.not_before`. No new span kinds.

## R16. Outcome and compensation triggers

**Decision**: `CompensationTriggerKind.DEADLINE_EXPIRED`; `CompensationTrigger` and `OutcomeTrigger` gain a nullable
`deadlineName`, non-null iff kind is `DEADLINE_EXPIRED` (old constructors kept, as 012 did for `commandType`). A
`@StartCompensation @DeadlineExpired` step records `DEADLINE_EXPIRED` instead of `FAILURE_OUTCOME`; `stepName` is the
timeout step; `eventType` is `DeadlineReached`'s class name. Postgres trigger JSON gains `deadlineName` (no upgrade
script for the JSON; the column changes are in `schema-upgrade-017.sql`).

## R17. Fixture

**Decision**: the fixture's in-memory message store supports `notBefore`/`cancel`; `FixtureClock.advance` is already
the time source. `SagaFixture.When.timeElapses(Duration)` / `timeReaches(Instant)` advances the clock and delivers every
due deadline and scheduled command, in position order, through the same loop-back path as outbox commands. `Then` gains
`expectOpenDeadline(name)`, `expectNoOpenDeadline(name)`, `expectDeadlineExpired(name)`; `InstanceView.openDeadlines()`.

## R18. Example design

**Decision**: `CheckoutSaga` starts `paymentWindow` in `onStockReserved` (`durationFrom = "paymentWindow"`, written in
`onOrderPlaced` from `OrderPlacedEvent.express()` → `PT10M` or `PT30M`), with no `cancelledBy`: both
`onPaymentAuthorized` and `onPaymentDeclined` leave RUNNING, which cancels it (changed at implementation, T043); timeout step `onPaymentWindowExpired` is
`@StartCompensation`, and `cancelCheckout` maps it to `RejectionReason.PAYMENT_TIMED_OUT`. `FulfilmentSaga` starts
`courierAssignment` (`duration = "${checkout.courier-assignment:PT2H}"`) when it requests the shipment, cancelled by
`onShipmentDispatched`; timeout step `onCourierNotAssigned` is `@StartCompensation`. Snippets in `quickstart.md`.

## Gaps recorded by this plan (memory.md, at implementation)

- **G-017-01**: no deadlines with Spring Modulith interop (R13).
- **G-017-02**: one running instance per message store until 013; 013 must lease due messages under its leader lease.
- **G-017-03**: overdue messages are delivered in position, not due, order (R11).
- **G-017-04**: deadline latency is bounded by the relay poll interval, no wake-up (R10).
- **G-017-05**: a deadline already leased when cancelled is delivered and ignored (R3), costing one delivery.
- G-005-01/-02 (durable backoff) stay open; G-ENG-25 closes.
