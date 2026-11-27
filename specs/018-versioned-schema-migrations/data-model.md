# Data Model: Versioned Schema Migrations

**Feature**: `specs/018-versioned-schema-migrations` | **Date**: 2026-11-27

All new types live in `saga-forge-storage-postgres` (package `io.github.gkosharovdev.sagaforge.storage.postgres`), or
in the starter where marked. Core and runtime are untouched (Constitution Principle I).

## Resources (storage module)

| Resource | Content |
|---|---|
| `.../storage/postgres/migration/V1__base_schema.sql` … `V5__saga_operations.sql` | Flyway versioned migrations (research R1, R2) |
| `.../storage/postgres/schema-levels/V1.json` … `V5.json` | the expected shape after each version (R5) |
| `.../storage/postgres/schema.sql`, `schema-upgrade-NNN.sql` | unchanged; still shipped (FR-005) |

### Snapshot file (`schema-levels/V<N>.json`)

```text
{
  "version": 5,
  "tables": [
    {
      "name": "saga_instance",
      "columns": [ { "name": "saga_id", "type": "character varying(255)", "notNull": true }, ... ],
      "constraints": [
        { "kind": "PRIMARY_KEY", "columns": ["saga_id"] },
        { "kind": "CHECK", "definition": "CHECK (...)" }
      ],
      "indexes": [ { "definition": "ON saga_instance USING btree (status, last_modified_at, saga_id)" } ]
    },
    {
      "name": "saga_context",
      "columns": [ ... ],
      "constraints": [
        { "kind": "FOREIGN_KEY", "columns": ["saga_id"], "references": "saga_instance", "referencedColumns": ["saga_id"],
          "onDelete": "CASCADE" }
      ],
      "indexes": []
    }
  ]
}
```

Rules:

- Tables and columns are keyed by name.
- Constraints and indexes carry no name. They are matched by meaning (R5).
- Definitions are stored normalised: whitespace collapsed, index name and schema qualifier removed.
- Indexes that back a primary key or unique constraint are not listed twice. They are covered by the constraint.
- Written only by `SchemaSnapshotTest` (R5). It is never edited by hand.

## Public types (storage module)

### `PostgresSchema` (final, non-instantiable)

| Member | Value |
|---|---|
| `MIGRATION_LOCATION` | `"classpath:io/github/gkosharovdev/sagaforge/storage/postgres/migration"` |
| `DEFAULT_HISTORY_TABLE` | `"saga_forge_schema_history"` |
| `SCHEMA_SCRIPT` | the existing `schema.sql` classpath path (moved here from the starter's `SchemaInitializer.SCHEMA`, which delegates to it) |
| `static SchemaVersion currentVersion()` | the newest version this jar ships (V5) |
| `static List<SchemaVersion> versions()` | V1..V5, ascending, unmodifiable |

### `SchemaVersion` (record, `Comparable<SchemaVersion>`)

- **Fields**: `int number` (≥ 1) and `String description` (non-blank, taken from the file name).
- **Factory**: `static SchemaVersion of(int number)` looks the number up among the shipped versions. An unknown number
  throws `IllegalArgumentException` naming the known range. It is used for `baseline-version`.
- **Text form**: `toString()` gives `"V5 (saga operations)"`.

### `PostgresSchemaValidator` (final, thread-safe, read-only)

| Member | Behaviour |
|---|---|
| `PostgresSchemaValidator(DataSource dataSource)` | no I/O in the constructor |
| `SchemaValidationReport validate()` | compares with `currentVersion()` |
| `SchemaValidationReport validate(SchemaVersion expected)` | compares with that version's snapshot (FR-011 to FR-013) |
| `SchemaDetection detectVersion()` | R6; reads only |
| `Optional<SchemaVersion> appliedVersion(String historyTable)` | the newest successful version in a Flyway history table, or empty when there is no table; plain SQL, no Flyway |

All reads use one connection, and the transaction is read-only. An `SQLException` is wrapped in the module's existing
`UncheckedSqlException`.

### `SchemaValidationReport` (record)

- **Fields**: `SchemaVersion expected` and `List<SchemaDifference> differences` (an immutable copy, ordered by table,
  then kind, then name).
- **Methods**:
  - `boolean passed()`: true when there are no differences of severity `FAILURE`;
  - `List<SchemaDifference> failures()` and `warnings()`;
  - `String describe()`: a multi-line text, one line per difference, in the format of contracts/schema-check.md.

### `SchemaDifference` (record)

| Field | Type | Notes |
|---|---|---|
| `table` | `String` | non-null |
| `kind` | `ObjectKind` | `TABLE`, `COLUMN`, `CONSTRAINT`, `INDEX` |
| `name` | `String` | the column name, the constraint's or index's normalised definition, or the table name for `TABLE` |
| `problem` | `Problem` | `MISSING` or `DIFFERENT` (extras are never reported, FR-013) |
| `expected` | `String` | the expected shape, for example `"character varying(255) NOT NULL"` |
| `found` | `String` | the found shape, or `null` when `MISSING` |
| `severity` | `Severity` | `FAILURE`, or `WARNING` for `INDEX` (FR-013) |

The severity is derived from the kind in the constructor, so there is no boolean parameter. `ObjectKind`, `Problem`
and `Severity` are nested enums.

### `SchemaDetection` (sealed interface; three records)

| Variant | Fields | Meaning |
|---|---|---|
| `Empty` | — | none of the snapshot's tables exist |
| `Matched` | `SchemaVersion version`, `SchemaValidationReport report` | the newest version with `report.passed()` (warnings possible) |
| `Unmatched` | `SchemaValidationReport closest` | no version passes; `closest` has the fewest failures, ties go to the newer version |

A sealed hierarchy of value variants is allowed by the constitution's Development Workflow, because it is designed as
one.

### `SagaSchemaException` (extends `RuntimeException`)

It carries a `SchemaValidationReport`. The starter throws it on a failing check or an unmatched detection, and the
storage module offers it for plain-Java hosts (`report.passed()` is the non-throwing path).

## Package-private types (storage module)

| Type | Role |
|---|---|
| `SchemaSnapshot` | the parsed `V<N>.json`; `load(SchemaVersion)` reads the resource with Jackson |
| `CatalogReader` | reads the same shape from `pg_catalog` for the snapshot's table names in `current_schema()` (R5) |
| `SchemaComparison` | `compare(SchemaSnapshot expected, SchemaSnapshot found)` → `List<SchemaDifference>`; a pure function |
| `Definitions` | normalises constraint and index definitions (whitespace, names, schema qualifier) |

`SchemaComparison` is pure and gets most of the unit tests. `CatalogReader` is covered by the container tests.

## Starter types (`io.github.gkosharovdev.sagaforge.spring.storage`)

| Type | Kind | Notes |
|---|---|---|
| `SagaForgeProperties.SchemaInitialization` | enum, gains `VALIDATE` and `MIGRATE` | Javadoc per value |
| `SagaForgeProperties.Postgres.Migration` | nested properties | `historyTable` (default `PostgresSchema.DEFAULT_HISTORY_TABLE`) and `baselineVersion` (`Integer`, nullable) |
| `SchemaInitializer` | existing public class | the constructor gains `PostgresSchemaValidator`, an optional `SchemaMigrator` and `Migration`; `initialize()` dispatches by mode (R9); `status()` → `SchemaStatus` |
| `SchemaMigrator` | package-private interface | `SchemaVersion migrate(SchemaVersion baselineOrNull)` returns the newest applied version. There are two implementations: `FlywaySchemaMigrator` and the test double in `SchemaInitializerTest` |
| `FlywaySchemaMigrator` | package-private, final | R7; only loaded inside `@ConditionalOnClass(Flyway.class)` |
| `SchemaStatus` | public record | `mode`, `version` (nullable) and `report` (nullable when the mode ran no check); read by the `sagaforge` endpoint |
| `SagaSchemaFailureAnalyzer` | public, in `spring.diagnostics` | R9; analyses the storage module's `SagaSchemaException` |

`migrate` without Flyway throws the existing `SagaForgeConfigurationException`, which the existing
`SagaForgeConfigurationFailureAnalyzer` already reports. No new exception type is needed for it.

### `SchemaInitializer.initialize()` state flow

```text
mode NEVER    → existence check (unchanged) → validate() for status only, failures logged as warnings
mode ALWAYS   → unchanged → validate() for status only
mode VALIDATE → report = validate(); report.passed() ? log warnings : throw SagaSchemaException
mode MIGRATE  → migrator absent          → throw SagaForgeConfigurationException (names both artifacts)
                applied = appliedVersion(historyTable)
                baseline = applied.isPresent() ? none
                         : configured baselineVersion != null ? SchemaVersion.of(it)
                         : switch detectVersion(): Empty → none; Matched m → m.version();
                                                   Unmatched u → throw SagaSchemaException(u.closest())
                version = migrator.migrate(baseline)
                report  = validate(); not passed → throw SagaSchemaException
```

## Database objects

| Object | Owner | Notes |
|---|---|---|
| `saga_forge_schema_history` (configurable name) | Flyway | Flyway's standard history table layout, in `current_schema()`. Created by the first `migrate` |
| the Saga Forge tables | Saga Forge | unchanged by this feature. V5 equals the current `schema.sql` |
