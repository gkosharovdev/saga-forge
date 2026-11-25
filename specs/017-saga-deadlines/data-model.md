# Data Model: Saga Deadlines (017)

All core types live in `saga-forge-core`, are immutable records or enums, and validate in their canonical constructor
(`IllegalArgumentException` naming the component). Research references: R1–R18.

## Annotations (core.annotation)

### `@StartDeadline` (repeatable, container `@StartDeadlines`), on a `@SagaStep` method

| Attribute | Type | Default | Rule |
|-----------|------|---------|------|
| `name` | `String` | — | non-blank, at most once per step (R24) |
| `duration` | `String` | `""` | ISO-8601 duration or `${key}` / `${key:default}`; exactly one of `duration`, `durationFrom` (R25) |
| `durationFrom` | `String` | `""` | name of a `SagaContext` attribute holding an ISO-8601 `String` |
| `cancelledBy` | `String[]` | `{}` | names of `@SagaStep` methods on the same saga, not the deadline's own timeout step (R26) |

Effect: when the annotated step's unit of work commits, the deadline is open with `dueAt = now + duration`.

### `@DeadlineExpired`, on a `@SagaStep` method

| Attribute | Type | Rule |
|-----------|------|------|
| `value` | `String` | names a deadline declared on the same saga; at most one timeout step per deadline, every deadline has one (R24) |

The method takes no parameter or one `DeadlineReached` (R27), its `@SagaStep.associationProperty` is blank (R27), it is
not `@StartSaga` (R28), and may be `@StartCompensation` or `@EndSaga`. It may itself carry `@StartDeadline` (a
reminder that re-arms its own deadline), but not when it is also `@StartCompensation` or `@EndSaga`, since leaving
`RUNNING` would cancel the new deadline at once (R29).

### `@SagaStep.associationProperty`

Gains `default ""`. Event steps still require a non-blank value (existing rule R2, unchanged message).

## Core values

### `core.deadline.DeadlineReached` (record) — the delivered payload, also the optional timeout-step parameter

| Field | Type | Rule |
|-------|------|------|
| `sagaType` | `String` | non-blank, FQN of the `@Saga` |
| `sagaId` | `String` | non-blank |
| `deadlineName` | `String` | non-blank |
| `deadlineId` | `String` | non-blank; equals the stored message id |
| `dueAt` | `Instant` | non-null |
| `startedByStep` | `String` | non-blank |

Not a `@DomainEvent`; never routed by association. `eventId()` = `deadlineId`.

### `core.deadline.OpenDeadline` (record)

`deadlineId`, `dueAt`, `startedByStep`, `startedAt` — all non-null/non-blank.

### `core.deadline.DeadlineDurationException` (extends `IllegalStateException`, implements `FinalFailure`)

Thrown when `durationFrom` is missing or not an ISO-8601 duration; message names saga, deadline and attribute.

### `core.command.ScheduledCommandId` (record)

`value` (`String`, the message id, non-blank). `toString()` returns `value`.

### `core.spi.ScheduledMessage` (record) — one entry of `OutgoingMessages.scheduled`

| Field | Type | Rule |
|-------|------|------|
| `messageId` | `String` | non-blank |
| `kind` | `MessageKind` | `EVENT` for a deadline, `COMMAND` for a scheduled command |
| `payload` | `Object` | `DeadlineReached` for `EVENT`; an `@Command(delivery = OUTBOX)` instance for `COMMAND` |
| `commandId` | `CommandId` | non-null iff `kind == COMMAND` |
| `notBefore` | `Instant` | non-null |

## SPI changes

| Type | Change |
|------|--------|
| `store.NewMessage` | + `Instant notBefore` (nullable); non-null requires `orderingKey == null` (R2). Old constructor kept. |
| `store.StoredMessage` | + `Instant notBefore` (nullable). Old constructor kept. |
| `store.MessageStatus` | + `CANCELLED` (terminal; not leasable; does not block a key; removed by retention). |
| `store.MessageStore` | + `default boolean supportsScheduling()` → `false`; + `default Set<String> cancel(LocalTransaction tx, Collection<String> messageIds, Instant at)` → throws `UnsupportedOperationException`. |
| `store.MessageQuery` | `statuses` may include `CANCELLED`; no due-time criterion (no requirement needs one). |
| `Outbox` | + `default boolean supportsScheduling()` → `false`; + `default Set<String> cancel(Collection<String> messageIds)` → throws `UnsupportedOperationException` (in-transaction cancel, contracts/outbox-and-gateway.md). |
| `OutgoingMessages` | + `List<ScheduledMessage> scheduled` (defensive copy, default empty); a batch may consist only of it. Old constructors kept. |
| `SagaInstance` | + `Map<String, OpenDeadline> openDeadlines` (unmodifiable copy); must be empty unless `status` is `CREATED` or `RUNNING`. Old constructor kept (empty map). |
| `compensation.CompensationTriggerKind` | + `DEADLINE_EXPIRED`. |
| `compensation.CompensationTrigger`, `outcome.OutcomeTrigger` | + `String deadlineName`, non-null iff kind `DEADLINE_EXPIRED`; for that kind `stepName` is the timeout step, `eventType` is `DeadlineReached`'s FQN, failure fields null. |
| `command.CommandGateway` | + `default ScheduledCommandId schedule(Object command, Instant at)`, + `default boolean cancel(ScheduledCommandId id)`, both throwing `UnsupportedOperationException`. |
| `lifecycle.SagaLifecycleEventKind` | + `DEADLINE_STARTED`, `DEADLINE_CANCELLED`, `DEADLINE_EXPIRED`. |

## Postgres

```sql
-- schema.sql and schema-upgrade-017.sql
ALTER TABLE saga_message_store ADD COLUMN not_before timestamptz NULL;
ALTER TABLE saga_message_store DROP CONSTRAINT saga_message_store_status_check;
ALTER TABLE saga_message_store ADD CONSTRAINT saga_message_store_status_check
    CHECK (status IN ('UNCONSUMED','CONSUMED','SET_ASIDE','CANCELLED'));
ALTER TABLE saga_instance ADD COLUMN open_deadlines jsonb NOT NULL DEFAULT '{}'::jsonb;
-- lifecycle kind check gains the three DEADLINE_* values
```

On append, `next_attempt_at = not_before` (R1). `cancel` = `UPDATE ... SET status='CANCELLED', consumed_at=? WHERE
message_id = ANY(?) AND status='UNCONSUMED' AND (leased_until IS NULL OR leased_until <= ?) RETURNING message_id`.
Exact constraint names are taken from the current `schema.sql` at implementation.

## State transitions

Open deadline (per instance, per name):

```
(none) --step with @StartDeadline commits--> OPEN(id1)
OPEN(id1) --same deadline started again--> OPEN(id2)                [id1 cancelled, "replaced"]
OPEN(id)  --cancelledBy step commits--> (none)                       [message cancelled]
OPEN(id)  --instance leaves RUNNING (any write)--> (none)            [message cancelled]
OPEN(id)  --DeadlineReached(id) delivered, instance RUNNING--> timeout step runs; (none) in its unit of work
          (if the timeout step's unit of work fails: rolled back, OPEN(id) stays; FAILED write then cancels it)
any       --DeadlineReached(x) with no OPEN(x) or not RUNNING--> ignored, message consumed
```

Scheduled message (store):

```
UNCONSUMED(notBefore > now) --now >= notBefore--> leasable --> CONSUMED | retried | SET_ASIDE  (007/012 rules)
UNCONSUMED, not leased --cancel--> CANCELLED
```
