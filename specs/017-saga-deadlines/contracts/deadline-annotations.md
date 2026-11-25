# Contract: Deadline annotations, scanner rules R24–R29, Properties 40–43 (FR-009, FR-010, FR-017)

To be copied into `.kiro/specs/saga-forge-framework/design.md` (rules table, validation-order list, Properties section,
error table). Rules run in `validate(Class<?>)` (single-saga), like R19–R22.

## Scanner rules

| Rule | Property | Check | Error text (prefix `Saga <FQN>: `) |
|------|----------|-------|------------------------------------|
| R24 | 40 | A step declares a deadline name at most once (several steps may start one); every `@StartDeadline` name has exactly one `@DeadlineExpired` step and every `@DeadlineExpired` names a declared deadline | `deadline 'x' is started by step 'a' but no @DeadlineExpired step handles it` / `deadline 'x' has more than one @DeadlineExpired step: 'a', 'b'` / `@DeadlineExpired step 'a' names deadline 'x', which no step starts` |
| R25 | 40 | Exactly one of `duration`, `durationFrom` is non-blank; a fixed `duration` that is not `${...}` parses as ISO-8601 and is not negative | `deadline 'x' on step 'a' must declare exactly one of duration and durationFrom` / `deadline 'x' on step 'a' has duration 'P1X', which is not an ISO-8601 duration` |
| R26 | 41 | Every `cancelledBy` name is a `@SagaStep` method of the saga and not the deadline's own `@DeadlineExpired` step | `deadline 'x' is cancelled by 'b', which is not a step of this saga` |
| R27 | 40 | A `@DeadlineExpired` method is also `@SagaStep` with a blank `associationProperty` and takes no parameter or one `DeadlineReached`; `@DeadlineExpired` without `@SagaStep` is rejected; `@StartDeadline` without `@SagaStep` is rejected | `@DeadlineExpired step 'a' must take no parameter or one DeadlineReached` / `@DeadlineExpired step 'a' must not declare an associationProperty` |
| R28 | 40 | A `@DeadlineExpired` step is not `@StartSaga` | `@DeadlineExpired step 'a' cannot also be @StartSaga` |
| R29 | 41 | A step carrying both `@DeadlineExpired` and `@StartDeadline` is neither `@StartCompensation` nor `@EndSaga` | `step 'a' starts deadline 'y' but also leaves RUNNING, so 'y' would be cancelled at once` |

Amended: Property 10 (step parameter is a `@DomainEvent`) and Property 11 (one step per event type) apply to steps
without `@DeadlineExpired`; the existing non-blank `associationProperty` rule (R2) applies to the same steps.
`@SagaCompensation(forStep = ...)` may target a timeout step that is not `@StartCompensation` (it is an ordinary step).

## Correctness properties

- **Property 40 — Deadline model well-formedness**: R24, R25, R27, R28 hold for every valid model.
- **Property 41 — Deadline cancellation**: for every committed unit of work *u* of instance *i*: if a step completed in
  *u* is named in deadline *d*'s `cancelledBy`, or *u* moves *i* out of `RUNNING`, then after *u* *d* is not open on *i*
  and its message is `CANCELLED` unless it was leased at that moment.
- **Property 42 — Deadline fires at most once and only while RUNNING**: a timeout step for deadline *d* of instance *i*
  runs only in a unit of work that started with *i* `RUNNING` and *d* open with the delivered `deadlineId`; that unit of
  work closes *d*; so each `deadlineId` runs at most one committed timeout step.
- **Property 43 — Not before due**: no `DeadlineReached` or scheduled command is handed to a receiver before its
  `notBefore` by the store's clock.

## Annotation usage

```java
@SagaStep(associationProperty = "orderId")
@StartDeadline(name = "paymentWindow", durationFrom = "paymentWindow")
public void onStockReserved(StockReservedEvent event) { ... }

@StartCompensation
@DeadlineExpired("paymentWindow")
@SagaStep
public void onPaymentWindowExpired(DeadlineReached deadline) { ... }
```
