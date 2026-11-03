package io.github.gkosharovdev.sagaforge.example;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.scanner.ReflectiveAnnotationScanner;
import io.github.gkosharovdev.sagaforge.core.scanner.SagaValidationResult;
import io.github.gkosharovdev.sagaforge.core.scanner.ValidationReport;
import io.github.gkosharovdev.sagaforge.example.commands.ProcessPaymentCommand;
import io.github.gkosharovdev.sagaforge.example.commands.ReserveStockCommand;
import io.github.gkosharovdev.sagaforge.example.events.OrderCompletedEvent;
import io.github.gkosharovdev.sagaforge.example.events.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.example.events.PaymentProcessedEvent;

/**
 * Living spec for the canonical example: the whole example model passes {@code validateAll}, no step returns
 * a value, and each handler records the event that triggers the next step (feature 002 US4, SC-005).
 */
class OrderProcessingSagaValidationTest {

    private static final List<Class<?>> EXAMPLE_MODEL = List.of(
            OrderProcessingSaga.class,
            Order.class,
            OrderRepository.class,
            Payment.class,
            PaymentService.class,
            ProcessPaymentCommand.class,
            ReserveStockCommand.class,
            OrderCreatedEvent.class,
            PaymentProcessedEvent.class,
            OrderCompletedEvent.class);

    private final ReflectiveAnnotationScanner scanner = new ReflectiveAnnotationScanner();

    @Test
    void exampleModelPassesValidateAll() {
        ValidationReport report = scanner.validateAll(EXAMPLE_MODEL);

        assertThat(report.isValid()).as("validateAll errors: %s", report.allErrors()).isTrue();
    }

    @Test
    void orderProcessingSagaPassesValidate() {
        SagaValidationResult result = scanner.validate(OrderProcessingSaga.class);

        assertThat(result.isValid()).as("validate errors: %s", result.errors()).isTrue();
    }

    @Test
    void noSagaStepReturnsAValue() {
        List<Method> steps = Arrays.stream(OrderProcessingSaga.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(SagaStep.class))
                .toList();

        assertThat(steps).hasSize(3).allSatisfy(step -> assertThat(step.getReturnType()).isEqualTo(void.class));
    }

    @Test
    void handlersRecordTheEventsThatTriggerTheNextSteps() {
        List<Object> paymentEvents = DomainEvents.capture(() ->
                new PaymentService().handle(new ProcessPaymentCommand("order-1", new BigDecimal("42.00"))));
        assertThat(paymentEvents).singleElement().isInstanceOfSatisfying(PaymentProcessedEvent.class, event -> {
            assertThat(event.orderId()).isEqualTo("order-1");
            assertThat(event.amount()).isEqualByComparingTo("42.00");
        });

        Order order = new Order("order-1");
        List<Object> orderEvents = DomainEvents.capture(() -> order.handle(new ReserveStockCommand("order-1")));
        assertThat(orderEvents).containsExactly(new OrderCompletedEvent("order-1"));
        assertThat(order.status()).isEqualTo(Order.Status.COMPLETED);
    }
}
