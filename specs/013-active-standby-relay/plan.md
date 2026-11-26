# Implementation Plan: Active/Standby Relay

**Branch**: `013-active-standby-relay` | **Date**: 2026-11-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/013-active-standby-relay/spec.md`

## Summary

Several application instances share one message store; one relay is active and the rest are standby. Underneath:

1. **Core SPI**: a narrow `LeaderElection` (`tryAcquire`, `renew`, `release`, `current`) holding one `LeaderLease`
   record per store (holder, host, fencing token, acquired, expires), reached through the new abstract
   `MessageStore.leaderElection()` (research R1). `MessageStore.lease` takes the `LeaderLease` and is fenced by its
   token in the same transaction, throwing `LeadershipLostException` for a stale one (R2). Times come from the caller
   (R3). Consumer heartbeats are removed (R7).
2. **Stores**: Postgres table `saga_message_store_leader` (one row, token never reset) with `FOR SHARE` fencing in the
   lease transaction; `InMemoryMessageStore` under its monitor; `ModulithMessageStore` delegates to its Postgres
   records (R10). `MessageStoreContract` cases C1–C12.
3. **Transport**: a new internal `LeaderElector` runs the election on its own thread, trusts its lease only until
   `lastRenewStart + lease − renewInterval` (R4, R5); `Relay` leases only with a trusted lease, steps down on the fence,
   lets in-flight deliveries finish while keeping their message leases (R6), sweeps retention only as leader (R8), and
   resigns on stop. Public `LeadershipStatus`/`RelayRole`/`LeadershipSettings`.
4. **Starter, telemetry, docs**: properties `saga-forge.transport.leadership.*`, Actuator and health (`DEGRADED`),
   gauge and counter (R9), README, guide, architecture, memory.md gaps G-013-01..03.

## Technical Context

**Language/Version**: Java, `maven.compiler.release` 21 (CI also on 25)

**Primary Dependencies**: none new. Core stays dependency-free; transport keeps `opentelemetry-api`; Postgres keeps
JDBC + Jackson 3; starter keeps Spring Boot 4.1 / Spring Modulith.

**Storage**: Postgres — new `saga_message_store_leader`; `saga_message_store_consumer` dropped; `schema.sql`,
`schema-upgrade-013.sql`.

**Testing**: JUnit 5 + AssertJ; `MessageStoreContract` (in-memory and `PostgresMessageStoreContainerTest`);
`LeaderElectorTest` and `TwoRelaysTest` over `MutableClock` and `InMemoryMessageStore`; container suites
`MultiInstanceAcceptanceTest` (storage-postgres module, two runtime+transport pairs with separate pools) and starter
`MultiInstanceContainerTest`; `SchemaUpgrade013Test`.

**Target Platform**: JVM library | **Project Type**: multi-module Maven library

**Performance Goals**: crash takeover ≤ 35 s with defaults (SC-002); one leadership row update per 10 s per store;
no extra query per message (the fence is one indexed single-row `SELECT ... FOR SHARE` per lease batch).

**Constraints**: Principle I (core: records, one interface, one exception); Principle IV (no new storage/transport,
no partitioned leasing); breaking SPI change accepted (no external users, user decision 2026-11-25).

**Scale/Scope**: core, transport-inmemory (+ test-jar), storage-postgres, starter, docs; no new module; annotations,
scanner, runtime and fixture unchanged (FR-021).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Check | Result |
|-----------|-------|--------|
| I. Zero-Dependency Core | Core gains `LeaderElection`, `LeaderLease`, `LeadershipLostException` and one `MessageStore` method; JDK types only. | Pass |
| II. Annotation-Driven, Declarative | No saga-facing API; nothing for saga authors to call. | Pass (n/a) |
| III. Formal Spec Conformance | No annotation or lifecycle semantics change; no scanner rule or Property added. Cross-instance correctness reuses 005/017 duplicate rules (FR-016), tested in the container suite. | Pass |
| IV. Explicit, Narrow Scope | Same stores and transport; Modulith covered by delegation; scale-out explicitly out. | Pass |
| V. Documentation Hierarchy | `docs/architecture.md` (multi-instance guarantees), starter README, `docs/guide/` transport and operations pages, `limitations.md`, agent skill/snippet, `llms-full.txt`, memory.md, roadmap, AGENTS.md status — tasks. | Pass (task) |
| Workflow: composition over inheritance | No class hierarchy. `LeaderElector` is held by `Relay`; `ModulithMessageStore` delegates its election. | Pass |
| Workflow: abstraction tested before proposed | Shapes proven by the spike (research "Spike"): `LeaderElection` with an in-memory implementation and a `LeaderElector` caller, 7 checks. Real tests: contract cases on two implementations + `LeaderElectorTest`. | Pass |
| Workflow: recognisable abstractions | Rated below. | Pass |

### Abstraction familiarity rating

| Abstraction | Kind | Community counterpart | Rating |
|-------------|------|-----------------------|--------|
| `LeaderElection` | SPI interface | Kubernetes leader election, Curator `LeaderLatch`, Spring Integration `LeaderInitiator`/`LockRegistryLeaderInitiator` | High |
| `LeaderLease` | record | Kubernetes `coordination.k8s.io/Lease` (holderIdentity, acquireTime, renewTime) | High |
| fencing token (`LeaderLease.fencingToken`) | field | Kleppmann's fencing tokens; ZooKeeper zxid; etcd revision | High (well-known in distributed-systems writing) |
| `LeadershipLostException` | exception | Curator "lost leadership", sibling of the existing `LeaseLostException` | High |
| `LeaderElector` | internal class | client-go `leaderelection.LeaderElector` | High |
| `LeadershipSettings` | settings record | client-go `LeaderElectionConfig` (LeaseDuration, RenewDeadline, RetryPeriod) | High |
| `RelayRole` (`ACTIVE`/`STANDBY`) | enum | active/standby (HA vocabulary) | High |
| `LeadershipStatus` | value record | Spring Integration `Context`/leader status, Actuator-style status objects | Medium–high |

No boutique names were needed; "relay" is already the transport's term (007, Outbox relay pattern).

### Design-rule application (effective-java, clean-code-java, solid-design-review)

- **SRP**: leadership leaves `Relay` (641 lines) for `LeaderElector`; `Relay` keeps delivery. `MessageStore` loses
  heartbeats.
- **ISP**: `LeaderElection` is separate from the 14-method `MessageStore`; `LeaderElector` depends on it only.
- **DIP**: `LeaderElector` takes `LeaderElection`, `Clock`, `Logger`, instrumentation through its constructor; no
  static access; threads come from an injected `ScheduledExecutorService` factory default so tests call `tick()`.
- **LSP**: no "unsupported" defaults — every store implements leadership (removes the `UnsupportedOperationException`
  pattern for this capability).
- **OCP**: a later MySQL/MongoDB store adds leadership by implementing `LeaderElection`; no relay change.
- **EJ Items 17, 49, 55, 69, 72**: immutable validated records; `Optional` returns for expected absence (refused
  acquisition, lost renewal); unchecked standard-style exception for the fence; no `Optional` record components
  (`LeadershipStatus.leader` is nullable and documented, as other core records do).
- **EJ Item 2 / Clean Code parameter objects**: `LeadershipSettings` groups three durations instead of widening
  `TransportSettings` to twelve positional components.
- **Clean Code**: no boolean-flag parameters (`lease(LeaderLease, …)` instead of `lease(id, isLeader, …)`); no
  temporal coupling on public types (`InMemoryTransport.start` still the one entry); log event names state intent.

Post-design re-check: Pass. One deliberate break: the `MessageStore` SPI changes incompatibly (`lease` signature,
heartbeat methods removed, `leaderElection()` abstract) — accepted, see Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/013-active-standby-relay/
├── plan.md              # this file
├── research.md          # spike, R1–R12, gaps G-013-01..03
├── data-model.md        # records, table, settings, state machine, telemetry
├── quickstart.md        # validation runs
├── contracts/
│   ├── leader-election-spi.md      # LeaderElection + fenced lease, cases C1–C12
│   ├── relay-leadership.md         # LeaderElector E1–E8, two relays T1–T5
│   └── starter-and-operations.md   # properties, Actuator, health, telemetry, logs
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
saga-forge-core/src/main/java/io/github/gkosharovdev/sagaforge/core/spi/store/
├── LeaderElection.java, LeaderLease.java, LeadershipLostException.java      (new)
├── MessageStore.java            (+leaderElection(), lease(LeaderLease,…), −heartbeat, −liveConsumers)
└── ConsumerHeartbeat.java       (deleted)
saga-forge-transport-inmemory/src/main/java/.../transport/inmemory/
├── LeadershipSettings.java, LeadershipStatus.java, RelayRole.java           (new, public)
├── TransportSettings.java       (heartbeatInterval → leadership)
├── InMemoryTransport.java       (+leadership())
└── internal/ LeaderElector.java (new), Relay.java (lease with leadership, step down, resign, no heartbeats),
             TransportInstrumentation.java (gauge, counter)
saga-forge-transport-inmemory/src/test/java/.../transport/inmemory/
├── support/InMemoryMessageStore.java (leader election), MessageStoreContract.java (C1–C12)
├── internal/LeaderElectorTest.java, TwoRelaysTest.java                       (new)
└── SecondInstanceTest.java      (deleted)
saga-forge-storage-postgres/
├── src/main/java/.../PostgresMessageStore.java (+PostgresLeaderElection, package-private), schema.sql,
│   schema-upgrade-013.sql
└── src/test/java/.../MultiInstanceAcceptanceTest.java, SchemaUpgrade013Test.java   (new)
saga-forge-spring-boot-starter/
├── transport/TransportProperties.java, InMemoryTransportConfiguration.java (leadership properties)
├── modulith/ModulithMessageStore.java (delegate election, fenced lease)
├── actuator/SagaForgeEndpoint.java, SagaForgeHealthIndicator.java (leadership, DEGRADED)
├── META-INF/additional-spring-configuration-metadata.json, README.md
└── src/test/.../MultiInstanceContainerTest.java (new), ActuatorTest, ReadmePropertiesTest (updated)
docs/ architecture.md, guide/ (transport, operations, limitations), agents/, llms-full.txt; .specify/memory/ memory.md,
      roadmap.md; AGENTS.md status
```

**Structure Decision**: existing modules only. `PostgresLeaderElection` is a package-private class composed into
`PostgresMessageStore` (shares its `DataSource` and SQL helpers), not a public type: hosts reach it through
`MessageStore.leaderElection()`.

## Implementation Phases (input for `/speckit-tasks`)

1. **Core SPI**: `LeaderLease`, `LeaderElection`, `LeadershipLostException`; `MessageStore` changes; remove
   `ConsumerHeartbeat`; record tests (`StoreRecordsTest`).
2. **Contract first**: `MessageStoreContract` C1–C12 (fails), then `InMemoryMessageStore` leadership and fenced lease.
3. **Postgres**: table, `PostgresLeaderElection`, fenced lease transaction, upgrade script, `SchemaUpgrade013Test`;
   contract green in the container suite.
4. **Transport**: `LeadershipSettings` in `TransportSettings`; `LeaderElector` with `LeaderElectorTest` E1–E8;
   `Relay` integration (lease, step down, R6, R8, stop/resign), heartbeat removal; `TwoRelaysTest` T1–T5; public
   `LeadershipStatus`, `InMemoryTransport.leadership()`; telemetry gauge/counter in `TransportTelemetryTest`.
5. **Modulith**: delegate election, fenced lease; `ModulithInteropAcceptanceContainerTest` still green.
6. **Starter**: properties + metadata + README table; Actuator payload and health (`ActuatorTest`);
   `MultiInstanceContainerTest`.
7. **Multi-instance acceptance**: `MultiInstanceAcceptanceTest` (SC-001 to SC-005), including deadlines across a
   handover and a closed-pool crash.
8. **Docs**: architecture multi-instance section (one relay, takeover times, deposed leader, instance row lock as the
   cross-instance guarantee, throughput of one relay), guide pages, agent skill/snippet, `build-llms-full.sh`;
   memory.md (close G-007-09, G-017-02; narrow G-ENG-08, G-ENG-11; add G-013-01..03); roadmap 013 status; AGENTS.md.
9. **Verification**: `mvn spotless:apply`; `mvn -Pcontainer-tests verify`; `mvn -P release -Dgpg.skip -DskipTests
   install`.

## Complexity Tracking

| Deviation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Incompatible `MessageStore` SPI change | Fencing must be unforgeable: `lease` takes the `LeaderLease`; leadership is required, not an optional default (spec FR-018) | Keeping `lease(String, …)` plus defaults would leave an unfenced path and an `UnsupportedOperationException` default (LSP); no external users to protect |
| A second thread per transport (`saga-forge-transport-leader`) | Renewal independent of polling (FR-004) avoids flapping when a poll or lease query is slow | Renewing in the coordinator loop ties leadership to delivery latency |
