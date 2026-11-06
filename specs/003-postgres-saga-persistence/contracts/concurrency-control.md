# Contract: Concurrency Control (`PostgresStorageProvider.save`)

**Kind**: behavioral contract, internal to `saga-forge-storage-postgres` | Spec: FR-010, SC-004 |
Supersedes spec.md's Assumptions bullet about an "optimistic check (version marker)" — see research.md §3
for the full rationale on why a version-marker CAS is not implementable against the existing
`StorageProvider.save(SagaInstance)` signature.

## Mechanism

| Step | Action |
|---|---|
| 1 | `save(incoming)` opens a transaction and executes `SELECT ... FROM saga_instance i WHERE i.saga_id = ? FOR UPDATE OF i` (`LOCK_PREVIOUS`; the select list also reads the instance's associations through a `jsonb_object_agg` scalar subquery on `saga_association`, which is not locked, G-003-21). A concurrent `save()` for the same `sagaId` blocks here until the first transaction commits or rolls back — it never reads a half-written row. |
| 2 | If a row is found, deserialize it as `previous`. If no row is found, there is nothing to lock or validate against; proceed as a first save (`CREATED`), guarded by step 6. |
| 3 | Validate `incoming` is a **forward transition** from `previous` (see "Forward transition" below). |
| 4 | On violation: throw `ConcurrentSagaUpdateException` (unchecked, this module, with a `sagaId()` accessor and a message naming the violated rule) and roll back — the transaction holding the lock releases it without writing, so the *other* transaction (whichever one is first to the lock) always wins outright; this call is the loser. |
| 5 | On success: derive the history entries (data-model.md), then upsert the instance with `INSERT ... ON CONFLICT (saga_id) DO UPDATE ... RETURNING (xmax = 0) AS inserted`, replace the associations, insert the history rows, and commit, still holding the row lock until commit. |
| 6 | **First-save race**: if step 2 found no row but the upsert reports `inserted = false` (it updated a row that a concurrent first save committed in the meantime), throw `ConcurrentSagaUpdateException` and roll back before the history insert. This rejects the second of two concurrent first saves even when both snapshots are identical (G-003-03). Reading `xmax = 0` as "freshly inserted" is widely relied-on but undocumented Postgres behavior (G-003-22). |

## Forward transition

`incoming` is a forward transition from `previous` when **all** of the following hold:

- For every step name where `previous.steps().get(name).status() == COMPLETED`:
  `incoming.steps().get(name)` exists, is also `COMPLETED`, and has an equal `touchedAggregates` list
  (same elements, same order — a completed step's recorded facts never change). A previously `COMPLETED`
  step that is missing from `incoming.steps()` is a violation; a previously `NOT_RUN` step may be missing
  (decided at T024, G-003-15).
- If `previous.status()` is `COMPLETED` or `FAILED` (terminal), `incoming.status()` equals `previous.status()`
  (a terminal instance's status never changes).

The check does **not** compare a terminal instance's `outcome` or `failure`. `SagaInstance`'s constructor ties
only their *presence* to the status, not their value, so a `COMPLETED` instance re-saved with a different
`outcome` (or a `FAILED` one with a different `failure`) is accepted, and the derivation adds no history row for
it because the status is unchanged (G-003-19).

Everything else is allowed to differ — a step going `NOT_RUN → COMPLETED`, `associations` gaining entries,
`state` changing, `status` advancing (`CREATED → RUNNING → COMPLETED`/`FAILED`), `lastModifiedAt` advancing.
Also not checked: `sagaType` and `createdAt` (G-003-13), and the order of non-terminal statuses, so a
regression such as `RUNNING → CREATED` is accepted (G-003-20).
This is deliberately permissive: it rejects exactly "two processes raced and one has a stale, conflicting
view of what already completed," not "a legitimate sequential update."

## Why this satisfies FR-010/SC-004 without a version field

Two processes `P1`/`P2` both read the same `previous` row (e.g., via `findById`), then each independently
compute a new `incoming` and call `save()`:

- Whichever calls `save()` first acquires the row lock, validates against the *original* `previous` (which
  still matches what it read), and commits — the row now reflects its `incoming`.
- The second caller's `SELECT ... FOR UPDATE` blocks until the first commits, then reads the row the first
  writer *just* wrote (no longer the original `previous`). Its own `incoming` — computed against the stale
  `previous` it read earlier — now fails the forward-transition check against what is actually persisted
  (typically because a step it thinks is still `NOT_RUN` is now `COMPLETED` under the first writer's commit,
  or because `previous.status()` was terminal and `incoming.status()` disagrees). It throws
  `ConcurrentSagaUpdateException` and rolls back.

No instance ever ends up with a row that reflects only part of either writer's intent (SC-004's "never
silently left in a state that reflects only part of either attempt"): the losing writer's transaction never
commits anything.

## What this does not detect

Two processes computing **identical** `incoming` values from the same `previous` (e.g., both independently
decide to complete the same single step with the same `touchedAggregates`, with nothing else differing) are
not distinguishable from a benign retry of the same logical update, and the second writer's `save()`
succeeds — it is a forward transition from whatever is now persisted (which already equals its own
`incoming` in every diffed dimension, so the check trivially passes and the derivation algorithm produces
zero new lifecycle entries for it, per data-model.md). This is intentional: idempotent re-delivery of the
same outcome is not a conflict FR-010 asks to be rejected, only *diverging* concurrent attempts are.

Known gaps, tracked in `.specify/memory/memory.md`:
- **G-003-02 (deferred)**: stale writers whose snapshots differ only in `state` or `associations` (no step
  or terminal-status change) pass the check, so the last write wins. FR-010's "diverging attempts" reads
  broader than this.
- **G-003-03**: on the very first save there is no row to lock; the `RETURNING (xmax = 0)` check (Mechanism
  step 6) rejects the second of two concurrent first saves even when both snapshots are identical, unlike the
  idempotent case above (quickstart.md scenario 12).
- **G-003-19, G-003-13, G-003-20**: see "Forward transition" above.
- **G-003-23**: the concurrency behavior described here is covered by `ConcurrencyControlTest` and the pure
  `ForwardTransitionPropertyTest`, but only the latter has run; the container-backed suite has not been run
  against Postgres.
