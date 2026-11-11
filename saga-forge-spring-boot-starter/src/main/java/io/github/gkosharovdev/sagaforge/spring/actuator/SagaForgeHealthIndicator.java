package io.github.gkosharovdev.sagaforge.spring.actuator;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.MessageStoreQueries;

/**
 * {@code DOWN} while messages are set aside and wait for an operator; {@code UP} otherwise
 * ({@code specs/008-spring-boot-starter} FR-020).
 */
public class SagaForgeHealthIndicator extends AbstractHealthIndicator {

    private final MessageStoreQueries queries;

    public SagaForgeHealthIndicator(MessageStoreQueries queries) {
        super("Saga Forge health check failed");
        this.queries = queries;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        int setAside = queries.status().counts().getOrDefault(MessageStatus.SET_ASIDE, 0);
        if (setAside > 0) {
            builder.down().withDetail("setAside", setAside);
        } else {
            builder.up();
        }
    }
}
