# Contract: `SagaOperations` (runtime behaviour)

Types: [data-model.md](../data-model.md). Each case is a test in `saga-forge-runtime` over the in-memory doubles
(`EngineSagaOperationsTest`, `RepairConcurrencyTest`, `RecoverySweeperTest`), unless marked (C) for the Postgres
container suite (`OperationsAcceptanceTest`).

## Queries

| # | Given | When | Then |
|---|---|---|---|
| O1 | completed instance `s1` | `find("s1")` | `COMPLETED`, outcome step, steps with attempts and completion order |
| O2 | unknown id | `find`, `history`, `context` | empty `Optional`, empty list, empty map; no exception |
| O3 | instances of two types share `orderId=42` | `findByAssociation("orderId","42")` | both |
| O4 | 120 `FAILED` of A, 30 of B | `list(FAILED, A, limit 50)` repeated with `next` | 3 pages, 120 once each, none of B |
| O5 | instance with context values | `find` | no context value anywhere in the result; `context(id)` returns them |
| O6 | any | a query | no instance lock taken, no write (a concurrent unit of work is not blocked) |
| O7 | mixed statuses, two resolved `FAILED` | `count(Set.of())` | per-status counts; `failedUnresolved` excludes the resolved |

## Repairs: common

| # | Case | Expectation |
|---|---|---|
| R1 | blank operator or reason | `OperatorRequest` constructor throws `IllegalArgumentException`; nothing touched |
| R2 | unknown id | `NotApplied(NOT_FOUND, null)` |
| R3 | wrong status | `NotApplied(WRONG_STATUS, actual)`; no write, no history entry |
| R4 | resolved instance, any action | `NotApplied(ALREADY_RESOLVED, status)` |
| R5 | applied action | history entry with operator, reason, time in the same transaction as the change (rollback test: storage failure leaves neither) |
| R6 | action concurrent with an event for the same instance | serialised by the instance lock; each compensation runs at most once |

## Resume compensation

| # | Given | Then |
|---|---|---|
| RC1 | 3 steps, step-2 compensation failed (`COMPENSATION_FAILED`), now succeeds | step 2 then 1 compensated; `COMPENSATED`; `compensationRound` 1; one `SagaCompensated` with id `sagaId:COMPENSATED:r1` |
| RC2 | same, fails again | `COMPENSATION_FAILED`, round 1, new failure on step 2; `SagaFailed` id `sagaId:COMPENSATION_FAILED:r1` (distinct from round 0's) |
| RC3 | step-3 already `COMPENSATED` before the failure | step 3 does not run again |
| RC4 | 012 case: step's compensation committed, its outbox command failed (`COMPENSATED → FAILED`) | that compensation runs again and sends its command again |
| RC5 | stalled `COMPENSATING` (crash after first compensation commit) | remaining compensations run; round unchanged; `COMPENSATION_RESUMED` entry |
| RC6 | `COMPLETED` | `WRONG_STATUS` |
| RC7 | history after RC1 | `COMPENSATION_FAILED` … `COMPENSATION_RESUMED`(operator) … `STEP_COMPENSATED` … `COMPENSATED` |

## Compensate

| # | Given | Then |
|---|---|---|
| CP1 | `RUNNING`, 2 committed steps | both compensated newest first; `COMPENSATED`; trigger `OPERATOR_REQUEST` with operator and reason, `stepName` = latest completed step; on `SagaCompensated.trigger` too |
| CP2 | `FAILED` (unclassified), 1 committed step | compensated; `COMPENSATED`; trigger carries the recorded failure |
| CP3 | `RUNNING` with an open deadline and a pending retry | deadline closed (message cancelled), pending retry cleared |
| CP4 | `RUNNING`, nothing committed | no compensation runs; `COMPENSATED` at once; one `SagaCompensated` |
| CP5 | `@SagaCompensation(CompensationTrigger)` method | receives `kind = OPERATOR_REQUEST` and the `OperatorRequest` |
| CP6 | `COMPENSATING`, `COMPLETED`, `COMPENSATED` | `WRONG_STATUS` |

## Mark resolved

| # | Given | Then |
|---|---|---|
| MR1 | `COMPENSATION_FAILED` | status unchanged, `resolution` set, `RESOLVED` entry, no outcome event, no compensation or command |
| MR2 | `FAILED` | same |
| MR3 | `RUNNING`, `CREATED`, `COMPENSATING` | `WRONG_STATUS` |
| MR4 | already resolved | `ALREADY_RESOLVED` |

## Stranded start (EventRouter)

| # | Given | Then |
|---|---|---|
| ST1 | `CREATED` instance, no step completed; start event delivered again | start step runs on that instance; no new instance; `START_RECOVERED` entry; result `started = false` |
| ST2 | start event redelivered while another thread's first unit of work for it is running | second sees `RUNNING` after the lock: `DUPLICATE_START` as today |
| ST3 | `RUNNING` instance with same association | `DUPLICATE_START` as today |
| ST4 (C) | crash injected after the creation write, in-memory transport over Postgres | after restart the relay redelivers; saga completes; exactly one instance |

## Recovery sweeper

| # | Given | Then |
|---|---|---|
| SW1 | `COMPENSATING`, modified 6 min ago, stall age 5 min | `recover()` resumes it; report `compensationsResumed = 1`; entry operator `saga-forge-recovery` |
| SW2 | `COMPENSATING`, modified 1 min ago | untouched |
| SW3 | 250 stalled, batch 100 | first sweep handles 100, the rest on later sweeps |
| SW4 | one instance's compensation lookup throws | `errors = 1`, others still processed, `saga.recovery.failed` logged |
| SW5 | two runtimes over one storage double, both `recover()` concurrently over 500 stalled | every compensation once; every instance one terminal status (SC-004; (C) variant over Postgres) |
| SW6 | `CREATED`, 20 min old, policy `REPORT` | logged and counted once; again only after another 15 min; no state change |
| SW7 | same, policy `FAIL` | `FAILED`, failure cause type `SagaStartAbandonedException`, `UNCLASSIFIED`; one `SagaFailed` |
| SW8 | policy `FAIL` ended the instance; its start event is redelivered later | the ended instance does not block a start (only an active one does, 001), so a **new** instance starts; documented in the guide as the reason `REPORT` is the default |
| SW9 | `enabled = true`, runtime started | sweeps at `interval` on thread `saga-forge-recovery`; `stop()` waits for a running sweep and ends the thread |
| SW10 | deadline due for `COMPENSATING` | still `IGNORED` (017); the sweeper resumes it after stall age |
