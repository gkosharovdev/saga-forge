# Research: Versioned Schema Migrations

**Feature**: `specs/018-versioned-schema-migrations` | **Date**: 2026-11-27

Each item records a decision, why it was taken, and what else was considered. The facts come from the repository, the
release tags and the local Maven repository: Spring Boot 4.1.1 manages Flyway 12.4.0, and `flyway-database-postgresql`
12.4.0 is available.

## R1 — Version numbering: one version per schema-changing feature, in commit order

**Decision.** There are five versions. Each one is the upgrade script of a feature, in the order its change reached
`schema.sql` (`git log v0.1.0..HEAD -- schema.sql`):

| Version | File | Content | Released level |
|---|---|---|---|
| V1 | `V1__base_schema.sql` | `schema.sql` as tagged `v0.1.0` (003 to 010) | 0.1.0 |
| V2 | `V2__saga_outcome_events.sql` | `schema-upgrade-011.sql` | 0.2.0, 0.3.0 |
| V3 | `V3__saga_deadlines.sql` | `schema-upgrade-017.sql` | (not a release on its own) |
| V4 | `V4__active_standby_relay.sql` | `schema-upgrade-013.sql` | 0.4.0 |
| V5 | `V5__saga_operations.sql` | `schema-upgrade-015.sql` | next release |

Every later feature that changes the schema adds the next version.

**Rationale.** One version per feature keeps a version reviewable next to its spec, and the next feature (015b or 019)
simply adds V6. Every released level is "V1..VN" for one N (FR-002): 0.1.0 is V1, 0.2.0 and 0.3.0 are V2, and 0.4.0 is
V4. A level that was never a release on its own (V3) does no harm; detection may find it, and it is still a correct
baseline.

**Alternatives.**
- *One version per release.* That would merge 013 and 017 into one version. It ties version numbers to release
  numbering, and a release with no schema change would need an empty version.
- *Version numbers from spec numbers* (V11, V13, ...). Flyway would order 017 before 013, but 017's change reached
  `schema.sql` before 013's and the scripts were written against that state. The numbers would also leave gaps that
  suggest missing versions.

## R2 — Resource location outside every Flyway default

**Decision.** `classpath:io/github/gkosharovdev/sagaforge/storage/postgres/migration`.

**Rationale.** Flyway scans a location recursively. Its default location (and Spring Boot's
`spring.flyway.locations` default) is `classpath:db/migration`, so the request's example
`db/migration/saga-forge/postgres` would be picked up by the application's own Flyway, applied under the
application's history, and its `V1` would clash with the application's `V1`. The package path also sits next to
`schema.sql` and the upgrade scripts, so a reader finds all of them in one place.

**Alternatives.** `db/saga-forge/postgres`: it is still caught by an application that points Flyway at `classpath:db`.

## R3 — The migrations are copies of the scripts, and tests prove they are equal

**Decision.** Each version file holds the same statements as its upgrade script, so V1 holds `schema.sql` of
`v0.1.0`. The files' header comments are rewritten for Flyway (no "apply by hand" text). The scripts stay as they are
(spec D8). Three container tests prove they match:

1. **`MigrationEquivalenceTest`**: V1..V5 applied to an empty database gives the same catalog as the current
   `schema.sql`.
2. **Released levels**: `schema.sql` of `v0.1.0`, `v0.2.0` and `v0.4.0`, copied into test resources under
   `released/`, plus their upgrade scripts in commit order, gives the same catalog as V1..V5.
3. **Detection**: each released `schema.sql` on its own is detected as V1, V2 or V4 respectively.

"Same catalog" is measured by the same catalog reader the schema check uses (R5), plus column defaults.

**Rationale.** The scripts already use `IF NOT EXISTS` / `DROP ... IF EXISTS` and were verified by the 003 to 017
container suites. Copying them unchanged keeps that evidence. Generating one form from the other at build time would
need a build step for a handful of files.

**Alternatives.** *Drop the upgrade scripts.* Hosts without Flyway still need them, and FR-005 keeps them.

## R4 — Released versions are frozen by checksum

**Decision.** `src/test/resources/.../released-migrations.sha256` lists the SHA-256 of every released version file.
`ReleasedMigrationsTest` fails if a listed file changed, or if a version file is missing from the list. A release adds
its new versions to the list (a release checklist line in `memory.md`, DEC-REL-01). Flyway's own checksum validation
(`validateOnMigrate`, default `true`) is the second guard, in the database.

**Rationale.** Editing a released version breaks every database that already applied it. A unit test catches this
before release, without a database.

## R5 — The schema check reads `pg_catalog` and compares with a shipped snapshot per version

**Decision.** For each version, the module ships an expected-shape snapshot: a JSON resource
`schema-levels/V<N>.json`, parsed with the module's Jackson. A package-private `CatalogReader` reads the live database
from `pg_catalog`, limited to the connection's `current_schema()` and the tables named in the snapshot. It reads:

- **Columns**: `attname`, `format_type(atttypid, atttypmod)`, `attnotnull`.
- **Constraints**: `contype`, the column lists (`conkey`, `confkey`), the referenced table, the on-delete action, and
  `pg_get_constraintdef(oid)` for checks.
- **Indexes**: the column or expression list, uniqueness and the predicate, all from `pg_get_indexdef`.

Constraints and indexes are matched by meaning, never by name:

- primary keys and unique constraints by kind and column set;
- foreign keys by columns, referenced table and columns, and on-delete action;
- checks by their normalised definition (whitespace collapsed);
- indexes by their normalised definition with the index name removed.

A container test, `SchemaSnapshotTest`, rebuilds each snapshot from a database migrated to that version, and fails on
a difference. Run with `-Dsaga-forge.write-snapshots=true`, it rewrites the files.

**Rationale.** Generated names differ between a database created from `schema.sql`, from upgrade scripts or from a
copied migration. Inline `CHECK`s get names such as `saga_message_store_leader_id_check`, and an adopter's copy may
have renamed them. Matching by meaning makes the check and detection agree for all of them (US2). A snapshot keeps the
check database-free at build time, and a test proves the snapshot against Postgres, as FR-003 requires.

**Alternatives.**
- *`information_schema` only.* It doesn't expose index definitions, and check clauses are only partly normalised.
- *Building the expected shape at startup by migrating a scratch schema.* That needs `CREATE SCHEMA` rights in
  production and writes during `validate`, which FR-014 forbids.
- *A hand-written Java model per version.* It would duplicate the SQL, and nothing would tie the two together.

**Risk.** `pg_get_constraintdef` output can differ between Postgres major versions, for example how
`IN (...)` is rendered as `= ANY (ARRAY[...])`. The snapshot comes from the build's image (`postgres:16-alpine`).
New gap G-018-03: run `SchemaSnapshotTest` on the oldest and newest supported Postgres majors, and normalise further
if they differ.

## R6 — Detection: newest version with no failing difference

**Decision.** `detectVersion()` walks V5 down to V1 and returns the first version whose report has no failure
(warnings allowed, extra objects ignored, FR-013). There are three results:

- **no Saga Forge table**: an empty database;
- **matched** a version;
- **unmatched**: it carries the report with the fewest failures as the closest level.

**Rationale.** Later versions only add to earlier ones, except 013, which drops `saga_message_store_consumer`. Because
extra objects are ignored, a V4 database would also pass V1's check. Walking newest first returns the right level.
A V1 database fails V2 on the `trigger_iff_compensation` check (011 changed its definition) and fails V4 on the
missing `saga_message_store_leader` table. So each released level is distinguishable, and the detection test (R3.3)
proves it.

**Edge.** V2 differs from V1 only by a check definition, and the data rewrite (status rename) can't be seen in the
catalog. A database whose check was changed by hand without its data would be detected as V2 and skip the rewrite.
That only happens to a hand-edited schema, and it is recorded in G-018-02.

## R7 — Flyway is driven through its Java API, after the application's initialisers

**Decision.** The starter's `FlywaySchemaMigrator` (in an `@ConditionalOnClass(Flyway.class)` configuration) runs:

```text
Flyway.configure(classLoader)
  .dataSource(dataSource)
  .locations(PostgresSchema.MIGRATION_LOCATION)
  .table(historyTable)
  .baselineOnMigrate(baseline != null).baselineVersion(baseline)
  .placeholderReplacement(false)
  .load()
  .migrate()
```

`baseline` is the configured `baseline-version`, or the detected version (R6), or none when the database is empty or
already has the history table. The schema-initialiser bean carries Spring Boot's `@DependsOnDatabaseInitialization`,
so it is created after every database initialiser Boot knows: the application's Flyway, Liquibase, and
`spring.sql.init`. The storage beans depend on it, as today.

**Rationale.**
- The application's Flyway bean isn't reused, because its locations, table and placeholders are the application's. A
  separate `Flyway` instance gives Saga Forge its own history table (spec D2) and works with `spring.flyway.enabled=false`
  (FR-016).
- `@DependsOnDatabaseInitialization` is Spring Boot's documented way for a bean to wait for the database's
  initialisers, it is in `spring-boot-sql`, which the starter already depends on, and it needs no Flyway class
  reference. That gives D5's order with one annotation.
- `placeholderReplacement(false)`: the SQL has no placeholders, and the application's placeholder settings mustn't
  leak in.
- Flyway's defaults are kept: `validateOnMigrate=true` (checksums, R4), the `ignoreMigrationPatterns` default
  `*:future` (an older jar against a newer database doesn't fail, spec edge case) and `cleanDisabled=true`.

**Alternatives.**
- *A `FlywayConfigurationCustomizer` on the application's Flyway.* There is one history, so numbers clash, the
  opposite of D2.
- *Registering a `DatabaseInitializerDetector`.* That would make Saga Forge an initialiser that application JPA beans
  wait for. The order among initialisers is by detector order, which is harder to reason about and not needed (spec
  assumption: application migrations don't need Saga Forge's tables).

## R8 — Concurrent startup: Flyway's lock, no lock of our own

**Decision.** No Saga Forge advisory lock. Flyway on Postgres serialises `migrate` with a Postgres advisory lock, and
each versioned migration and its history row commit in one transaction.

Detection runs only when the history table doesn't exist. These races are all safe:

1. Instance B detects while A holds the lock and migrates. A's changes aren't committed, so B detects the old level.
   B then waits for A's lock, finds the history table, and Flyway ignores B's baseline.
2. B detects after A committed some versions. B sees a newer level, which is still a correct baseline, and Flyway
   ignores it anyway because the history now exists.
3. Two instances find an empty database. One applies V1 to V5; the other waits, then finds nothing to do.

SC-005 (three instances against one empty database) and a variant with a pre-existing V1 database prove it in a
container test.

**Alternatives.** *Our own `pg_advisory_lock` around detect and migrate.* It is correct too, but adds a second lock
with no case it fixes. It can be added if the test shows a race.

## R9 — Modes and failure reporting in the starter

**Decision.** `SchemaInitialization` gains `VALIDATE` and `MIGRATE`; `NEVER` (default) and `ALWAYS` are unchanged
(FR-015). `SchemaInitializer` keeps its public type and constructor shape, and gains an optional `SchemaMigrator`
collaborator (null when Flyway is absent).

| Mode | Runs |
|---|---|
| `never` | the table existence check (today) |
| `always` | creates from `schema.sql` in an empty database, else the existence check (today) |
| `validate` | `PostgresSchemaValidator.validate()`; failures throw `SagaSchemaException`; warnings are logged |
| `migrate` | detect when there is no history, then `SchemaMigrator.migrate(baseline)`, then `validate` |

Failures are reported like this:

- **`migrate` without `flyway-core`, or without `flyway-database-postgresql`**: a `SagaForgeConfigurationException`
  naming the property, the value and both artifacts, reported by the existing `SagaForgeConfigurationFailureAnalyzer`.
- **A failing check or an unmatched detection**: the storage module's `SagaSchemaException`, reported by one new
  `SagaSchemaFailureAnalyzer`, which prints `SchemaValidationReport.describe()`.

Detecting a missing Postgres module: Flyway raises `FlywayException("No database found to handle ...")`. The migrator
also checks `ClassUtils.isPresent("org.flywaydb.database.postgresql.PostgreSQLDatabaseType")` before it migrates.

**Rationale.** The starter already reports misconfiguration through `FailureAnalyzer`s (008). Keeping
`SchemaInitializer` as the one bean preserves the `@ConditionalOnMissingBean` override that applications already use.

## R10 — Starter properties

| Property | Default | Meaning |
|---|---|---|
| `saga-forge.storage.postgres.initialize-schema` | `never` | `never`, `always`, `validate` or `migrate` |
| `saga-forge.storage.postgres.migration.history-table` | `saga_forge_schema_history` | Saga Forge's Flyway history table |
| `saga-forge.storage.postgres.migration.baseline-version` | unset | A version number that replaces detection; used only when there is no history |

The configuration metadata, the README table and `ReadmePropertiesTest` are kept in step (FR-019).

## R11 — Actuator

**Decision.** When the `sagaforge` endpoint exists (it needs the in-memory transport today), its response gains
`schema: { mode, version, check: PASSED | FAILED, failures, warnings }`, taken from `SchemaInitializer.status()`.
`version` is the newest applied version from Saga Forge's history table, or the detected version when there is no
history. It is read once at startup, not on every request.

**Rationale.** FR-020 is met without a new endpoint. A failed check never reaches a running application in
`validate`/`migrate`, so the field mostly reports `PASSED` plus warnings. It is still useful for `never`, where the
check is run for information only.

**Decision (follow-on).** With `never`, the starter also runs the check, logs failures as warnings and reports them in
`status()`. It never fails startup, so the default stays as lenient as today (spec D3).

## R12 — Plain Java

**Decision.** Plain-Java hosts use the public `PostgresSchema` constants with their own Flyway, and
`PostgresSchemaValidator` for detection and the check. `docs/guide/schema.md` shows both from compiling code in
`saga-forge-storage-postgres`'s test sources (`PlainJavaMigrationExampleTest`, a container test). The storage module
itself never depends on Flyway: the test uses Flyway at test scope only (FR-004).

## New gaps (recorded in memory.md by the tasks)

- **G-018-01**: Liquibase is not supported. Its changelog form would be a second copy of the versions.
- **G-018-02**: detection can't see data rewrites (R6). A hand-edited schema can be detected one level too high.
- **G-018-03**: the constraint and index definitions in the snapshot are from Postgres 16. Other majors are untested
  (R5).
- **G-018-04**: nothing enforces that a version keeps the previous release working, which rolling deploys need
  (spec edge case). It is a review rule for now.
- **G-018-05**: MySQL and MongoDB migrations follow when their storage exists.
- **G-018-06**: the schema status is visible only through the `sagaforge` endpoint, which needs the in-memory transport.
