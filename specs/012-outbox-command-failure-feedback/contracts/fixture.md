# Contract: Test fixture support

**Module**: `saga-forge-test`

- `SagaFixture.Builder.outboxCommandAttempts(int attempts)` — how many times `LoopBackTransport` delivers a failing
  outbox command before it reports it as `EXHAUSTED`; default `1`, must be `>= 1`.
- A failing outbox command (a real handler, or `failCommand(...)`) is classified with `FailureClassification.of`: a
  `FinalFailure` is reported after one attempt, anything else after `outboxCommandAttempts`.
- A saga-scoped failure is handed to `runtime.commandReceiver().receiveFailure(...)`; the reaction is visible in the
  same `when` step: `InstanceView` shows the compensation, `EventResult.outcomeEvents()` the outcome event with
  `OutcomeTrigger.kind() == COMMAND_FAILED`.
- `EventResult` keeps listing the command failures it lists today (one per failed attempt), so existing expectations
  keep working (SC-006).
- Parity: `FixtureParityTest` gains a case comparing the fixture with a hand-assembled runtime over the in-memory
  transport for a final and an exhausted command failure.

## Behaviour change

A fixture test whose saga sends an outbox command that fails now sees the saga compensate. Task work reviews
`FailureAndCompensationTest`, `HandlerModeTest`, `FixtureParityTest` and the examples' fixture tests and updates any
expectation of the old silence.
