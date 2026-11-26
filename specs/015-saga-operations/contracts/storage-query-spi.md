# Contract: `StorageProvider` query, count and history

`StorageProviderQueryContract` (abstract, in `saga-forge-runtime`'s test-jar) runs against
`TransactionalInMemoryStorageProvider`, the fixture's `FixtureStorage` (through a test in `saga-forge-test`) and
`PostgresStorageProvider` (`PostgresStorageProviderQueryContainerTest`). Instances are written with `save` in the
implementation's own transaction helper; times come from the test.

| # | Case | Expectation |
|---|---|---|
| Q1 | empty store, `find(all())` | empty page, no `next` |
| Q2 | 7 instances, limit 3 | pages of 3, 3, 1; order (`lastModifiedAt`, `sagaId`) ascending; `next` absent on the last |
| Q3 | ties: 10 instances with one `lastModifiedAt` | broken by `sagaId`; all seen once over pages of 4 |
| Q4 | status filter `{FAILED, COMPENSATION_FAILED}` | only those |
| Q5 | type filter `{A}` | only A |
| Q6 | `modifiedBefore` / `modifiedAfter` | exclusive bounds |
| Q7 | an instance re-saved (newer `lastModifiedAt`) between page 1 and 2 | it may appear again later; every unchanged instance exactly once |
| Q8 | `countByStatus(Set.of())` and `countByStatus({A})` | every `SagaStatus` key present; unresolved counts exclude resolved |
| Q9 | `history(id)` after three saves with entries | all entries in stored order, `operator` round-tripped; unknown id → empty |
| Q10 | instance with `resolution`, `compensationRound = 2`, `OPERATOR_REQUEST` trigger | `findById` and `find` return them equal |

Forward-transition cases (Postgres `ForwardTransitionTest` and the doubles' checks):

| # | Previous → incoming | Result |
|---|---|---|
| F1 | `COMPENSATION_FAILED` → `COMPENSATING`, round +1, step comp `FAILED → NOT_ATTEMPTED` | accepted |
| F2 | same without the round increment | `ConcurrentSagaUpdateException` |
| F3 | `FAILED` → `COMPENSATING` with a new `OPERATOR_REQUEST` trigger | accepted |
| F4 | `FAILED` → `COMPENSATING` with any other trigger | rejected |
| F5 | `FAILED` (no resolution) → `FAILED` with resolution | accepted |
| F6 | resolved → resolution changed or removed | rejected |
| F7 | round decreases | rejected |
| F8 | `COMPLETED` → anything else | rejected (unchanged rule) |

Schema: `SchemaUpgrade015Test` applies `schema-before-015.sql` (test resource) + `schema-upgrade-015.sql` and compares
with `schema.sql` (columns, defaults, index), as `SchemaUpgrade013Test` does.
