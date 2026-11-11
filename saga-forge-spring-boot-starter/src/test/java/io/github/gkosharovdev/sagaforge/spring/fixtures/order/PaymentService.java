package io.github.gkosharovdev.sagaforge.spring.fixtures.order;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.PaymentProcessedEvent;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.ProcessPaymentCommand;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.RefundPaymentCommand;

/** Takes and refunds payments, keyed by command identity; a bean. */
public class PaymentService {

    private final Map<String, BigDecimal> charges = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> refunds = new ConcurrentHashMap<>();

    @CommandHandler
    public void handle(ProcessPaymentCommand command, CommandId commandId) {
        String paymentId = "payment-" + commandId.value();
        charges.putIfAbsent(paymentId, command.amount());
        DomainEvents.record(new PaymentProcessedEvent(paymentId, command.orderId(), command.amount()));
    }

    @CommandHandler
    public void handle(RefundPaymentCommand command, CommandId commandId) {
        refunds.putIfAbsent(command.paymentId(), command.amount());
    }

    public Map<String, BigDecimal> charges() {
        return Map.copyOf(charges);
    }

    public Map<String, BigDecimal> refunds() {
        return Map.copyOf(refunds);
    }
}
