# Contract: Relay classification of failed outbox commands

**Module**: `saga-forge-transport-inmemory` (`internal.Relay`)

Applies to `MessageKind.COMMAND` messages only. Events keep today's path.

| Handler outcome | Saga-scoped command | Command outside a saga |
|-----------------|---------------------|------------------------|
| success | consumed in the unit of work (unchanged) | unchanged |
| failure classified `FINAL` (`FailureClassification.of`) | no retry; `receiveFailure(FINAL, attempts)`; set aside `FINAL_FAILURE` in its transaction | no retry; set aside `FINAL_FAILURE` |
| any other failure, `attempts < maxAttempts` | failed attempt, backoff (unchanged) | unchanged |
| any other failure, `attempts >= maxAttempts` | `receiveFailure(EXHAUSTED, attempts)`; set aside `MAX_ATTEMPTS` in its transaction | set aside `MAX_ATTEMPTS` (unchanged) |
| payload unreadable | `receiveFailure(FINAL, 1, causeType = UnreadableMessageException)`; set aside `UNREADABLE` | set aside `UNREADABLE` (unchanged) |
| `receiveFailure` throws | failed attempt, backoff; message redelivered | n/a |

- Retry count and backoff are the relay's `TransportSettings` (`maxAttempts`, backoff), never the step's
  `@RetryPolicy` (FR-002, clarified).
- `attempts` in the report counts handler attempts for this message, including the last one.
- The classification walks the cause chain of the exception the `CommandReceiver.receive` call threw.

## Telemetry

- Delivery span of a failed command: `saga_forge.command.classification` = `final` | `transient` | `unclassified`;
  `saga_forge.command.reported` = `true` when it reported.
- Counter `saga_forge.command.failures`, attribute `classification` = `final` | `exhausted`, one per report.

## Tests

`TransportAcceptanceSuite` cases: final → one attempt (SC-004); unclassified → `maxAttempts` attempts then report;
transient marker → retried; outside a saga → no report; unreadable saga-scoped → report; receiver throws → retried and
reported once the receiver recovers; crash between handler failure and set-aside → redelivered and reported (SC-003).
