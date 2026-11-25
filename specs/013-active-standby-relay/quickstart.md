# Quickstart: validating the active/standby relay

Prerequisites: JDK 21+, Maven, Docker (for `-Pcontainer-tests`).

## 1. Store contract (both stores)

```bash
mvn -pl saga-forge-transport-inmemory -am test -Dtest='InMemoryMessageStoreTest'
```

```bash
mvn -pl saga-forge-storage-postgres -am verify -Pcontainer-tests -Dtest='PostgresMessageStoreContainerTest'
```

Expected: cases C1 to C12 of [contracts/leader-election-spi.md](contracts/leader-election-spi.md) pass on both.

## 2. Elector and relay (no database)

```bash
mvn -pl saga-forge-transport-inmemory -am test -Dtest='LeaderElectorTest,TwoRelaysTest'
```

Expected: E1 to E8 and T1 to T5 of [contracts/relay-leadership.md](contracts/relay-leadership.md).

## 3. Two instances on one Postgres (SC-001 to SC-005)

```bash
mvn -pl saga-forge-transport-inmemory,saga-forge-storage-postgres -am verify -Pcontainer-tests -Dtest='MultiInstanceAcceptanceTest'
```

Expected: 10,000 events over 100 associations, published through both instances, run every step once and in order;
after instance A's connection pool is closed, B is `ACTIVE` within 35 s; 20 forced handovers lose nothing; deadlines
due before, during and after a handover each run their timeout step once.

## 4. Two Spring Boot contexts (starter)

```bash
mvn -pl saga-forge-spring-boot-starter -am verify -Pcontainer-tests -Dtest='MultiInstanceContainerTest'
```

Expected: two `SpringApplicationBuilder` contexts on one database; `/actuator/sagaforge` shows one `ACTIVE` and one
`STANDBY` naming the same `fencingToken`; closing the active context makes the other `ACTIVE`
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
database created with 017's `schema.sql`; a new `SchemaUpgrade013Test` (like `SchemaUpgrade017Test`) checks the result equals a fresh `schema.sql`.
