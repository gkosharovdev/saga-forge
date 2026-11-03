package io.github.gkosharovdev.sagaforge.example;

import java.util.UUID;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.example.commands.ProcessPaymentCommand;
import io.github.gkosharovdev.sagaforge.example.events.PaymentProcessedEvent;

/**
 * A service-hosted command handler: a plain class, not an aggregate, that takes the payment and records
 * {@link PaymentProcessedEvent}. An event recorded here triggers the next saga step exactly as one recorded
 * inside an aggregate would.
 */
public class PaymentService {

    @CommandHandler
    public void handle(ProcessPaymentCommand command) {
        // A real service would charge the customer through a payment provider here.
        String paymentId = UUID.randomUUID().toString();
        DomainEvents.record(new PaymentProcessedEvent(paymentId, command.orderId(), command.amount()));
    }
}
