package io.github.gkosharovdev.sagaforge.spring.actuator;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.spi.store.LeaderLease;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.LeadershipSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.LeadershipStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.RelayRole;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Unrouted;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The {@code sagaforge} endpoint and health indicator ({@code specs/008-spring-boot-starter}
 * FR-020, T046).
 */
class ActuatorTest {

  private static InMemoryTransport transport() {
    return InMemoryTransport.builder()
        .messageStore(new InMemoryMessageStore())
        .transactions(new InMemoryTransactionBoundary())
        .settings(
            TransportSettings.defaults().withMaxAttempts(1).withPollInterval(Duration.ofMillis(20)))
        .build();
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(SagaForgeActuatorConfiguration.class))
          .withBean(InMemoryTransport.class, ActuatorTest::transport)
          .withPropertyValues("management.endpoints.web.exposure.include=health,sagaforge");

  private static final Duration LEASE = LeadershipSettings.defaults().leaseDuration();

  private static SagaForgeHealthIndicator health(InMemoryTransport transport) {
    return new SagaForgeHealthIndicator(
        transport.queries(), transport::leadership, LEASE, Clock.systemUTC());
  }

  @Test
  @DisplayName(
      "payload and health: DEGRADED before any relay led, UP as the active relay, DOWN with setAside")
  void payloadAndHealth() {
    InMemoryTransport transport = transport();
    SagaForgeEndpoint endpoint = new SagaForgeEndpoint(transport.queries(), transport::leadership);
    SagaForgeHealthIndicator health = health(transport);
    assertThat(health.health().getStatus()).isEqualTo(SagaForgeHealthIndicator.DEGRADED);

    transport.start(
        event -> {
          throw new IllegalStateException("receiver failed");
        },
        null);
    try {
      assertThat(health.health().getStatus()).isEqualTo(Status.UP);
      assertThat(health.health().getDetails())
          .containsEntry("role", "ACTIVE")
          .containsEntry("fencingToken", 1L)
          .doesNotContainKey("leader");
      assertThat(endpoint.status().get("leadership"))
          .asInstanceOf(InstanceOfAssertFactories.MAP)
          .containsEntry("role", "ACTIVE")
          .containsEntry("relayId", transport.leadership().relayId())
          .extractingByKey("leader")
          .asInstanceOf(InstanceOfAssertFactories.MAP)
          .containsEntry("fencingToken", 1L)
          .containsEntry("holderId", transport.leadership().relayId());
      transport.dispatcher().dispatch(new Unrouted("o-1"));
      await(
          () -> transport.queries().status().counts().get(MessageStatus.SET_ASIDE) == 1,
          Duration.ofSeconds(5),
          "the message to be set aside");
    } finally {
      transport.stop(Duration.ofSeconds(2));
    }

    Map<String, Object> payload = endpoint.status();
    assertThat(payload).containsKey("counts").doesNotContainKey("oldestUnconsumed");
    assertThat(payload.get("counts"))
        .isEqualTo(Map.of("CANCELLED", 0, "CONSUMED", 0, "SET_ASIDE", 1, "UNCONSUMED", 0));
    assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.health().getDetails()).containsEntry("setAside", 1);
  }

  @Test
  @DisplayName(
      "a standby names the leader; DEGRADED once the last leadership expired more than a lease ago")
  void standbyAndLeaderless() {
    InMemoryTransport transport = transport();
    Instant now = Instant.now();
    LeaderLease fresh = new LeaderLease("other", "host-b", 7, now, now.plus(LEASE));
    LeaderLease stale =
        new LeaderLease(
            "other",
            "host-b",
            7,
            now.minus(LEASE.multipliedBy(3)),
            now.minus(LEASE.multipliedBy(2)));
    AtomicReference<LeadershipStatus> status =
        new AtomicReference<>(new LeadershipStatus(RelayRole.STANDBY, "me", fresh, now));
    SagaForgeHealthIndicator health =
        new SagaForgeHealthIndicator(transport.queries(), status::get, LEASE, Clock.systemUTC());

    assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    assertThat(health.health().getDetails())
        .containsEntry("role", "STANDBY")
        .containsEntry("leader", "other@host-b")
        .containsEntry("fencingToken", 7L);

    status.set(new LeadershipStatus(RelayRole.STANDBY, "me", stale, now));
    assertThat(health.health().getStatus()).isEqualTo(SagaForgeHealthIndicator.DEGRADED);
    assertThat(health.health().getDetails())
        .containsEntry("reason", "no active relay for longer than the leadership lease");
  }

  @Test
  @DisplayName("the endpoint has read operations only")
  void readOnly() {
    for (Method method : SagaForgeEndpoint.class.getDeclaredMethods()) {
      assertThat(
              method.isAnnotationPresent(WriteOperation.class)
                  || method.isAnnotationPresent(DeleteOperation.class))
          .as(method.getName())
          .isFalse();
    }
  }

  @Test
  @DisplayName("registered with a transport; switched off by management properties")
  void registration() {
    runner.run(
        context ->
            assertThat(context)
                .hasSingleBean(SagaForgeEndpoint.class)
                .hasBean("sagaforgeHealthIndicator"));
    runner
        .withPropertyValues(
            "management.endpoint.sagaforge.enabled=false",
            "management.health.sagaforge.enabled=false")
        .run(
            context ->
                assertThat(context)
                    .doesNotHaveBean(SagaForgeEndpoint.class)
                    .doesNotHaveBean(SagaForgeHealthIndicator.class));
  }

  @Test
  @DisplayName("without Actuator classes nothing is registered and nothing fails")
  void withoutActuator() {
    runner
        .withClassLoader(
            new FilteredClassLoader(
                "org.springframework.boot.actuate", "org.springframework.boot.health"))
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(SagaForgeEndpoint.class)
                    .doesNotHaveBean(SagaForgeHealthIndicator.class));
  }

  @Test
  @DisplayName("without a transport nothing is registered")
  void withoutTransport() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SagaForgeActuatorConfiguration.class))
        .run(
            context -> assertThat(context).hasNotFailed().doesNotHaveBean(SagaForgeEndpoint.class));
  }
}
