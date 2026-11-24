# Changelog

All notable changes to Saga Forge. Versions follow the `vX.Y.Z` release tags.

## Unreleased

### Added

- **Outcome events** (`io.github.gkosharovdev.sagaforge.core.outcome`): a saga that reaches a terminal status records
  one event in the unit of work that writes the status: `SagaCompleted` (`COMPLETED`), `SagaFailed` with
  `phase() == STEP` (`FAILED`) or `phase() == COMPENSATION` (`COMPENSATION_FAILED`), and `SagaCompensated`
  (`COMPENSATED`). Each carries the saga type and id, the associations, the time and why it ended, never a failure
  message, context value or saga field. `eventId()` is `sagaId:STATUS`; deduplicate on it. The event is the last one
  of its unit of work's batch, so it reaches Spring listeners (`saga-forge.application-events.mode`) and the in-memory
  transport (ordering key `saga:<sagaId>`) like recorded events. It is not a domain event: no saga step can take one.
- **On by default.** Switch them off with `saga-forge.outcome-events.enabled=false` (starter),
  `SagaRuntime.Builder.outcomeEvents(false)` or `SagaFixture.Builder.outcomeEvents(false)`.
- Test fixture: `EventResult.outcomeEvents()`, `Then.expectOutcomeEvent(Class, Predicate)`,
  `Then.expectOutcomeEvent(Class)` and `Then.expectNoOutcomeEvent()`.

### Changed

- **Renamed** `SagaStatus.REQUIRES_INTERVENTION` and `HandlingOutcome.REQUIRES_INTERVENTION` to `COMPENSATION_FAILED`.
  The lifecycle-history kind `REQUIRES_INTERVENTION` is removed: a failed compensation writes one
  `COMPENSATION_FAILED` entry. The telemetry outcome value `requires_intervention` (`saga_forge.saga.ended`,
  `saga_forge.handle` spans) is now `compensation_failed`; update dashboards and alerts.
- **Postgres upgrade**: existing databases apply `schema-upgrade-011.sql` (renames the status, recreates the
  `trigger_iff_compensation` check, deletes the removed history kind's rows) once, while no runtime runs. A new
  database uses `schema.sql` as before.
- **The `FAILED` write can now fail on an outbox error.** It carries the `SagaFailed` event, so with an
  `IN_TRANSACTION` outbox (the in-memory transport) a failure to store it rolls the write back: the instance keeps its
  previous status, the runtime logs `saga.instance.failedNotRecorded` and the exception reaches the caller. With an
  `AFTER_COMMIT` outbox the status stays written and the failure is listed in `SagaHandlingResult.outboxFailures()`,
  which is now allowed for `FAILED` results too.
- Internally every runtime write goes through one unit-of-work type; no public API changes from that.
