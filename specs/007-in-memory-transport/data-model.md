# Data Model: In-Memory Transport with a Durable Message Log

Storage-agnostic entities first (they live in `saga-forge-core`, `core.spi.log`), then the Postgres table.

## LogEntry (written by the transport through `MessageLog.append`)

| Field | Type | Rules |
|---|---|---|
| `messageId` | String | Unique in the log. Event: `unitOfWorkId + ":" + index` (004 key). Logged command: `CommandId.value()` (005, deterministic for saga-scoped commands). Event published outside a unit of work: random UUID. |
| `kind` | `MessageKind` | `EVENT` or `COMMAND`. |
| `payloadType` | String | Fully qualified class name; non-blank. |
| `payload` | Object | The event or command; non-null. Encoded by the storage module (research §7). |
| `commandId` | `CommandId` | Present if and only if `kind == COMMAND`. |
| `orderingKey` | String, nullable | Research §5: `aggregateType + "#" + aggregateId` for events; for commands the `@AggregateIdentifier` value with the aggregate type, else `saga:` + saga id, else null. |
| `sourceSagaType`, `sourceSagaId` | String, nullable | Both present for a saga-scoped unit of work, both absent otherwise (as `OutgoingMessages`). |
| `recordedAt` | Instant | Transport clock at append. |

## LoggedMessage (returned by leases and queries)

`LogEntry` fields plus:

| Field | Type | Rules |
|---|---|---|
| `position` | long | Assigned by the log at append; strictly increasing per log. |
| `status` | `MessageStatus` | See state machine. |
| `attempts` | int | ≥ 0; incremented when an entry is leased (research §6). |
| `nextAttemptAt` | Instant | ≤ `recordedAt` for a fresh entry; backoff target after a failure. |
| `lastFailure` | `DeliveryFailure`, nullable | Exception class, message (≤ 4,000 chars), time. |
| `leasedBy`, `leasedUntil` | String, Instant, nullable | Lease; both present or both absent. |
| `consumedAt` | Instant, nullable | Present if and only if `CONSUMED`. |
| `setAsideReason` | String, nullable | Present if and only if `SET_ASIDE`: `MAX_ATTEMPTS` or `UNREADABLE` plus detail. |
| `externalRef` | String, nullable | Interop mode: Spring Modulith publication identity (research §10); null otherwise. |

Payload is optional in query results (`MessageQuery.includePayload`, default false) so a diagnostic query never fails on
an unreadable class.

## MessageStatus state machine

```text
            append
              │
              ▼
        ┌────────────┐  lease (attempts+1)   ┌──────────────────┐
        │ UNCONSUMED │ ────────────────────► │ UNCONSUMED+lease │
        └────────────┘ ◄──────────────────── └──────────────────┘
          ▲      ▲      failure: release,          │        │
          │      │      nextAttemptAt=backoff       │        │ consumer returned /
          │      │      (attempts < max)            │        │ command committed
          │      │                                  │        ▼
 redeliver│      │ lease expired (crash)            │   ┌──────────┐  retention   ┌─────────┐
 (operator│      └──────────────────────────────────┘   │ CONSUMED │ ───────────► │ removed │
  FR-023, │                                             └──────────┘              └─────────┘
 attempts │   failure with attempts ≥ max, or unreadable payload
  reset)  │                         │
          │                         ▼
          │                   ┌───────────┐
          └────────────────── │ SET_ASIDE │   (never removed by retention)
                              └───────────┘
```

Invariants:
- An entry is leasable only if `UNCONSUMED`, unleased or lease expired, `nextAttemptAt <= now`, and no `UNCONSUMED` entry
  with the same non-null `orderingKey` has a lower `position` (FR-007, FR-016).
- `CONSUMED` and `SET_ASIDE` are reached only by the leaseholder; a write by a non-holder (lease lost) is rejected and
  reported, never applied.
- Redelivery (FR-023) is allowed only from `SET_ASIDE`; it sets `attempts = 0`, `nextAttemptAt = now`, keeps
  `lastFailure`.

## Delivery record (interop mode)

The same `LoggedMessage` row with `payload` null and `externalRef` set; the payload is the Spring Modulith publication's
event (`SagaForgeEnvelope`). One row per publication; written in the same transaction (FR-028a).

## Consumer heartbeat

| Field | Type | Rules |
|---|---|---|
| `consumerId` | String | Random per transport start. |
| `host` | String | Host name, informational. |
| `startedAt`, `lastSeenAt` | Instant | `lastSeenAt` refreshed every 30 s; another consumer younger than 90 s → WARNING (FR-021). |

## Postgres schema (added to `schema.sql`; `schema-upgrade-007.sql` for existing databases)

```sql
CREATE TABLE saga_message_log (
    position          BIGSERIAL    PRIMARY KEY,
    message_id        VARCHAR(255) NOT NULL UNIQUE,
    kind              VARCHAR(16)  NOT NULL CHECK (kind IN ('EVENT', 'COMMAND')),
    payload_type      VARCHAR(512) NOT NULL,
    payload           JSONB,                      -- null only for interop delivery records
    command_id        JSONB,                      -- CommandId components, COMMAND only
    ordering_key      VARCHAR(1024),
    source_saga_type  VARCHAR(512),
    source_saga_id    VARCHAR(255),
    recorded_at       TIMESTAMPTZ  NOT NULL,
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('UNCONSUMED', 'CONSUMED', 'SET_ASIDE')),
    attempts          INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL,
    last_failure      JSONB,
    leased_by        VARCHAR(64),
    leased_until     TIMESTAMPTZ,
    consumed_at       TIMESTAMPTZ,
    set_aside_reason  VARCHAR(4096),
    external_ref      VARCHAR(255)
);
CREATE INDEX saga_message_log_head ON saga_message_log (ordering_key, position) WHERE status = 'UNCONSUMED';
CREATE INDEX saga_message_log_due ON saga_message_log (next_attempt_at, position) WHERE status = 'UNCONSUMED';
CREATE INDEX saga_message_log_consumed ON saga_message_log (consumed_at) WHERE status = 'CONSUMED';
CREATE INDEX saga_message_log_source ON saga_message_log (source_saga_type, source_saga_id);

CREATE TABLE saga_message_log_consumer (
    consumer_id   VARCHAR(64)  PRIMARY KEY,
    host          VARCHAR(255) NOT NULL,
    started_at    TIMESTAMPTZ  NOT NULL,
    last_seen_at  TIMESTAMPTZ  NOT NULL
);
```

No foreign key to `saga_instance`: an entry may come from a non-saga unit of work, and its source instance may later be
gone.

## Relationship to existing entities

- `OutgoingMessages` (004) → one `LogEntry` per event and per `OutgoingCommand`, in batch order.
- `StepRecord.handledCommands` (005) lists a logged command's `CommandId` once the sending unit of work commits
  (research §3).
- `SagaInstance` is unchanged; queries by saga instance (FR-022) match `source_saga_type`/`source_saga_id` (messages a
  saga produced). Messages a saga *received* are found by ordering key — recorded as part of G-007-04.
