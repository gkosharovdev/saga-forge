package io.github.gkosharovdev.sagaforge.spring.actuator;

import io.github.gkosharovdev.sagaforge.core.operations.InstanceCounts;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.LeadershipStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.MessageStoreQueries;
import io.github.gkosharovdev.sagaforge.transport.inmemory.RelayRole;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * {@code DOWN} while messages are set aside and wait for an operator ({@code
 * specs/008-spring-boot-starter} FR-020); otherwise {@link #DEGRADED} while no relay has led the
 * message store for longer than one leadership lease ({@code specs/013-active-standby-relay}
 * FR-023); {@code UP} otherwise. Details name this instance's role and the leader's fencing token.
 */
public class SagaForgeHealthIndicator extends AbstractHealthIndicator {

  /** No relay delivers: the last leadership expired more than one lease ago, or none was seen. */
  public static final Status DEGRADED =
      new Status("DEGRADED", "no active relay for longer than the leadership lease");

  private final MessageStoreQueries queries;
  private final Supplier<LeadershipStatus> leadership;
  private final Duration leaseDuration;
  private final Clock clock;
  private final Supplier<Optional<InstanceCounts>> instances;

  /**
   * @param leadership this instance's view of the leadership, read on every check
   * @param leaseDuration the leadership lease the transport runs with
   */
  public SagaForgeHealthIndicator(
      MessageStoreQueries queries,
      Supplier<LeadershipStatus> leadership,
      Duration leaseDuration,
      Clock clock) {
    this(queries, leadership, leaseDuration, clock, Optional::empty);
  }

  /**
   * As the other constructor, with the details {@code instances.compensationFailed} and {@code
   * instances.failedUnresolved} from {@code instances} ({@code specs/015-saga-operations} E7). They
   * never change the status.
   *
   * @param instances the runtime's counts, read on every check; empty while the runtime is not
   *     started
   */
  public SagaForgeHealthIndicator(
      MessageStoreQueries queries,
      Supplier<LeadershipStatus> leadership,
      Duration leaseDuration,
      Clock clock,
      Supplier<Optional<InstanceCounts>> instances) {
    super("Saga Forge health check failed");
    this.instances = instances;
    this.queries = queries;
    this.leadership = leadership;
    this.leaseDuration = leaseDuration;
    this.clock = clock;
  }

  @Override
  protected void doHealthCheck(Health.Builder builder) {
    LeadershipStatus status = leadership.get();
    int setAside = queries.status().counts().getOrDefault(MessageStatus.SET_ASIDE, 0);
    if (setAside > 0) {
      builder.down().withDetail("setAside", setAside);
    } else if (status.isLeaderless(clock.instant(), leaseDuration)) {
      builder.status(DEGRADED).withDetail("reason", DEGRADED.getDescription());
    } else {
      builder.up();
    }
    builder.withDetail("role", status.role().name());
    status
        .currentLeader()
        .ifPresent(
            leader -> {
              if (status.role() == RelayRole.STANDBY) {
                builder.withDetail("leader", leader.holderId() + "@" + leader.holderHost());
              }
              builder.withDetail("fencingToken", leader.fencingToken());
            });
    instances
        .get()
        .ifPresent(
            counts -> {
              builder.withDetail(
                  "instances.compensationFailed", counts.compensationFailedUnresolved());
              builder.withDetail("instances.failedUnresolved", counts.failedUnresolved());
            });
  }
}
