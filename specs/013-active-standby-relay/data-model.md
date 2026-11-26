# Data Model: Active/Standby Relay

**Feature**: `specs/013-active-standby-relay` | **Date**: 2026-11-25

Types are immutable records with validating canonical constructors (Effective Java Item 17, 49), as in the rest of
`core.spi.store`. No new class hierarchy.

## Core (`saga-forge-core`, `core.spi.store`)

### `LeaderLease` (record, new)

The leadership record of one message store, as last written or read.

| Component | Type | Rule |
|-----------|------|------|
| `holderId` | `String` | non-blank; the relay id (random per transport start, ≤ 64 chars) |
| `holderHost` | `String` | non-blank; informational |
| `fencingToken` | `long` | ≥ 1; strictly increasing per store across acquisitions; unchanged by renewal |
| `acquiredAt` | `Instant` | non-null; time of the acquisition that issued the token |
| `expiresAt` | `Instant` | non-null; not before `acquiredAt` (equal after a release at the acquisition instant); moved forward by renewal |

Methods: `boolean isExpiredAt(Instant now)` (`!now.isBefore(expiresAt)`), `boolean isHeldBy(String relayId)`.

### `LeaderElection` (interface, new) — see contracts/leader-election-spi.md

### `LeadershipLostException` (unchecked, new)

Thrown by `MessageStore.lease` when the given lease is not the current, unexpired leadership. Carries the stale
`LeaderLease` (`staleLease()`); nothing was leased. Sibling of `LeaseLostException`, same style.

### `MessageStore` (changed)

| Change | Detail |
|--------|--------|
| + `LeaderElection leaderElection()` | abstract; the election sharing this store's transaction for fencing |
| `lease(String, Instant, Duration, int)` → `lease(LeaderLease, Instant, Duration, int)` | fenced (research R2); message leases are held by `leader.holderId()` |
| − `heartbeat(ConsumerHeartbeat)`, − `liveConsumers(Instant)` | removed (R7) |

`renew`, `release`, `markConsumed`, `recordFailure`, `setAside` keep their `consumerId` parameter: they act on message
leases, which a deposed relay still holds (R6).

### `ConsumerHeartbeat` — removed (R7)

## Postgres (`saga-forge-storage-postgres`)

### Table `saga_message_store_leader` (new; `schema.sql` and `schema-upgrade-013.sql`)

| Column | Type | Rule |
|--------|------|------|
| `id` | `SMALLINT` | primary key, `CHECK (id = 1)`: one row per store |
| `holder_id` | `VARCHAR(64)` | NOT NULL |
| `holder_host` | `VARCHAR(255)` | NOT NULL |
| `fencing_token` | `BIGINT` | NOT NULL, `CHECK (fencing_token >= 1)` |
| `acquired_at` | `TIMESTAMPTZ` | NOT NULL |
| `expires_at` | `TIMESTAMPTZ` | NOT NULL, `CHECK (expires_at >= acquired_at)` |

The row is never deleted: `release(lease, now)` sets `expires_at` to `now` so the token history survives (the next
acquisition increments it). On an empty table the first acquisition inserts token 1 (`INSERT ... ON CONFLICT (id) DO
UPDATE ... WHERE saga_message_store_leader.expires_at <= ?`, `RETURNING`).

`schema-upgrade-013.sql` also drops `saga_message_store_consumer`.

## Transport (`saga-forge-transport-inmemory`)

### `LeadershipSettings` (record, new, public)

| Component | Default | Rule |
|-----------|---------|------|
| `leaseDuration` | 30 s | positive |
| `renewInterval` | 10 s | positive, `< leaseDuration` |
| `checkInterval` | 5 s | positive, `< leaseDuration` |

`LeadershipSettings.defaults()`; violations throw `IllegalArgumentException` naming both values (FR-005).

### `TransportSettings` (changed)

`heartbeatInterval` component replaced by `LeadershipSettings leadership` (a parameter object grouping the three
durations, so the record does not grow to twelve positional components); `withLeadership(LeadershipSettings)`.

### `RelayRole` (enum, new, public): `ACTIVE`, `STANDBY`

### `LeadershipStatus` (record, new, public)

| Component | Type | Rule |
|-----------|------|------|
| `role` | `RelayRole` | non-null; `ACTIVE` only while `LeaderElector.leadership()` is present |
| `relayId` | `String` | non-blank; this relay |
| `leader` | `LeaderLease` | nullable: no leadership record read yet |
| `observedAt` | `Instant` | non-null; when `leader` was last read or written |

Method `boolean isLeaderless(Instant now, Duration leaseDuration)`: no record, or the record expired more than
`leaseDuration` before `now` (health `DEGRADED`, FR-023).

### `LeaderElector` (internal, final) — see contracts/relay-leadership.md

State machine per relay:

```text
            start(): tryAcquire ok                      renew ok
 CANDIDATE ───────────────────────────────▶ LEADING ◀──────────┐
 (STANDBY)  ◀─────────────────────────────  (ACTIVE) ──────────┘
     ▲  │      renew refused / lost token /
     │  │      local trust expired (FR-007) /
     │  │      LeadershipLostException from lease
     │  └── tryAcquire refused (every checkInterval)
     │
 STOPPED ◀── resign() (release, from LEADING) or stop() (from CANDIDATE)
```

## Starter (`saga-forge-spring-boot-starter`)

Properties (replacing `saga-forge.transport.heartbeat-interval`):

| Property | Default |
|----------|---------|
| `saga-forge.transport.leadership.lease-duration` | `30s` |
| `saga-forge.transport.leadership.renew-interval` | `10s` |
| `saga-forge.transport.leadership.check-interval` | `5s` |

Actuator `sagaforge` payload gains `leadership: {role, relayId, leader: {holderId, holderHost, fencingToken,
acquiredAt, expiresAt}}`; health details gain `role`, `leader`, `fencingToken`; status `DEGRADED` per R9.

## Telemetry

| Instrument | Kind | Attributes |
|------------|------|------------|
| `saga_forge.relay.active` | observable gauge, `{relay}` (1 or 0) | none |
| `saga_forge.relay.leadership_changes` | counter, `{change}` | `saga_forge.leadership.change` = `acquired`/`lost`/`released` |
