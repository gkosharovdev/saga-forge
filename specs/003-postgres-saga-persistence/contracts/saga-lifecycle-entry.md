# Contract: `SagaLifecycleEntry` / `SagaLifecycleEventKind` (new `saga-forge-core` vocabulary)

**Package**: `io.github.gkosharovdev.sagaforge.core.spi` | **Kind**: record + enum, storage-agnostic |
**Producer**: each storage module's `StorageProvider.save(...)`, by diffing snapshots (research.md §2) |
**Consumer**: none in this feature — persisted for direct data-store inspection only (FR-012). Spec: FR-007.

This is new public surface in `saga-forge-core`. No existing interface gains a method that accepts or
returns either type; `StorageProvider`/`SagaContextStore` keep their current signatures unchanged
(spec.md Assumptions).

## `SagaLifecycleEventKind`

| Value | Meaning |
|---|---|
| `CREATED` | Instance first persisted. |
| `STEP_COMPLETED` | A step transitioned `NOT_RUN` → `COMPLETED`. |
| `COMPLETED` | `SagaInstance.status()` transitioned to `COMPLETED`. |
| `FAILED` | `SagaInstance.status()` transitioned to `FAILED`. |

## `SagaLifecycleEntry`

| Field | Type | Required | Failure if violated |
|---|---|---|---|
| `sagaId` | `String` | always | `IllegalArgumentException` if `null` |
| `sequenceNumber` | `long` | always | `IllegalArgumentException` if negative |
| `kind` | `SagaLifecycleEventKind` | always | `IllegalArgumentException` if `null` |
| `stepName` | `String` | iff `kind == STEP_COMPLETED` | `IllegalArgumentException` if present/absent inconsistently with `kind` |
| `touchedAggregates` | `List<AggregateReference>` | non-null always; non-empty only if `kind == STEP_COMPLETED` | `IllegalArgumentException` if `null`, or non-empty for another `kind` |
| `outcomeStep` | `String` | iff `kind == COMPLETED` | `IllegalArgumentException` if present/absent inconsistently with `kind` |
| `failure` | `SagaFailure` | iff `kind == FAILED` | `IllegalArgumentException` if present/absent inconsistently with `kind` |
| `occurredAt` | `Instant` | always | `IllegalArgumentException` if `null` |

- **Present-iff pattern**: mirrors `SagaInstance.outcome()`/`failure()` — the compact constructor rejects a
  `kind`-inconsistent combination of the four optional fields the same way `SagaInstance` rejects an
  `outcome` without `COMPLETED`.
- **Ordering**: entries for one `sagaId` are totally ordered by `sequenceNumber`, ascending, assigned at
  derivation time (never a database identity column — data-model.md "State / sequencing notes").
  `sequenceNumber` is unique per `sagaId`, not globally.
- **No query API**: this type has no associated `find`/`list` SPI method anywhere in this feature. FR-012
  is satisfied by direct data-store access to whatever table a storage module persists it in
  ([postgres-schema.md](./postgres-schema.md) for this module); a future observability feature may add a
  query API without changing this type's shape.
- **Known limitation (research.md §5)**: when more than one `STEP_COMPLETED` entry is derived from the same
  `save()` call, their relative `sequenceNumber` order is an alphabetical-by-`stepName` tiebreak, not a
  reconstruction of true execution order — `SagaInstance.steps()` carries no per-step timestamp from which
  true order could be recovered. This affects only the multi-step-per-commit case; see research.md §5 for
  the full rationale and scope boundary (`.specify/memory/memory.md` G-003-04).
- **Known limitation, first save (deferred)**: a step already `COMPLETED` in an instance's first `save()`
  gets no `STEP_COMPLETED` entry, and `CREATED` is emitted even when the first `status` is `RUNNING`
  (`.specify/memory/memory.md` G-003-01).

## 004 delta (2026-11-09, `specs/004-saga-engine`)

Source: `specs/004-saga-engine/contracts/contract-deltas.md`. Where this contract says "the future engine", read `saga-forge-engine`.

- **Producer**: the engine, no longer `StorageProvider.save` diffing snapshots. **Consumer**: storage, through
  `save(instance, entries)`.
- `sequenceNumber` is removed from the record; storage assigns the stored position in hand-over order.
- "Known limitation (research.md §5)" and "Known limitation, first save" no longer apply to engine-driven instances
  (G-003-04 and G-003-16 closed; G-003-01 cannot arise).
- "No existing interface gains a method that accepts or returns either type" no longer holds:
  `StorageProvider.save(instance, entries)` accepts entries.
