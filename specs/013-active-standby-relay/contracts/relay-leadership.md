# Contract: relay behaviour under leadership

Module `saga-forge-transport-inmemory`. `LeaderElector` and `Relay` are internal (`transport.inmemory.internal`, "Not
API"); `InMemoryTransport.leadership()`, `LeadershipStatus`, `RelayRole` and `LeadershipSettings` are public.

## Collaboration

```text
InMemoryTransport.Builder ── builds ──▶ Relay ── holds ──▶ LeaderElector ── holds ──▶ LeaderElection
                                          │                     ▲                   (messageStore.leaderElection())
                                          │ leadership()/stepDown│
                                          ▼                     │
                                   MessageStore.lease(LeaderLease, ...)  ── throws LeadershipLostException
```

- `Relay.start` → `elector.start()`: one synchronous `tryAcquire`, then schedules rounds on the
  `saga-forge-transport-leader` daemon thread (holder: every `renewInterval`; candidate: every `checkInterval`).
- `Relay.pollOnce`: `elector.leadership()`; empty → lease nothing this round (no store call). Present → `lease(lease,
  now, leaseDuration, room)`. On `LeadershipLostException` → `elector.stepDown(lease)`, lease nothing.
- `Relay.housekeeping`: message-lease renewal of in-flight deliveries always (R6); retention sweep only with
  leadership (R8).
- Retries: a message whose attempt failed is never re-leased by a relay without leadership; its `nextAttemptAt` is in
  the store, so the leader picks it up (FR-015).
- `Relay.stop(timeout)`: stop leasing → wait for in-flight (as today) → `messageStore.release(relayId)` (message
  leases) → `elector.resign()` (leadership), so a standby takes over on its next check (FR-014).

## `LeaderElector` rules (`LeaderElectorTest`, `tick()` with `MutableClock`)

| # | Given | When | Then |
|---|-------|------|------|
| E1 | empty store | `start()` | `leadership()` present at once; status `ACTIVE`; log `saga.transport.leadership.acquired` once; counter `acquired` +1 |
| E2 | other holder unexpired | `tick()` | `leadership()` empty; status `STANDBY` with the other holder as `leader` |
| E3 | holding, last renew at t | clock at `t + lease − renew` | `leadership()` empty even before `tick()` (FR-007); one `lost` change |
| E4 | holding | `renew` returns empty | `STANDBY`; log `saga.transport.leadership.lost` with own and new holder |
| E5 | holding | `stepDown(lease)` | `STANDBY`; a later `stepDown` with an older lease is ignored |
| E6 | holding | `resign()` | `release` called once; counter `released` +1; thread stopped |
| E7 | election throws (store unreachable) | `tick()` | logged `saga.transport.leadership.checkFailed`, no role change by itself; E3 still applies |
| E8 | settings `renewInterval >= leaseDuration` | construct | `IllegalArgumentException` naming both |

## Relay rules over two relays and one store (`TwoRelaysTest`, `InMemoryMessageStore`)

| # | Scenario | Expected |
|---|----------|----------|
| T1 | two relays started, 500 messages over 20 keys appended through both | all delivered once, per-key in append order, all by the active relay |
| T2 | active relay stopped gracefully mid-stream | standby `ACTIVE` within one `checkInterval` after `stop` returns; nothing lost |
| T3 | active relay's store calls blocked past its lease (pause double), standby takes over and delivers | paused relay's next `lease` throws `LeadershipLostException`; it becomes `STANDBY`; no message delivered by both; its in-flight delivery finishes and the key's next message is delivered only after it |
| T4 | deadline (017 `notBefore`) due during a handover | delivered once by the new leader |
| T5 | retention sweep scheduled on the standby | does not run |
