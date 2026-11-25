# Contract: Message store scheduling (FR-001 to FR-006)

Applies to every `MessageStore` whose `supportsScheduling()` returns `true` (`InMemoryMessageStore`,
`PostgresMessageStore`). Proved by new cases in `MessageStoreContract` (transport test-jar), run against both.

## Append

- `append(tx, [NewMessage(notBefore = T)])` stores the message `UNCONSUMED` with `nextAttemptAt = T` and `notBefore = T`.
- Rolled-back `tx` ⇒ nothing stored (existing rule).
- `NewMessage` with `notBefore != null` and `orderingKey != null` ⇒ `IllegalArgumentException` at construction.
- A store with `supportsScheduling() == false` given a message with `notBefore` ⇒ `UnsupportedOperationException`
  (`ModulithMessageStore`).

## Lease

- `lease(consumer, now < T, ...)` never returns it (FR-002).
- `lease(consumer, now >= T, ...)` returns it like any due message, in position order with the rest (FR-004).
- An unleased future message with no key never blocks any other message.

## Cancel

`Set<String> cancel(LocalTransaction tx, Collection<String> messageIds, Instant at)`

| Message state at call | Result | Returned |
|-----------------------|--------|----------|
| `UNCONSUMED`, not leased or lease expired (due or not) | `CANCELLED`, `consumedAt = at` | id included |
| `UNCONSUMED`, leased by someone | unchanged | not included |
| `CONSUMED`, `SET_ASIDE`, `CANCELLED`, unknown | unchanged | not included |

- Runs in `tx`: rolled back ⇒ the message stays `UNCONSUMED`.
- A cancelled message is never leased, `markConsumed`/`recordFailure`/`setAside` on it throw `LeaseLostException`.
- `query(statuses = {CANCELLED})` lists it; retention removes it like `CONSUMED`.

## Restart (SC-004)

10,000 messages appended with `notBefore` in the past relative to a relay that starts later are all leased and
consumed; none is lost (container suite next to 007's 10k restart test).
