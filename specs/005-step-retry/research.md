# Research: Step Retry and Idempotent Command Processing

**Feature**: `005-step-retry` | **Spec**: [spec.md](./spec.md) | **Date**: 2026-11-09

Every section resolves one design question left open by the spec. Code references are to the state of `master` after
004 (`17bc2c9`).

## §1 Shape of the command identity

**Decision**: A new core value type `core.command.CommandId` with the components `sagaId`, `stepName`,
`commandType` (fully qualified name), `ordinal` (1-based position among commands of that type dispatched by that
step run) and a derived `value()`: a name-based UUID (`UUID.nameUUIDFromBytes`, JDK only) over the four components
joined with `\u0000`. `toString()` returns `value()`.

**Rationale**: Outside systems usually limit idempotency keys (e.g. 255 characters) and reject some characters; a
36-character UUID fits every common provider while staying deterministic (FR-002). The components stay available for
logs and diagnostics. Counting per command type (not overall) keeps the identity of `ChargePaymentCommand` stable
even if a later attempt dispatches an extra command of another type first (spec, Edge Cases).

**Alternatives considered**: a readable composite string (`sagaId:step:Type#1`) — can exceed provider limits and leaks
class names to third parties; a random id stored in `SagaContext` — needs a write before dispatch and does not
survive a context-less crash window; hashing with SHA-256 — no benefit over the name-based UUID for a key that is not
a secret.

## §2 How a handler obtains the identity

**Decision**: A handler declares a parameter of type `CommandId`; the engine supplies it through a built-in
`HandlerParameterResolver` that is consulted before the host's resolvers. The engine binds the identity of the command
being handled for the duration of the handler call (a stack, so a handler that itself sends a command gets the inner
identity in the inner handler). Outside a saga step (FR-005) the parameter receives a fresh random identity per
dispatch, marked `sagaScoped() == false`.

**Rationale**: `EngineCommandGateway` already builds handler arguments through `HandlerParameterResolver`
(`HandlerArguments.forCommand`); a resolver is the existing, declarative way to supply framework values without a
field on the command class (FR-003) and without a static thread-local API on core.

**Alternatives considered**: a static `CommandId.current()` — hidden thread-local coupling, fails silently on
another thread; requiring an `@IdempotencyKey` field on every command — spec rejects it (FR-003); failing startup
when a `CommandId` parameter could be reached outside a saga — handlers serve both paths, and a random identity is
the truthful answer outside one.

## §3 Recording handled commands

**Decision**: `StepRecord` gains `handledCommands` (ordered list of `CommandId` values) and is written with the rest
of the instance in the unit of work's `StorageProvider.save`. Before routing a command inside a saga unit of work, the
gateway asks the unit of work whether that identity is already among the instance's committed `StepRecord`s (loaded
by `findByIdAndLock`); if so the handler is not invoked, the dispatch counts as handled, and `saga.command.skipped` is
logged (FR-004).

**Rationale**: The identity is per step run and a step completes at most once per instance, so the committed step
records are the complete set of handled identities; storing them there commits atomically with the handler's
effects (same transaction) at no extra statement. It also gives `006-saga-compensation` the list of commands a step
issued. The ledger is defence in depth behind the step-level duplicate check (§4): it matters when a completed step
could be reached again (006 resumption, a future command-replay path).

**Alternatives considered**: a separate `saga_handled_command` table behind a new SPI — an extra insert per command and
a new contract for no additional guarantee; checking only in-memory — does not survive a restart.

## §4 Duplicate events for completed steps

**Decision**: After `findByIdAndLock`, a unit of work whose triggering step is `COMPLETED` on the instance rolls back
and returns a new `HandlingOutcome.DUPLICATE`. This check runs **before** the terminal check, so a redelivered event of
a completed step of an ended saga is `DUPLICATE`, not `UNEXPECTED` (US2 scenario 3). Inside a unit of work, a recorded
event that would queue a step already completed (committed or earlier in this run) is not queued; it is buffered and
handed off like any non-queued event. The start step keeps its 004 behaviour (`DUPLICATE_START`).

**Rationale**: Uses the step records the engine already loads; no new storage call (FR-006, FR-007). A rolled-back
step stays `NOT_RUN`, so the first redelivery after a failure is correctly not a duplicate.

**Alternatives considered**: deduplicating by an event identity — domain events carry no framework identity today and
adding one is a transport concern (Kafka feature); writing a history row per duplicate — needs a commit for an event
that changed nothing (see §11).

## §5 Failure classification vocabulary

**Decision**: New core package `core.failure`:
- marker interfaces `TransientFailure` and `FinalFailure`, implemented by any exception class (no required base
  class, so authors keep their own hierarchies);
- enum `FailureClassification { TRANSIENT, FINAL, EXHAUSTED, UNCLASSIFIED }`.

The engine classifies the failure that rolled the unit of work back (the first failure the gateway reported, else the
thrown one, as 004 already chooses it) by walking its cause chain (cycle-safe): the first throwable implementing
`FinalFailure` gives `FINAL`, `TransientFailure` gives `TRANSIENT`; if neither is found, the chain is matched against the
failing step's `retryOn` types (`isInstance`), giving `TRANSIENT`; otherwise `UNCLASSIFIED`. A `java.lang.Error`
anywhere at the top is `UNCLASSIFIED` regardless of `retryOn`. A `TRANSIENT` failure on the last attempt is recorded
`EXHAUSTED` (FR-008, FR-010).

**Rationale**: Interfaces let a host mark e.g. its own `PaymentGatewayTimeoutException` without changing its
superclass (Principle II: framework-owned, no third-party taxonomy). Walking the cause chain is needed because the
gateway and reflection wrap handler exceptions (G-ENG-18). The marker beats `retryOn` (FR-010).

**Alternatives considered**: framework exception base classes (`TransientSagaException`) — forces a superclass;
classifying only the top-level exception — misses wrapped handler failures.

## §6 Where the retry policy is declared

**Decision**: A new attribute on `@SagaStep`: `RetryPolicy retry() default @RetryPolicy`, with the nested annotation
`@RetryPolicy` (no `@Target`, used only as an attribute value):

| Attribute | Type | Default | Meaning |
|---|---|---|---|
| `maxAttempts` | `int` | `1` | attempts including the first |
| `initialBackoffMillis` | `long` | `0` | wait before attempt 2 |
| `multiplier` | `double` | `1.0` | growth per further attempt (1.0 = fixed) |
| `maxBackoffMillis` | `long` | `0` | cap on one wait; `0` means "same as initial" |
| `retryOn` | `Class<? extends Throwable>[]` | `{}` | unmarked types treated as transient |

The `.kiro/specs/saga-forge-framework/design.md` annotation section and correctness properties are updated first
(FR-012, Constitution Principle III) with Property 29 (policy values valid: `maxAttempts >= 1`, backoffs `>= 0`,
`multiplier >= 1.0`, `maxBackoffMillis == 0 || maxBackoffMillis >= initialBackoffMillis`, `maxAttempts == 1` implies
no backoff and empty `retryOn`) checked by `validate`/`validateAll`.

**Rationale**: AGENTS.md and the constitution name `@SagaStep` attributes as the place and require framework-owned
primitive data. Millisecond `long`s are the only duration form an annotation can carry without parsing.

**Alternatives considered**: a separate `@Retry` method annotation — a second annotation to keep consistent with
`@SagaStep` for no gain; ISO-8601 duration strings — parsing and a new failure mode at startup.

## §7 Bounding the total backoff

**Decision**: Engine setting `SagaEngine.Builder.maxTotalRetryBackoff(Duration)`, default **30 seconds**. Startup adds
an error to the 004 `StartupReport` for every step whose policy's total wait
`sum_{k=2..maxAttempts} min(initial * multiplier^(k-2), cap)` exceeds it (FR-014a). The scanner cannot check this
(it has no engine settings).

**Rationale**: 30 s stays below common transport timeouts (Kafka's default `max.poll.interval.ms` is 5 minutes, with
headroom for several retried events in one poll) and keeps the lock hold short (G-005-02).

**Alternatives considered**: a hard-coded bound — hosts with slow providers need to raise it; no bound — contradicts
FR-014a.

## §8 The retry loop and the instance lock

**Decision**: A new package-private `AttemptLoop` in `engine.internal` owns the in-process instance lease that
`EventRouter` acquires today and runs `SagaUnitOfWork` attempts:

1. Read the attempts already used for this trigger from the instance (§9) — done by the unit of work after its locked
   read, returned with a `FAILED` result.
2. Run the attempt. Committed, `DUPLICATE`, `UNMATCHED`, `UNEXPECTED`, `NOT_HANDLED` → return.
3. On failure, classify (§5). `TRANSIENT` with attempts left → record the attempt (§9, its own transaction, like the
   004 `FailedWrite`), wait the computed backoff through an injectable `RetryPause`, and go to 2 with a new
   `SagaUnitOfWork` (new `unitOfWorkId`, same trigger, `started = false` after the first attempt).
4. Otherwise write `FAILED` with classification and attempt count (the existing `FailedWrite`, extended).

`SagaUnitOfWork` stops closing the lease itself; the loop closes it once, after the last attempt (before the
`AFTER_COMMIT` handoff, as today). One unit of work per instance therefore holds in-process for the whole sequence
(FR-014). Across processes the database lock is released between attempts, so another process may run a different
event for the instance in between; that is still one unit of work at a time, and the retry re-reads the instance.

**Rationale**: Keeps `SagaUnitOfWork` a single attempt (easy to compare with the 002 harness) and puts retry policy in
one place.

**Alternatives considered**: looping inside `SagaUnitOfWork` — mixes attempt bookkeeping into the transaction code;
retrying inside the same transaction (savepoints) — a rollback must undo the whole unit of work (002 contract).

## §9 Keeping the attempt count across rollback and restart

**Decision**: `SagaInstance` gains a nullable `pendingRetry: PendingRetry(String triggerStep, int attemptsStarted,
SagaFailure lastFailure)`, allowed only while the status is `CREATED` or `RUNNING`. After a transient failure with
attempts left, the attempt write (own transaction, `findByIdAndLock`, `save`) sets `attemptsStarted = n + 1` — it
**claims the next attempt before waiting** — and appends an `ATTEMPT_FAILED` lifecycle entry. Within one handling
call the loop then runs attempt `n + 1` itself. On a later delivery with the same `triggerStep` (after a crash or an
interrupted wait), the claimed attempt counts as used and the next is `attemptsStarted + 1`; if that exceeds the
`maxAttempts` of the step in `lastFailure`, nothing runs and the instance is marked `FAILED` `EXHAUSTED` with
`lastFailure`'s cause. Otherwise that attempt is claimed first, in its own write without a history entry, and then
run (found during implementation: without the claim, repeated crashes could repeat one attempt forever).
Limitation found during implementation: when the trigger step is the `@StartSaga` step, the redelivered start event
is a `DUPLICATE_START` and never resumes the claim (G-005-06). A committed unit of work with that trigger step clears `pendingRetry` and stores its attempt
number on the completed steps' `StepRecord.attempts` (FR-015, FR-017).

**Rationale**: Claiming before waiting means a crash during attempt `n+1` cannot grant it again, so the total stays
within `maxAttempts` after a restart (SC-005). Keying by trigger step keeps an unrelated event from consuming
another event's attempts. Storing it on the instance needs no new SPI (FR-019): `StorageProvider.save` already runs in
its own transaction for the `FAILED` write.

**Alternatives considered**: writing the count through `SagaContextStore` — mixes framework bookkeeping into business
state; counting in memory only — fails US5; recording every attempt start (including the first) — an extra write per
unit of work even when nothing fails. The first attempt of a fresh event is therefore not recorded; a crash during it
is indistinguishable from the event never having arrived (recorded as gap G-005-05).

## §10 Interruption instead of a stop API

**Decision**: The engine has no `stop()` today. FR-014b is met through thread interruption: if the thread is
interrupted while waiting, `AttemptLoop` stops waiting, restores the interrupt flag, leaves the claimed attempt
recorded, and returns `NOT_HANDLED` so the transport redelivers. `RetryPause` is a functional interface in the engine's
public package (`void pause(Duration) throws InterruptedException`), default `Thread::sleep`, settable through the
builder for tests.

**Rationale**: Interruption is how executors and Kafka consumers are shut down; adding an engine lifecycle method would
be scope creep.

## §11 Lifecycle history and diagnostics

**Decision**: `SagaLifecycleEventKind` gains `ATTEMPT_FAILED` (carries `stepName` of the failing step and a
`SagaFailure` with classification `TRANSIENT` and attempt number). `SagaFailure` gains `classification` and `attempts`
(4-argument constructor kept, defaulting to `UNCLASSIFIED`, `1`). Duplicate skips and already-handled commands are
**not** history rows: they change nothing, and writing them would need a commit on a path that otherwise rolls back.
They are logged (`saga.event.duplicate`, `saga.command.skipped`) and visible in the `HandlingReport` (`DUPLICATE`).

**Spec impact**: FR-018 is narrowed accordingly (updated in spec.md with this plan).

**Alternatives considered**: a history row per duplicate — one extra committed transaction per redelivered event, which
under Kafka redelivery storms multiplies write load for no state change.

## §12 Postgres storage

**Decision**:
- `saga_instance`: add `failure_classification TEXT`, `failure_attempts INT`, `pending_retry JSONB`; the
  `failure_iff_failed` check gains the two new columns; a new check forbids `pending_retry` on terminal rows.
- `saga_lifecycle_history`: add `failure_classification TEXT`, `failure_attempts INT`.
- `steps` JSONB: each entry gains `attempts` and `handledCommands`; readers default missing fields (`attempts = 1` for
  `COMPLETED`, `[]`), so rows written by 003/004 stay readable.
- `ForwardTransition`: a completed step's `attempts` and `handledCommands` must not change, like `touchedAggregates`.
- The module ships the updated `schema.sql` plus `schema-upgrade-005.sql` with the `ALTER TABLE` statements; the host
  applies it (G-003-25, no migration tool).

**Rationale**: JSONB for the nested `pendingRetry` follows the existing `steps` column; columns for classification
follow the existing flattened `failure_*` columns, keeping them queryable.

## §13 Conflicts, handoff and the example

- A write-time conflict stays `NOT_HANDLED` and is not retried in-process (004 research §3): it is not a step failure.
- The handoff is unchanged; a retried unit of work hands off only its committed attempt's events.
- `saga-forge-example`: `handleOrderCreated`, the step whose `ProcessPaymentCommand` reaches the provider, gets a
  policy (3 attempts, 200 ms, ×2, cap 1 s); `PaymentGatewayTimeoutException` implements `TransientFailure`.
  `PaymentService.handle` takes a `CommandId` and passes it to an in-memory `PaymentProvider` that returns the
  earlier payment for a repeated key (FR-020).
