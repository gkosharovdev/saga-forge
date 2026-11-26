# Contract: `LeaderElection` and the fenced `MessageStore.lease`

Package `io.github.gkosharovdev.sagaforge.core.spi.store`. Storage-agnostic, no dependency (Constitution Principle I).
Implemented by `PostgresMessageStore`, `InMemoryMessageStore` (transport test-jar) and, by delegation,
`ModulithMessageStore`. Every rule below is a `MessageStoreContract` case.

```java
public interface LeaderElection {

  /**
   * Becomes leader if no leadership exists or the current one expired at {@code now}.
   * The new lease has a fencing token greater than every earlier one of this store,
   * acquiredAt = now, expiresAt = now + leaseDuration.
   *
   * @return the new lease, or empty if another unexpired leadership exists (also when it is held by candidateId)
   */
  Optional<LeaderLease> tryAcquire(String candidateId, String host, Instant now, Duration leaseDuration);

  /**
   * Extends {@code lease} to now + leaseDuration if it is still the current leadership (same fencing token)
   * and has not expired at {@code now}.
   *
   * @return the extended lease (same token), or empty if leadership was lost
   */
  Optional<LeaderLease> renew(LeaderLease lease, Instant now, Duration leaseDuration);

  /** Ends {@code lease} at once if it is still current; otherwise does nothing. Never throws for a stale lease. */
  void release(LeaderLease lease, Instant now);

  /** @return the leadership record as last written, expired or not; empty if none was ever acquired */
  Optional<LeaderLease> current();
}
```

`MessageStore`:

```java
  /** The leader election whose current lease fences {@link #lease}. */
  LeaderElection leaderElection();

  /**
   * In one transaction: checks that {@code leader} is the current leadership and unexpired at {@code now}, then
   * reserves up to {@code limit} leasable messages for {@code leader.holderId()} until now + leaseDuration
   * and counts one attempt for each (rules unchanged from 007).
   *
   * @throws LeadershipLostException if {@code leader} is not current at {@code now}; nothing is leased
   */
  LeasedMessages lease(LeaderLease leader, Instant now, Duration leaseDuration, int limit);
```

## Rules (contract cases)

| # | Given | When | Then |
|---|-------|------|------|
| C1 | empty store | `tryAcquire(A, t0, 30s)` | lease A, token 1, expires t0+30s; `current()` returns it |
| C2 | A holds until t0+30s | `tryAcquire(B, t0+29s)` | empty; `tryAcquire(A, …)` also empty |
| C3 | A held until t0+30s | `tryAcquire(B, t0+30s)` | lease B, token 2 |
| C4 | A holds | `renew(A, t0+10s, 30s)` | same token, expires t0+40s |
| C5 | B took over | `renew(A-lease, …)` | empty; `current()` still B |
| C6 | A's lease expired, nobody took over | `renew(A, after expiry)` | empty (expired leases are re-acquired, not renewed, so the token grows) |
| C7 | A holds | `release(A)` then `tryAcquire(B, same instant)` | B acquires with a higher token |
| C8 | B holds | `release(A-lease)` | no change |
| C9 | B holds token 2 | `lease(A-lease token 1, …)` | `LeadershipLostException`; no message changed state or attempts |
| C10 | A holds, expired at `now` | `lease(A, now, …)` | `LeadershipLostException` |
| C11 | two threads, empty store | both `tryAcquire` at the same `now` | exactly one present result |
| C12 | A is inside `lease` (Postgres: transaction open) | B calls `tryAcquire` after expiry | B's acquisition completes only after A's lease commits (Postgres row lock); A's leased messages keep A's message lease |

Postgres SQL sketch (plan detail, not a contract): acquisition `INSERT INTO saga_message_store_leader (...) VALUES
(1, ?, ?, 1, ?, ?) ON CONFLICT (id) DO UPDATE SET holder_id = EXCLUDED.holder_id, ..., fencing_token =
saga_message_store_leader.fencing_token + 1 WHERE saga_message_store_leader.expires_at <= ? RETURNING *`; lease
transaction starts with `SELECT 1 FROM saga_message_store_leader WHERE fencing_token = ? AND expires_at > ? FOR SHARE`.
