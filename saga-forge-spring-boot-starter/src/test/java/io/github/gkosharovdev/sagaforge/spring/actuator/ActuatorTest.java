package io.github.gkosharovdev.sagaforge.spring.actuator;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Unrouted;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;

/** The {@code sagaforge} endpoint and health indicator ({@code specs/008-spring-boot-starter} FR-020, T046). */
class ActuatorTest {

    private static InMemoryTransport transport() {
        return InMemoryTransport.builder().messageStore(new InMemoryMessageStore())
                .transactions(new InMemoryTransactionBoundary())
                .settings(TransportSettings.defaults().withMaxAttempts(1).withPollInterval(Duration.ofMillis(20)))
                .build();
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SagaForgeActuatorConfiguration.class))
            .withBean(InMemoryTransport.class, ActuatorTest::transport)
            .withPropertyValues("management.endpoints.web.exposure.include=health,sagaforge");

    @Test
    @DisplayName("payload and health: UP while nothing is set aside, DOWN with setAside once a message is")
    void payloadAndHealth() {
        InMemoryTransport transport = transport();
        SagaForgeEndpoint endpoint = new SagaForgeEndpoint(transport.queries());
        SagaForgeHealthIndicator health = new SagaForgeHealthIndicator(transport.queries());
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);

        transport.start(event -> {
            throw new IllegalStateException("receiver failed");
        }, null);
        try {
            transport.dispatcher().dispatch(new Unrouted("o-1"));
            await(() -> transport.queries().status().counts().get(MessageStatus.SET_ASIDE) == 1,
                    Duration.ofSeconds(5), "the message to be set aside");
        } finally {
            transport.stop(Duration.ofSeconds(2));
        }

        Map<String, Object> payload = endpoint.status();
        assertThat(payload).containsKey("counts").doesNotContainKey("oldestUnconsumed");
        assertThat(payload.get("counts")).isEqualTo(Map.of("CONSUMED", 0, "SET_ASIDE", 1, "UNCONSUMED", 0));
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.health().getDetails()).containsEntry("setAside", 1);
    }

    @Test
    @DisplayName("the endpoint has read operations only")
    void readOnly() {
        for (Method method : SagaForgeEndpoint.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(WriteOperation.class) || method.isAnnotationPresent(DeleteOperation.class))
                    .as(method.getName()).isFalse();
        }
    }

    @Test
    @DisplayName("registered with a transport; switched off by management properties")
    void registration() {
        runner.run(context -> assertThat(context).hasSingleBean(SagaForgeEndpoint.class)
                .hasBean("sagaforgeHealthIndicator"));
        runner.withPropertyValues("management.endpoint.sagaforge.enabled=false", "management.health.sagaforge.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SagaForgeEndpoint.class)
                        .doesNotHaveBean(SagaForgeHealthIndicator.class));
    }

    @Test
    @DisplayName("without Actuator classes nothing is registered and nothing fails")
    void withoutActuator() {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.boot.actuate",
                "org.springframework.boot.health")).run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(SagaForgeEndpoint.class).doesNotHaveBean(SagaForgeHealthIndicator.class));
    }

    @Test
    @DisplayName("without a transport nothing is registered")
    void withoutTransport() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SagaForgeActuatorConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(SagaForgeEndpoint.class));
    }
}
