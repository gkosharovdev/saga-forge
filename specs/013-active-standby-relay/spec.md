# Feature Specification: Active/Standby Relay

**Feature Branch**: `013-active-standby-relay`

**Created**: 2026-11-25

**Status**: Implemented (2026-11-26)

**Input**: User description: "013 multi-consumer support with the message store (several running instances sharing one message store), taking into account the saga deadlines from 017" — roadmap feature 013, `.specify/memory/roadmap.md` "### 013: Active/standby relay"; closes G-007-09 and G-017-02; booking-service feedback SF-6, workaround W9.

## Context

The in-memory transport's relay assumes it is the only running consumer of its message store
(`specs/007-in-memory-transport` FR-021, G-007-09). Message leases keep two relays from delivering the same message at
the same moment, but per-aggregate order, backoff, retention and the poll loop are coordinated only inside one process.
A second application instance against the same database is detected through heartbeats and logged
(`saga.transport.secondInstance`), not coordinated. Since 017 the same holds for due messages: two instances may both
deliver a due deadline, and only the runtime's open-deadline check keeps the second delivery from running a step
(G-017-02). The first adopter therefore pins one replica and avoids overlapping rolling deploys (booking-service W9).

This feature lets several instances of one application share one message store safely. Exactly one instance's relay is
**active** at a time and delivers messages, including due deadlines and scheduled commands; the others are **standby**
and take over when the active one stops or disappears. Every instance can still write: units of work on any instance
append messages to the store in their own transactions, and the active relay delivers them. This is active/standby, not
scale-out: delivery throughput stays that of one relay. Spreading delivery across active instances (partitioned leasing)
is the Kafka transport's job or a later spec.

## Decisions Taken In This Spec

These settle the questions in the roadmap brief; each is reflected in the requirements below.

- **D1 — One active relay per message store.** Leadership is held per message store (one store is one application's
  log), not per saga type, ordering key or configured group name. The leadership record lives in the message store,
  next to the messages and heartbeats, and is written and checked through the `MessageStore` SPI so every store
  implementation (Postgres, `InMemoryMessageStore`, the starter's Modulith interop store) provides it and the
  `MessageStoreContract` covers it (Constitution Principle IV: no new transport, no new storage). The leadership
  operations are required SPI methods, not optional defaults: the library has no external users yet, so no
  compatibility path for custom stores is kept (user decision, 2026-11-25).
- **D2 — A time-bounded leadership lease.** The active relay holds a leadership lease with an expiry and renews it well
  before it runs out. A standby instance acquires leadership only once the lease has expired or was released. Defaults:
  leadership lease 30 s, renewed every 10 s, standby check every 5 s; all three are configurable and validated
  (renewal interval shorter than the lease). A crashed active instance is replaced within one lease duration plus one
  standby check; a gracefully stopping one releases the lease so a standby takes over within one standby check.
- **D3 — Leadership fences leasing.** Leasing messages is conditional, in the same store operation, on the caller
  holding the current leadership. Each acquisition gets a strictly increasing leadership epoch; a relay that lost its
  lease (paused, partitioned or slow) cannot lease new messages with its stale epoch, so it can never start a delivery
  after a successor took over.
- **D4 — A deposed relay finishes what it started.** A relay that learns it lost leadership stops leasing at once and
  lets deliveries already in progress run to their end (commit or roll back), bounded by the existing graceful-shutdown
  wait; it does not interrupt them. Message leases (`lease-duration`, 5 min) outlive the leadership lease, and the store
  already refuses to lease a message while an earlier message with the same ordering key is unconsumed, so the new
  leader neither redelivers a message still in progress nor overtakes it on its key. Correctness under overlap rests on
  the instance row lock (`findByIdAndLock`) and the duplicate checks (005, 017), not on the in-process lock.
- **D5 — Due messages follow leadership.** Deadlines and scheduled commands (017) are leased only by the active relay,
  like every other message. Messages that came due during a handover are delivered by the new leader on its first poll,
  in stored order (017 D3, G-017-03); none is skipped. Deadline latency becomes "poll interval, plus handover time
  while a handover runs". Closes G-017-02.
- **D6 — Housekeeping on the leader only.** Retention purging of consumed and cancelled messages runs only on the
  active instance. Operator queries are read-only and work on every instance; a redelivery request may be made on any
  instance and is delivered by the active relay.
- **D7 — Modulith interop takes part.** Under `saga-forge.transport.modulith-interop` the messages live in Spring
  Modulith's event publication registry, but Saga Forge's delivery records and heartbeats sit beside them in its own
  tables; the leadership record does too, so the same active/standby rule applies. Modulith's own republication of
  incomplete publications on restart is Modulith's behaviour and is documented, not changed.
- **D8 — Roles are visible.** Every instance reports its role (`ACTIVE` or `STANDBY`), the current leader's identity and
  host, and the leadership epoch, through the Actuator endpoint and health details, the startup and handover
  diagnostics, and an OpenTelemetry gauge. A standby instance is healthy; "no active relay anywhere for longer than
  the lease duration" is reported as degraded by every standby instance that sees it. The `secondInstance` warning is
  replaced by these role reports.
- **D9 — Explicitly single-node remains possible.** Leadership is on by default whenever the relay runs. A host that
  runs one instance sees no behavioural change apart from the role reports; it is always `ACTIVE` after startup
  (within one standby check when an expired lease of a previous run is still recorded).

### Decisions taken during implementation (2026-11-26)

- **I1 — Heartbeats removed.** The leadership record replaces the consumer heartbeats: `ConsumerHeartbeat`,
  `MessageStore.heartbeat`/`liveConsumers`, `saga-forge.transport.heartbeat-interval` and the
  `saga_message_store_consumer` table are gone (`schema-upgrade-013.sql` drops it). D1 and D7's mentions of heartbeats
  describe the starting point.
- **I2 — The lease transaction holds the leader row (Postgres).** `PostgresMessageStore.lease` first reads the leader
  row `FOR SHARE` for the given fencing token, so a takeover (`UPDATE ... WHERE expires_at <= now`) waits until a lease
  in progress committed; the deposed leader's next lease then fails with `LeadershipLostException` (contract C12).
- **I3 — Expired trust triggers an immediate round.** When a leader's local trust runs out, `LeaderElector` gives up the
  role and schedules its next round at once instead of after `checkInterval`, so a lone instance whose clock jumped
  leads again without waiting.
- **I4 — `DEGRADED` is outside Spring Boot's default status order.** The health contributor reports it; the overall
  health includes it only when the application adds it to `management.endpoint.health.status.order` (starter README,
  operations guide).
- **I5 — Test-only gaps.** The redelivery-after-commit failover case is not forced (G-013-04); acceptance tests start
  every saga before cutting the leader, because a failover between creation write and first unit of work strands the
  start (G-013-05, G-ENG-05).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Two replicas share one database without double processing (Priority: P1)

An application team runs two replicas of a service that uses the starter, Postgres storage and the in-memory transport,
behind a load balancer. Requests land on either replica, and each replica's units of work append events and commands to
the shared message store. One replica's relay is active and delivers everything; the other is standby. A burst of
events on one association runs every saga step exactly once, in event order.

**Why this priority**: it is the reason the feature exists; without it adopters must pin one replica (W9).

**Independent Test**: start two runtime instances (with their relays) over one database, publish a burst of events for
the same association from both instances, and check that each step ran once, in order, all on the active instance.

**Acceptance Scenarios**:

1. **Given** two instances started against one empty message store, **When** both have started, **Then** exactly one
   reports `ACTIVE` and the other `STANDBY`, both name the same leader and epoch.
2. **Given** an active and a standby instance, **When** 1,000 events for 50 associations are published, half through
   each instance, **Then** every step runs once, the events of each association are handled in append order, and only
   the active instance delivers.
3. **Given** an active and a standby instance, **When** a step on the standby instance's request path sends an outbox
   command, **Then** the command is stored with that unit of work and handled by the active relay after it commits.

---

### User Story 2 - The active instance dies and a standby takes over (Priority: P1)

The active replica crashes (killed process, lost node). Within the leadership lease time a standby instance becomes
active and continues from where the store stands: messages appended in the meantime and messages that were in progress
on the dead instance are delivered, nothing is lost, and nothing reorders.

**Why this priority**: a standby that never takes over is no better than one instance.

**Independent Test**: with two instances and a continuous stream of events, stop the active one without a graceful
shutdown and measure the time until the other reports `ACTIVE` and delivers again; afterwards compare every saga
instance's step history with the expected one.

**Acceptance Scenarios**:

1. **Given** an active instance that stops without releasing its lease, **When** the lease expires, **Then** a standby
   instance becomes active within one standby check, with a higher epoch, and logs the handover with the old and new
   leader.
2. **Given** messages leased by the crashed instance and not consumed, **When** their message leases expire, **Then** the
   new leader delivers them; a step that had committed before the crash is reported `DUPLICATE`, not run again.
3. **Given** an active instance that stops gracefully (rolling deploy), **When** it shuts down, **Then** it releases its
   lease after its deliveries in progress end, and a standby takes over within one standby check, without waiting for
   the lease to expire.

---

### User Story 3 - Deadlines and scheduled commands survive a handover (Priority: P1)

A checkout saga started a 15-minute payment window on one replica. Before it comes due the active replica is replaced.
The deadline runs once, on whichever instance is active at its due time, and a deadline cancelled by the payment event
on either instance never runs a step.

**Why this priority**: 017 shipped deadlines for one instance only and named 013 as the condition for scaling out
(G-017-02).

**Independent Test**: with two instances and the fixture's or a controllable clock, start sagas with deadlines, hand
leadership over before, at and after their due times, and check that each expired deadline ran its timeout step once.

**Acceptance Scenarios**:

1. **Given** open deadlines started on instance A, **When** A stops and B becomes active before they come due, **Then**
   B delivers each at its due time and each timeout step runs once.
2. **Given** deadlines that came due during the handover, **When** the new leader polls for the first time, **Then** it
   delivers them in stored order and none is skipped.
3. **Given** two instances, **When** a deadline comes due, **Then** only the active instance leases it, and no
   `saga.deadline.ignored` diagnostic is caused by a second delivery.
4. **Given** a scheduled command whose due time falls during a handover, **When** the new leader takes over, **Then** the
   command is handled once.

---

### User Story 4 - A paused leader cannot cause harm (Priority: P2)

The active instance stalls (long garbage-collection pause, frozen VM, network partition from the database) longer than
its lease. A standby takes over. When the stalled instance wakes up it finds that it lost leadership, leases nothing
more, finishes or abandons only what it had already started, and becomes standby.

**Why this priority**: the split-brain window is where reordering or double execution would come from; it is rarer
than a crash but must be safe.

**Independent Test**: inject a pause into the active relay longer than the lease (a store or clock double), let a
standby take over and deliver, then resume the paused relay and check that it leased nothing after its lease expired
and that no step ran twice or out of order.

**Acceptance Scenarios**:

1. **Given** an active relay paused past its lease and a successor with a higher epoch, **When** the paused relay tries
   to lease, **Then** the store refuses with its stale epoch and the relay switches to standby with a diagnostic.
2. **Given** a delivery in progress on the paused relay, **When** it resumes, **Then** the delivery runs to its end, and
   the successor has not delivered that message or any later message with the same ordering key in the meantime.
3. **Given** a paused relay that tries to renew its expired lease, **When** a successor holds leadership, **Then** the
   renewal fails and does not take leadership back.

---

### User Story 5 - Operators see who is active (Priority: P2)

An operator looks at the Actuator endpoint, health details or dashboards of any replica and sees whether it is active
or standby, which instance leads, since when, and whether a handover happened.

**Why this priority**: without visibility, a stuck "everyone standby" state or flapping leadership is invisible.

**Independent Test**: start two instances, read each one's Actuator endpoint, health and telemetry, force a handover,
read them again.

**Acceptance Scenarios**:

1. **Given** two instances, **When** the operator reads the `sagaforge` endpoint of each, **Then** one shows `ACTIVE`, the
   other `STANDBY`, and both show the leader id, host, epoch and the time leadership was acquired.
2. **Given** a handover, **When** it completes, **Then** both the deposed and the new leader log it once, and the role
   gauge changes on both.
3. **Given** a leadership record whose lease has been expired longer than one lease duration with no instance taking
   over, **When** health is checked on a standby instance, **Then** it reports degraded with the reason.

### Edge Cases

- **Two instances start at the same moment against an empty store**: exactly one acquisition succeeds; the other is
  standby. No instance is active before its acquisition committed.
- **Clock skew between instances**: lease expiry is judged with the calling instance's clock, like every other message
  store time (plan research R3). Skew cannot make two relays lease at once, because leasing is fenced by the epoch
  (D3); it can only cause an early takeover. Instance clocks are assumed to agree within a small fraction of the lease
  (NTP; documented), and every early takeover is logged and counted.
- **The database is unreachable for the active instance only**: it cannot renew; it stops leasing once its own view of
  the lease is close to expiry (before the store would hand leadership to another), and a standby that can reach the
  database takes over after expiry.
- **Every instance is standby and the lease never expires** (a stale record with a future expiry, e.g. after manual
  edits): takeover waits at most one lease duration from the record's expiry; health reports it (US5-3).
- **Leadership flaps** (renewal just misses repeatedly under load): renewal runs on its own schedule, not behind
  deliveries, and every handover is logged and counted, so flapping is visible.
- **Events published outside a unit of work on a standby** (`EventPublisher` with the `IN_TRANSACTION` outbox): they are
  appended to the store and delivered by the active relay. With the default `ImmediateDispatchOutbox` nothing goes
  through the relay, so this feature does not apply there (documented).
- **A deposed leader's deliveries outlast the graceful-shutdown wait**: they are left unconsumed; their message leases
  expire and the active relay redelivers them under the existing idempotency rules.
- **Deadlines under Modulith interop** stay unsupported (G-017-01); leadership there covers immediate messages only.
- **The fixture**: `SagaFixture` runs one in-process runtime and is unaffected; leadership is tested with the transport
  and store suites.

## Requirements *(mandatory)*

### Functional Requirements

**Leadership**

- **FR-001**: Among all relays running against one message store, at most one MUST be active at any time, as judged by
  the store; the others MUST be standby.
- **FR-002**: The message store MUST offer, through the `MessageStore` SPI, operations to acquire, renew, release and
  read leadership. Acquisition succeeds only when no unexpired leadership exists; every successful acquisition MUST
  produce an epoch greater than every earlier one for that store.
- **FR-003**: Renewal MUST succeed only for the current holder with the current epoch and an unexpired lease; release
  MUST clear the leadership only for the current holder.
- **FR-004**: The active relay MUST renew its leadership on a schedule independent of message deliveries, at the
  configured renewal interval; a standby relay MUST try to acquire at the configured standby check interval.
- **FR-005**: The leadership lease, renewal interval and standby check interval MUST be configurable (defaults 30 s,
  10 s, 5 s); a renewal interval not shorter than the lease MUST be rejected at startup with a message naming both
  values.
- **FR-006**: A relay that fails to renew, or finds another holder or a higher epoch, MUST stop leasing immediately,
  switch to standby, and log the change with its own and the new leader's identity.
- **FR-007**: A relay that cannot reach the store to renew MUST stop leasing no later than when its last successful
  renewal plus the lease duration, minus one renewal interval, has passed by its own clock.

**Delivery under leadership**

- **FR-008**: Only the active relay MUST lease messages. Leasing MUST be fenced by the leadership epoch in the same store
  operation: a lease call with an epoch that is not the current one, or whose leadership expired, MUST lease nothing.
- **FR-009**: Deliveries already in progress when a relay loses leadership MUST be allowed to finish or roll back; the
  relay MUST NOT start any new delivery, retry or redelivery after losing leadership.
- **FR-010**: A new leader MUST NOT deliver a message still under another relay's unexpired message lease, and MUST NOT
  deliver a message while an earlier message with the same ordering key is unconsumed (the 007 order rule holds across
  instances).
- **FR-011**: Messages with a due time (017 deadlines and scheduled commands) MUST be leased only by the active relay.
  Due messages not delivered because of a handover MUST be delivered by the next leader, in stored order, none skipped.
- **FR-012**: Every instance MUST be able to append messages (units of work, outbox commands, deadlines, scheduled
  commands, outcome events, cancellations) whatever its role; a standby instance's writes MUST be delivered by the
  active relay.
- **FR-013**: Retention purging MUST run only on the active instance. Operator queries MUST work on every instance; a
  redelivery request MUST be accepted on every instance and delivered by the active relay.
- **FR-014**: On graceful shutdown the active relay MUST stop leasing, wait the existing bounded time for deliveries in
  progress, then release its leadership; release MUST happen even if some deliveries did not finish.
- **FR-015**: Relay backoff, outbox command retries and set-aside (007, 012) MUST be unchanged by a handover: the attempt
  count and next attempt time are stored with the message and honoured by the next leader.

**Saga correctness across instances**

- **FR-016**: A message delivered twice because of a handover (once on the deposed leader, once after its message lease
  expired) MUST NOT run a step twice: the existing instance row lock and duplicate checks (005 `DUPLICATE`, 017
  open-deadline check) MUST decide, and no rule may rely on the in-process instance lock across instances.
- **FR-017**: Because every relayed delivery runs on the one active instance, two start events with the same association
  value relayed through the store MUST NOT start two instances; the documentation MUST state that this narrows
  G-ENG-08 to start events received outside the relay.

**Compatibility**

- **FR-018**: Leadership MUST be a required part of the `MessageStore` SPI (abstract methods, no "not supported"
  default and no single-instance fallback in the relay); the relay always runs under leadership. The library has no
  external users yet, so breaking third-party `MessageStore` implementations is accepted. The heartbeat-based
  `secondInstance` warning is removed.
- **FR-019**: Postgres, `InMemoryMessageStore` and the starter's `ModulithMessageStore` MUST implement leadership, and
  `MessageStoreContract` MUST cover acquisition, renewal, release, epoch ordering, fencing and concurrent acquisition.
- **FR-020**: Existing Postgres databases MUST be upgradable by a shipped upgrade script; `schema.sql` MUST create the
  leadership record for new databases.
- **FR-021**: No change to annotations, the scanner, the saga model or `SagaFixture` behaviour.

**Visibility**

- **FR-022**: Each relay MUST expose its role, the leader's id and host, the epoch and the acquisition time to the host,
  and the starter MUST show them in the `sagaforge` Actuator endpoint and health details.
- **FR-023**: A standby instance MUST report healthy; health MUST report degraded when no unexpired leadership has
  existed for longer than one lease duration while the instance is running.
- **FR-024**: Telemetry MUST add a role gauge per instance and a handover counter (acquired, lost, released), with no
  per-message attribute; leadership changes MUST be logged once per change on the instance that changed.
- **FR-025**: The documentation (starter README, the guide's transport and operations pages, `docs/architecture.md`)
  MUST describe the multi-instance guarantees: one active relay, takeover times, what a deposed leader does, that
  delivery throughput is one relay's, and that the instance row lock, not the in-process lock, is the cross-instance
  guarantee.

### Key Entities

- **Leadership record**: one per message store — holder (relay consumer id), holder host, epoch, acquired at, lease
  expires at. Stored beside the consumer heartbeats.
- **Leadership epoch**: strictly increasing number per store, issued on each acquisition; the fencing token checked on
  every lease call.
- **Relay role**: `ACTIVE` or `STANDBY`, per running relay, as last confirmed against the store.
- **Leadership settings**: lease duration, renewal interval, standby check interval.
- **Handover**: a change of holder or epoch, observed and logged by the instances involved.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: With two instances on one database and a burst of 10,000 events over 100 associations published through
  both, no step runs twice and no association's events are handled out of append order, over 20 repeated runs.
- **SC-002**: After the active instance is killed, a standby delivers again within the lease duration plus one standby
  check (≤ 35 s with defaults) in every run; after a graceful stop, within one standby check plus the shutdown wait.
- **SC-003**: Across 20 forced handovers during a continuous stream, no message is lost and every saga instance ends in
  its expected status.
- **SC-004**: With deadlines coming due before, during and after a handover, each expired deadline runs its timeout step
  exactly once and no second delivery of a due message is reported as ignored.
- **SC-005**: A relay paused longer than its lease leases zero messages after its lease expired, in every injected run.
- **SC-006**: A single-instance deployment shows no change in delivery behaviour or latency beyond the role reports
  (existing transport acceptance suites pass unchanged).
- **SC-007**: An operator can tell from any one instance which instance is active, within one renewal interval of a
  change.

## Assumptions

- One message store serves one application (all its replicas); two different applications do not share one store.
- Hosts sharing a store use the same Saga Forge version and the same leadership settings; mixed settings are
  documented as unsupported, not detected.
- Lease expiry is evaluated with the caller's clock, as the store already does for message leases; instance clocks
  agree within a few seconds. Safety never depends on it (fencing, D3); only takeover timing does.
- The instance row lock (`findByIdAndLock`) is the cross-instance guarantee for saga state; its Postgres reasoning is
  verified by this feature's container tests (narrowing G-ENG-11 for the relayed path).
- Delivery throughput is that of one relay; horizontal scaling of delivery (partitioned leasing across active
  instances) is out of scope and stays with the Kafka transport or a later spec.
- Durable retry backoff (G-005-01/-02) is not part of this feature; when it comes it reuses due times and therefore
  leadership.
- An optional Postgres advisory lock as an extra guard is a planning decision, not required by this spec.
- Out of scope: operator commands to move leadership by hand, preferred-leader configuration, the Kafka transport,
  a Quarkus extension (G-007-01).
