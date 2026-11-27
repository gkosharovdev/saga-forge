# Contract: Schema check and detection

**Module**: `saga-forge-storage-postgres` | **Covers**: FR-007, FR-008, FR-011 to FR-014 | **Research**: R5, R6

## API

```java
PostgresSchemaValidator validator = new PostgresSchemaValidator(dataSource);

SchemaValidationReport report = validator.validate();          // against PostgresSchema.currentVersion()
SchemaValidationReport report = validator.validate(SchemaVersion.of(2));
SchemaDetection detection    = validator.detectVersion();      // Empty | Matched | Unmatched
Optional<SchemaVersion> applied = validator.appliedVersion(PostgresSchema.DEFAULT_HISTORY_TABLE);

if (!report.passed()) {
  throw new SagaSchemaException(report);
}
```

Every method reads and never writes. The connection is set read-only for the call.

## What is compared

| Kind | Identity | Compared shape | Severity when missing or different |
|---|---|---|---|
| Table | name | exists | FAILURE |
| Column | table + name | `format_type` type, `NOT NULL` | FAILURE |
| Primary key / unique | table + kind + column set | — | FAILURE |
| Foreign key | table + columns + referenced table and columns | on-delete action | FAILURE |
| Check | table + normalised definition | — | FAILURE |
| Index | table + normalised definition (name and schema removed) | — | WARNING |

These are never reported: extra tables, extra columns, extra constraints, extra indexes, column defaults, comments,
grants, and objects of another schema.

## `describe()` format

The first line names the expected version and the counts. Then there is one line per difference, failures first, then
warnings, each group ordered by table and name.

```text
Saga Forge's schema does not match V5 (saga operations): 2 failures, 1 warning.
  FAILURE saga_instance: column resolution is missing (expected jsonb NULL)
  FAILURE saga_instance: column compensation_round is different (expected integer NOT NULL, found bigint NOT NULL)
  WARNING saga_instance: index (status, last_modified_at, saga_id) is missing
```

A passing report with warnings gives the same first line with `0 failures`, followed by the warnings.

## Detection

| Database | Result |
|---|---|
| no Saga Forge table from the newest snapshot | `Empty` |
| passes V*k* and fails every V*j* with *j* > *k* | `Matched(Vk, report)` |
| passes no version | `Unmatched(closest)`: the fewest failures, ties go to the newer version |

## Proven by

- **`SchemaComparisonTest`** (unit, pure): each row of the comparison table, extras ignored, definition normalisation,
  ordering, and the `describe()` text.
- **`PostgresSchemaValidatorTest`** (container):
  - a migrated database passes;
  - one dropped column per Saga Forge table fails, naming that table and column (SC-004);
  - a column of the wrong type, a dropped check and a dropped index give two failures and one warning;
  - an extra column, table and index give no difference;
  - the validator writes nothing (the transaction is read-only, and the catalog is unchanged afterwards).
- **`SchemaDetectionTest`** (container): `Empty`; released `schema.sql` of v0.1.0, v0.2.0 and v0.4.0 → V1, V2 and V4;
  renamed constraints are still matched; a dropped column → `Unmatched` with the closest level.
