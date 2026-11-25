# Contract: starter, Actuator, health and telemetry

## Properties (`TransportProperties`, configuration metadata, starter README table; `ReadmePropertiesTest`)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `saga-forge.transport.leadership.lease-duration` | `Duration` | `30s` | How long the active instance's leadership lasts without renewal; a standby takes over after it. |
| `saga-forge.transport.leadership.renew-interval` | `Duration` | `10s` | How often the active instance renews; must be shorter than the lease. |
| `saga-forge.transport.leadership.check-interval` | `Duration` | `5s` | How often a standby instance tries to take over. |

Removed: `saga-forge.transport.heartbeat-interval`. An invalid combination fails startup through the existing
`SagaForgeConfigurationFailureAnalyzer` with both values in the message.

## Actuator `GET /actuator/sagaforge`

```json
{
  "counts": { "UNCONSUMED": 3, "CONSUMED": 120 },
  "oldestUnconsumed": "PT0.4S",
  "leadership": {
    "role": "STANDBY",
    "relayId": "6c1f...",
    "leader": {
      "holderId": "a93e...",
      "holderHost": "booking-7f9c",
      "fencingToken": 14,
      "acquiredAt": "2026-11-25T21:00:03Z",
      "expiresAt": "2026-11-25T22:15:40Z"
    }
  }
}
```

`leader` is omitted when no record exists.

## Health (`SagaForgeHealthIndicator`)

| Situation | Status | Details |
|-----------|--------|---------|
| `ACTIVE` | existing rules (UP unless set-aside rules say otherwise) | `role`, `fencingToken` |
| `STANDBY`, leader record unexpired or expired less than one lease ago | same as above | `role`, `leader` (`holderId@holderHost`), `fencingToken` |
| `LeadershipStatus.isLeaderless(now, leaseDuration)` | `DEGRADED` (custom `Status`) | `reason: "no active relay for longer than the leadership lease"` |

The README documents adding `DEGRADED` to `management.endpoint.health.status.order` and its HTTP mapping if the host
wants it to fail readiness.

## Telemetry (`TransportInstrumentation`)

- Gauge `saga_forge.relay.active` — 1 while `leadership()` is present, else 0.
- Counter `saga_forge.relay.leadership_changes`, attribute `saga_forge.leadership.change` ∈ {`acquired`, `lost`,
  `released`}.
- No span per leadership round (rounds are not work a trace would follow).

## Logs (`java.util.logging`, the transport's `Logger`)

| Event | Level | Fields |
|-------|-------|--------|
| `saga.transport.leadership.acquired` | INFO | `relay`, `host`, `fencingToken`, `previousHolder` |
| `saga.transport.leadership.lost` | WARNING | `relay`, `fencingToken`, `newHolder` (if known), `cause` (`renewRefused`, `trustExpired`, `fenced`) |
| `saga.transport.leadership.released` | INFO | `relay`, `fencingToken` |
| `saga.transport.leadership.checkFailed` | WARNING | `relay`, exception |

Removed: `saga.transport.secondInstance`, `saga.transport.heartbeatFailed`.
