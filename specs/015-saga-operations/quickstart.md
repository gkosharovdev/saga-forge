# Quickstart: validating saga operations

Prerequisites: JDK 21+, Maven, Docker (container suites only). Run from the repository root.

## 1. Unit and contract tests (no Docker)

```bash
mvn -pl saga-forge-core,saga-forge-runtime,saga-forge-test -am verify
```

Expect green: `StorageProviderQueryContract` on the in-memory doubles (cases Q1–Q10, F1–F8 in
[contracts/storage-query-spi.md](contracts/storage-query-spi.md)), and the operations, concurrency and sweeper tests
(O, R, RC, CP, MR, ST, SW in [contracts/operations-api.md](contracts/operations-api.md)).

## 2. Fixture scenario (what an application test looks like)

In `saga-forge-example`, `OrderProcessingSagaOperationsFixtureTest` should show:

1. A compensation that fails once leaves the instance `COMPENSATION_FAILED`. `fixture.operations().resumeCompensation(id,
   new OperatorRequest("alice", "payment service fixed"))` ends it `COMPENSATED`, and `expectHistory(COMPENSATION_FAILED,
   COMPENSATION_RESUMED, COMPENSATED)` passes.
2. With `recovery(RecoverySettings.enabledWithDefaults())`, a compensation that stalls is resumed by
   `whenTimeElapses(Duration.ofMinutes(6))`.

## 3. Postgres and multi-instance (Docker)

```bash
mvn -Pcontainer-tests -pl saga-forge-storage-postgres -am verify
```

Expect: `PostgresStorageProviderQueryContainerTest`, `SchemaUpgrade015Test`, `OperationsAcceptanceTest` (ST4 stranded
start after a crash; SW5 with two runtimes over 500 stalled instances, SC-004; a 10,000-instance listing at 500 per page,
SC-005; the 1,000-saga fault injection of SC-003).

## 4. Spring example over HTTP

```bash
mvn -Pcontainer-tests -pl saga-forge-example-spring -am verify
```

Expect `SagasEndpointWebContainerTest` (E1–E7 in [contracts/starter-operations.md](contracts/starter-operations.md)).
To try it by hand, start the example with:

```properties
management.endpoints.web.exposure.include=health,sagas
management.endpoint.sagas.access=unrestricted
```

Then call `GET /actuator/sagas?status=COMPENSATION_FAILED` and `POST /actuator/sagas/{id}/resume-compensation` with
`{"reason":"…"}` as an authenticated user.

## 5. Whole build

```bash
mvn -Pcontainer-tests verify
```

```bash
mvn -P release -Dgpg.skip -DskipTests install
```

The first runs the whole build including the container suites. The second checks that the Javadoc still passes the
release build. Then run `docs/guide/build-llms-full.sh` and check that `llms-full.txt` has no diff.
