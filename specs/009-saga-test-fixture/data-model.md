# Data Model: Saga Test Fixture

The fixture keeps everything in memory for one test scenario. No table, no file.

## SagaFixture

| Field | Meaning |
|---|---|
| model | the registered classes: sagas, handler/loader/saver hosts, aggregates (validated at build, FR-003) |
| sagaObjects | per saga type, a supplier of fresh saga objects (default: the no-argument constructor) |
| handlers | per host class, the instance to run handlers on; a command whose handler host is not registered is recorded only (FR-011) |
| clock | `FixtureClock`: starts at `2026-01-01T00:00:00Z`, `set(Instant)`, `advance(Duration)`; retry waits advance it |
| identities | saga ids `saga-1`, `saga-2`, …; unit-of-work ids `uow-1`, … |
| failureRules | ordered `CommandFailureRule`s, first match wins |
| aggregates | `AggregateStore`, used only when handlers are registered |
| checkContextValues | default `true` (FR-019) |
| runtime | the `SagaRuntime` built over the fixture's plug-ins and started |

Lifecycle: `builder → build()` (runtime started, model validated) → any number of `given(...)` → `when(...)` →
expectations on the returned `Then`. A later `when(...)` continues from the state reached (spec edge case "Fixture
reuse").

## CommandFailureRule

| Field | Meaning |
|---|---|
| commandType | the `@Command` class |
| matcher | optional predicate over the command |
| failure | supplier of the exception thrown |
| times | how many matching commands fail: `1`, `n`, or unlimited |
| remaining | decremented per failure; a rule at `0` no longer matches |

Classification is the runtime's: the exception's `TransientFailure`/`FinalFailure` marker or the step's `retryOn`.

## RecordedCommand

| Field | Meaning |
|---|---|
| command | the command object |
| commandId | its identity (`sagaId`, `stepName`, `commandType`, `ordinal`, `value`); `stepName` is the sending step or compensation |
| handled | `true` if a registered handler ran; `false` if recorded only |
| delivery | `IN_UNIT_OF_WORK` or `OUTBOX` |
| attempt | the unit-of-work attempt that sent it (1-based) |
| committed | whether that attempt committed |

## EventResult (the "then" side)

Produced by `when(event)`; `given(...)` produces the same internally but exposes none.

| Field | Meaning |
|---|---|
| event | the event under test |
| reports | every `HandlingReport` of the event and of what it caused (events and outbox commands delivered by the loop-back, §4), in delivery order |
| instances | per touched instance (saga type + association value + saga id): `InstanceView` before and after |
| commands | `RecordedCommand`s of committed attempts, in send order; `allAttempts()` gives every attempt's |
| unhandled | `true` if no saga type handled the event (FR-007) |

## InstanceView

A read-only projection of the stored `SagaInstance` and its `SagaContextRecord`: status, outcome step, failure
(step, type, message, classification, attempts), compensation trigger, per-step status, attempts, completion
sequence and compensation status, and the context values.

## AggregateStore

`(aggregate type, identifier value) → aggregate object`. Seeded with `givenAggregates(...)`, read with
`aggregate(type, id)`; written by the runtime through the fixture's `AggregateResolver` after an aggregate handler ran.

## Validation rules

- Building a fixture with an invalid model throws the runtime's `SagaRuntimeStartupException` (FR-003).
- `when(...)` without a prior `build()` is impossible by construction (the builder returns the fixture).
- An expectation on "the instance" when the event touched none or several fails, naming them and pointing to
  `instance(type, associationValue)`.
- A `SagaContext` value outside the durable value types fails `given`/`when` with `SagaExpectationError` (FR-019),
  unless `checkContextValues(false)`.
