# Contract: Trace context through the message store

## Core (`core.spi.store`)

- `NewMessage` and `StoredMessage` gain a final component `Map<String, String> traceContext` (never `null`; empty when
  absent; an unmodifiable copy). The 007 constructors stay and pass an empty map.
- `MessageStore` implementations MUST store `traceContext` with the message and return it unchanged on lease and query.
  `MessageStoreContract` (transport test-jar) gains the round-trip case, so every store is checked.

## In-memory transport

- `LogAppender` injects `Context.current()` with the configured `TextMapPropagator` into `traceContext` for every
  message of a batch (the unit of work's `attempt` span is current, research §3).
- The relay extracts each message's `traceContext` and delivers it inside a `saga_forge.delivery` span
  (`SpanKind.CONSUMER`) with that parent, made current around the receive call; a delivery failure sets `error.type`
  and status `ERROR` on that span, and the transport's existing retry applies.
- Each stored message is delivered on its own, so each delivery continues exactly one stored context; span links
  are not needed.

## Postgres

- `saga_message_store.trace_context JSONB NULL`; `schema.sql` includes it; existing databases apply
  `schema-upgrade-010.sql` (`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`). `NULL` reads as an empty map.

## Spring Modulith interop

- The interop store keeps Saga Forge's delivery records in `PostgresMessageStore` (`appendDeliveryRecord`), so the
  context is stored in `trace_context` there; `SagaForgeEnvelope` is unchanged.
