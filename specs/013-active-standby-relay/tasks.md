---

description: "Task list for 013-active-standby-relay"
---

# Tasks: Active/Standby Relay

**Input**: Design documents from `/specs/013-active-standby-relay/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: included. Every story in the spec has an independent test, the contracts number their cases (C1–C12,
E1–E8, T1–T5), and AGENTS.md and the constitution ask for a test with every new abstraction, written with or before its
first caller (contract first for `LeaderElection`, `LeaderElectorTest` with `LeaderElector`).

**Organization**: setup; then the foundational phase (core SPI, both stores with the contract, Modulith delegation,
`LeaderElector` and the relay leasing under leadership), which is needed for anything to compile and which every story
builds on. Then the stories in spec order: US1 proves two instances never double-process or reorder; US2 adds graceful
and crash handover; US3 covers deadlines and scheduled commands across a handover; US4 hardens the paused-leader case;
US5 adds visibility (status, telemetry, Actuator, health, properties). The final phases are the **README for
multi-instance support** and the **patterns and prior-art documentation** (both requested explicitly), the rest of the
docs, then verification.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: the user story from spec.md (US1 to US5)

## Path Conventions

`…` stands for `src/main/java/io/github/gkosharovdev/sagaforge` and `…test` for
`src/test/java/io/github/gkosharovdev/sagaforge` inside the named module. Other paths are relative to the repository
root. `store/` means `saga-forge-core/…/core/spi/store/`; `transport/` means
`saga-forge-transport-inmemory/…/transport/inmemory/`, `internal/` its `internal/` package; `support/` means
`saga-forge-transport-inmemory/…test/transport/inmemory/support/`; `postgres/` means
`saga-forge-storage-postgres/…/storage/postgres/` and `pg-resources/` its
`src/main/resources/io/github/gkosharovdev/sagaforge/storage/postgres/`; `spring/` means
`saga-forge-spring-boot-starter/…/spring/`.

---

## Phase 1: Setup

**Purpose**: a green baseline and the design rules in front of the implementer.

- [X] T001 On branch `013-active-standby-relay` (created from `origin/master`, no upstream), run `mvn -q install` from the repository root and confirm it is green before any change
- [X] T002 Load the `effective-java`, `clean-code-java` and `solid-design-review` skills (AGENTS.md) and re-read plan.md "Design-rule application" and "Abstraction familiarity rating"; keep the names in data-model.md unchanged unless a test shows a better shape (then update plan.md and data-model.md in the same change)

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: the leadership SPI, both stores implementing it under one contract, and a relay that leases only as
leader. Nothing compiles across modules until T005–T021 are done, because the `MessageStore` change is breaking.

**⚠️ CRITICAL**: no user story work starts before this phase is complete.

### Core SPI (contracts/leader-election-spi.md, data-model.md "Core")

- [X] T003 [P] Create record `LeaderLease` (holderId, holderHost, fencingToken, acquiredAt, expiresAt; validation per data-model.md, `isExpiredAt(Instant)`, `isHeldBy(String)`, Javadoc without `@throws` on the record comment) in `saga-forge-core/…/core/spi/store/LeaderLease.java`
- [X] T004 [P] Create unchecked `LeadershipLostException` (carries `staleLease()`, message names holder and token, `serialVersionUID`, same style as `LeaseLostException`) in `store/LeadershipLostException.java`
- [X] T005 Create interface `LeaderElection` (`tryAcquire(candidateId, host, now, leaseDuration)`, `renew(lease, now, leaseDuration)`, `release(lease, now)`, `current()`, Javadoc rules from the contract) in `store/LeaderElection.java`
- [X] T006 Change `MessageStore`: add abstract `LeaderElection leaderElection()`; replace `lease(String, Instant, Duration, int)` by `lease(LeaderLease, Instant, Duration, int)` (fenced, throws `LeadershipLostException`); remove `heartbeat` and `liveConsumers`; update the type Javadoc ("Leasable", new "Leadership" section) in `store/MessageStore.java`
- [X] T007 Delete `store/ConsumerHeartbeat.java`; replace its cases in `saga-forge-core/…test/core/spi/store/StoreRecordsTest.java` with `LeaderLease` cases (blank holder, token < 1, expiresAt before acquiredAt, `isExpiredAt` at the boundary)

### Store contract first (contracts/leader-election-spi.md C1–C12)

- [X] T008 Add cases C1–C12 to `support/MessageStoreContract.java` (helper `leader()` acquiring for test relay "R1"; update every existing `lease(...)` call to pass a `LeaderLease`; remove heartbeat cases; C11 with two threads and a `CountDownLatch`; C12 is Postgres-only — the in-memory store has no open transaction to wait for — and lives in `PostgresMessageStoreContainerTest`, T013)
- [X] T009 Implement leadership in `support/InMemoryMessageStore.java`: one `LeaderLease` field and a token counter under the store's monitor, `leaderElection()` returning a view over them, fenced `lease`, heartbeat map removed; `InMemoryMessageStoreTest` green

### Postgres (data-model.md "Postgres")

- [X] T010 Add table `saga_message_store_leader` and drop `saga_message_store_consumer` in `pg-resources/schema.sql`; create `pg-resources/schema-upgrade-013.sql` (create table, drop consumer table, header comment naming 013)
- [X] T011 Create package-private `PostgresLeaderElection` (acquire by `INSERT ... ON CONFLICT (id) DO UPDATE ... WHERE expires_at <= ? RETURNING`, renew by `UPDATE ... WHERE fencing_token = ? AND expires_at > ?`, release sets `expires_at = GREATEST(?, acquired_at)`, `current()`; `UncheckedSqlException` on SQL errors) in `postgres/PostgresLeaderElection.java`, composed into `PostgresMessageStore` (shares its `DataSource` helpers)
- [X] T012 Fence `PostgresMessageStore.lease`: in the lease transaction run `SELECT 1 FROM saga_message_store_leader WHERE fencing_token = ? AND expires_at > ? FOR SHARE` first, throw `LeadershipLostException` when no row, then the existing lease `UPDATE` with `leader.holderId()`; remove `heartbeat`/`liveConsumers` and their SQL in `postgres/PostgresMessageStore.java`
- [X] T013 Implement `leaseBlocksTakeover()` (C12) in `saga-forge-storage-postgres/…test/storage/postgres/PostgresMessageStoreContainerTest.java`; run it with `-Pcontainer-tests` until C1–C12 pass
- [X] T014 [P] Create `SchemaUpgrade013Test` (017 schema + `schema-upgrade-013.sql` equals a fresh `schema.sql`, modelled on `SchemaUpgrade017Test`) in `saga-forge-storage-postgres/…test/storage/postgres/SchemaUpgrade013Test.java`

### Modulith delegation (research R10)

- [X] T015 In `spring/modulith/ModulithMessageStore.java`: `leaderElection()` returns `records.leaderElection()`; `lease(LeaderLease, …)` passes the lease to `records.lease` (fence covered); remove the heartbeat delegations

### Transport: settings, elector, relay (contracts/relay-leadership.md)

- [X] T016 [P] Create public record `LeadershipSettings` (leaseDuration 30 s, renewInterval 10 s, checkInterval 5 s; `defaults()`; validation naming both values, FR-005) in `transport/LeadershipSettings.java`; in `transport/TransportSettings.java` replace component `heartbeatInterval` by `LeadershipSettings leadership` and add `withLeadership(...)`; update `TransportSettings` tests
- [X] T017 [P] Create public enum `RelayRole` and record `LeadershipStatus` (nullable `leader`, `isLeaderless(now, leaseDuration)`) in `transport/RelayRole.java` and `transport/LeadershipStatus.java`, with unit tests in `saga-forge-transport-inmemory/…test/transport/inmemory/LeadershipStatusTest.java` (needed by `LeaderElector.status()` and by the US2–US4 tests)
- [X] T018 Write `LeaderElectorTest` cases E1–E8 first (driven by `tick()` with `support/MutableClock` and an `InMemoryMessageStore`'s election; a throwing election double for E7) in `saga-forge-transport-inmemory/…test/transport/inmemory/internal/LeaderElectorTest.java`
- [X] T019 Implement `LeaderElector` (final; constructor takes `LeaderElection`, `Clock`, `LeadershipSettings`, relay id, host, `Logger`, `TransportInstrumentation`, and a `ScheduledExecutorService` supplier defaulting to one daemon thread `saga-forge-transport-leader`; `start()` with one synchronous attempt, package-visible `tick()`, `leadership()` trusting until `lastCallStart + lease − renewInterval`, `stepDown(LeaderLease)` ignoring older leases, `resign()`, `status()`) in `internal/LeaderElector.java` until T018 passes
- [X] T020 Integrate in `internal/Relay.java`: hold a `LeaderElector`; `pollOnce` leases only with `leadership()` present and steps down on `LeadershipLostException`; message-lease renewal of in-flight deliveries regardless of role (R6); retention sweep only with leadership (R8); `stop` releases message leases then `elector.resign()`; remove heartbeat fields, `beat`, `warnAboutOtherConsumers`; update the class Javadoc list
- [X] T021 Assemble the elector in `transport/InMemoryTransport.java` and add public `InMemoryTransport.leadership()` returning `LeaderElector.status()` (Builder passes `messageStore.leaderElection()`, settings, clock, logger, instrumentation, host name); delete `saga-forge-transport-inmemory/…test/transport/inmemory/SecondInstanceTest.java`; run `mvn -pl saga-forge-transport-inmemory -am test` until existing suites (`TransportAcceptanceSuite`, `RestartTest`, `ScheduledDeliveryTest`, `ShutdownTest`, `RetentionTest`) pass unchanged (SC-006)

**Checkpoint**: everything compiles; a single instance behaves as before and is `ACTIVE` at start.

---

## Phase 3: User Story 1 — Two replicas share one database without double processing (Priority: P1) 🎯 MVP

**Goal**: one relay delivers, the other stands by; every instance appends; steps run once, in order.

**Independent Test**: two runtime+transport pairs over one database, a burst published through both, each step ran
once, in append order, all on the active instance.

- [X] T022 [P] [US1] Create `TwoRelaysTest` with case T1 (two `InMemoryTransport`s over one `InMemoryMessageStore`, 500 messages over 20 keys appended through both, delivered once, per key in order, all by the active one) and T5 (retention does not run on the standby) in `saga-forge-transport-inmemory/…test/transport/inmemory/TwoRelaysTest.java`
- [X] T023 [US1] Add US1 acceptance 3 to `TwoRelaysTest`: an outbox command stored by a unit of work on the standby is handled by the active relay after commit
- [X] T024 [US1] Create `MultiInstanceAcceptanceTest` (`@Tag("container")`; two `SagaRuntime` + `InMemoryTransport` pairs, each with its own `DataSource` on one Testcontainers Postgres; a test saga with three steps on one association) with the SC-001 scenario: 10,000 events over 100 associations published half through each instance, no step twice, no reordering, 20 repetitions behind `-Dsaga-forge.sc001.runs` (default 1: one run took about 95 s on unpooled connections, about 9 s pooled; G-013-06) in `saga-forge-storage-postgres/…test/storage/postgres/MultiInstanceAcceptanceTest.java`
- [X] T025 [US1] Add a start-event case to `MultiInstanceAcceptanceTest`: the same start event appended through both instances creates one saga instance (FR-017, narrows G-ENG-08)

**Checkpoint**: US1 independently demonstrable; MVP.

---

## Phase 4: User Story 2 — The active instance dies and a standby takes over (Priority: P1)

**Goal**: graceful and crash handover within the lease time, nothing lost, retry state honoured.

**Independent Test**: kill the active instance mid-stream, measure time to `ACTIVE` on the other, compare every
instance's step history.

- [X] T026 [P] [US2] Add case T2 to `TwoRelaysTest`: the active transport stopped gracefully mid-stream; the standby is `ACTIVE` within one `checkInterval` after `stop` returns; nothing lost
- [X] T027 [US2] Add to `TwoRelaysTest`: a message whose attempt failed on the old leader is retried by the new leader at its stored `nextAttemptAt` with the attempt count kept, and set aside at `maxAttempts` (FR-015)
- [X] T028 [US2] Add the crash scenarios to `MultiInstanceAcceptanceTest`: cut instance A's `DataSource` (test `CuttableDataSource`) without stopping its transport; B `ACTIVE` within lease + check (≤ 35 s; use 3 s / 1 s / 0.5 s leadership settings in the test to keep it fast, assert against the configured bound); messages leased by A are delivered after their message lease (set to 5 s in the test); (SC-002; the committed-on-A-then-`DUPLICATE` case is not forced, see G-013-04)
- [X] T029 [US2] Add the SC-003 scenario to `MultiInstanceAcceptanceTest`: 20 forced handovers (alternating graceful stop/restart of the active instance) during a continuous stream; no message lost, every saga ends in its expected status

**Checkpoint**: US1 and US2 pass independently.

---

## Phase 5: User Story 3 — Deadlines and scheduled commands survive a handover (Priority: P1)

**Goal**: due messages follow leadership (D5, FR-011); closes G-017-02.

**Independent Test**: deadlines started on A, handover before/at/after their due times, each timeout step ran once.

- [X] T030 [P] [US3] Add case T4 to `TwoRelaysTest`: a message with `notBefore` due during a handover is delivered once, by the new leader, using `MutableClock`
- [X] T031 [US3] Add the SC-004 scenarios to `MultiInstanceDeadlineContainerTest` (two `Replica`s; was planned in `MultiInstanceAcceptanceTest`) with a test saga using `@StartDeadline`/`@DeadlineExpired`: deadlines due before, during and after a handover each run their timeout step once; a deadline cancelled by an event appended on the standby never runs; no `saga.deadline.ignored` caused by a second delivery (capture the runtime logger)
- [X] T032 [US3] Add a `CommandGateway.schedule` case to `MultiInstanceDeadlineContainerTest`: a scheduled command due during a handover is handled once

**Checkpoint**: US1–US3 pass independently.

---

## Phase 6: User Story 4 — A paused leader cannot cause harm (Priority: P2)

**Goal**: fencing and local distrust make a stalled leader harmless (FR-006 to FR-010).

**Independent Test**: pause the active relay past its lease, let the standby take over, resume; nothing leased after
expiry, nothing run twice or out of order.

- [X] T033 [P] [US4] Create test double `PausingMessageStore` (delegates to an `InMemoryMessageStore`; can block the next `lease`/`renew` call of one relay id on a latch) in `support/PausingMessageStore.java`
- [X] T034 [US4] Add case T3 to `PausedLeaderTest` (plain receivers, so not in `TwoRelaysTest`) with `PausingMessageStore`: paused relay's resumed `lease` throws `LeadershipLostException`, it becomes `STANDBY` (log cause `fenced`), its in-flight delivery finishes, and the key's next message is delivered only after it; no message delivered by both
- [X] T035 [US4] Add to `PausedLeaderTest`: a deposed relay keeps renewing the message leases of its in-flight deliveries (successor cannot lease them) and starts no retry or redelivery after losing leadership (FR-009, R6)
- [X] T036 [US4] Add a paused-leader scenario to `MultiInstanceAcceptanceTest` (`CuttableDataSource.stall()` freezes instance A; a lease spy in `Replica` proves no lease started by A after the freeze returned messages; A logs `lost cause=fenced`), proving SC-005 against Postgres

**Checkpoint**: US1–US4 pass independently.

---

## Phase 7: User Story 5 — Operators see who is active (Priority: P2)

**Goal**: role, leader, token and changes visible through the transport API, telemetry, logs, Actuator and health.

**Independent Test**: two instances, read each one's endpoint, health and telemetry, force a handover, read again.

- [X] T037 [US5] Add US5-1 and US5-2 to `TwoRelaysTest`: both transports' `leadership()` name the same leader and token; after a handover both report the change
- [X] T038 [US5] Add gauge `saga_forge.relay.active` and counter `saga_forge.relay.leadership_changes` (attribute `saga_forge.leadership.change`) to `internal/TransportInstrumentation.java`, called from `LeaderElector`; assert in `saga-forge-transport-inmemory/…test/transport/inmemory/TransportTelemetryTest.java`
- [X] T039 [US5] Assert the log events of contracts/starter-and-operations.md (acquired, lost with cause, released, checkFailed; each once per change) in `LeaderElectorTest`
- [X] T040 [P] [US5] Add properties `saga-forge.transport.leadership.lease-duration|renew-interval|check-interval` and remove `heartbeat-interval` in `spring/transport/TransportProperties.java`, `spring/transport/InMemoryTransportConfiguration.java` and `saga-forge-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json`; invalid combination fails through `SagaForgeConfigurationFailureAnalyzer` (case in `MisconfigurationTest`)
- [X] T041 [US5] Add the `leadership` object to `spring/actuator/SagaForgeEndpoint.java` and role/leader/token details plus custom `DEGRADED` status to `spring/actuator/SagaForgeHealthIndicator.java`; pass `InMemoryTransport` (for `leadership()`) from `spring/actuator/SagaForgeActuatorConfiguration.java`; cases in `ActuatorTest`
- [X] T042 [US5] Create `MultiInstanceContainerTest` (`@Tag("container")`; two `SpringApplicationBuilder` contexts of a minimal app on one Testcontainers Postgres; `/actuator/sagaforge` shows one `ACTIVE`, one `STANDBY`, same token; closing the active context makes the other `ACTIVE`) in `saga-forge-spring-boot-starter/…test/spring/MultiInstanceContainerTest.java`
- [X] T043 [US5] Update the starter README property table and a new "Running several instances" subsection (properties, health `DEGRADED` and `management.endpoint.health.status.order`, roles in the endpoint) in `saga-forge-spring-boot-starter/README.md`; `ReadmePropertiesTest` green

**Checkpoint**: all stories pass independently.

---

## Phase 8: README for multi-instance support (explicit request)

- [X] T044 Update `README.md` for multi-instance support: (a) a "Running several instances" section after "Getting started" saying what is supported (any number of replicas on one database, one active relay delivering, standby takeover ≤ lease + check, every instance accepts requests and appends), what is not (delivery throughput of one relay, no partitioned leasing; Kafka transport for scale-out), the leadership properties and where to see roles (Actuator, health, `saga_forge.relay.active`); (b) the "Status" paragraph and the in-memory transport row in "Modules" naming active/standby; (c) a link to the architecture section of T045 and to `docs/guide/operations.md`; keep every Java snippet copied from compiling code with its source file named

---

## Phase 9: Patterns and prior art documentation (explicit request)

- [X] T045 Extend `docs/architecture.md` "### Patterns behind the design" into a recap of the patterns and prior art behind the whole design, for the maintainer's own later reference: (a) update the existing rows (the Lease row no longer says "targets one running instance"; Message Relay now runs on the leader only); (b) add rows for **Leader Election** with a lease (Kubernetes `coordination.k8s.io` Lease and client-go `LeaderElector`, Apache Curator `LeaderLatch`, Spring Integration `LeaderInitiator`), **Fencing Token** (Kleppmann, "How to do distributed locking", 2016; ZooKeeper zxid), **Active/Standby** versus **Competing Consumers** (EIP) and **Singular Update Queue / single consumer per partition**; (c) add a subsection "#### How other frameworks solved the same problems" with one table, problem → framework → mechanism → what Saga Forge took or rejected, covering at least: transactional outbox and relay (Eventuate Tram's message table with its CDC service, Spring Modulith's event publication registry, NServiceBus Outbox, Debezium's outbox event router), one active relay across replicas (Eventuate CDC's leader election over ZooKeeper, Axon's `TrackingEventProcessor`/`PooledStreamingEventProcessor` token claims with an owner and claim timeout in the `TokenStore`, Quartz's clustered JDBC job store row locks, ShedLock), fencing and zombie instances (Kafka consumer-group generation ids and transactional producer epochs that fence a zombie `transactional.id`), scale-out delivery as the alternative not taken (Kafka partitions with one consumer per partition in a group, Axon segments), saga model (Axon `@Saga`/`@SagaEventHandler(associationProperty)`, Eventuate Tram Sagas orchestration), retries and dead letters (Spring Kafka `DefaultErrorHandler` with `DeadLetterPublishingRecoverer`, EIP Dead Letter Channel), deadlines (Axon `DeadlineManager`, NServiceBus saga timeouts), idempotency (EIP Idempotent Receiver, microservices.io Idempotent Consumer); (d) link each claim to the vendor's documentation and check every link and mechanism name against the current docs before writing it (mark anything not confirmed as such, never guess); (e) a five-to-ten line "Recap" paragraph at the end of the section in plain words; (f) mirror one short paragraph with a link to it in `docs/guide/operations.md` ("Why one active relay")

---

## Phase 10: Remaining documentation and records

- [X] T046 [P] Update `docs/architecture.md`: "Status at a glance" transport row; a "### Several application instances" subsection under "How saga instances exchange messages" (one active relay, takeover times, what a deposed leader does, the instance row lock `findByIdAndLock` as the cross-instance guarantee, not the in-process lock; throughput of one relay); the 017 "Known limitations" line without G-017-02
- [X] T047 [P] Update `docs/guide/operations.md` (leadership properties table, roles in Actuator and health, `DEGRADED`, telemetry instruments, the new log events, removed `heartbeat-interval`, upgrade script 013), `docs/guide/limitations.md` (row 1 replaced by "one active relay per database, delivery throughput of one instance" with G-013-01/02; row 4 without "one running instance"), and `docs/guide/deadlines.md` (deadlines work with several instances)
- [X] T048 [P] Update `docs/agents/saga-forge/SKILL.md` and `docs/agents/AGENTS-snippet.md` (several instances are supported; don't expect delivery on every node; leadership properties), then run `docs/guide/build-llms-full.sh` and commit the regenerated `llms-full.txt`
- [X] T049 [P] Update `.specify/memory/memory.md`: close G-007-09 and G-017-02 with dates; narrow G-ENG-08 and G-ENG-11; add G-013-01 (standby-appended messages wait for the leader's poll), G-013-02 (throughput of one relay, no partitioned leasing), G-013-03 (takeover timing depends on instance clocks; safety does not), plus any gap found during implementation; mark 013 done in the "Adoption backlog" list
- [X] T050 [P] Update `.specify/memory/roadmap.md` (013 row and brief status "done") and `AGENTS.md` (Current Status bullet for 013, the 007 "one running instance per log" and 017 G-017-02 mentions, Known Gaps line for 013)
- [X] T051 Set `specs/013-active-standby-relay/spec.md` status to "Implemented" and record implementation-time decisions (if any) in its Decisions section

---

## Phase 11: Polish & verification

- [X] T052 Run `mvn spotless:apply` from the repository root
- [X] T053 Run `mvn -Pcontainer-tests verify` from the repository root; all suites green (record the test count for AGENTS.md) — run as targeted suites per the user's instruction (2026-11-26): unit tests of all eight touched modules, Postgres container suites (store contract C1–C12, schema and upgrades, fault injection, transport acceptance, MultiInstanceAcceptanceTest, MultiInstanceDeadlineContainerTest) and starter container suites (SchemaInitializer, MultiInstance, Modulith interop, module listener, own-log acceptance, Actuator, runtime assembly), all green; the whole `mvn -Pcontainer-tests verify` was not run
- [X] T054 Run `mvn -P release -Dgpg.skip -DskipTests install` (Javadoc: no `<h3>` in type comments, no `@throws` on record comments)
- [X] T055 Walk through [quickstart.md](quickstart.md) steps 1–6 and check each expected outcome

---

## Dependencies & Execution Order

- **Setup (T001–T002)** → **Foundational (T003–T021)**: T003–T005 before T006; T006–T007 before T008; T008 before
  T009 and T013; T010 before T011–T012; T011–T012 before T013; T014 after T010; T006 before T015; T016–T017 before
  T019; T018 before T019 (test first); T019 before T020–T021.
- **US1 (T022–T025)** after Foundational. **US2 (T026–T029)**, **US3 (T030–T032)** and **US4 (T033–T036)** after
  Foundational; their `TwoRelaysTest` and `MultiInstanceAcceptanceTest` tasks follow T022 and T024 (same files,
  sequential). **US5 (T037–T043)** after Foundational; T040–T041 before T042–T043.
- **README (T044)** after US5 (it names properties and roles). **Patterns doc (T045)** can start any time after
  Foundational; it is documentation only. **T046–T051** after the stories; **T052–T055** last.
- Same-file chains (never parallel): `TwoRelaysTest` T022 → T023 → T026 → T027 → T030 → T034 → T035 → T037;
  `MultiInstanceAcceptanceTest` T024 → T025 → T028 → T029 → T031 → T032 → T036; `LeaderElectorTest` T018 → T039;
  `docs/architecture.md` T045 → T046.

## Parallel Opportunities

- Foundational: T003 and T004 together; T014, T016 and T017 alongside the Postgres tasks; T018 while T010–T013 run.
- After Foundational: one developer on the `TwoRelaysTest` chain, one on the `MultiInstanceAcceptanceTest` chain, one
  on US5 (T038, T040 in parallel; T033 `PausingMessageStore` too).
- Docs: T047, T048, T049, T050 in parallel once the stories are done.

### Example: US5 in parallel

```text
T038 telemetry instruments                 (internal/TransportInstrumentation.java)
T040 starter properties + metadata        (spring/transport/, META-INF/)
T043 starter README section               (saga-forge-spring-boot-starter/README.md, after T040)
```

## Implementation Strategy

1. **MVP = Foundational + US1**: two instances on one database, one delivering, no double processing. Stop and run
   T024 here.
2. **Then US2 and US3** (both P1): handover and deadlines across it — what booking-service needs to drop W9.
3. **Then US4 and US5** (P2): paused-leader hardening and operator visibility.
4. **Then the README (T044) and the patterns recap (T045)**, the remaining docs and records, and verification.
