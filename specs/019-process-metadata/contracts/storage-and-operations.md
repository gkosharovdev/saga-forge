# Contract: Storage, Operations and Endpoints

**Modules**: `saga-forge-storage-postgres`, `saga-forge-core` (`core.operations`), starter | **Spec**: FR-014 to FR-017,
D10, D11

## Postgres

- `migration/V6__process_metadata.sql`:
  ```sql
  ALTER TABLE saga_instance          ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
  ALTER TABLE saga_lifecycle_history ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
  ALTER TABLE saga_message_store     ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
  ```
- `schema.sql` declares the columns; `schema-upgrade-019.sql` uses `ADD COLUMN IF NOT EXISTS` (re-runnable);
  `schema-levels/V6.json` regenerated; `PostgresSchema.versions()` → 0..6; V1 to V5 untouched.
- `PostgresStorageProvider` writes `SagaInstance.metadata` on every save (unchanged value after creation) and each
  entry's metadata; reads `{}`/`NULL` as empty. `PostgresMessageStore` writes and reads `metadata` on append, lease and
  queries, and in `appendDeliveryRecord` (Modulith interop).
- Container tests: round trip of each column; `PostgresSchemaValidator` detects V6 and reports a missing `metadata`
  column by name; a V5 database migrated to V6 reads old rows as empty (SC-004).

## Operations API

- `SagaOperations.find`, `findByAssociation`, `list` return `SagaInstance` with `metadata`; `history` returns entries with
  `metadata`. No new query (G-019-02).

## `sagas` endpoint (starter, 015)

- Instance view: `"metadata": {"correlationId": "c-1", ...}` (always shown, D10).
- History view: each entry has `"metadata"`.

## `sagaforge` endpoint

```json
"metadata": { "accessor": "MdcMetadataAccessor", "mdcKeys": ["correlationId"] }
```
`"accessor": "none"` when no accessor is active.
