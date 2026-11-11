package io.github.gkosharovdev.sagaforge.spring.runtime;

import static io.github.gkosharovdev.sagaforge.spring.support.StarterApp.await;
import static io.github.gkosharovdev.sagaforge.spring.support.StarterApp.count;
import static io.github.gkosharovdev.sagaforge.spring.support.StarterApp.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.FulfilmentSaga;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.Order;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderApp;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.ProcessPaymentCommand;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderProcessingSaga;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderRepository;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.PaymentService;
import io.github.gkosharovdev.sagaforge.spring.support.StarterApp;
import io.github.gkosharovdev.sagaforge.spring.transport.TransportLifecycle;

/** US1 scenarios 1 to 4 ({@code specs/008-spring-boot-starter}): a runtime without assembly code. */
@Tag("container")
class RuntimeAssemblyContainerTest {

    private static final BigDecimal AMOUNT = new BigDecimal("12.50");

    @Test
    @DisplayName("one started runtime, its CommandGateway and EventPublisher beans, the lifecycles running")
    void startedRuntime() {
        try (ConfigurableApplicationContext context = StarterApp.run(OrderApp.class)) {
            assertThat(context.getBeansOfType(SagaRuntime.class)).hasSize(1);
            assertThat(context.getBean(SagaRuntime.class).isStarted()).isTrue();
            assertThat(context.getBean(CommandGateway.class)).isNotNull();
            assertThat(context.getBean(SagaRuntimeLifecycle.class).isRunning()).isTrue();
            assertThat(context.getBean(TransportLifecycle.class).isRunning()).isTrue();
        }
    }

    @Test
    @DisplayName("an event through the EventPublisher bean completes the saga; the outcome reaches a second saga")
    void eventPublisherDrivesSaga() {
        try (ConfigurableApplicationContext context = StarterApp.run(OrderApp.class)) {
            context.getBean(OrderRepository.class).save(new Order("o-1", true));
            context.getBean(EventPublisher.class).publish(new OrderCreatedEvent("o-1", AMOUNT));

            await(() -> "COMPLETED".equals(status(OrderProcessingSaga.class)), "the order saga to complete");
            assertThat(context.getBean(PaymentService.class).charges()).containsValue(AMOUNT);
            await(() -> count(FulfilmentSaga.class) == 1, "the fulfilment saga to start through the transport");
        }
    }

    @Test
    @DisplayName("the CommandGateway bean runs a command handler bean")
    void commandGatewayBean() {
        try (ConfigurableApplicationContext context = StarterApp.run(OrderApp.class)) {
            context.getBean(CommandGateway.class).send(new ProcessPaymentCommand("o-2", AMOUNT));
            assertThat(context.getBean(PaymentService.class).charges()).containsValue(AMOUNT);
        }
    }

    @Test
    @DisplayName("an event stored before shutdown is delivered after the restart: nothing is lost")
    void shutdownLosesNothing() {
        ConfigurableApplicationContext context = StarterApp.run(OrderApp.class,
                "saga-forge.transport.poll-interval=1h");
        context.getBean(OrderRepository.class).save(new Order("o-3", true));
        context.getBean(EventPublisher.class).publish(new OrderCreatedEvent("o-3", AMOUNT));
        SagaRuntimeLifecycle lifecycle = context.getBean(SagaRuntimeLifecycle.class);
        context.close();
        assertThat(lifecycle.isRunning()).isFalse();

        try (ConfigurableApplicationContext again = StarterApp.restart(OrderApp.class)) {
            await(() -> "COMPLETED".equals(status(OrderProcessingSaga.class)), "the stored event to complete the order");
            await(() -> count(FulfilmentSaga.class) == 1, "the outcome to reach the fulfilment saga");
        }
    }

    @Test
    @DisplayName("the runtime starts one phase before the transport and stops after it")
    void lifecyclePhases() {
        assertThat(SagaRuntimeLifecycle.PHASE).isLessThan(TransportLifecycle.PHASE);
    }
}
