# Quickstart: validating the active/standby relay

Prerequisites: JDK 21+, Maven, Docker (for `-Pcontainer-tests`).

## 1. Store contract (both stores)

```bash
mvn -pl saga-forge-transport-inmemory -am test -Dtest='InMemoryMessageStoreTest' -Dsurefire.failIfNoSpecifiedTests=false
```

```bash
mvn -pl saga-forge-storage-postgres -am verify -Pcontainer-tests -Dtest='PostgresMessageStoreContainerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: cases C1 to C11 of [contracts/leader-election-spi.md](contracts/leader-election-spi.md) pass on both; C12
(a takeover waits for a lease in progress) is Postgres-only.

## 2. Elector and relay (no database)

```bash
mvn -pl saga-forge-transport-inmemory -am test -Dtest='LeaderElectorTest,TwoRelaysTest,PausedLeaderTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: E1 to E8 and T1 to T5 of [contracts/relay-leadership.md](contracts/relay-leadership.md); `PausedLeaderTest`
covers the fenced paused leader (T3) and a deposed relay finishing its in-flight delivery.

## 3. Two instances on one Postgres (SC-001 to SC-005)

```bash
mvn -pl saga-forge-transport-inmemory,saga-forge-storage-postgres -am verify -Pcontainer-tests -Dtest='MultiInstanceAcceptanceTest,MultiInstanceDeadlineContainerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 10,000 events over 100 associations, published through both instances, run every step once and in order
(about 9 s; `-Dsaga-forge.sc001.runs=N` repeats it); the same start event appended through both instances starts one
saga; after instance A's connections are cut, B is `ACTIVE` within lease + check (the tests use 5 s / 1 s / 0.5 s
timings); 20 forced handovers lose nothing; a stalled leader leases nothing after the freeze. In
`MultiInstanceDeadlineContainerTest`, deadlines due before, during and after a handover each run their timeout step
once, a deadline cancelled from the standby ends `CANCELLED`, and a scheduled command is handled once.

## 4. Two Spring Boot contexts (starter)

```bash
mvn -pl saga-forge-spring-boot-starter -am verify -Pcontainer-tests -Dtest='MultiInstanceContainerTest,ActuatorTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: two `SpringApplicationBuilder` contexts on one database; their transports report one `ACTIVE` and one
`STANDBY` naming the same `fencingToken`; closing the active context makes the other `ACTIVE` with a newer token, and
a saga then completes through it. `ActuatorTest` checks the `leadership` object of `/actuator/sagaforge` and the
health details (`role`, `fencingToken`, `leader`, `DEGRADED`)
([contracts/starter-and-operations.md](contracts/starter-and-operations.md)).

## 5. Single instance unchanged (SC-006) and the whole build

```bash
mvn -Pcontainer-tests verify
```

```bash
mvn -P release -Dgpg.skip -DskipTests install
```

Expected: the existing transport acceptance, restart, scheduled-delivery and Spring example suites pass unchanged; the
Javadoc build passes.

## 6. Existing database

Apply `schema-upgrade-013.sql` (creates `saga_message_store_leader`, drops `saga_message_store_consumer`) to a
database created with 017's `schema.sql`; `SchemaUpgrade013Test` (like `SchemaUpgrade017Test`) checks the result equals a fresh `schema.sql`.
