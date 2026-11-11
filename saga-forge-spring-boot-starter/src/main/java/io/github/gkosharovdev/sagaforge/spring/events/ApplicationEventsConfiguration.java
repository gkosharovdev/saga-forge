package io.github.gkosharovdev.sagaforge.spring.events;

import java.lang.System.Logger.Level;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.spring.transport.ApplicationEventBridge;
import io.github.gkosharovdev.sagaforge.spring.transport.InMemoryTransportConfiguration;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;

/**
 * Saga events as Spring application events, by {@code saga-forge.application-events.mode}
 * ({@code specs/008-spring-boot-starter} FR-018a, contracts/application-events.md): {@code in-transaction} (default)
 * decorates the {@link Outbox} bean with {@link ApplicationEventPublishingOutbox}; {@code after-delivery} registers
 * 007's {@link ApplicationEventBridge}; {@code none} does neither. Exactly one is active, so a listener sees each event
 * once.
 */
@AutoConfiguration(after = InMemoryTransportConfiguration.class)
@ConditionalOnProperty(prefix = "saga-forge", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(SagaRuntime.class)
public class ApplicationEventsConfiguration {

    /** {@code in-transaction}: every recorded event, published inside the unit of work's transaction. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "saga-forge.application-events", name = "mode", havingValue = "in-transaction",
            matchIfMissing = true)
    static class InTransaction {

        @Bean
        static BeanPostProcessor sagaForgeApplicationEventPublishingOutbox(
                ObjectProvider<ApplicationEventPublisher> publisher) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof Outbox outbox && !(bean instanceof ApplicationEventPublishingOutbox)) {
                        if (outbox.timing() != Outbox.Timing.IN_TRANSACTION) {
                            System.getLogger("io.github.gkosharovdev.sagaforge.spring").log(Level.WARNING,
                                    "saga-forge.application-events.mode=in-transaction needs an IN_TRANSACTION outbox;"
                                            + " bean '" + beanName + "' is " + outbox.timing() + ", events are not"
                                            + " published as application events");
                            return bean;
                        }
                        return new ApplicationEventPublishingOutbox(outbox, publisher.getObject());
                    }
                    return bean;
                }
            };
        }
    }

    /** {@code after-delivery}: 007's bridge, after the transport delivered an event, outside any transaction. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(InMemoryTransport.class)
    @ConditionalOnBean(InMemoryTransport.class)
    @ConditionalOnProperty(prefix = "saga-forge.application-events", name = "mode", havingValue = "after-delivery")
    static class AfterDelivery {

        @Bean
        @ConditionalOnMissingBean
        ApplicationEventBridge sagaForgeApplicationEventBridge(InMemoryTransport transport,
                ApplicationEventPublisher publisher) {
            return new ApplicationEventBridge(transport, publisher);
        }
    }
}
