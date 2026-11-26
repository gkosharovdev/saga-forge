package io.github.gkosharovdev.sagaforge.transport.inmemory.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.spi.store.LeaderElection;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeaderLease;
import io.github.gkosharovdev.sagaforge.core.telemetry.SagaTelemetrySettings;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.transport.inmemory.LeadershipSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.RelayRole;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.MutableClock;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LeaderElector} rules E1 to E8 ({@code specs/013-active-standby-relay}
 * contracts/relay-leadership.md), driven round by round through {@code tick()} over a {@link
 * MutableClock}; no background thread runs.
 */
class LeaderElectorTest {

  private static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");
  private static final LeadershipSettings SETTINGS = LeadershipSettings.defaults();

  private final MutableClock clock = new MutableClock(T0);
  private final InMemoryMessageStore store = new InMemoryMessageStore();
  private final RecordingLogger logger = new RecordingLogger();

  private LeaderElector elector(String relayId, LeaderElection election) {
    return new LeaderElector(
        election,
        SETTINGS,
        clock,
        relayId,
        "host-" + relayId,
        logger,
        new TransportInstrumentation(
            OpenTelemetry.noop(), SagaTelemetrySettings.defaults(), logger),
        LeaderElector.manualScheduling());
  }

  private LeaderElector elector(String relayId) {
    return elector(relayId, store.leaderElection());
  }

  @Test
  @DisplayName("E1: on an empty store start() leads at once and logs the acquisition once")
  void startLeadsAtOnce() {
    LeaderElector a = elector("A");

    a.start();

    assertThat(a.leadership()).map(LeaderLease::holderId).contains("A");
    assertThat(a.status().role()).isEqualTo(RelayRole.ACTIVE);
    assertThat(logger.byEvent("saga.transport.leadership.acquired"))
        .singleElement()
        .satisfies(
            logged -> {
              assertThat(logged.keys()).containsEntry("relay", "A");
              assertThat(logged.keys()).containsEntry("fencingToken", "1");
            });
  }

  @Test
  @DisplayName("E2: while another relay holds an unexpired lease, a relay stays standby")
  void standbyWhileHeld() {
    LeaderElector a = elector("A");
    LeaderElector b = elector("B");
    a.start();

    b.start();
    clock.advance(SETTINGS.checkInterval());
    b.tick();

    assertThat(b.leadership()).isEmpty();
    assertThat(b.status().role()).isEqualTo(RelayRole.STANDBY);
    assertThat(b.status().currentLeader()).map(LeaderLease::holderId).contains("A");
  }

  @Test
  @DisplayName("E2: renewal keeps the leader active and a standby takes over only after expiry")
  void renewalAndTakeover() {
    LeaderElector a = elector("A");
    LeaderElector b = elector("B");
    a.start();
    b.start();

    clock.advance(SETTINGS.renewInterval());
    a.tick();
    clock.advance(SETTINGS.renewInterval());
    a.tick();
    b.tick();
    assertThat(a.leadership()).isPresent();
    assertThat(b.leadership()).isEmpty();

    clock.advance(SETTINGS.leaseDuration());
    b.tick();
    assertThat(b.leadership()).map(LeaderLease::fencingToken).contains(2L);
  }

  @Test
  @DisplayName("E3: a holder stops trusting its lease one renew interval before it expires")
  void localDistrust() {
    LeaderElector a = elector("A");
    a.start();

    clock.advance(SETTINGS.leaseDuration().minus(SETTINGS.renewInterval()).minusMillis(1));
    assertThat(a.leadership()).isPresent();

    clock.advance(Duration.ofMillis(1));
    assertThat(a.leadership()).isEmpty();
    assertThat(a.status().role()).isEqualTo(RelayRole.STANDBY);
    assertThat(logger.byEvent("saga.transport.leadership.lost"))
        .singleElement()
        .satisfies(logged -> assertThat(logged.keys()).containsEntry("cause", "trustExpired"));
  }

  @Test
  @DisplayName("E4: a refused renewal makes the relay standby and names the new holder")
  void renewRefused() {
    LeaderElector a = elector("A");
    a.start();
    clock.advance(SETTINGS.leaseDuration());
    LeaderElector b = elector("B");
    b.start();

    a.tick();

    assertThat(a.leadership()).isEmpty();
    assertThat(logger.byEvent("saga.transport.leadership.lost"))
        .singleElement()
        .satisfies(
            logged -> {
              assertThat(logged.keys()).containsEntry("relay", "A");
              assertThat(logged.keys()).containsEntry("cause", "renewRefused");
              assertThat(logged.keys()).containsEntry("newHolder", "B");
            });
  }

  @Test
  @DisplayName(
      "E5: stepDown with the held lease makes the relay standby; an older lease is ignored")
  void stepDown() {
    LeaderElector a = elector("A");
    a.start();
    LeaderLease first = a.leadership().orElseThrow();
    a.stepDown(first);
    assertThat(a.leadership()).isEmpty();
    assertThat(logger.byEvent("saga.transport.leadership.lost"))
        .singleElement()
        .satisfies(logged -> assertThat(logged.keys()).containsEntry("cause", "fenced"));

    clock.advance(SETTINGS.leaseDuration());
    a.tick();
    assertThat(a.leadership()).map(LeaderLease::fencingToken).contains(2L);
    a.stepDown(first);
    assertThat(a.leadership()).isPresent();
  }

  @Test
  @DisplayName("E6: resign releases the leadership once so another relay acquires at once")
  void resign() {
    AtomicInteger releases = new AtomicInteger();
    LeaderElection counting = new CountingReleases(store.leaderElection(), releases);
    LeaderElector a = elector("A", counting);
    a.start();

    a.resign();
    a.resign();

    assertThat(releases).hasValue(1);
    assertThat(a.leadership()).isEmpty();
    assertThat(logger.byEvent("saga.transport.leadership.released")).hasSize(1);
    LeaderElector b = elector("B");
    b.start();
    assertThat(b.leadership()).isPresent();
  }

  @Test
  @DisplayName("E7: a failing store is logged and changes no role by itself; E3 still applies")
  void storeFailure() {
    FailingElection failing = new FailingElection(store.leaderElection());
    LeaderElector a = elector("A", failing);
    a.start();
    failing.failing = true;

    clock.advance(SETTINGS.renewInterval());
    a.tick();

    assertThat(logger.byEvent("saga.transport.leadership.checkFailed")).hasSize(1);
    assertThat(a.leadership()).isPresent();
    clock.advance(SETTINGS.leaseDuration());
    assertThat(a.leadership()).isEmpty();
  }

  @Test
  @DisplayName("E8: a renew interval not shorter than the lease is rejected, naming both")
  void invalidSettings() {
    assertThatThrownBy(
            () ->
                new LeadershipSettings(
                    Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("renewInterval (PT10S)")
        .hasMessageContaining("leaseDuration (PT10S)");
    assertThatThrownBy(
            () ->
                new LeadershipSettings(
                    Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(10)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checkInterval");
  }

  @Test
  @DisplayName("logs once per change: renewals and refused acquisitions log no leadership event")
  void oncePerChange() {
    LeaderElector a = elector("A");
    LeaderElector b = elector("B");
    a.start();
    b.start();
    for (int round = 0; round < 5; round++) {
      clock.advance(SETTINGS.renewInterval());
      a.tick();
      b.tick();
    }

    assertThat(logger.records())
        .extracting(RecordingLogger.Logged::event)
        .containsExactly("saga.transport.leadership.acquired");
  }

  /** Delegates and counts releases. */
  private record CountingReleases(LeaderElection delegate, AtomicInteger releases)
      implements LeaderElection {

    @Override
    public Optional<LeaderLease> tryAcquire(
        String candidateId, String host, Instant now, Duration leaseDuration) {
      return delegate.tryAcquire(candidateId, host, now, leaseDuration);
    }

    @Override
    public Optional<LeaderLease> renew(LeaderLease lease, Instant now, Duration leaseDuration) {
      return delegate.renew(lease, now, leaseDuration);
    }

    @Override
    public void release(LeaderLease lease, Instant now) {
      releases.incrementAndGet();
      delegate.release(lease, now);
    }

    @Override
    public Optional<LeaderLease> current() {
      return delegate.current();
    }
  }

  /** Delegates until {@link #failing} is set, then throws like an unreachable database. */
  private static final class FailingElection implements LeaderElection {

    private final LeaderElection delegate;
    private volatile boolean failing;

    FailingElection(LeaderElection delegate) {
      this.delegate = delegate;
    }

    private void check() {
      if (failing) {
        throw new IllegalStateException("database unreachable");
      }
    }

    @Override
    public Optional<LeaderLease> tryAcquire(
        String candidateId, String host, Instant now, Duration leaseDuration) {
      check();
      return delegate.tryAcquire(candidateId, host, now, leaseDuration);
    }

    @Override
    public Optional<LeaderLease> renew(LeaderLease lease, Instant now, Duration leaseDuration) {
      check();
      return delegate.renew(lease, now, leaseDuration);
    }

    @Override
    public void release(LeaderLease lease, Instant now) {
      check();
      delegate.release(lease, now);
    }

    @Override
    public Optional<LeaderLease> current() {
      check();
      return delegate.current();
    }
  }
}
