package io.github.gkosharovdev.sagaforge.spring.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TestTransport;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderCompletedEvent;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.spring.support.StarterRunner;
import io.github.gkosharovdev.sagaforge.spring.transport.ApplicationEventBridge;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;

/** {@code saga-forge.application-events.mode} ({@code specs/008-spring-boot-starter} FR-018a, tasks.md T043). */
class ApplicationEventsModeTest {

    private final ApplicationContextRunner runner = StarterRunner.withDoubles(StarterRunner.runner()
            .withConfiguration(AutoConfigurations.of(ApplicationEventsConfiguration.class)), ShortSaga.class,
            Completer.class)
            .withBean(Completer.class, Completer::new)
            .withBean(Listener.class, Listener::new)
            .withUserConfiguration(OutboxConfig.class);

    /** Publishes through the runtime's own EventPublisher and loops dispatched events back into it. */
    private static void publish(ApplicationContext context, Object event) {
        ((TestTransport) context.getBean(EventDispatcher.class)).connect(context.getBean(SagaRuntime.class)).loopBack();
        context.getBean(EventPublisher.class).publish(event);
    }

    private static String sagaStatus(ApplicationContext context) {
        return context.getBean(StorageProvider.class).findByAssociation("orderId", "o-1").stream()
                .map(instance -> instance.status().name()).findFirst().orElse(null);
    }

    @Test
    @DisplayName("in-transaction (default): every recorded event, in-process ones included, is published once")
    void inTransaction() {
        runner.run(context -> {
            assertThat(context.getBean(Outbox.class)).isInstanceOf(ApplicationEventPublishingOutbox.class);
            publish(context, new OrderCreatedEvent("o-1", java.math.BigDecimal.ONE));

            assertThat(context.getBean(Listener.class).events).containsExactly(new OrderCompletedEvent("o-1"));
            assertThat(sagaStatus(context)).isEqualTo("COMPLETED");
        });
    }

    @Test
    @DisplayName("in-transaction: a failing plain @EventListener rolls the unit of work back")
    void listenerFailureRollsBack() {
        runner.run(context -> {
            context.getBean(Listener.class).fail = true;
            publish(context, new OrderCreatedEvent("o-1", java.math.BigDecimal.ONE));

            assertThat(sagaStatus(context)).isEqualTo("FAILED");
        });
    }

    @Test
    @DisplayName("after-delivery: the bridge only, no publishing outbox")
    void afterDelivery() {
        runner.withPropertyValues("saga-forge.application-events.mode=after-delivery")
                .withBean(MessageStore.class, InMemoryMessageStore::new).run(context -> {
                    assertThat(context).hasSingleBean(ApplicationEventBridge.class);
                    assertThat(context.getBean(Outbox.class)).isNotInstanceOf(ApplicationEventPublishingOutbox.class);
                });
    }

    @Test
    @DisplayName("none: nothing is published")
    void none() {
        runner.withPropertyValues("saga-forge.application-events.mode=none")
                .withBean(MessageStore.class, InMemoryMessageStore::new).run(context -> {
                    assertThat(context).doesNotHaveBean(ApplicationEventBridge.class);
                    assertThat(context.getBean(Outbox.class)).isNotInstanceOf(ApplicationEventPublishingOutbox.class);
                    publish(context, new OrderCreatedEvent("o-1", java.math.BigDecimal.ONE));
                    assertThat(context.getBean(Listener.class).events).isEmpty();
                });
    }

    /** An IN_TRANSACTION outbox over the runtime's transaction boundary. */
    @Configuration(proxyBeanMethods = false)
    static class OutboxConfig {

        @Bean
        Outbox outbox(TransactionBoundary boundary) {
            return CapturingOutbox.inTransaction((InMemoryTransactionBoundary) boundary);
        }
    }

    @Saga
    public static class ShortSaga {

        @SagaId
        private String sagaId;

        private CommandGateway commandGateway;

        @StartSaga
        @SagaStep(associationProperty = "orderId")
        public void on(OrderCreatedEvent event) {
            commandGateway.send(new Complete(event.orderId()));
        }

        @EndSaga
        @SagaStep(associationProperty = "orderId")
        public void done(OrderCompletedEvent event) {
            // ends
        }
    }

    @Command
    public record Complete(String orderId) {
    }

    public static class Completer {

        @CommandHandler
        public void handle(Complete command) {
            DomainEvents.record(new OrderCompletedEvent(command.orderId()));
        }
    }

    public static class Listener {

        final List<Object> events = new CopyOnWriteArrayList<>();
        volatile boolean fail;

        @EventListener
        public void on(OrderCompletedEvent event) {
            events.add(event);
            if (fail) {
                throw new IllegalStateException("listener failed");
            }
        }
    }
}
