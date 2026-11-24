# Contract: Builders, Starter Properties and Fixture API

**Feature**: `011-saga-outcome-events` | Decisions: [research.md](../research.md) §7, §9, §10

All additions are additive; existing calls keep their behaviour apart from outcome events being on by default (FR-016)
and the renamed status (FR-019).

## `SagaRuntime.Builder`

| Method | Default | Effect |
|---|---|---|
| `outcomeEvents(boolean enabled)` | `true` | produce outcome events (O8) |

## `SagaFixture.Builder`

| Method | Default | Effect |
|---|---|---|
| `outcomeEvents(boolean enabled)` | `true` | passed to the fixture's runtime |

## `EventResult` and `Then` (saga test fixture)

| Member | Contract |
|---|---|
| `EventResult.outcomeEvents()` | `List<SagaOutcome>`: the outcome events committed while handling the `when` event, in commit order |
| `Then.expectOutcomeEvent(Class<T> type, Predicate<? super T> matching)` | passes if exactly one outcome event was produced, it is a `type` and it matches; otherwise `SagaExpectationError` listing the outcome events |
| `Then.expectOutcomeEvent(Class<?> type)` | as above, with any content |
| `Then.expectNoOutcomeEvent()` | passes if none was produced |

## Spring Boot starter properties

| Property | Default | Meaning |
|---|---|---|
| `saga-forge.outcome-events.enabled` | `true` | `SagaRuntime.Builder.outcomeEvents` |

The README table "Defaults and overrides" gains a row; `ReadmePropertiesTest` covers the property.

## Telemetry attribute value

The handling-outcome attribute value `requires_intervention` becomes `compensation_failed` (research §6). Dashboards
that filter on it must follow; the release notes say so.
