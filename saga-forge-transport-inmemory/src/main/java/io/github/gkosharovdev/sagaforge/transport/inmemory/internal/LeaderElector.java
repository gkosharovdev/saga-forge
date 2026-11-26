package io.github.gkosharovdev.sagaforge.transport.inmemory.internal;

import io.github.gkosharovdev.sagaforge.core.spi.store.LeaderElection;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeaderLease;
import io.github.gkosharovdev.sagaforge.transport.inmemory.LeadershipSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.LeadershipStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.RelayRole;
import io.github.gkosharovdev.sagaforge.transport.inmemory.internal.TransportInstrumentation.LeadershipChange;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The relay's leadership loop ({@code specs/013-active-standby-relay} research R4, R5,
 * contracts/relay-leadership.md), shaped like client-go's {@code leaderelection.LeaderElector}: as
 * holder it renews every {@code renewInterval}, as candidate it tries to acquire every {@code
 * checkInterval}, on a thread of its own so renewal never waits for deliveries (FR-004).
 *
 * <p>The holder trusts its lease only until {@code start of the last successful call +
 * leaseDuration - renewInterval} by its own clock (FR-007), so it stops leasing before the store
 * would hand the leadership on. {@link #leadership()} is what the relay leases with.
 *
 * <p><strong>Not API.</strong> Public only because {@code InMemoryTransport} lives in another
 * package. Thread-safe: store calls run outside the monitor, their results are applied under it.
 */
public final class LeaderElector {

  private final LeaderElection election;
  private final LeadershipSettings settings;
  private final Clock clock;
  private final String relayId;
  private final String host;
  private final Logger logger;
  private final TransportInstrumentation instrumentation;
  private final Supplier<ScheduledExecutorService> executorFactory;

  private LeaderLease held;
  private Instant trustedUntil = Instant.MIN;
  private LeaderLease lastSeen;
  private Instant observedAt;
  private ScheduledExecutorService executor;
  private ScheduledFuture<?> nextRound;
  private AutoCloseable gauge = () -> {};
  private boolean stopped;

  /**
   * @param executorFactory makes the thread rounds run on; {@link #manualScheduling()} for tests
   *     that drive {@link #tick()} themselves
   */
  public LeaderElector(
      LeaderElection election,
      LeadershipSettings settings,
      Clock clock,
      String relayId,
      String host,
      Logger logger,
      TransportInstrumentation instrumentation,
      Supplier<ScheduledExecutorService> executorFactory) {
    this.election = election;
    this.settings = settings;
    this.clock = clock;
    this.relayId = relayId;
    this.host = host;
    this.logger = logger;
    this.instrumentation = instrumentation;
    this.executorFactory = executorFactory;
    this.observedAt = clock.instant();
  }

  /**
   * @return one daemon thread {@code saga-forge-transport-leader}
   */
  public static Supplier<ScheduledExecutorService> backgroundScheduling() {
    return () ->
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "saga-forge-transport-leader");
              thread.setDaemon(true);
              return thread;
            });
  }

  /**
   * @return no thread: rounds run only when a test calls {@link #tick()}
   */
  static Supplier<ScheduledExecutorService> manualScheduling() {
    return () -> null;
  }

  /** Runs one round now, so a lone instance leads at once (D9), then schedules the next rounds. */
  public void start() {
    tick();
    synchronized (this) {
      gauge = instrumentation.relayActiveGauge(() -> leadership().isPresent());
      executor = executorFactory.get();
    }
    scheduleNext();
  }

  /**
   * @return the lease to lease messages with, while this relay holds and still trusts it
   */
  public synchronized Optional<LeaderLease> leadership() {
    if (held != null && !clock.instant().isBefore(trustedUntil)) {
      lose("trustExpired", null);
      schedule(0);
    }
    return Optional.ofNullable(held);
  }

  /**
   * @return whether this relay leads now
   */
  public boolean isLeading() {
    return leadership().isPresent();
  }

  /**
   * Gives up {@code stale} after the store refused a lease with it (fenced). A lease older than the
   * one held is ignored.
   */
  public synchronized void stepDown(LeaderLease stale) {
    if (held != null && held.fencingToken() == stale.fencingToken()) {
      lose("fenced", null);
    }
  }

  /** Stops the rounds and releases the leadership if held, so a standby takes over at once. */
  public void resign() {
    LeaderLease releasing;
    synchronized (this) {
      if (stopped) {
        return;
      }
      stopped = true;
      if (executor != null) {
        executor.shutdownNow();
      }
      closeGauge();
      releasing = held;
      held = null;
      trustedUntil = Instant.MIN;
    }
    if (releasing == null) {
      return;
    }
    try {
      election.release(releasing, clock.instant());
      instrumentation.leadershipChanged(LeadershipChange.RELEASED);
      logger.log(
          Level.INFO,
          () ->
              "saga.transport.leadership.released relay="
                  + relayId
                  + " fencingToken="
                  + releasing.fencingToken());
    } catch (RuntimeException failure) {
      logger.log(Level.WARNING, "saga.transport.leadership.checkFailed relay=" + relayId, failure);
    }
  }

  /**
   * @return this relay's role and the leadership record as last seen
   */
  public synchronized LeadershipStatus status() {
    Optional<LeaderLease> leading = leadership();
    return new LeadershipStatus(
        leading.isPresent() ? RelayRole.ACTIVE : RelayRole.STANDBY,
        relayId,
        leading.orElse(lastSeen),
        observedAt);
  }

  /** One round: renew as holder, try to acquire as candidate. Package-visible for tests. */
  void tick() {
    LeaderLease before;
    synchronized (this) {
      if (stopped) {
        return;
      }
      before = held;
    }
    Instant started = clock.instant();
    try {
      if (before != null) {
        Optional<LeaderLease> renewed = election.renew(before, started, settings.leaseDuration());
        Optional<LeaderLease> now = renewed.isPresent() ? renewed : election.current();
        apply(before, renewed, now, started);
      } else {
        Optional<LeaderLease> acquired =
            election.tryAcquire(relayId, host, started, settings.leaseDuration());
        Optional<LeaderLease> now = acquired.isPresent() ? acquired : election.current();
        apply(null, acquired, now, started);
      }
    } catch (RuntimeException failure) {
      logger.log(Level.WARNING, "saga.transport.leadership.checkFailed relay=" + relayId, failure);
    }
  }

  private synchronized void apply(
      LeaderLease before, Optional<LeaderLease> won, Optional<LeaderLease> seen, Instant started) {
    if (stopped || held != before) {
      return;
    }
    LeaderLease previous = lastSeen;
    lastSeen = seen.orElse(lastSeen);
    observedAt = started;
    if (won.isPresent()) {
      held = won.get();
      trustedUntil = started.plus(settings.leaseDuration()).minus(settings.renewInterval());
      if (before == null) {
        acquired(previous);
      }
    } else if (before != null) {
      lose("renewRefused", seen.filter(lease -> !lease.isHeldBy(relayId)).orElse(null));
    }
  }

  private void acquired(LeaderLease previous) {
    LeaderLease lease = held;
    instrumentation.leadershipChanged(LeadershipChange.ACQUIRED);
    logger.log(
        Level.INFO,
        () ->
            "saga.transport.leadership.acquired relay="
                + relayId
                + " host="
                + host
                + " fencingToken="
                + lease.fencingToken()
                + " previousHolder="
                + (previous == null ? "none" : previous.holderId()));
  }

  /** Called under the monitor. */
  private void lose(String cause, LeaderLease newHolder) {
    LeaderLease lost = held;
    held = null;
    trustedUntil = Instant.MIN;
    instrumentation.leadershipChanged(LeadershipChange.LOST);
    logger.log(
        Level.WARNING,
        () ->
            "saga.transport.leadership.lost relay="
                + relayId
                + " fencingToken="
                + lost.fencingToken()
                + " cause="
                + cause
                + (newHolder == null ? "" : " newHolder=" + newHolder.holderId()));
  }

  private void scheduleNext() {
    long delay;
    synchronized (this) {
      delay = (held != null ? settings.renewInterval() : settings.checkInterval()).toMillis();
    }
    schedule(delay);
  }

  /** Replaces the pending round with one in {@code delayMillis}; no-op without a thread. */
  private void schedule(long delayMillis) {
    synchronized (this) {
      if (stopped || executor == null) {
        return;
      }
      if (nextRound != null) {
        nextRound.cancel(false);
      }
      try {
        nextRound =
            executor.schedule(
                () -> {
                  tick();
                  scheduleNext();
                },
                delayMillis,
                TimeUnit.MILLISECONDS);
      } catch (RejectedExecutionException shutDown) {
        // resign() stopped the executor; no more rounds
      }
    }
  }

  private void closeGauge() {
    try {
      gauge.close();
    } catch (Exception failure) {
      logger.log(Level.DEBUG, "saga.transport.leadership.gaugeCloseFailed", failure);
    }
  }
}
