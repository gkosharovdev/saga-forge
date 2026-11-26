# Research: Active/Standby Relay

**Feature**: `specs/013-active-standby-relay` | **Date**: 2026-11-25

Each entry: Decision, Rationale, Alternatives considered. R-numbers are cited by plan.md, data-model.md and contracts/.

## Spike (Constitution, Development Workflow: test an abstraction before proposing it)

Before this plan proposed any new abstraction, the SPI shape was exercised by a throwaway spike in the session
scratchpad (`spike013/`, plain `javac`, not part of the build): `LeaderLease`, `LeaderElection`,
`LeadershipLostException`, an in-memory `LeaderElection` with a fenced critical section standing in for
`MessageStore.lease`, and a `LeaderElector` caller driven by `tick()` over a mutable clock. Seven checks passed: one
active and one standby; renewal keeps leadership; a paused holder stops trusting its lease before the store expires it
(FR-007); a successor gets a higher fencing token; a lease call with the stale token is refused (FR-008); the stale
holder's renewal fails and does not take leadership back; a graceful release hands over on the next standby check.
The shapes below are those the spike proved; the real tests (contract cases, `LeaderElectorTest`) replace it.

## R1 — Leadership is a separate SPI reached from `MessageStore`, not more methods on it

**Decision**: a new interface `core.spi.store.LeaderElection` (`tryAcquire`, `renew`, `release`, `current`) and one new
abstract method `MessageStore.leaderElection()` returning the store's election. `MessageStore.lease` takes the
`LeaderLease` instead of a consumer id (R2).

**Rationale**: `MessageStore` already has 16 methods serving appenders, the relay, operators and retention.
Four more leadership methods would widen it for every caller (ISP). The relay's leadership loop (`LeaderElector`)
depends only on `LeaderElection`, so its tests use a small double instead of a whole store. The accessor keeps the two
in one implementation (they share a table and a transaction for fencing) while letting callers depend on the narrow
interface. Composition: the Modulith store returns its Postgres delegate's election.

**Alternatives**: (a) methods directly on `MessageStore` — fatter interface, every `LeaderElector` test needs a full
store double; (b) a separately wired `LeaderElection` bean beside the store — the fence (R2) needs both in one
transaction, so a mismatched pair (Postgres store, in-memory election) would be unsafe and has to be prevented anyway;
(c) Postgres advisory locks only — session-bound, lost silently on a pooled connection reset, no fencing token, not
portable to `InMemoryMessageStore` or a later MySQL/MongoDB store.

**Familiarity**: high. "Leader election" with a lease is the vocabulary of Kubernetes (`coordination.k8s.io/Lease`,
client-go `leaderelection.LeaderElector`), Apache Curator (`LeaderLatch`), Spring Integration (`LeaderInitiator`).

## R2 — Fencing: `lease(LeaderLease, ...)` is checked against the current leadership in the same transaction

**Decision**: `LeasedMessages lease(LeaderLease leader, Instant now, Duration leaseDuration, int limit)` replaces
`lease(String consumerId, ...)`. The message lease holder is `leader.holderId()`. Before leasing, the store checks, in
the same transaction, that the leadership record still has `leader.fencingToken()` and has not expired at `now`;
otherwise it leases nothing and throws `LeadershipLostException`. Postgres: `SELECT ... FROM saga_message_store_leader
WHERE fencing_token = ? AND expires_at > ? FOR SHARE` in the lease transaction, then the existing lease `UPDATE`; an
acquisition `UPDATE`s that row and therefore waits for an in-progress lease to commit, so no lease can interleave with
a takeover. `InMemoryMessageStore`: both under its monitor.

**Rationale**: the fencing token (Kleppmann, "How to do distributed locking", 2016; ZooKeeper zxid, etcd revision) is
the standard answer to a paused leader. Taking the lease as a parameter makes "lease without leadership" impossible to
write (no consumer-id overload left). Losing leadership at that point is exceptional (the relay checked its local view
first), so an unchecked exception matches `LeaseLostException` (EJ Item 69, 72).

**Alternatives**: a boolean "is leader" check before leasing (race between check and lease); returning an empty batch
(indistinguishable from "nothing due", the relay would keep polling as a leader).

## R3 — Time: callers pass `now`, as for every other store method

**Decision**: `tryAcquire` and `renew` take `Instant now` from the relay's `Clock`; the store compares `expires_at`
with it. No database clock.

**Rationale**: consistency with the rest of `MessageStore` (message leases, due times, cancellation already use the
caller's `now`); deterministic contract tests for both stores with `MutableClock` (Postgres cannot have its clock
advanced). Safety does not depend on clocks: two relays can never lease in the same window because of the fence (R2);
skew only shifts *when* a takeover happens. Spec Edge Cases and Assumptions were amended accordingly.

**Alternatives**: database clock (`clock_timestamp()`) for Postgres — robust to skew but untestable without sleeping,
and inconsistent with message leases, which would still use the caller's clock.

## R4 — Lease timing and local distrust

**Decision**: defaults `leaseDuration` 30 s, `renewInterval` 10 s, `checkInterval` 5 s, in a `LeadershipSettings`
record (validation: all positive, `renewInterval < leaseDuration`, `checkInterval < leaseDuration`). The holder trusts
its lease until `startOfLastSuccessfulCall + leaseDuration - renewInterval` by its own clock (FR-007) and leases
nothing after that, even before the store would hand leadership on. Takeover after a crash ≤ lease (30 s) + check
(5 s) = 35 s (SC-002).

**Rationale**: the same ratios as Kubernetes' defaults (lease 15 s, renew deadline 10 s, retry 2 s) scaled for a
database round trip; the local distrust margin is client-go's `RenewDeadline`. 30 s keeps database writes at one row
update per 10 s.

## R5 — `LeaderElector`: the relay's leadership loop is its own class

**Decision**: `transport.inmemory.internal.LeaderElector` (final, package-private constructor used by `Relay`'s
assembly) holds `LeaderElection`, `Clock`, `LeadershipSettings`, the relay id and host, `Logger` and
`TransportInstrumentation`. It runs on its own daemon thread (`ScheduledExecutorService`, one thread,
`saga-forge-transport-leader`), independent of deliveries (FR-004): as holder it renews every `renewInterval`, as
candidate it tries to acquire every `checkInterval`. It exposes `Optional<LeaderLease> leadership()` (only while
locally trusted, R4), `stepDown(LeaderLease)` (called by `Relay` on `LeadershipLostException`), `status()`, `start()`
(one synchronous attempt first, so a lone instance is active at once, D9) and `resign()` (release, used by
`Relay.stop`). A package-visible `tick()` runs one round, so `LeaderElectorTest` drives it with `MutableClock` and an
election double, no threads.

**Rationale**: SRP — `Relay` is 641 lines and already coordinates leasing, workers, retries and retention; leadership
is a separate reason to change. No listener interface: the relay asks `leadership()` before each lease and the elector
logs and instruments changes itself, so no new callback abstraction is needed (fewer abstractions to prove).

**Familiarity**: high — client-go `LeaderElector` with `OnStartedLeading`/`OnStoppedLeading`, here without callbacks.

**Alternatives**: leadership inside `Relay.housekeeping()` (couples renewal to the poll loop, so a long poll or a slow
lease query delays renewal and causes flapping, against FR-004); a `LeadershipListener` interface (an extra
abstraction with one caller).

## R6 — A deposed relay finishes in-flight deliveries

**Decision**: on losing leadership the relay leases nothing more, never retries or redelivers, but keeps renewing the
*message* leases of its in-flight deliveries until they end, so a successor cannot lease them (FR-009, FR-010).
`recordFailure` by a deposed relay still writes (it holds the message lease): the next attempt then happens on the
leader at the stored `nextAttemptAt` (FR-015).

**Rationale**: interrupting a unit of work mid-commit is unsafe and would need cooperation from host code; letting it
finish is what the instance row lock and duplicate checks already make safe (005 `DUPLICATE`, 017 open-deadline
check). Message leases (5 min) are much longer than leadership leases (30 s), so in-progress work blocks its own
redelivery and, through the ordering-key rule, any later message on the same key.

## R7 — Remove consumer heartbeats

**Decision**: delete `ConsumerHeartbeat`, `MessageStore.heartbeat`/`liveConsumers`, the
`saga_message_store_consumer` table (dropped by `schema-upgrade-013.sql`), `TransportSettings.heartbeatInterval`,
property `saga-forge.transport.heartbeat-interval`, the `secondInstance` warning and `SecondInstanceTest`.

**Rationale**: their only use was the single-instance warning (007 FR-021), which 013 replaces; the leadership record
carries holder id and host for visibility. No external users (user decision, 2026-11-25), so no deprecation period.

## R8 — Housekeeping on the leader

**Decision**: retention sweeps run only while `leadership()` is present. Message-lease renewal runs whatever the role
(R6). Queries and `redeliver` stay role-free.

## R9 — Visibility

**Decision**: public `transport.inmemory.LeadershipStatus` record (`RelayRole role`, `String relayId`, nullable
`LeaderLease leader` — the last record read, possibly expired —, `Instant observedAt`) from
`InMemoryTransport.leadership()`. Starter: `sagaforge` endpoint gains a `leadership` object; the health indicator adds
role, leader and epoch as details and reports a custom status `DEGRADED` when the last record expired more than one
lease duration ago (FR-023), documented with `management.endpoint.health.status.order`. Telemetry: observable gauge
`saga_forge.relay.active` (1 or 0) and counter `saga_forge.relay.leadership_changes` with attribute
`saga_forge.leadership.change` = `acquired` | `lost` | `released`. Logs: `saga.transport.leadership.acquired`,
`.lost`, `.released`, once per change.

**Alternatives**: `Status.DOWN` (would make orchestrators restart a healthy standby); `OUT_OF_SERVICE` (means "taken
out on purpose").

## R10 — Modulith interop

**Decision**: `ModulithMessageStore.leaderElection()` returns its `PostgresMessageStore` delegate's; its `lease`
passes the `LeaderLease` on to the delegate, so the fence covers Modulith mode too (D7).

## R11 — Latency of messages written on a standby

**Decision**: accepted. A standby's unit of work appends; only the leader's poll (`poll-interval`, 500 ms) picks it
up, since the in-process signal reaches only the local relay. Recorded as G-013-01. A database notification
(Postgres `LISTEN/NOTIFY`) is a later option.

## R12 — Testing strategy

- `MessageStoreContract` (in-memory and Postgres): acquire on empty, acquire refused while held, acquire after expiry
  with higher token, renew by holder, renew refused after expiry or takeover, release only by holder, lease with stale
  token throws and leases nothing, concurrent acquisition (two threads, one winner), lease and takeover interleaving.
- `LeaderElectorTest`: `tick()` with `MutableClock` and an in-memory election; settings validation.
- Relay tests over `InMemoryMessageStore`: two relays, one store (`TwoRelaysTest`): one delivers; handover on stop;
  pause injection (a store double that delays `lease`) proves FR-008/US4.
- Container (`@Tag("container")`): `MultiInstanceAcceptanceTest` — two `SagaRuntime` + `InMemoryTransport` pairs over one
  Postgres, each with its own connection pool; 10,000 events over 100 associations through both (SC-001), crash
  simulated by closing instance A's pool (SC-002), 20 forced handovers (SC-003), deadlines across a handover (SC-004).
  Starter: two `SpringApplicationBuilder` contexts on one database (G-008-04 style, `MultiInstanceContainerTest`), Actuator roles.
- Existing `TransportAcceptanceSuite`, `RestartTest`, `ScheduledDeliveryTest` pass unchanged (SC-006).

## Gaps to record (memory.md)

- **G-013-01**: messages appended on a standby wait up to one poll interval for the leader (R11).
- **G-013-02**: delivery throughput is one relay's; no partitioned leasing (spec Assumptions).
- **G-013-03**: takeover timing depends on instance clocks agreeing (R3); safety does not.
- Close **G-007-09** and **G-017-02**; narrow **G-ENG-08** (start events relayed through the store run on one instance)
  and **G-ENG-11** (`FOR NO KEY UPDATE` exercised by two instances in the container suite).
