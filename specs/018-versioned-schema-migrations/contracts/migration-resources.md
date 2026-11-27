# Contract: Migration resources

**Module**: `saga-forge-storage-postgres` | **Covers**: FR-001 to FR-005, FR-006 | **Research**: R1 to R4

## Location and names

```text
classpath:io/github/gkosharovdev/sagaforge/storage/postgres/migration/
  V1__base_schema.sql            schema.sql of v0.1.0
  V2__saga_outcome_events.sql    schema-upgrade-011.sql
  V3__saga_deadlines.sql         schema-upgrade-017.sql
  V4__active_standby_relay.sql   schema-upgrade-013.sql
  V5__saga_operations.sql        schema-upgrade-015.sql
```

- Flyway's default prefix `V`, the separator `__` and the suffix `.sql` are used. There are no repeatable (`R__`) or
  undo migrations.
- No file contains a Flyway placeholder (`${...}`) or a statement that can't run in a transaction (no
  `CREATE INDEX CONCURRENTLY`, no `VACUUM`).
- A version never names a schema. Objects are created in the connection's `current_schema()`.

## Rules for every future schema change

1. Add `V<N+1>__<feature_words>.sql`, holding the feature's upgrade statements.
2. Update `schema.sql` so that it still equals V1..V(N+1) (`MigrationEquivalenceTest`).
3. Add `schema-upgrade-<spec>.sql` while the scripts are kept (FR-005).
4. Regenerate `schema-levels/V<N+1>.json` with `SchemaSnapshotTest -Dsaga-forge.write-snapshots=true`.
5. At release, add the new file's SHA-256 to `released-migrations.sha256`. A released version is never edited.
6. The version must keep the previous release working while both run (rolling deploy), or the release notes say it
   needs a stop-the-world deploy (G-018-04).

## Running them with Flyway (any host)

| Flyway setting | Value |
|---|---|
| `locations` | `PostgresSchema.MIGRATION_LOCATION` |
| `table` | `PostgresSchema.DEFAULT_HISTORY_TABLE` (or the application's chosen name, the same on every instance) |
| `placeholderReplacement` | `false` |
| `baselineOnMigrate` / `baselineVersion` | `true`, with the version from `PostgresSchemaValidator.detectVersion()` for a database with Saga Forge's tables and no history, else `0`: Flyway refuses a schema that holds the application's own tables but no history (implementation note, G-018-07) |
| everything else | Flyway defaults (`validateOnMigrate=true`, `ignoreMigrationPatterns=*:future`, `cleanDisabled=true`) |

The application's own Flyway must not list this location.

## Proven by

- **`MigrationEquivalenceTest`** (container): V1..V5 equals the current `schema.sql`, and each released `schema.sql`
  plus its upgrades equals V1..V5.
- **`ReleasedMigrationsTest`** (unit): checksums are unchanged, and every version file is listed after its release.
- **`MigrationResourcesTest`** (unit): names are contiguous from V1, there are no placeholders, and there is no
  non-transactional statement.
