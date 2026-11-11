package io.github.gkosharovdev.sagaforge.spring.actuator;

import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.gkosharovdev.sagaforge.spring.transport.InMemoryTransportConfiguration;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;

/**
 * The {@code sagaforge} Actuator endpoint and health indicator over the in-memory transport's message store
 * ({@code specs/008-spring-boot-starter} FR-020). Registered only when Actuator is on the class path; switched by Spring
 * Boot's {@code management.endpoint.sagaforge.*}, {@code management.endpoints.web.exposure.*} and
 * {@code management.health.sagaforge.enabled}.
 */
@AutoConfiguration(after = InMemoryTransportConfiguration.class)
@ConditionalOnProperty(prefix = "saga-forge", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(name = { "org.springframework.boot.actuate.endpoint.annotation.Endpoint",
        "org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint",
        "io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport" })
@ConditionalOnBean(InMemoryTransport.class)
public class SagaForgeActuatorConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint(endpoint = SagaForgeEndpoint.class)
    public SagaForgeEndpoint sagaForgeEndpoint(InMemoryTransport transport) {
        return new SagaForgeEndpoint(transport.queries());
    }

    /** The health indicator, when Spring Boot's health module is present. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = { "org.springframework.boot.health.contributor.AbstractHealthIndicator",
            "org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator" })
    static class Health {

        /** Named {@code sagaforgeHealthIndicator}, so the contributor is {@code sagaforge}. */
        @Bean
        @ConditionalOnMissingBean(name = "sagaforgeHealthIndicator")
        @ConditionalOnEnabledHealthIndicator("sagaforge")
        public SagaForgeHealthIndicator sagaforgeHealthIndicator(InMemoryTransport transport) {
            return new SagaForgeHealthIndicator(transport.queries());
        }
    }
}
