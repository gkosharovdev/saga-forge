# Quickstart: Validating Process Metadata Propagation

**Feature**: `specs/019-process-metadata` | Contracts: [contracts/](contracts/) | Model: [data-model.md](data-model.md)

## Prerequisites

- JDK 21 (CI also runs 25), Maven, Docker for the container suites.
- Branch `019-process-metadata`; `mvn spotless:apply` before committing.

## 1. Core value, SPI and scanner rules

```bash
mvn -pl saga-forge-core test -Dtest='SagaMetadataTest,MetadataAccessorContractTest*,*ScannerTest*'
```

Expect: every row of [metadata-spi.md](contracts/metadata-spi.md) passes; R32 and R33 report the texts of
[saga-code.md](contracts/saga-code.md); Property 50 (a) holds.

## 2. Runtime propagation without Spring (User Story 3)

```bash
mvn -pl saga-forge-runtime -am test -Dtest='MetadataPropagationTest,*Property50*'
```

Expect, with a thread-local test accessor holding `correlationId=c-1` when the start event is handed over:
- every step, handler, compensation and deadline step sees `c-1` through the saga field, the handler parameter and the
  accessor;
- a later event carrying `c-2` leaves the instance at `c-1` and its history entry at `c-2`;
- the accessor holds the thread's previous value after each unit of work;
- with `MetadataAccessor.NONE`, every existing runtime test passes unchanged (SC-003);
- a throwing accessor never fails a unit of work (SC-005).

## 3. Transport hops and restart

```bash
mvn -pl saga-forge-transport-inmemory -am test -Dtest='*TransportAcceptance*,*MessageStoreContract*'
```

Expect: metadata on every stored event, command and scheduled message; restored around each delivery; preserved
after a relay restart over the same store.

## 4. Postgres V6

```bash
mvn -pl saga-forge-storage-postgres -am -Pcontainer-tests verify
```

Expect: round trip of the three `metadata` columns; a V5 database migrated to V6 (Flyway) and one upgraded with
`schema-upgrade-019.sql` read old rows as empty (SC-004); `PostgresSchemaValidator` names a missing `metadata` column.

## 5. Fixture and examples

```bash
mvn -pl saga-forge-test,saga-forge-example -am test
```

Expect: `OrderProcessingSagaFixtureTest` starts the canonical saga with metadata and `expectMetadata` passes; recorded
commands carry it.

## 6. Spring end to end (User Story 1, SC-001)

```bash
mvn -pl saga-forge-example-spring -am -Pcontainer-tests verify -Dtest=MetadataContainerTest
```

With `saga-forge.metadata.mdc-keys=correlationId`: the test puts `correlationId=c-1` in MDC, places an order, captures
MDC inside the saga's steps, the payment handler (an outbox command, so a relay hop) and the refund compensation, stops
and restarts the application context between two steps, and expects `c-1` in every capture, the thread's previous MDC
after each, and `{"correlationId":"c-1"}` in the `sagas` endpoint's view of the instance.

As built: the Spring example has no outbox command (memory.md G-019-08), so the relay hop is the stored start event. A
first context (relay poll 1 h) places two orders under `c-1` and `c-2` and closes before any saga starts; a second
context delivers them, and the test expects each order's id in the MDC its charge (and, for the out-of-stock order, its
refund) saw, plus the stored `saga_instance.metadata` and history rows. The `sagas` endpoint's view is covered by
`SagasEndpointWebContainerTest.metadataInView`.

## 7. Whole build and docs

```bash
mvn -Pcontainer-tests verify
```

```bash
mvn -P release -Dgpg.skip -DskipTests install
```

```bash
docs/guide/build-llms-full.sh
```

Expect: green build, Javadoc without errors, `llms-full.txt` up to date.
