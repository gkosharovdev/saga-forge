package io.github.gkosharovdev.sagaforge.example;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.example.commands.ProcessPaymentCommand;
import io.github.gkosharovdev.sagaforge.example.commands.RefundPaymentCommand;
import io.github.gkosharovdev.sagaforge.example.events.PaymentProcessedEvent;

/**
 * A service-hosted command handler: a plain class, not an aggregate, that takes the payment and records
 * {@link PaymentProcessedEvent}. An event recorded here triggers the next saga step exactly as one recorded
 * inside an aggregate would.
 *
 * <p>The handler declares a {@link CommandId} parameter, which the engine supplies: the identity of this command,
 * the same on every retry and redelivery. Passing it to the provider as its idempotency key means a retried step
 * never charges the customer twice.</p>
 */
public class PaymentService {

    private final PaymentProvider provider;

    public PaymentService(PaymentProvider provider) {
        this.provider = provider;
    }

    public PaymentService() {
        this(new PaymentProvider());
    }

    @CommandHandler
    public void handle(ProcessPaymentCommand command, CommandId commandId) {
        String paymentId = provider.charge(commandId.value(), command.amount());
        DomainEvents.record(new PaymentProcessedEvent(paymentId, command.orderId(), command.amount()));
    }

    /**
     * Refunds a payment; sent by the saga's compensation. The command identity is again the provider's idempotency
     * key, so a compensation that runs again after a crash refunds once.
     */
    @CommandHandler
    public void handle(RefundPaymentCommand command, CommandId commandId) {
        provider.refund(commandId.value(), command.paymentId(), command.amount());
    }
}
