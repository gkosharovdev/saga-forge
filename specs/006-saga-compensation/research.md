# Research: Saga Compensation

**Feature**: `006-saga-compensation` | **Spec**: [spec.md](./spec.md) | **Builds on**: `005-step-retry` (classification,
command identities), `004-saga-engine` (unit of work, instance lock, handoff)

Each section records one decision: what was chosen, why, and what else was considered. Section numbers (§n) are cited
by plan.md, data-model.md and contracts/.

## §1 Declaring a business failure

**Decision**: A new marker annotation `@StartCompensation` (core `annotation`, no attributes), placed together with
`@SagaStep` on the step that handles the failure event. The step's method name is the recorded failure outcome, the
same way an `@EndSaga` step's method name is the recorded success outcome.

```java
@StartCompensation
@SagaStep(associationProperty = "orderId")
public void rejectDelivery(DeliveryRejectedEvent event) {
    context.putState("rejectionReason", event.reason());
}
```

**Rationale**: It mirrors `@StartSaga`/`@EndSaga` (FR-001) and says what happens next, which a passive name such as
`@FailureOutcome` does not. No attribute is needed: the outcome name comes from the method, as for `@EndSaga`.

**Alternatives considered**: `@EndSaga(failure = true)` would change an existing annotation's meaning and make
`@EndSaga` steps run compensation; `@FailSaga` suggests the `FAILED` status, which this outcome is not; a
`failureEvents = {...}` list on `@Saga` would lose the step body that FR-002 requires.

## §2 Scanner rules

**Decision**: Three new rules, checked by `validate` and `validateAll` and therefore at engine startup (FR-001a,
FR-008):

| Rule | Text | Property |
|---|---|---|
| R20 | `@StartCompensation` co-occurs with `@SagaStep` and not with `@StartSaga` or `@EndSaga` | 30 |
| R21 | A `@SagaCompensation` method returns `void`, declares no parameter or exactly one of type `CompensationTrigger`, and is not a `@SagaStep` | 31 |
| R22 | At most one `@SagaCompensation` targets a given step, and none targets a `@StartCompensation` step | 32 |

R3 (at least one `@EndSaga`) is unchanged: a saga still needs a success outcome. R6 / Property 5 (`forStep` names an
existing `@SagaStep`) is unchanged.

**Rationale**: R22's second half follows from §5. The failure-outcome step is the trigger, not a forward effect to
undo, so a compensation for it would never run, and a declaration that never runs should be an error. R21 fixes the
signature that §4 defines.

**Alternatives considered**: Checking these rules at engine startup only would break the single-pass scanner contract
(Property 24), which reports every model error in one place.

## §3 Statuses and outcomes

**Decision**: `SagaStatus` gains two terminal values, `COMPENSATED` and `REQUIRES_INTERVENTION`. The existing
`COMPENSATING` (unused until now) is the non-terminal status while compensations run (FR-014). `HandlingOutcome` gains
`COMPENSATED` and `REQUIRES_INTERVENTION`. `EngineSagaLifecycle.isTerminal` covers
`COMPLETED, FAILED, COMPENSATED, REQUIRES_INTERVENTION`.

`SagaInstance.outcome` stays non-null if and only if `COMPLETED`, and `failure` if and only if `FAILED`. Why a saga
compensated is recorded in a new component, `compensationTrigger` (§6).

**Rationale**: FR-015 and FR-016 require outcomes that differ from `COMPLETED` and `FAILED`. Separate statuses keep
every existing invariant and the Postgres `CHECK` constraints valid. Reusing `FAILED` plus a flag would make
"was anything undone?" a two-field question and break the rule that `failure` is present if and only if `FAILED`.

**Alternatives considered**: Keeping `COMPLETED` with a special outcome name such as `"compensated"` hides the
failure from every consumer that treats `COMPLETED` as success.

## §4 What a compensation receives

**Decision**: A compensation method takes no parameter or one `CompensationTrigger` parameter (core
`core.compensation`). It uses the saga's injected `SagaContext` and `CommandGateway` fields exactly like a step
(FR-009). `CompensationTrigger` is a record with these components:
- `kind`: `FAILURE_OUTCOME`, `FINAL_FAILURE` or `RETRIES_EXHAUSTED`
- `stepName`: the failure-outcome step or the failing step
- `eventType`: the triggering event's class name for `FAILURE_OUTCOME`, else `null`
- `failure`: 005's `SagaFailure`, carrying classification, attempts and cause, for the two failure kinds, else `null`
- `at`

The triggering event object itself is not passed on. Business data the compensation needs, such as the rejection
reason or the payment reference, travels through `SagaContext` (FR-002, Assumptions).

**Rationale**: A compensation can run after a crash, in a later handling call that never saw the event (User Story 5).
Only persisted data is available then, and events are not persisted. Passing the event when it happens to be in memory
would make a compensation behave differently after a restart. A persisted trigger is identical in both cases and still
lets the business rule depend on the cause (FR-010).

**Alternatives considered**: Persisting the event as JSON in core would break the zero-dependency rule. Passing the
event as a nullable parameter has the restart problem above. Recorded as gap G-006-06.

## §5 Which steps are compensated, in which order

**Decision**:
- **Completion order**: `StepRecord` gains `completionSequence`, which is 0 unless `COMPLETED` and otherwise a number
  ≥ 1 that is unique within the instance. The lifecycle assigns it in `recordStepCompleted` as one more than the
  highest so far, so steps completed in one unit of work keep their execution order.
- **Selection**: compensation covers every `COMPLETED` step except the `@StartCompensation` step that triggered it, in
  decreasing `completionSequence` order (FR-005).
- **No compensation declared**: a step without a `@SagaCompensation` is marked `NOTHING_TO_COMPENSATE` (FR-007).
- **Excluded automatically**: steps that are `NOT_RUN`, including those whose unit of work rolled back, are never
  selected (US2 scenario 3).
- **Nothing committed**: if no step at all is `COMPLETED` when a final failure is classified, which only happens when
  the start step's unit of work rolled back, the instance is written `FAILED` as before (edge case "failure in the
  start step").

**Rationale**: Reverse completion order needs a total order that survives persistence. Timestamps can tie within one
unit of work, and the lifecycle history has no query API (G-003-24). A per-instance sequence is exact, cheap, and
written with the step record that already exists. Excluding the trigger step follows §2.

**Alternatives considered**: Ordering by `lastModifiedAt`, or by the step-name tie-break Postgres uses for history
(G-003-04), is not the true order. Ordering by declaration order contradicts "steps run in event order".

## §6 Recording the trigger and per-step status

**Decision**:
- **Trigger**: `SagaInstance` gains `compensationTrigger` (a `CompensationTrigger`), non-null if and only if the
  status is `COMPENSATING`, `COMPENSATED` or `REQUIRES_INTERVENTION`. It is written once, by the write that enters
  `COMPENSATING`, and never changes afterwards (FR-004, FR-017).
- **Per-step status**: `StepRecord` gains `compensation` (a `StepCompensation`: `status`, `at`, `failure`), which is
  `null` unless the step is `COMPLETED` and the instance has entered compensation.
- **Status values**: `CompensationStatus` is `NOT_ATTEMPTED`, `COMPENSATED`, `NOTHING_TO_COMPENSATE` or `FAILED`. Only
  `FAILED` carries a `failure`; `at` is `null` only for `NOT_ATTEMPTED`.
- **Who sets what**: the write that enters compensation sets every selected step to `NOT_ATTEMPTED` or
  `NOTHING_TO_COMPENSATE`. The trigger step and `NOT_RUN` steps keep `compensation == null`.

**Rationale**: An operator reads everything FR-017 and SC-004 ask for from the instance alone: the trigger, each
committed step's status, when it happened, and the cause of a failure.

## §7 Units of work and the instance lock

**Decision**: Compensation runs in the same handling call that triggered it, under the in-process instance lease the
`AttemptLoop` already holds (004 FR-025), as a sequence of separate transactions:

1. **Start write**. With a failure outcome, this is the triggering unit of work itself: the `@StartCompensation` step
   runs, then `lifecycle.startCompensation(FAILURE_OUTCOME)` stops the queue, like an `@EndSaga` step does, and the
   commit carries the step's effects, `COMPENSATING`, the trigger and the per-step statuses (FR-002). With a
   `FINAL`/`EXHAUSTED` failure, the `AttemptLoop` calls a new `CompensationStartWrite` where it called `FailedWrite`
   before. It runs in its own transaction, like `FailedWrite`, and writes `FAILED` instead when no step completed (§5).
2. **One unit of work per compensation** (`CompensationUnitOfWork`), for the next selected step that is
   `NOT_ATTEMPTED`, highest sequence first. Each one runs these phases:
   - **Lock and check**: `begin`, then `findByIdAndLock`. It requires `COMPENSATING` and the step still
     `NOT_ATTEMPTED`.
   - **Run**: create the saga object, inject its fields, bind the event recorder, run the compensation method.
   - **Mark and commit**: mark the step `COMPENSATED` and commit (FR-011). If no `NOT_ATTEMPTED` step is left, the same
     commit also ends the instance `COMPENSATED`, so the terminal outcome costs no extra write.
   - **Durability**: each commit is durable before the next compensation begins (FR-012).
3. **No compensation to run**: if the start write leaves no `NOT_ATTEMPTED` step, it ends the instance `COMPENSATED`
   itself.
4. **Lease and handoff**: the lease is released after the last write. Then the `AFTER_COMMIT` batches of the trigger
   unit of work and of each compensation are handed off in commit order. The handoff timing rules of 004 apply per
   unit of work.

**Rationale**:
- **One handling call**: keeping compensation in the call that triggered it means no scheduler is needed, which the
  engine does not have (Assumptions), and the caller learns the result.
- **Separate transactions**: these are what FR-011 and FR-012 require.
- **Holding the lease**: keeps one unit of work per instance in-process. Across processes the database lock still
  serializes, because every unit of work takes `findByIdAndLock`.

**Alternatives considered**:
- **All compensations in one transaction**: contradicts FR-011. One failing refund would roll back the stock release
  that already succeeded, and then nothing would be recorded.
- **Handing compensation to a transport event**: adds a transport dependency to the engine and loses the caller's
  result.

## §8 What compensation units of work may do

**Decision**: A compensation dispatches commands through the injected `CommandGateway` and reads and writes
`SagaContext` (FR-009).
- **Command identities**: commands get 005 identities scoped to the compensation method's name. Method names are unique
  in a class and distinct from step names, so identities never collide with a step's. A re-run after a crash in the
  middle of a compensation produces the same identities, so idempotent outside systems are protected (Assumptions).
- **Recorded events**: domain events recorded by its handlers are buffered and handed off. They are never queued to a
  step of this instance, because forward steps must not run while compensating (FR-014).
- **Failures**: a failure reported by the gateway fails the compensation even if the method caught it, the same rule
  as for steps (edge case "a compensation dispatches a command whose handler fails").

**Rationale**: It reuses 004 and 005 machinery unchanged. The in-process queue is the only behaviour that differs.

## §9 A compensation that fails

**Decision**: The compensation's unit of work rolls back. A `CompensationFailedWrite` then runs in its own
transaction, like `FailedWrite`:
- the step becomes `FAILED` with a `SagaFailure`, classified by 005's `FailureClassifier` with the default one-attempt
  policy, so the record is `FINAL`, `UNCLASSIFIED`, or `EXHAUSTED` for a transient cause after its single attempt;
- the instance ends `REQUIRES_INTERVENTION`;
- steps not yet reached stay `NOT_ATTEMPTED`.

There is no retry, whatever the classification (FR-016, SC-007).

A failure of this write itself propagates to the caller, like a `FailedWrite` failure. The instance stays
`COMPENSATING` and resumes at the failed step when next handled (§10).

**Rationale**: It is the spec's decision, and it reuses the classification so an operator can tell a refused refund
(`FINAL`, if the provider's exception is marked) from a bug (`UNCLASSIFIED`).

**Alternatives considered**: Retrying a failed compensation, for example with a policy of its own, is out of scope
(FR-021) and is recorded as gap G-006-09.

## §10 Resuming after a crash; events for a compensating instance

**Decision**: When a unit of work's locked read finds the instance `COMPENSATING`, the event's own step does not run.
The attempt rolls back and returns a new `AttemptResult.Compensating`, and the `AttemptLoop` runs the remaining
compensations as in §7 step 2. The handling call reports the terminal outcome reached (`COMPENSATED` or
`REQUIRES_INTERVENTION`).
- **Check order**: this check comes before 005's duplicate check, because a redelivered failure-outcome event has a
  `COMPLETED` step and would otherwise be skipped as `DUPLICATE` without resuming.
- **After the end**: once the instance is terminal, events are `UNEXPECTED`, as for any ended saga, and a repeated
  failure-outcome event is `DUPLICATE`. Neither runs anything (scenario 1.4).

**Rationale**:
- **Exactly-once compensations**: the per-step `COMPENSATED` mark is committed with each compensation's effects, so a
  resumed run starts at the first `NOT_ATTEMPTED` step and never repeats a committed compensation (FR-012, SC-003).
- **Who resumes**: any event for the instance can resume, so the transport's redelivery of the trigger, the usual case
  after a crash, is enough.

**Spec refinement**: the edge case "late forward events" now says that the forward event is not run and that a
compensation left unfinished by a crash is resumed by it. The spec is updated with this plan.

**Gap**: an instance left `COMPENSATING` with no further event never resumes. There is no sweeper (Assumptions;
compare G-ENG-05). This is gap G-006-05.

## §11 Lifecycle history and diagnostics

**Decision**: `SagaLifecycleEventKind` gains five kinds. `SagaLifecycleEntry` gains `trigger`, a
`CompensationTrigger` that is non-null if and only if the kind is `COMPENSATION_STARTED`.

| Kind | Carries |
|---|---|
| `COMPENSATION_STARTED` | `trigger` |
| `STEP_COMPENSATED` | `stepName` |
| `COMPENSATION_FAILED` | `stepName` and `failure` |
| `COMPENSATED` | nothing |
| `REQUIRES_INTERVENTION` | nothing |

Steps with `NOTHING_TO_COMPENSATE` produce no entry; their status is on the instance. This satisfies FR-018.

New diagnostics:

| Name | Level |
|---|---|
| `saga.compensation.started` | `INFO` |
| `saga.compensation.step` | `INFO` |
| `saga.compensation.failed` | `ERROR` |
| `saga.compensation.ended` | `INFO` |
| `saga.compensation.resumed` | `INFO` |

**Rationale**: History records what happened, in order (Property 27 extended). A skipped step is not an event.

## §12 Postgres

**Decision**:
- **`saga_instance`**: gains a `compensation_trigger JSONB` column, with
  `CHECK ((status IN ('COMPENSATING','COMPENSATED','REQUIRES_INTERVENTION')) = (compensation_trigger IS NOT NULL))`.
- **Step JSON**: each step object in `steps` gains `sequence` and `compensation`. Decoding defaults: `0` for a missing
  `sequence`, `null` for a missing `compensation`.
- **`saga_lifecycle_history`**: gains `compensation_trigger JSONB`. The `step_name` and `failure_*` columns are reused
  for the new kinds.
- **`ForwardTransition` additions**:
  - a completed step's `sequence` never changes;
  - a step's `compensation` only moves `null → NOT_ATTEMPTED|NOTHING_TO_COMPENSATE` and
    `NOT_ATTEMPTED → COMPENSATED|FAILED`, and is unchanged otherwise;
  - the trigger never changes once set;
  - `COMPENSATING` only moves to `COMPENSATED` or `REQUIRES_INTERVENTION`;
  - the two new statuses are terminal.
- **`schema-upgrade-006.sql`**:
  - adds the columns;
  - backfills `sequence` for completed steps from the order of their `STEP_COMPLETED` history rows (`sequence_number`);
  - for steps with no history row, assigns sequences after those, by step name. That fallback is gap G-006-07.

**Rationale**: It follows the 005 pattern: JSON for nested per-step data, a column plus `CHECK` for instance-level
invariants, and an upgrade script, with no migration tool (G-003-25).

## §13 Example and docs

**Decision**: `OrderProcessingSaga` gains the following (FR-019):
- `rejectDelivery(DeliveryRejectedEvent)`, a `@StartCompensation @SagaStep(associationProperty = "orderId")`;
- `compensatePayment(CompensationTrigger)`, which sends a `RefundPaymentCommand` for the payment reference and amount
  in `SagaContext`;
- `PaymentService` handles that command with its `CommandId` as the provider's idempotency key, as in 005.

The existing happy path (`completeOrder` on `OrderCompletedEvent`) is unchanged. Docs to update: README, the
architecture feature-gap section "Failure categorization and automatic compensation dispatch" (marked implemented),
use-cases (the refund and unwinding flows), AGENTS.md (Current Status, saga model, gaps) and memory.md (§14).

## §14 Gaps recorded by this plan

| ID | Gap |
|---|---|
| G-006-01 | Closed by 005: classification exists. |
| G-006-02 to G-006-04 | Unchanged: no confirmation wait, no resume from intervention, no irreversible marker. |
| G-006-05 | A `COMPENSATING` instance with no further event never resumes. |
| G-006-06 | A compensation sees the trigger's event type, not the event; data must go through `SagaContext`. |
| G-006-07 | Pre-006 completed steps without history rows get a name-ordered `sequence` on upgrade. |
| G-006-08 | Compensations run on the caller's thread and hold the instance lock for the whole sequence, like G-005-02. |
| G-006-09 | A failed compensation is never retried, even for a transient cause; the saga goes straight to "requires intervention". |
