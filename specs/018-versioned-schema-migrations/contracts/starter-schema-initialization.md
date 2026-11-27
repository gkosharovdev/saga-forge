# Contract: Starter schema initialisation

**Module**: `saga-forge-spring-boot-starter` | **Covers**: FR-009, FR-010, FR-015 to FR-020 | **Research**: R7 to R11

## Properties

```properties
# never (default) | always | validate | migrate
saga-forge.storage.postgres.initialize-schema=migrate
# Saga Forge's own Flyway history table
saga-forge.storage.postgres.migration.history-table=saga_forge_schema_history
# Optional: replaces detection; used only when the history table does not exist
saga-forge.storage.postgres.migration.baseline-version=1
```

An unknown `baseline-version` fails startup through `SagaForgeConfigurationException`, which names the known range.

## Modes

| Mode | Writes to the database | Fails startup when | Otherwise |
|---|---|---|---|
| `never` | never | a Saga Forge table is missing (unchanged) | runs the check for `status()`, logs failures and warnings as WARN |
| `always` | creates from `schema.sql` in an empty database (unchanged) | only some tables exist (unchanged) | as `never` |
| `validate` | never | the check has a failure | logs warnings |
| `migrate` | applies missing versions under Saga Forge's history | Flyway or its Postgres module is absent; detection is `Unmatched`; a version fails; the check fails after migrating | logs applied versions at INFO and warnings at WARN |

## Order

1. The application's own database initialisers (Spring Boot's Flyway, Liquibase, `spring.sql.init`).
2. `SchemaInitializer`: its bean carries `@DependsOnDatabaseInitialization`.
3. The Postgres storage beans: they take `SchemaInitializer` as a parameter, as today.
4. The runtime (`SagaRuntimeLifecycle`) and the transport.

`migrate` does not depend on the application's `Flyway` bean or on `spring.flyway.enabled`.

## Dependencies

The starter's `pom.xml` adds both as `<optional>true</optional>`, with versions from Spring Boot's BOM:

- `org.flywaydb:flyway-core`
- `org.flywaydb:flyway-database-postgresql`

An application that wants `migrate` adds the same two, which it usually has already when it uses Flyway.

## Failure messages

- **No Flyway**:
  - *Description*: "saga-forge.storage.postgres.initialize-schema=migrate needs Flyway, but
    org.flywaydb:flyway-core [and/or org.flywaydb:flyway-database-postgresql] is not on the class path."
  - *Action*: "Add the missing dependency, or use initialize-schema=validate and apply the migrations at
    classpath:io/github/gkosharovdev/sagaforge/storage/postgres/migration yourself."
- **Unmatched**:
  - *Description*: "Saga Forge's tables exist but match no released schema version. Closest: V<N>." followed by
    `describe()`.
  - *Action*: "Repair the listed differences, or set saga-forge.storage.postgres.migration.baseline-version to the
    version the database is at."
- **Check failed**:
  - *Description*: `describe()`.
  - *Action*: "Apply the missing Saga Forge migrations (initialize-schema=migrate) or repair the listed differences."

## Actuator (`sagaforge` endpoint, when present)

```json
"schema": { "mode": "migrate", "version": "V5 (saga operations)", "check": "PASSED", "failures": 0, "warnings": 1 }
```

## Proven by

- **`SchemaInitializerTest`** (container, with a `SchemaMigrator` double and the real validator, which is a final
  class and isn't stubbed): the dispatch per mode; baseline from the property, from detection, or none; `Unmatched`
  throws; a missing migrator throws with both artifacts named.
- **`FlywaySchemaMigrationIntegrationTest`** (container, `ApplicationContextRunner`):
  - the application's own Flyway V1..V62 and Saga Forge's history are both complete (SC-001);
  - a takeover from released v0.1.0, v0.2.0 and v0.4.0 databases with rows keeps every row, and a saga started before
    the switch completes after it (SC-002);
  - the second start applies nothing;
  - `spring.flyway.enabled=false` still migrates;
  - a configured baseline skips detection.
- **`ConcurrentMigrationTest`** (container): three contexts start against one empty database at once, and against one
  V1 database. Each version is applied exactly once (SC-005).
- **`MigrateWithoutFlywayTest`** (unit, `FilteredClassLoader` hiding Flyway): the failure analyzer's text.
- **`SagaSchemaFailureAnalyzerTest`** and the endpoint test.
- **`ReadmePropertiesTest`** (existing) covers the new properties.
