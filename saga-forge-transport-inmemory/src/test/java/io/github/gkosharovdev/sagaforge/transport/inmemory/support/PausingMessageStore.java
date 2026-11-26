package io.github.gkosharovdev.sagaforge.transport.inmemory.support;

import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.store.DeliveryFailure;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeaderElection;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeaderLease;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeasedMessages;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageQuery;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.core.spi.store.NewMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * An {@link InMemoryMessageStore} whose leasing or leadership renewal can stall for one relay, as a
 * long GC pause or a frozen process would ({@code specs/013-active-standby-relay} US4): {@link
 * #pauseLeasing(String)} blocks that relay's next {@code lease} calls, {@link
 * #pauseRenewal(String)} its leadership renewals, until resumed. The call then proceeds against the
 * store as it is by then. Everything else delegates. Thread-safe.
 */
public final class PausingMessageStore implements MessageStore {

  private enum Call {
    LEASE,
    RENEW
  }

  private record Pause(String relayId, Call call) {}

  private final InMemoryMessageStore delegate;
  private final Map<Pause, CountDownLatch> pauses = new ConcurrentHashMap<>();
  private final Map<Pause, CountDownLatch> entered = new ConcurrentHashMap<>();
  private final LeaderElection election;

  public PausingMessageStore(InMemoryMessageStore delegate) {
    this.delegate = delegate;
    LeaderElection inner = delegate.leaderElection();
    this.election =
        new LeaderElection() {
          @Override
          public Optional<LeaderLease> tryAcquire(
              String candidateId, String host, Instant now, Duration leaseDuration) {
            return inner.tryAcquire(candidateId, host, now, leaseDuration);
          }

          @Override
          public Optional<LeaderLease> renew(
              LeaderLease lease, Instant now, Duration leaseDuration) {
            await(new Pause(lease.holderId(), Call.RENEW));
            return inner.renew(lease, now, leaseDuration);
          }

          @Override
          public void release(LeaderLease lease, Instant now) {
            inner.release(lease, now);
          }

          @Override
          public Optional<LeaderLease> current() {
            return inner.current();
          }
        };
  }

  /** Blocks {@code relayId}'s next {@code lease} calls until {@link #resumeLeasing(String)}. */
  public void pauseLeasing(String relayId) {
    pause(new Pause(relayId, Call.LEASE));
  }

  /** Blocks {@code relayId}'s next leadership renewals until {@link #resumeRenewal(String)}. */
  public void pauseRenewal(String relayId) {
    pause(new Pause(relayId, Call.RENEW));
  }

  /** Lets {@code relayId}'s blocked and later {@code lease} calls proceed. */
  public void resumeLeasing(String relayId) {
    resume(new Pause(relayId, Call.LEASE));
  }

  /** Lets {@code relayId}'s blocked and later leadership renewals proceed. */
  public void resumeRenewal(String relayId) {
    resume(new Pause(relayId, Call.RENEW));
  }

  private void resume(Pause pause) {
    CountDownLatch latch = pauses.remove(pause);
    if (latch != null) {
      latch.countDown();
    }
  }

  /**
   * @return whether {@code relayId} is blocked in a {@code lease} call now
   */
  public boolean isBlockedInLease(String relayId) {
    CountDownLatch inside = entered.get(new Pause(relayId, Call.LEASE));
    return inside != null && inside.getCount() == 0;
  }

  private void pause(Pause pause) {
    pauses.putIfAbsent(pause, new CountDownLatch(1));
    entered.put(pause, new CountDownLatch(1));
  }

  private void await(Pause pause) {
    CountDownLatch latch = pauses.get(pause);
    if (latch == null) {
      return;
    }
    CountDownLatch inside = entered.get(pause);
    if (inside != null) {
      inside.countDown();
    }
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("paused " + pause + " for more than 30 s");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while paused", interrupted);
    }
  }

  @Override
  public LeaderElection leaderElection() {
    return election;
  }

  @Override
  public LeasedMessages lease(LeaderLease leader, Instant now, Duration leaseDuration, int limit) {
    await(new Pause(leader.holderId(), Call.LEASE));
    return delegate.lease(leader, now, leaseDuration, limit);
  }

  @Override
  public void append(LocalTransaction tx, List<NewMessage> entries) {
    delegate.append(tx, entries);
  }

  @Override
  public Set<String> renew(String consumerId, Collection<String> messageIds, Instant until) {
    return delegate.renew(consumerId, messageIds, until);
  }

  @Override
  public void markConsumed(String consumerId, String messageId, Instant at) {
    delegate.markConsumed(consumerId, messageId, at);
  }

  @Override
  public void markConsumed(LocalTransaction tx, String consumerId, String messageId, Instant at) {
    delegate.markConsumed(tx, consumerId, messageId, at);
  }

  @Override
  public void recordFailure(
      String consumerId, String messageId, DeliveryFailure failure, Instant nextAttemptAt) {
    delegate.recordFailure(consumerId, messageId, failure, nextAttemptAt);
  }

  @Override
  public void setAside(
      String consumerId, String messageId, DeliveryFailure failure, String reason) {
    delegate.setAside(consumerId, messageId, failure, reason);
  }

  @Override
  public void setAside(
      LocalTransaction tx,
      String consumerId,
      String messageId,
      DeliveryFailure failure,
      String reason) {
    delegate.setAside(tx, consumerId, messageId, failure, reason);
  }

  @Override
  public void release(String consumerId) {
    delegate.release(consumerId);
  }

  @Override
  public List<StoredMessage> query(MessageQuery query) {
    return delegate.query(query);
  }

  @Override
  public boolean redeliver(String messageId, Instant now) {
    return delegate.redeliver(messageId, now);
  }

  @Override
  public int purgeConsumed(Instant cutoff, int batchSize) {
    return delegate.purgeConsumed(cutoff, batchSize);
  }

  @Override
  public boolean supportsScheduling() {
    return delegate.supportsScheduling();
  }

  @Override
  public Set<String> cancel(LocalTransaction tx, Collection<String> messageIds, Instant at) {
    return delegate.cancel(tx, messageIds, at);
  }
}
