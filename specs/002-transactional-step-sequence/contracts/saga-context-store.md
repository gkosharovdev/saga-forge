# Contract: `SagaContextStore` SPI

**Package**: `io.github.gkosharovdev.sagaforge.core.spi` | **Kind**: interface, implemented by storage modules
| **Consumer**: the saga engine only. Spec: FR-024.

| Operation | Effect | Failure |
|---|---|---|
| `Optional<SagaContextRecord> find(String sagaId)` | Returns the saga's context record, or empty if none was ever saved | `IllegalArgumentException` if `sagaId` is `null` |
| `void save(SagaContextRecord record)` | Creates or replaces the record for `record.sagaId()` | `IllegalArgumentException` if `record` is `null`; storage failures propagate unchecked |
| `void delete(String sagaId)` | Removes the record, if any | `IllegalArgumentException` if `sagaId` is `null` |

- **Independence**: a `save` MUST NOT join the saga-scoped unit of work's transaction. Postgres and MySQL
  modules use their own table and a separate transaction; MongoDB uses its own collection.
- **Reference**: `record.sagaId()` equals the `SagaInstance.sagaId` it belongs to. A record may exist for a
  saga whose instance record is `FAILED`.
- **Values**: stored as given; structured values are nested `Map<String, Object>` (feature 001 semantics).
  Serialization of value types is the storage module's concern, as for `SagaInstance.state`.

## 004 delta (2026-11-09, `specs/004-saga-engine`)

Source: `specs/004-saga-engine/contracts/contract-deltas.md`. Where this contract says "the future engine", read `saga-forge-engine`.

- New rule: a `SagaContextRecord` is saved only after its instance's creation write committed (G-ENG-01).
- Unchanged: the store never joins the unit of work's transaction.
