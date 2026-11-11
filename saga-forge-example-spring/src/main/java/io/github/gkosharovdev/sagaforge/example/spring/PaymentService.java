package io.github.gkosharovdev.sagaforge.example.spring;

import org.springframework.stereotype.Service;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.example.spring.commands.ProcessPaymentCommand;
import io.github.gkosharovdev.sagaforge.example.spring.commands.RefundPaymentCommand;
import io.github.gkosharovdev.sagaforge.example.spring.events.PaymentProcessedEvent;

/**
 * A service-hosted command handler, an ordinary Spring bean with a constructor-injected collaborator. Its handlers run
 * on this bean; the {@link CommandId} parameter is the provider's idempotency key, so a retry never charges twice.
 */
@Service
public class PaymentService {

    private final PaymentProvider provider;

    public PaymentService(PaymentProvider provider) {
        this.provider = provider;
    }

    @CommandHandler
    public void handle(ProcessPaymentCommand command, CommandId commandId) {
        String paymentId = provider.charge(commandId.value(), command.amount());
        DomainEvents.record(new PaymentProcessedEvent(paymentId, command.orderId(), command.amount()));
    }

    @CommandHandler
    public void handle(RefundPaymentCommand command) {
        provider.refund(command.paymentId(), command.amount());
    }
}
