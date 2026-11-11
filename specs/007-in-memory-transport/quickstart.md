# Quickstart: validating the in-memory transport

## Prerequisites

- JDK 21, Maven; Docker for container suites (`-Pcontainer-tests`).
- Postgres schema applied: `saga-forge-storage-postgres/src/main/resources/.../schema.sql` (fresh) or
  `schema-upgrade-007.sql` (on a 006 database).

## Commands

```bash
mvn -pl saga-forge-core,saga-forge-engine,saga-forge-transport-inmemory -am test
```

```bash
mvn -Pcontainer-tests -pl saga-forge-storage-postgres,saga-forge-spring-boot-starter,saga-forge-example -am verify
```

## Scenarios

| # | Scenario | Setup | Expected | Covers |
|---|---|---|---|---|
| 1 | Canonical order saga end to end | Plain Java: Postgres log, engine, transport ([contracts/in-memory-transport.md](contracts/in-memory-transport.md)); publish `OrderCreatedEvent` outside a unit of work | Saga `COMPLETED`; every log entry `CONSUMED`; no broker | US1, SC-004 |
| 2 | Rolled-back unit of work | A step throws after recording an event | No log entry for that unit of work | US1-3, SC-002 |
| 3 | Crash before delivery | Stop the transport (no `stop`, kill relay) right after commit; start a new transport on the same database | Entry delivered once, saga continues | US2-1, SC-001 |
| 4 | Crash after handling, before consumed mark | Fault-inject between `receive` returning and `markConsumed` | Redelivery reported `DUPLICATE`; step ran once; entry `CONSUMED` | US2-3 |
| 5 | Logged command across restart | `ProcessPaymentCommand` with `delivery = LOGGED`; stop after the step commits | After restart the handler runs once with the original `CommandId` | US3, Property 35 |
| 6 | Non-logged command failure | Default command whose handler throws | Step rolls back; retry/compensation as 005/006; no log entry | US3-5 |
| 7 | Poison message | Consumer always throws | `SET_ASIDE` after 10 attempts; other aggregates keep flowing | FR-015/016, SC-006 |
| 8 | Operator view | Query by status and by source saga; redeliver a set-aside entry | Attributes per [data-model.md](data-model.md); redelivered entry consumed | US4, SC-007 |
| 9 | Restart with backlog | 10,000 unconsumed entries | All consumed and new work accepted within 60 s | SC-005 |
| 10 | Retention | Consumed entries older than 7 days | Removed by the sweeper; set-aside ones kept | FR-019 |
| 11 | Spring host, own log | Boot app with the starter, no Modulith | Scenarios 1–8 pass unchanged | SC-008 |
| 12 | Spring Modulith interop | Boot app with `spring-modulith-starter-jdbc` | Scenarios 1–8 pass; messages in the registry table, delivery records with null payload; registry publications completed | FR-028/028a, SC-008 |
| 13 | Second instance | Start two transports on one database | WARNING logged; no entry lost | FR-021 |

Scenarios 3–5 and 9 are container suites; 11–12 run in the starter module.
