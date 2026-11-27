# Quickstart: Versioned Schema Migrations

**Feature**: `specs/018-versioned-schema-migrations`

This guide lists the runnable checks that show the feature works end to end. Types are described in
[data-model.md](data-model.md), and behaviour in [contracts/](contracts/).

## Prerequisites

- JDK 21+ and Docker, for the Testcontainers suites with `postgres:16-alpine`.
- The 016 branch merged, or this branch, which contains it.

## 1. Build without a database

```bash
mvn -pl saga-forge-storage-postgres,saga-forge-spring-boot-starter -am verify
```

Expected: `MigrationResourcesTest`, `ReleasedMigrationsTest`, `SchemaComparisonTest`, the migrate-without-Flyway
analyzer test and `ReadmePropertiesTest` pass.

## 2. Container suites

```bash
mvn -Pcontainer-tests -pl saga-forge-storage-postgres,saga-forge-spring-boot-starter -am verify
```

| Scenario | Test | Expected |
|---|---|---|
| V1..V5 equals `schema.sql` and every released upgrade path | `MigrationEquivalenceTest` | 0 differences (SC-003) |
| Snapshots match migrated databases | `SchemaSnapshotTest` | pass; `-Dsaga-forge.write-snapshots=true` rewrites them |
| Dropped column per table | `PostgresSchemaValidatorTest` | each fails, naming table and column (SC-004) |
| Released `schema.sql` → level | `SchemaDetectionTest` | v0.1.0 → V1, v0.2.0 → V2, v0.4.0 → V4 |
| Application V1..V62 + Saga Forge | `FlywaySchemaMigrationIntegrationTest` | both histories complete, saga completes (SC-001) |
| Takeover with rows | same | 0 rows lost, a pre-switch saga completes (SC-002) |
| Three instances at once | `ConcurrentMigrationTest` | each version applied once (SC-005) |
| Plain Java with Flyway | `PlainJavaMigrationExampleTest` | migrated and validated without Spring |

## 3. Try it by hand (Spring example)

1. In `saga-forge-example-spring`, add `flyway-core` and `flyway-database-postgresql`, and set
   `saga-forge.storage.postgres.initialize-schema=migrate`.
2. Start it against an empty Postgres:
   - `\dt` shows the Saga Forge tables and `saga_forge_schema_history`;
   - `SELECT version, success FROM saga_forge_schema_history` lists 1 to 5.
3. Restart. The log reports that no version was applied.
4. Run `ALTER TABLE saga_instance DROP COLUMN resolution;`, switch to `initialize-schema=validate` and start. Startup
   fails with:

   ```text
   Saga Forge's schema does not match V5 (saga operations): 1 failure, 0 warnings.
     FAILURE saga_instance: column resolution is missing (expected jsonb NULL)
   ```

## 4. Adopter check (booking-service, SC-006)

With the release that contains this feature:

1. Set `initialize-schema=migrate` and keep the service's own `V62` unchanged.
2. Start against a copy of production: Saga Forge's history shows the detected baseline and then the applied
   versions, and the service's `flyway_schema_history` is unchanged.
3. Delete the drift test (W11) and stop copying Saga Forge's schema.

## 5. Docs

```bash
docs/guide/build-llms-full.sh && git diff --exit-code llms-full.txt
```

Expected: no diff once the new `schema` page is in the script's page list and `llms-full.txt` has been regenerated
and committed. `mkdocs build --strict` passes with the page in `mkdocs.yml`'s navigation.
